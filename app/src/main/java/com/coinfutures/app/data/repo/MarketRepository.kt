package com.coinfutures.app.data.repo

import com.coinfutures.app.data.db.AppDatabase
import com.coinfutures.app.data.db.MarketEntity
import com.coinfutures.app.data.market.MarketDataSource
import com.coinfutures.app.data.market.MarketException
import com.coinfutures.app.data.market.Venues
import com.coinfutures.app.domain.AlertDispatcher
import com.coinfutures.app.domain.PlanTracker
import com.coinfutures.engine.AnalysisEngine
import com.coinfutures.engine.AnalysisResult
import com.coinfutures.engine.ErrorCode
import com.coinfutures.engine.Instrument
import com.coinfutures.engine.InstrumentResolver
import com.coinfutures.engine.MarketSnapshot
import com.coinfutures.engine.SearchResult
import com.coinfutures.engine.StrategyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Snapshot + result + the persisted plan revision it maps to. */
data class AnalysisBundle(val snapshot: MarketSnapshot, val result: AnalysisResult, val planRevision: Int?)

class MarketRepository(
    private val sources: Map<String, MarketDataSource>,
    private val db: AppDatabase,
    private val tracker: PlanTracker,
    private val dispatcher: AlertDispatcher,
    private val config: StrategyConfig = StrategyConfig.DEFAULT,
) {
    private val latest = ConcurrentHashMap<String, AnalysisBundle>()

    fun source(venue: String): MarketDataSource =
        sources[venue] ?: throw MarketException(ErrorCode.UNKNOWN_INSTRUMENT, "Unknown venue $venue")

    val strategy: StrategyConfig get() = config

    suspend fun search(query: String, venue: String): SearchResult = withContext(Dispatchers.Default) {
        InstrumentResolver(source(venue).instruments()).search(query)
    }

    suspend fun instrument(instrumentId: String): Instrument {
        val src = source(Venues.venueOf(instrumentId))
        return src.instruments().firstOrNull { it.instrumentId == instrumentId }
            ?: src.instruments(forceRefresh = true).firstOrNull { it.instrumentId == instrumentId }
            ?: throw MarketException(ErrorCode.UNKNOWN_INSTRUMENT, "Unknown instrument")
    }

    fun cached(instrumentId: String): AnalysisBundle? = latest[instrumentId]

    /**
     * Fetch, analyze, persist plan transitions and dispatch alerts. The engine is the only place a
     * signal is computed; the UI only renders [AnalysisResult].
     */
    suspend fun analyze(instrument: Instrument): AnalysisBundle {
        if (!instrument.isSupported) throw MarketException(ErrorCode.UNSUPPORTED_CONTRACT, "Unsupported contract")
        val snapshot = source(instrument.venue).snapshot(instrument)
        val result = withContext(Dispatchers.Default) { AnalysisEngine.analyze(snapshot, config) }
        val outcome = tracker.record(result)
        dispatcher.onAnalysis(result, outcome.candidates, config)
        val bundle = AnalysisBundle(snapshot, result, outcome.revision)
        latest[instrument.instrumentId] = bundle
        val existing = db.markets().get(instrument.instrumentId)
        db.markets().upsert(
            (existing ?: entity(instrument)).copy(
                lastStatus = result.status.name,
                lastPrice = result.currentPrice?.toPlainString(),
                lastAnalysisAt = result.snapshotTime,
            )
        )
        return bundle
    }

    suspend fun markViewed(instrument: Instrument, now: Long = System.currentTimeMillis()) {
        val existing = db.markets().get(instrument.instrumentId)
        db.markets().upsert((existing ?: entity(instrument)).copy(lastViewedAt = now))
    }

    suspend fun setSaved(instrument: Instrument, saved: Boolean) {
        val existing = db.markets().get(instrument.instrumentId)
        db.markets().upsert((existing ?: entity(instrument)).copy(saved = saved))
    }

    fun observeMarket(instrumentId: String): Flow<MarketEntity?> = db.markets().observe(instrumentId)
    fun saved(): Flow<List<MarketEntity>> = db.markets().saved()
    fun recent(): Flow<List<MarketEntity>> = db.markets().recent(8)

    suspend fun ping(venue: String): Long = source(venue).ping()

    fun clearCache() = latest.clear()

    private fun entity(i: Instrument) = MarketEntity(
        instrumentId = i.instrumentId, venue = i.venue, venueName = i.venueName, symbol = i.symbol, base = i.base,
        displayName = i.displayName, saved = false, lastViewedAt = null, lastStatus = null, lastPrice = null, lastAnalysisAt = null,
    )
}
