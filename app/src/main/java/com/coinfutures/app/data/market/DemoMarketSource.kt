package com.coinfutures.app.data.market

import com.coinfutures.engine.ContractType
import com.coinfutures.engine.ErrorCode
import com.coinfutures.engine.Instrument
import com.coinfutures.engine.MarketSnapshot
import com.coinfutures.engine.TradingStatus
import com.coinfutures.engine.demo.Fixtures
import com.coinfutures.engine.demo.Fixtures.Scenario
import java.math.BigDecimal

/**
 * Clearly labelled simulated venue backed by deterministic fixtures. It lets every app state
 * (Long/Short setup, Wait, No Trade, Data Unavailable, ambiguous symbols, delisted markets) be
 * reviewed offline. Prices are fictional and carry no market meaning.
 */
class DemoMarketSource(clock: () -> Long = System::currentTimeMillis) : MarketDataSource {
    override val venueId = Venues.DEMO
    override val venueName = "Demo (simulated)"
    override val simulated = true

    private data class Entry(val instrument: Instrument, val scenario: Scenario?, val mirrored: Boolean = false, val quoteAgeMs: Long = 1_000, val spread: String = "0.02")

    /** Fixture start, 4H-aligned so the simulated snapshot ends a few hours before "now". */
    private val t0: Long = run {
        val fourH = 4 * 3_600_000L
        Math.floorDiv(clock() - 1_296L * 3_600_000L, fourH) * fourH
    }

    private val entries: List<Entry> = listOf(
        Entry(Fixtures.instrument("TESTUSDT", "TEST", "Test Coin"), Scenario.ENTRY_READY),
        Entry(
            Fixtures.instrument("TESTUSDT_261225", "TEST", "Test Coin quarterly").copy(contractType = ContractType.DELIVERY),
            null,
        ),
        Entry(Fixtures.instrument("SHORTYUSDT", "SHORTY", "Shorty Token"), Scenario.ENTRY_READY, mirrored = true),
        Entry(Fixtures.instrument("RETESTUSDT", "RETEST", "Retest Network"), Scenario.WAITING_RETEST),
        Entry(Fixtures.instrument("CONFIRMUSDT", "CONFIRM", "Confirm Chain"), Scenario.WAITING_CONFIRMATION),
        Entry(Fixtures.instrument("WATCHUSDT", "WATCH", "Watch Protocol"), Scenario.WATCHING),
        Entry(Fixtures.instrument("RANGEUSDT", "RANGE", "Range Finance"), Scenario.RANGE),
        Entry(Fixtures.instrument("NOTGTUSDT", "NOTGT", "No Target Labs"), Scenario.MISSING_TARGETS),
        Entry(Fixtures.instrument("WIDEUSDT", "WIDE", "Wide Spread Coin"), Scenario.ENTRY_READY, spread = "0.40"),
        Entry(Fixtures.instrument("EXPIREDUSDT", "EXPIRED", "Expired Setup Coin"), Scenario.ENTRY_EXPIRED),
        Entry(Fixtures.instrument("STALEUSDT", "STALE", "Stale Feed Coin"), Scenario.ENTRY_READY, quoteAgeMs = 25_000),
        Entry(Fixtures.instrument("DUPUSDT", "DUP", "Duplicate One"), Scenario.WATCHING),
        Entry(Fixtures.instrument("DUPXUSDT", "DUP", "Duplicate Two").copy(base = "DUP"), Scenario.RANGE),
        Entry(Fixtures.instrument("OLDUSDT", "OLD", "Old Delisted Coin").copy(tradingStatus = TradingStatus.DELISTED), null),
    )

    private val cache = HashMap<String, MarketSnapshot>()

    override suspend fun instruments(forceRefresh: Boolean): List<Instrument> = entries.map { it.instrument }

    override suspend fun snapshot(instrument: Instrument): MarketSnapshot {
        val e = entries.firstOrNull { it.instrument.instrumentId == instrument.instrumentId }
            ?: throw MarketException(ErrorCode.UNKNOWN_INSTRUMENT, "Unknown demo instrument")
        val scenario = e.scenario ?: throw MarketException(ErrorCode.UNSUPPORTED_CONTRACT, "Not a supported futures market")
        return synchronized(cache) {
            cache.getOrPut(instrument.instrumentId) {
                Fixtures.snapshot(
                    scenario, t0 = t0, instrument = e.instrument, mirrored = e.mirrored,
                    spread = BigDecimal(e.spread), quoteAgeMs = e.quoteAgeMs, center = BigDecimal("104.5"),
                )
            }
        }
    }

    override suspend fun ping(): Long = 0
}
