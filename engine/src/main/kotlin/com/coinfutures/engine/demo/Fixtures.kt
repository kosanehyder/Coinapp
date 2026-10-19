package com.coinfutures.engine.demo

import com.coinfutures.engine.Candle
import com.coinfutures.engine.ContractType
import com.coinfutures.engine.Dec
import com.coinfutures.engine.Funding
import com.coinfutures.engine.Indicators
import com.coinfutures.engine.Instrument
import com.coinfutures.engine.MarketSnapshot
import com.coinfutures.engine.OpenInterest
import com.coinfutures.engine.Quote
import com.coinfutures.engine.StrategyConfig
import com.coinfutures.engine.Timeframe
import com.coinfutures.engine.TradingStatus
import com.coinfutures.engine.ZoneRole
import com.coinfutures.engine.Zones
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Deterministic, fictional market fixtures (TESTUSDT and friends). They are used by the unit
 * tests and by the app's clearly labelled "Demo (simulated)" venue for UI testing. They are not
 * market data and carry no trading meaning.
 */
object Fixtures {

    enum class Scenario {
        /** Long breakout, retest and confirmation complete; live quote inside the entry band. */
        ENTRY_READY,
        /** Breakout candle closed; waiting for the 15M retest. */
        WAITING_RETEST,
        /** Retest done; waiting for confirmation. */
        WAITING_CONFIRMATION,
        /** Entry Ready but the quote never re-entered the band for four 15M candles (AT08). */
        ENTRY_EXPIRED,
        /** Entry Ready, then a 15M close below the zone (EXIT03). */
        INVALIDATED,
        /** Entry Ready, then a stop touch. */
        STOP_TOUCH,
        /** Wick above resistance but the 1H close stays inside the zone (AT04). */
        WICK_FALSE_BREAK,
        /** Upper structure missing: fewer than two targets (AT06). */
        MISSING_TARGETS,
        /** Before the breakout: watching for a 1H close above resistance. */
        WATCHING,
        /** Converging range: 4H structure is mixed. */
        RANGE,
    }

    val TICK = BigDecimal("0.01")
    private val WICK = BigDecimal("0.05")
    private const val H = 3_600_000L
    private const val M15 = 900_000L
    /** 2026-01-01T00:00:00Z, aligned to 4H. */
    const val DEFAULT_T0 = 1_767_225_600_000L

    fun instrument(symbol: String = "TESTUSDT", base: String = "TEST", name: String = "Test Coin (simulated)") = Instrument(
        instrumentId = "DEMO:$symbol",
        venue = "DEMO",
        venueName = "Demo (simulated)",
        symbol = symbol,
        base = base,
        quote = "USDT",
        contractType = ContractType.PERPETUAL,
        linear = true,
        multiplier = BigDecimal.ONE,
        tickSize = TICK,
        qtyStep = BigDecimal("0.001"),
        minQty = BigDecimal("0.001"),
        minNotional = BigDecimal("5"),
        tradingStatus = TradingStatus.TRADING,
        displayName = name,
        simulated = true,
    )

    data class Built(
        val m15: List<Candle>,
        val now: Long,
        val quoteLast: BigDecimal,
        val zoneLower: BigDecimal,
        val zoneUpper: BigDecimal,
    )

    private data class Wp(val hour: Double, val price: Double)

    /**
     * Uptrend with higher 4H swing highs/lows. Two brief spikes (S1, S2) above the current range
     * leave 1H resistance pivots that become TP1/TP2; equal consolidation peaks at 104.50 form the
     * breakout zone without creating 4H pivots (ties never create pivots). Turning points sit inside
     * 4H candles so each one is unique.
     */
    private fun trendPath(upperZones: Boolean): List<Wp> {
        val base = mutableListOf(
            Wp(0.0, 55.0), Wp(152.5, 70.0), Wp(202.5, 64.0), Wp(352.5, 80.0), Wp(422.5, 74.0),
            Wp(602.5, 90.0), Wp(682.5, 80.0), Wp(1080.5, 101.0),
        )
        if (upperZones) {
            base += listOf(Wp(1090.5, 101.2), Wp(1092.5, 106.0), Wp(1094.5, 101.5))
        } else {
            base += listOf(Wp(1090.5, 101.2), Wp(1092.5, 102.4), Wp(1094.5, 101.5))
        }
        base += listOf(Wp(1110.5, 100.0), Wp(1115.5, 102.0), Wp(1128.5, 102.3))
        if (upperZones) {
            base += listOf(Wp(1130.5, 107.5), Wp(1132.5, 102.5))
        } else {
            base += listOf(Wp(1130.5, 102.9), Wp(1132.5, 102.5))
        }
        base += listOf(Wp(1150.5, 101.0), Wp(1184.5, 104.45))
        var h = 1184.5
        var valley = 103.0
        while (h < 1280.0) {
            base += Wp(h + 4.0, valley)
            base += Wp(h + 8.0, 104.45)
            valley += 0.08
            h += 8.0
        }
        base += Wp(1284.5, valley)
        base += Wp(1288.0, 103.9)
        return base
    }

