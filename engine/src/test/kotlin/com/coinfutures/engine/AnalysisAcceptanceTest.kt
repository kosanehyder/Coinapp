package com.coinfutures.engine

import com.coinfutures.engine.demo.Fixtures
import com.coinfutures.engine.demo.Fixtures.Scenario
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Acceptance tests from section 10 that exercise the full analysis pipeline. */
class AnalysisAcceptanceTest {

    private fun run(s: Scenario, mirrored: Boolean = false, cfg: StrategyConfig = StrategyConfig.DEFAULT, spread: String = "0.02", quoteAgeMs: Long = 1_000, center: String = "200") =
        AnalysisEngine.analyze(Fixtures.snapshot(s, mirrored = mirrored, spread = bd(spread), quoteAgeMs = quoteAgeMs, config = cfg, center = bd(center)), cfg)

    @Test
    fun longEntryReadyHasOrderedFrozenLevels() {
        val r = run(Scenario.ENTRY_READY)
        assertEquals(OverallStatus.LONG_SETUP, r.status)
        assertEquals(ReasonCode.ENTRY_READY, r.primaryReason)
        val p = assertNotNull(r.plan)
        assertEquals(PlanState.ENTRY_READY, p.state)
        assertTrue(p.actionable)
        val band = assertNotNull(p.entryBand)
        val stop = assertNotNull(p.stop)
        assertEquals(2, p.targets.size)
        assertTrue(stop < band.lower && band.upper < p.targets[0].price && p.targets[0].price < p.targets[1].price)
        assertTrue(p.metrics!!.netRrTp2!! >= bd("2.0"))
        assertEquals(p.zone.upper, band.lower)
        // Every frozen price is a valid tick multiple.
        for (v in listOf(stop, band.lower, band.upper) + p.targets.map { it.price }) {
            assertEquals(0, v.remainder(Fixtures.TICK).signum(), "tick alignment of $v")
        }
        assertTrue(ReasonCode.NEWS_NOT_CHECKED in r.limitations)
        assertTrue(ReasonCode.LIQUIDATION_NOT_CALCULATED in r.limitations)
    }

    @Test
    fun lifecycleProgressesThroughWaitingStates() {
        val a = run(Scenario.WATCHING)
        assertEquals(OverallStatus.WAIT, a.status)
        assertEquals(ReasonCode.AWAITING_BREAKOUT, a.primaryReason)
        assertNull(a.plan)
        assertNotNull(a.nextCondition?.price)

        val b = run(Scenario.WAITING_RETEST)
        assertEquals(OverallStatus.WAIT, b.status)
        assertEquals(PlanState.WAITING_RETEST, b.plan?.state)
        assertEquals(8, b.nextCondition?.candlesRemaining)

        val c = run(Scenario.WAITING_CONFIRMATION)
        assertEquals(OverallStatus.WAIT, c.status)
        assertEquals(PlanState.WAITING_CONFIRMATION, c.plan?.state)
        assertFalse(c.plan!!.actionable, "waiting confirmation is never actionable")
    }

    /** AT03: identical snapshot and strategy version produce identical results. */
    @Test
    fun at03StableReplay() {
        val s = Fixtures.snapshot(Scenario.ENTRY_READY)
        val a = AnalysisEngine.analyze(s)
        val b = AnalysisEngine.analyze(s.copy())
        assertEquals(a, b)
        assertEquals(a.analysisId, b.analysisId)
        // A later snapshot reproduces the same frozen plan levels (historical prices never move).
        val later = run(Scenario.ENTRY_EXPIRED)
        assertEquals(a.plan!!.planKey, later.plan!!.planKey)
        assertEquals(a.plan!!.stop, later.plan!!.stop)
        assertEquals(a.plan!!.entryBand, later.plan!!.entryBand)
        assertEquals(a.plan!!.targets, later.plan!!.targets)
    }

    /** AT04: a wick beyond resistance with a close inside the zone creates no breakout. */
    @Test
    fun at04WickIsNotABreakout() {
        val r = run(Scenario.WICK_FALSE_BREAK)
        assertNull(r.plan)
        assertEquals(ReasonCode.AWAITING_BREAKOUT, r.primaryReason)
    }

    /** AT05: mirrored prices produce mirrored direction, rounding and targets. */
    @Test
    fun at05LongShortSymmetry() {
        val zeroCost = StrategyConfig.DEFAULT.copy(gateCosts = CostModel(BigDecimal.ZERO, Slippage.Absolute(BigDecimal.ZERO)))
        val center = bd("104.5")
        val two = center.multiply(BigDecimal(2))
        for (s in listOf(Scenario.ENTRY_READY, Scenario.WAITING_CONFIRMATION, Scenario.ENTRY_EXPIRED, Scenario.INVALIDATED)) {
            val l = run(s, cfg = zeroCost)
            val sh = run(s, mirrored = true, cfg = zeroCost, center = "104.5")
            val lp = l.plan!!
            val sp = sh.plan!!
            assertEquals(Direction.LONG, lp.direction)
            assertEquals(Direction.SHORT, sp.direction)
            assertEquals(lp.state, sp.state, "state for $s")
            assertEquals(lp.terminalReason, sp.terminalReason)
            assertEquals(two - lp.stop!!, sp.stop, "stop mirror for $s")
            assertEquals(two - lp.entryBand!!.upper, sp.entryBand!!.lower)
            assertEquals(two - lp.entryBand!!.lower, sp.entryBand!!.upper)
            assertEquals(lp.targets.map { two - it.price }, sp.targets.map { it.price })
            assertEquals(lp.metrics!!.netRrTp2!!.compareTo(sp.metrics!!.netRrTp2!!), 0)
        }
        assertEquals(OverallStatus.SHORT_SETUP, run(Scenario.ENTRY_READY, mirrored = true, center = "104.5").status)
    }

