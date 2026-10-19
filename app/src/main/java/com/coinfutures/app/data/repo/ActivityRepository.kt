package com.coinfutures.app.data.repo

import com.coinfutures.app.data.db.AlertEntity
import com.coinfutures.app.data.db.AlertEventEntity
import com.coinfutures.app.data.db.AppDatabase
import com.coinfutures.app.data.db.PaperTradeEntity
import com.coinfutures.app.data.db.PlanRevisionEntity
import com.coinfutures.app.domain.AlertDispatcher
import com.coinfutures.app.domain.Delivery
import com.coinfutures.engine.AlertCandidate
import com.coinfutures.engine.AlertEventType
import com.coinfutures.engine.Bias
import com.coinfutures.engine.DataQuality
import com.coinfutures.engine.Direction
import com.coinfutures.engine.PaperTrackResult
import com.coinfutures.engine.PaperTracker
import com.coinfutures.engine.Timeframe
import com.coinfutures.engine.TouchType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigDecimal

@Serializable
data class RecordedExit(val price: String, val quantity: String, val at: Long, val note: String = "")

/** Alerts, event history, plan revisions and paper trades (all user-owned, local). */
class ActivityRepository(
    private val db: AppDatabase,
    private val markets: MarketRepository,
    private val dispatcher: AlertDispatcher,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun alerts(): Flow<List<AlertEntity>> = db.alerts().all()
    fun alertFor(instrumentId: String): Flow<AlertEntity?> = db.alerts().observeForInstrument(instrumentId)
    fun events(): Flow<List<AlertEventEntity>> = db.alerts().events(300)
    fun plans(): Flow<List<PlanRevisionEntity>> = db.plans().recent(200)
    fun paperTrades(): Flow<List<PaperTradeEntity>> = db.paper().all()
    fun paperTrade(id: Long): Flow<PaperTradeEntity?> = db.paper().observe(id)

    suspend fun saveAlert(instrumentId: String, symbol: String, venueName: String, types: Set<AlertEventType>, enabled: Boolean) {
        val existing = db.alerts().forInstrument(instrumentId)
        db.alerts().upsert(
            AlertEntity(
                alertId = existing?.alertId ?: 0,
                instrumentId = instrumentId, symbol = symbol, venueName = venueName,
                eventTypes = types.joinToString(",") { it.name }, enabled = enabled,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            )
        )
    }

    suspend fun setAlertEnabled(id: Long, enabled: Boolean) = db.alerts().setEnabled(id, enabled)
    suspend fun deleteAlert(id: Long) = db.alerts().delete(id)
    suspend fun hasEnabledAlerts(): Boolean = db.alerts().enabled().isNotEmpty() || db.paper().open().isNotEmpty()

    /** ALERT05: opening a notification marks the event Opened. */
    suspend fun markOpened(eventId: Long) {
        val e = db.alerts().event(eventId) ?: return
        db.alerts().setDelivery(eventId, Delivery.OPENED, e.sentAt)
    }

    suspend fun recordPaperTrade(
        planKey: String, revision: Int, instrumentId: String, symbol: String, direction: Direction,
        entry: BigDecimal, quantity: BigDecimal, stop: BigDecimal, tp1: BigDecimal?, tp2: BigDecimal?, zoneFarEdge: BigDecimal?, notes: String,
        at: Long,
    ): Long = db.paper().insert(
        PaperTradeEntity(
            planKey = planKey, planRevision = revision, instrumentId = instrumentId, symbol = symbol, direction = direction.name,
            enteredByUserAt = at, entryPrice = entry.toPlainString(), quantity = quantity.toPlainString(),
            remainingQuantity = quantity.toPlainString(), stop = stop.toPlainString(), tp1 = tp1?.toPlainString(), tp2 = tp2?.toPlainString(),
            zoneFarEdge = zoneFarEdge?.toPlainString(), recordedExits = "[]", notes = notes, status = "OPEN", updatedAt = at,
        )
    )

    fun exits(t: PaperTradeEntity): List<RecordedExit> = runCatching { json.decodeFromString<List<RecordedExit>>(t.recordedExits) }.getOrDefault(emptyList())

    /** Records a manual exit. Quantities never go negative; a fully exited trade is closed. */
    suspend fun recordExit(tradeId: Long, price: BigDecimal, qty: BigDecimal, note: String): Boolean {
        val t = db.paper().get(tradeId) ?: return false
        val remaining = BigDecimal(t.remainingQuantity)
        if (qty.signum() <= 0 || qty > remaining || price.signum() <= 0) return false
        val list = exits(t) + RecordedExit(price.toPlainString(), qty.toPlainString(), System.currentTimeMillis(), note)
        val left = remaining - qty
        db.paper().update(
            t.copy(
                recordedExits = json.encodeToString(list), remainingQuantity = left.toPlainString(),
                status = if (left.signum() == 0) "CLOSED" else t.status, updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    suspend fun closeTrade(tradeId: Long) {
        val t = db.paper().get(tradeId) ?: return
        db.paper().update(t.copy(status = "CLOSED", updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteTrade(tradeId: Long) = db.paper().delete(tradeId)

    /**
     * Observes stop/target touches and review events for an open paper trade from closed candles
     * (EXIT02-EXIT04). Touches are observations, never fills; events are deduplicated.
     */
    suspend fun trackPaper(t: PaperTradeEntity, dispatch: Boolean): PaperTrackResult? {
        val inst = runCatching { markets.instrument(t.instrumentId) }.getOrNull() ?: return null
        val bundle = markets.cached(t.instrumentId) ?: runCatching { markets.analyze(inst) }.getOrNull() ?: return null
        val now = bundle.snapshot.evaluatedAt
        val c15 = DataQuality.closedOnly(bundle.snapshot.candles[Timeframe.M15].orEmpty(), now)
        val c1h = DataQuality.closedOnly(bundle.snapshot.candles[Timeframe.H1].orEmpty(), now)
        val dir = Direction.valueOf(t.direction)
        val result = withContext(Dispatchers.Default) {
            PaperTracker.track(
                dir, t.enteredByUserAt, BigDecimal(t.stop), listOfNotNull(t.tp1, t.tp2).map { BigDecimal(it) },
                t.zoneFarEdge?.let { BigDecimal(it) }, c15, c1h, bundle.result.bias?.bias ?: Bias.UNKNOWN, markets.strategy,
            )
        }
        if (dispatch && t.status == "OPEN") {
            val fresh = bundle.result.qualityOk
            for (e in result.events) {
                val type = when (e.type) {
                    TouchType.TARGET_TOUCHED -> AlertEventType.TARGET_TOUCHED
                    TouchType.STOP_TOUCHED -> AlertEventType.STOP_TOUCHED
                    TouchType.AMBIGUOUS -> continue
                    TouchType.REVIEW_TIME, TouchType.REVIEW_DIRECTION, TouchType.THESIS_INVALIDATED -> AlertEventType.REVIEW_NEEDED
                }
                val c = AlertCandidate(
                    type, t.instrumentId, "paper:${t.tradeId}", null, "${e.type.name}:${e.targetIndex ?: 0}", e.price, dir,
                    t.enteredByUserAt, null, e.time,
                )
                dispatcher.dispatch(inst, c, enabledForType = true, dataFresh = fresh, now = now, paper = true)
            }
        }
        return result
    }

    suspend fun wipeAll() = withContext(Dispatchers.IO) {
        db.clearAllTables()
        markets.clearCache()
    }
}