    private fun rangePath(): List<Wp> {
        val out = mutableListOf(Wp(0.0, 100.0))
        var h = 2.5
        var hi = 112.0
        var lo = 88.0
        var up = true
        while (h < 1300.0) {
            h += 36.0
            out += Wp(h, if (up) hi else lo)
            if (up) hi -= 0.25 else lo += 0.25
            up = !up
        }
        return out
    }

    private fun interp(path: List<Wp>, hour: Double): BigDecimal {
        val i = path.indexOfLast { it.hour <= hour }.coerceAtLeast(0)
        val a = path[i]
        val b = path.getOrElse(i + 1) { a }
        val p = if (b.hour == a.hour) a.price else a.price + (b.price - a.price) * (hour - a.hour) / (b.hour - a.hour)
        return BigDecimal(p).setScale(2, RoundingMode.HALF_EVEN)
    }

    private fun candle(t: Long, o: BigDecimal, c: BigDecimal, vol: BigDecimal, highExtra: BigDecimal = Dec.ZERO, lowExtra: BigDecimal = Dec.ZERO): Candle {
        val hi = Dec.max(o, c) + WICK + highExtra
        val lo = Dec.min(o, c) - WICK - lowExtra
        return Candle(t, t + M15, o, hi, lo, c, vol)
    }

    private fun baseVolume(i: Int) = BigDecimal(1000 + (i % 7) * 20)

    /** Generates the scenario's closed 15M candles from [t0]; 1H/4H are aggregated from them. */
    fun build(scenario: Scenario, t0: Long = DEFAULT_T0, config: StrategyConfig = StrategyConfig.DEFAULT): Built {
        val path = when (scenario) {
            Scenario.RANGE -> rangePath()
            Scenario.MISSING_TARGETS -> trendPath(upperZones = false)
            else -> trendPath(upperZones = true)
        }
        val endHours = if (scenario == Scenario.RANGE) 1300 else 1288
        val m15 = ArrayList<Candle>()
        val steps = endHours * 4
        for (i in 0 until steps) {
            val o = interp(path, i * 0.25)
            val c = interp(path, (i + 1) * 0.25)
            m15 += candle(t0 + i * M15, o, c, baseVolume(i))
        }
        if (scenario == Scenario.RANGE) {
            val now = m15.last().closeTime + 5_000
            return Built(m15, now, m15.last().close, Dec.ZERO, Dec.ZERO)
        }

        // Locate the frozen resistance zone the way the engine will see it at the breakout.
        val h1 = aggregate(m15, Timeframe.H1).takeLast(500)
        val atr = Indicators.atr(h1, config.atrPeriod).last()!!
        val zones = Zones.build(h1, h1.lastIndex, atr, TICK, h1.last().close, config)
        val zone = zones.all.filter { it.role != ZoneRole.SUPPORT }.minBy { (it.mid - BigDecimal("104.5")).abs() }
        val u = zone.upper
        val l = zone.lower
        val buffer = Dec.ceilTo(atr.multiply(config.breakBufferAtr), TICK)
        var t = t0 + steps * M15
        var i = steps
        var prev = m15.last().close
        fun add(close: BigDecimal, volMul: Int = 1, highExtra: BigDecimal = Dec.ZERO, lowExtra: BigDecimal = Dec.ZERO) {
            m15 += candle(t, prev, close, baseVolume(i).multiply(BigDecimal(volMul)), highExtra, lowExtra)
            prev = close; t += M15; i++
        }
        val p = { s: String -> BigDecimal(s) }
        // 1H candle before the breakout: closes below the zone.
        add(l - p("0.30")); add(l - p("0.20")); add(l - p("0.28")); add(l - p("0.22"))
        if (scenario == Scenario.WATCHING) {
            return Built(m15, t + 5 * 60_000, l - p("0.20"), l, u)
        }
        if (scenario == Scenario.WICK_FALSE_BREAK) {
            add(l + p("0.05"), 4); add(u - p("0.02"), 4, highExtra = p("0.80")); add(u - p("0.04"), 4); add(u - p("0.06"), 4)
            return Built(m15, t + 5 * 60_000, u - p("0.05"), l, u)
        }
        // Breakout 1H candle with ~4x volume, close above the upper bound + buffer.
        add(l + p("0.10"), 4); add(u, 4); add(u + p("0.15"), 4); add(u + buffer + p("0.30"), 4)
        if (scenario == Scenario.WAITING_RETEST) return Built(m15, t + 10 * 60_000, u + buffer + p("0.25"), l, u)
        // Retest: overlaps the zone and closes at/above the upper bound.
        add(u + p("0.15")); add(u + p("0.04"))
        if (scenario == Scenario.WAITING_CONFIRMATION) return Built(m15, t + 3 * 60_000, u + p("0.10"), l, u)
        // Confirmation: close above retest high + 1 tick with RSI > 50.
        add(u + p("0.12")); add(u + p("0.30"))
        when (scenario) {
            Scenario.ENTRY_READY, Scenario.MISSING_TARGETS -> return Built(m15, t + 5_000, u + p("0.02"), l, u)
            Scenario.ENTRY_EXPIRED -> {
                add(u + p("0.40")); add(u + p("0.45")); add(u + p("0.50")); add(u + p("0.55"))
                return Built(m15, t + 5_000, u + p("0.55"), l, u)
            }
            Scenario.INVALIDATED -> {
                add(u + p("0.05")); add(l - p("0.10"))
                return Built(m15, t + 5_000, l - p("0.08"), l, u)
            }
            Scenario.STOP_TOUCH -> {
                // Wick through the stop while the close stays inside the zone.
                add(u + p("0.05")); add(u - p("0.02"), lowExtra = p("0.40"))
                return Built(m15, t + 5_000, u - p("0.01"), l, u)
            }
            else -> error("unhandled $scenario")
        }
    }

