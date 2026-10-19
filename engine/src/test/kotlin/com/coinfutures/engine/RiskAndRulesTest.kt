package com.coinfutures.engine

import com.coinfutures.engine.demo.Fixtures
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RiskAndRulesTest {

    private val test = Fixtures.instrument()
    private val exampleCosts = CostModel(bd("0.0005"), Slippage.Absolute(bd("0.02")), BigDecimal.ZERO)
    private val exampleTargets = listOf(TargetSpec(bd("104.00"), bd("0.5")), TargetSpec(bd("108.00"), bd("0.5")))

    private fun example(equity: String = "1000", instrument: Instrument = test) = RiskCalculator.estimate(
        Direction.LONG, bd("98.00"), exampleTargets, instrument,
        RiskInputs(bd(equity), bd("0.5"), bd("100.25"), bd("3"), null, exampleCosts),
    )

    /** AT09: the page 7 example returns 2.092 coins and a planned loss no more than 5 USDT. */
    @Test
    fun at09RiskExample() {
        val e = assertIs<RiskResult.Ok>(example()).estimate
        assertBd("5", e.riskBudget)
        assertBd("2.25", e.perCoin.priceRisk)
        assertBd("0.099125", e.perCoin.fees)
        assertBd("0.04", e.perCoin.slippage)
        assertBd("2.389125", e.perCoin.total)
        assertBd("2.092", e.quantity)
        assertTrue(e.plannedLoss <= bd("5"), "planned loss ${e.plannedLoss}")
        assertBd("209.72", e.notional.setScale(2, RoundingMode.HALF_EVEN))
        assertBd("69.91", e.marginEstimate.setScale(2, RoundingMode.HALF_EVEN))
        assertBd("3.18", e.netRrFinal!!.setScale(2, RoundingMode.HALF_EVEN))
        assertBd("2.35", e.blendedNetRr!!.setScale(2, RoundingMode.HALF_EVEN))
        assertTrue(e.splitAccepted)
        assertEquals(e.quantity, e.targets.fold(BigDecimal.ZERO) { a, t -> a + t.quantity }, "split preserves total")
        assertFalse(e.liquidationCalculated)
    }

    /** AT10: step rounding, multiplier and minimum notional are enforced; never round up past budget. */
    @Test
    fun at10QuantityLimits() {
        // Tiny account: below minimum notional -> MIN_SIZE_CONFLICT.
        val small = assertIs<RiskResult.Invalid>(example(equity = "1"))
        assertEquals(listOf(RiskError.MIN_SIZE_CONFLICT), small.errors)
        assertEquals(ErrorCode.MIN_SIZE_CONFLICT, small.errors.first().code)

        // Contract multiplier 10 coins per contract, step 1 contract.
        val m10 = test.copy(multiplier = bd("10"), qtyStep = bd("1"), minQty = bd("1"), minNotional = bd("5"))
        val e = assertIs<RiskResult.Ok>(example(equity = "20000", instrument = m10)).estimate
        // budget 100 / (2.389125 * 10) = 4.18 -> 4 contracts (rounded down)
        assertBd("4", e.quantity)
        assertBd("40", e.coins)
        assertTrue(e.plannedLoss <= e.riskBudget)

        // Partial split violating minimum size falls back to a labelled single exit.
        val coarse = test.copy(qtyStep = bd("1"), minQty = bd("1"), minNotional = bd("5"))
        val one = assertIs<RiskResult.Ok>(example(equity = "600", instrument = coarse)).estimate
        assertBd("1", one.quantity)
        assertFalse(one.splitAccepted)
        assertEquals(1, one.targets.size)
        assertBd("108.00", one.targets.single().price)
    }

    @Test
    fun riskInputValidation() {
        val bad = RiskCalculator.estimate(Direction.LONG, bd("100.25"), exampleTargets, test, RiskInputs(bd("1000"), bd("0.5"), bd("100.25"), bd("3"), null, exampleCosts))
        assertEquals(listOf(RiskError.ZERO_DISTANCE), assertIs<RiskResult.Invalid>(bad).errors)
        val wrongSide = RiskCalculator.estimate(Direction.SHORT, bd("98"), exampleTargets, test, RiskInputs(bd("1000"), bd("0.5"), bd("100.25"), bd("3"), null, exampleCosts))
        assertEquals(listOf(RiskError.WRONG_SIDE_STOP), assertIs<RiskResult.Invalid>(wrongSide).errors)
        val noEquity = RiskCalculator.estimate(Direction.LONG, bd("98"), exampleTargets, test, RiskInputs(null, bd("0.5"), bd("100.25"), bd("3"), null, exampleCosts))
        assertTrue(RiskError.EQUITY_REQUIRED in assertIs<RiskResult.Invalid>(noEquity).errors)
        val margin = RiskCalculator.estimate(Direction.LONG, bd("98"), exampleTargets, test, RiskInputs(bd("1000"), bd("0.5"), bd("100.25"), bd("3"), bd("10"), exampleCosts))
        assertTrue(RiskError.INSUFFICIENT_MARGIN in assertIs<RiskResult.Invalid>(margin).errors)
        // 50x leverage: initial margin move 2% < 2.24% stop distance -> liquidation before stop.
        val liq = RiskCalculator.estimate(Direction.LONG, bd("98"), exampleTargets, test, RiskInputs(bd("1000"), bd("0.5"), bd("100.25"), bd("50"), null, exampleCosts))
        assertTrue(RiskError.LIQUIDATION_BEFORE_STOP in assertIs<RiskResult.Invalid>(liq).errors)
    }

    @Test
    fun leverageChangesMarginNotLoss() {
        val a = assertIs<RiskResult.Ok>(example()).estimate
        val b = assertIs<RiskResult.Ok>(RiskCalculator.estimate(Direction.LONG, bd("98.00"), exampleTargets, test,
            RiskInputs(bd("1000"), bd("0.5"), bd("100.25"), bd("10"), null, exampleCosts))).estimate
        assertEquals(a.quantity, b.quantity)
        assertEquals(a.plannedLoss, b.plannedLoss)
        assertTrue(b.marginEstimate < a.marginEstimate)
    }

    /** AT01: two coins sharing a symbol require a selection; unknown coins report no market. */
    @Test
    fun at01CoinAmbiguity() {
        fun inst(sym: String, base: String, type: ContractType = ContractType.PERPETUAL, status: TradingStatus = TradingStatus.TRADING, venue: String = "X") =
            test.copy(instrumentId = "$venue:$sym", symbol = sym, base = base, contractType = type, tradingStatus = status, venue = venue, simulated = false)
        val list = listOf(
            inst("BTCUSDT", "BTC"),
            inst("BTCUSDT_261225", "BTC", ContractType.DELIVERY),
            inst("ABCUSDT", "ABC"),
            inst("ABCUSDT", "ABC", venue = "Y"),
            inst("OLDUSDT", "OLD", status = TradingStatus.DELISTED),
            inst("1000PEPEUSDT", "1000PEPE"),
        )
        val r = InstrumentResolver(list)
        val btc = r.search("Bitcoin")
        assertEquals("BTCUSDT", btc.uniqueMarket?.symbol)
        assertTrue(btc.candidates.any { !it.supported && it.instrument.contractType == ContractType.DELIVERY })
        assertEquals("BTCUSDT", r.search("btc").uniqueMarket?.symbol)

        val abc = r.search("ABC")
        assertNull(abc.uniqueMarket, "duplicate symbol must require a selection")
        assertEquals(2, abc.candidates.count { it.supported })

        val old = r.search("OLD")
        assertTrue(old.noSupportedMarket)
        assertEquals(ReasonCode.INSTRUMENT_DELISTED, old.candidates.single().instrument.unsupportedReason)

        assertTrue(r.search("NOSUCHCOIN").noSupportedMarket)
        assertEquals("1000PEPEUSDT", r.search("pepe").uniqueMarket?.symbol)
    }

    /** AT11: a bar touching stop and target with no lower-level sequence is Ambiguous (stop assumed first). */
    @Test
    fun at11TouchAmbiguity() {
        val c15 = candles(Timeframe.M15, 0, "100 100.5 99.5 100", "100 104.5 97.5 101")
        val r = PaperTracker.track(Direction.LONG, 0, bd("98"), listOf(bd("104"), bd("108")), bd("99"), c15, emptyList(), Bias.LONG)
        assertTrue(r.ambiguous)
        assertTrue(r.stopTouched)
        assertTrue(r.targetsTouched.isEmpty())
        assertEquals(TouchType.AMBIGUOUS, r.events.first().type)
    }

    @Test
    fun paperTrackingReviewEvents() {
        val rows = Array(17) { "100 100.6 99.6 100.2" }
        rows[3] = "100 104.2 99.8 103"
        val c15 = candles(Timeframe.M15, 0, *rows)
        val r = PaperTracker.track(Direction.LONG, 0, bd("98"), listOf(bd("104"), bd("108")), bd("99"), c15, emptyList(), Bias.SHORT)
        assertEquals(setOf(0), r.targetsTouched)
        assertFalse(r.stopTouched)
        assertTrue(r.events.any { it.type == TouchType.REVIEW_TIME && it.time == c15[15].closeTime })
        assertTrue(r.events.any { it.type == TouchType.REVIEW_DIRECTION })
    }

    /** AT12: repeated updates and retries produce one event per deduplication key. */
    @Test
    fun at12DuplicateDelivery() {
        val plan = AnalysisEngine.analyze(Fixtures.snapshot(Fixtures.Scenario.ENTRY_READY)).plan!!
        val a = AlertRules.planTransition(PlanState.WAITING_CONFIRMATION, false, plan, 3)!!
        val b = AlertRules.planTransition(PlanState.WAITING_CONFIRMATION, false, plan, 3)!!
        assertEquals(AlertEventType.ENTRY_READY, a.type)
        assertEquals(a.dedupKey("u1"), b.dedupKey("u1"))
        assertTrue(a.dedupKey("u1") != a.dedupKey("u2"))
        // No transition -> no event.
        assertNull(AlertRules.planTransition(PlanState.ENTRY_READY, true, plan, 3))

        val sent = HashSet<String>()
        repeat(5) { sent += a.dedupKey("u1") }
        assertEquals(1, sent.size)
    }

    @Test
    fun zoneApproachCooldownAndRearm() {
        val z = Zone("z", Timeframe.H1, bd("100"), bd("101"), ZoneRole.SUPPORT, emptyList(), 0, 1, null, 1, BreakStatus.NONE)
        val cd = 15 * 60_000L
        var (fire, st) = AlertRules.zoneApproach(null, z, bd("100.5"), 0, cd)
        assertTrue(fire)
        AlertRules.zoneApproach(st, z, bd("100.6"), 60_000, cd).also { assertFalse(it.first); st = it.second }
        AlertRules.zoneApproach(st, z, bd("102"), 120_000, cd).also { assertFalse(it.first); st = it.second }
        // Re-entered but still inside cooldown.
        AlertRules.zoneApproach(st, z, bd("100.5"), 180_000, cd).also { assertFalse(it.first); st = it.second }
        AlertRules.zoneApproach(st, z, bd("102"), 16 * 60_000L, cd).also { st = it.second }
        AlertRules.zoneApproach(st, z, bd("100.5"), 17 * 60_000L, cd).also { assertTrue(it.first) }
    }

    @Test
    fun tickRoundingIsSideSpecific() {
        assertBd("98.12", Dec.floorTo(bd("98.129"), bd("0.01")))
        assertBd("98.13", Dec.ceilTo(bd("98.121"), bd("0.01")))
        assertBd("-98.13", Dec.floorTo(bd("-98.121"), bd("0.01")))
    }
}
