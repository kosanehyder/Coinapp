package com.coinfutures.app.domain

import com.coinfutures.app.data.db.AlertDao
import com.coinfutures.app.data.db.AlertEventEntity
import com.coinfutures.app.data.db.LOCAL_OWNER
import com.coinfutures.app.data.db.ZoneApproachEntity
import com.coinfutures.app.data.settings.SettingsRepository
import com.coinfutures.app.notifications.Notifier
import com.coinfutures.app.ui.format.Fmt
import com.coinfutures.app.ui.l10n.stringsFor
import com.coinfutures.engine.AlertCandidate
import com.coinfutures.engine.AlertEventType
import com.coinfutures.engine.AlertRules
import com.coinfutures.engine.AnalysisResult
import com.coinfutures.engine.Direction
import com.coinfutures.engine.Instrument
import com.coinfutures.engine.StrategyConfig

object Delivery {
    const val RECORDED = "RECORDED"
    const val SENT = "SENT_TO_SYSTEM"
    const val OPENED = "OPENED"
    const val NOT_SENT_STALE = "NOT_SENT_STALE"
    const val NOT_SENT_DISABLED = "NOT_SENT_DISABLED"
    const val NOT_SENT_PERMISSION = "NOT_SENT_PERMISSION"
}

/**
 * Records deduplicated alert events and dispatches local notifications (ALERT01-ALERT05).
 * Events are recorded only for markets the user explicitly set an alert on.
 */
class AlertDispatcher(
    private val dao: AlertDao,
    private val settings: SettingsRepository,
    private val notifier: Notifier,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun onAnalysis(result: AnalysisResult, planCandidates: List<AlertCandidate>, config: StrategyConfig = StrategyConfig.DEFAULT) {
        val alert = dao.forInstrument(result.instrument.instrumentId) ?: return
        val wanted = alert.eventTypes.split(',').filter { it.isNotBlank() }.toSet()
        val all = planCandidates.toMutableList()
        if (alert.enabled && AlertEventType.ZONE_APPROACH.name in wanted && result.qualityOk) {
            all += zoneApproach(result, config)
        }
        val fresh = result.qualityOk && (result.quoteAgeMs ?: Long.MAX_VALUE) <= config.quoteMaxAgeMs
        for (c in all) dispatch(result.instrument, c, alert.enabled && c.type.name in wanted, fresh, result.snapshotTime)
    }

    private suspend fun zoneApproach(r: AnalysisResult, config: StrategyConfig): List<AlertCandidate> {
        val price = r.currentPrice ?: return emptyList()
        val zones = (r.supports + r.resistances + listOfNotNull(r.zones?.interaction)).distinctBy { it.zoneId }
        val states = dao.zoneStates(r.instrument.instrumentId).associateBy { it.zoneId }
        val out = ArrayList<AlertCandidate>()
        for (z in zones) {
            val prev = states[z.zoneId]?.let { AlertRules.ZoneApproachState(it.zoneId, it.inside, it.lastFiredAt) }
            val (fire, next) = AlertRules.zoneApproach(prev, z, price, r.snapshotTime, config.zoneApproachCooldownMs)
            dao.upsertZoneState(ZoneApproachEntity(r.instrument.instrumentId, z.zoneId, next.inside, next.lastFiredAt))
            if (fire) {
                out += AlertCandidate(
                    AlertEventType.ZONE_APPROACH, r.instrument.instrumentId, null, null, "${z.zoneId}@${r.snapshotTime}",
                    price, null, r.snapshotTime, null, r.snapshotTime,
                )
            }
        }
        return out
    }

    /** Shared by plan alerts and paper-trade tracking. */
    suspend fun dispatch(instrument: Instrument, c: AlertCandidate, enabledForType: Boolean, dataFresh: Boolean, now: Long, paper: Boolean = false) {
        val event = AlertEventEntity(
            ownerId = LOCAL_OWNER,
            dedupKey = c.dedupKey(LOCAL_OWNER),
            eventType = c.type.name,
            instrumentId = c.instrumentId,
            symbol = instrument.symbol,
            planKey = c.planKey,
            planRevision = c.revision,
            direction = c.direction?.name,
            triggerPrice = c.triggerPrice?.toPlainString(),
            planTime = c.planTime,
            validUntil = c.validUntil,
            triggeredAt = c.sourceTime,
            recordedAt = clock(),
            deliveryStatus = Delivery.RECORDED,
            sentAt = null,
        )
        val id = dao.insertEvent(event)
        if (id == -1L) return // duplicate (AT12)
        val s = settings.current()
        val status = when {
            !s.notificationsEnabled || !enabledForType -> Delivery.NOT_SENT_DISABLED
            !AlertRules.dispatchable(c, now, dataFresh) -> Delivery.NOT_SENT_STALE
            else -> {
                val str = stringsFor(s.language)
                val dir = c.direction?.let { str.direction(it == Direction.LONG) + " · " }.orEmpty()
                val price = c.triggerPrice?.let { " · " + Fmt.price(it, instrument) }.orEmpty()
                val valid = c.validUntil?.let { " · ${str.validUntil}: " + Fmt.shortTime(it, s.timeDisplay) }.orEmpty()
                val planTime = c.planTime?.let { " · " + Fmt.shortTime(it, s.timeDisplay) }.orEmpty()
                val body = dir + str.eventType(c.type) + price + planTime + valid
                if (notifier.post(id, c.instrumentId, c.planKey, str.notifTitle(c.type, instrument.symbol), body, paper)) Delivery.SENT
                else Delivery.NOT_SENT_PERMISSION
            }
        }
        dao.setDelivery(id, status, if (status == Delivery.SENT) clock() else null)
    }
}