    fun aggregate(m15: List<Candle>, tf: Timeframe): List<Candle> {
        if (tf == Timeframe.M15) return m15
        val out = ArrayList<Candle>()
        var group = ArrayList<Candle>()
        var key = Long.MIN_VALUE
        fun flush() {
            if (group.isEmpty()) return
            val open = key
            // Only complete (closed) higher-timeframe candles are emitted.
            if (group.size.toLong() * Timeframe.M15.millis == tf.millis) {
                out += Candle(
                    open, open + tf.millis, group.first().open, group.maxOf { it.high }, group.minOf { it.low },
                    group.last().close, group.fold(Dec.ZERO) { a, c -> a + c.volume },
                )
            }
            group = ArrayList()
        }
        for (c in m15) {
            val k = Math.floorDiv(c.openTime, tf.millis) * tf.millis
            if (k != key) { flush(); key = k }
            group += c
        }
        flush()
        return out
    }

    /** Mirror prices around [center] (p -> 2*center - p) for symmetry tests and the short demo. */
    fun mirror(m15: List<Candle>, center: BigDecimal = BigDecimal(200)): List<Candle> {
        val two = center.multiply(BigDecimal(2))
        return m15.map { c -> c.copy(open = two - c.open, high = two - c.low, low = two - c.high, close = two - c.close) }
    }

    fun snapshot(
        scenario: Scenario,
        t0: Long = DEFAULT_T0,
        instrument: Instrument = instrument(),
        mirrored: Boolean = false,
        spread: BigDecimal = BigDecimal("0.02"),
        quoteAgeMs: Long = 1_000,
        config: StrategyConfig = StrategyConfig.DEFAULT,
        center: BigDecimal = BigDecimal(200),
    ): MarketSnapshot {
        val b = build(scenario, t0, config)
        val m15 = if (mirrored) mirror(b.m15, center) else b.m15
        val last = if (mirrored) center.multiply(BigDecimal(2)) - b.quoteLast else b.quoteLast
        val half = spread.divide(BigDecimal(2))
        val bid = Dec.floorTo(last - half, TICK)
        val ask = Dec.ceilTo(last + half, TICK).let { if (it <= bid) bid + TICK else it }
        val quote = Quote(bid, ask, last, last, b.now - quoteAgeMs, b.now - quoteAgeMs)
        return MarketSnapshot(
            instrument = instrument,
            candles = mapOf(
                Timeframe.M15 to m15.takeLast(500),
                Timeframe.H1 to aggregate(m15, Timeframe.H1).takeLast(500),
                Timeframe.H4 to aggregate(m15, Timeframe.H4).takeLast(500),
            ),
            quote = quote,
            funding = Funding(BigDecimal("0.0001"), 8, b.now + 2 * H, b.now - 2_000, b.now - 2_000),
            openInterest = OpenInterest(BigDecimal("125000"), "contracts", b.now - 30_000, b.now - 30_000),
            btcContext4h = null,
            evaluatedAt = b.now,
            source = instrument.venueName,
        )
    }
}