    /** AT06: spread above limit, missing second target, or net TP2 ratio below 2.0 -> No Trade with exact reason. */
    @Test
    fun at06GateFailures() {
        val wide = run(Scenario.ENTRY_READY, spread = "0.30")
        assertEquals(OverallStatus.NO_TRADE, wide.status)
        assertEquals(ReasonCode.SPREAD_TOO_WIDE, wide.primaryReason)

        val missing = run(Scenario.MISSING_TARGETS)
        assertEquals(OverallStatus.NO_TRADE, missing.status)
        assertEquals(ReasonCode.MISSING_TARGETS, missing.primaryReason)

        val strict = StrategyConfig.DEFAULT.copy(version = "test-strict-rr", minNetRrTp2 = bd("50"))
        val rr = run(Scenario.ENTRY_READY, cfg = strict)
        assertEquals(OverallStatus.NO_TRADE, rr.status)
        assertEquals(ReasonCode.RR_TOO_LOW, rr.primaryReason)
        assertFalse(rr.plan!!.actionable)
    }

    /** AT07: stale quote or unresolved candle gap disables new actionable signals. */
    @Test
    fun at07MissingData() {
        val stale = run(Scenario.ENTRY_READY, quoteAgeMs = 11_000)
        assertEquals(OverallStatus.DATA_UNAVAILABLE, stale.status)
        assertEquals(ReasonCode.QUOTE_STALE, stale.primaryReason)
        assertTrue(stale.nextCondition == null)

        val s = Fixtures.snapshot(Scenario.ENTRY_READY)
        val m15 = s.candles.getValue(Timeframe.M15).toMutableList().also { it.removeAt(it.size - 40) }
        val gap = AnalysisEngine.analyze(s.copy(candles = s.candles + (Timeframe.M15 to m15)))
        assertEquals(OverallStatus.DATA_UNAVAILABLE, gap.status)
        assertEquals(ReasonCode.CANDLE_GAP, gap.primaryReason)
        assertNull(gap.plan)

        val noQuote = AnalysisEngine.analyze(s.copy(quote = null))
        assertEquals(OverallStatus.DATA_UNAVAILABLE, noQuote.status)
        assertEquals(ReasonCode.QUOTE_MISSING, noQuote.primaryReason)

        // A closed candle missing for more than 30 seconds after it was due.
        val late = AnalysisEngine.analyze(s.copy(evaluatedAt = s.evaluatedAt + Timeframe.M15.millis + 40_000, quote = s.quote!!.copy(receivedAt = s.evaluatedAt + Timeframe.M15.millis + 39_000)))
        assertEquals(OverallStatus.DATA_UNAVAILABLE, late.status)
        assertEquals(ReasonCode.CANDLE_STALE, late.primaryReason)
    }

    @Test
    fun insufficientHistoryIsDataUnavailable() {
        val s = Fixtures.snapshot(Scenario.ENTRY_READY)
        val short = s.copy(candles = s.candles.mapValues { it.value.takeLast(120) })
        val r = AnalysisEngine.analyze(short)
        assertEquals(OverallStatus.DATA_UNAVAILABLE, r.status)
        assertEquals(ReasonCode.INSUFFICIENT_HISTORY, r.primaryReason)
    }

    /** AT08: the quote never enters the band during four candles -> setup expires without a fill. */
    @Test
    fun at08EntryExpiration() {
        val r = run(Scenario.ENTRY_EXPIRED)
        val p = r.plan!!
        assertEquals(PlanState.EXPIRED, p.state)
        assertEquals(ReasonCode.ENTRY_WINDOW_EXPIRED, p.terminalReason)
        assertFalse(p.bandTouched)
        assertEquals(OverallStatus.WAIT, r.status)
        assertEquals(ReasonCode.PREVIOUS_SETUP_EXPIRED, r.primaryReason)
    }

    @Test
    fun invalidationByFarSideCloseAndStopTouch() {
        val a = run(Scenario.INVALIDATED).plan!!
        assertEquals(PlanState.INVALIDATED, a.state)
        assertEquals(ReasonCode.FAR_SIDE_CLOSE, a.terminalReason)
        val b = run(Scenario.STOP_TOUCH).plan!!
        assertEquals(PlanState.INVALIDATED, b.state)
        assertEquals(ReasonCode.STOP_TOUCHED, b.terminalReason)
    }

    @Test
    fun mixedStructureWaitsAndNeverImprovisesOppositeTrade() {
        val r = run(Scenario.RANGE)
        assertEquals(OverallStatus.WAIT, r.status)
        assertEquals(ReasonCode.MIXED_STRUCTURE, r.primaryReason)
        assertEquals(Bias.NEUTRAL, r.bias!!.bias)
        assertNull(r.plan)
    }

    @Test
    fun liveCandleNeverChangesStructure() {
        val s = Fixtures.snapshot(Scenario.ENTRY_READY)
        val m15 = s.candles.getValue(Timeframe.M15)
        val last = m15.last()
        // Append an in-progress candle that would invalidate the setup if it were treated as closed.
        val live = last.copy(openTime = last.closeTime, closeTime = last.closeTime + Timeframe.M15.millis, close = bd("90"), low = bd("89"))
        val r = AnalysisEngine.analyze(s.copy(candles = s.candles + (Timeframe.M15 to m15 + live)))
        assertEquals(PlanState.ENTRY_READY, r.plan!!.state)
    }
}
