package com.coinfutures.engine

import java.math.BigDecimal

/**
 * Breakout-and-retest lifecycle evaluated in "long space" (section 4). Short setups are run
 * through the same code on price-mirrored data (p -> -p), which makes long/short behavior
 * symmetric by construction (AT05). Floor-rounding in mirrored space equals ceiling-rounding
 * in real space, which gives the side-specific tick rounding the spec requires.
 */
internal class SetupFinder(
    private val instrumentId: String,
    private val c4h: List<Candle>,
    private val c1h: List<Candle>,
    private val c15: List<Candle>,
    private val tick: BigDecimal,
    private val quote: Quote?,
    private val now: Long,
    private val config: StrategyConfig,
) {
    private val bias = BiasEvaluator(c4h, config)
    private val atr1h = Indicators.atr(c1h, config.atrPeriod)
    private val atr15 = Indicators.atr(c15, config.atrPeriod)
    private val rsi15 = Indicators.rsi(c15.map { it.close }, config.rsiPeriod)

    /** Most recent qualifying breakout within the lookback, simulated to its current state. */
    fun find(): TradePlan? {
        val last = c1h.lastIndex
        val first = maxOf(config.volumeMeanPeriod, last - config.breakoutLookback1h + 1, 1)
        for (t in last downTo first) {
            val plan = tryTrigger(t)
            if (plan != null) return plan
        }
        return null
    }

    /** Next condition while watching for a breakout: nearest resistance edge + ATR buffer. */
    fun watchCondition(): NextCondition? {
        val last = c1h.lastIndex
        val atr = atr1h.getOrNull(last) ?: return null
        val zones = Zones.build(c1h, last, atr, tick, c1h[last].close, config)
        val r = zones.resistances.firstOrNull() ?: return NextCondition(ReasonCode.AWAITING_BREAKOUT, Timeframe.H1)
        val mean = Indicators.meanVolumeBefore(c1h, last + 1, config.volumeMeanPeriod)
        return NextCondition(
            code = ReasonCode.AWAITING_BREAKOUT,
            timeframe = Timeframe.H1,
            price = Dec.ceilTo(r.upper + atr.multiply(config.breakBufferAtr), tick),
            minVolume = mean?.multiply(config.breakoutVolumeMultiple),
        )
    }

    private fun tryTrigger(t: Int): TradePlan? {
        val atr = atr1h.getOrNull(t - 1) ?: return null
        val trigger = c1h[t]
        val ref = c1h[t - 1].close
        val zones = Zones.build(c1h, t - 1, atr, tick, ref, config)
        val buffer = atr.multiply(config.breakBufferAtr)
        // ENT01: close beyond the far edge by >= 0.10 x ATR. A wick alone never qualifies (AT04).
        val broken = zones.all
            .filter { it.role == ZoneRole.RESISTANCE && trigger.close >= it.upper + buffer }
            .maxByOrNull { it.upper } ?: return null
        val mean = Indicators.meanVolumeBefore(c1h, t, config.volumeMeanPeriod) ?: return null
        if (trigger.volume < mean.multiply(config.breakoutVolumeMultiple)) return null
        if (bias.at(trigger.closeTime).bias != Bias.LONG) return null

        val base = TradePlan(
            planKey = TradePlan.keyOf(instrumentId, config.version, Direction.LONG, trigger.openTime),
            instrumentId = instrumentId,
            direction = Direction.LONG,
            state = PlanState.WAITING_RETEST,
            strategyVersion = config.version,
            createdAt = trigger.closeTime,
            stateChangedAt = trigger.closeTime,
            triggerOpenTime = trigger.openTime,
            triggerClose = trigger.close,
            triggerVolume = trigger.volume,
            volumeMean = mean,
            atr1h = atr,
            breakBuffer = buffer,
            zone = broken,
            frozenZones = zones.all,
        )
        return simulate(base, trigger.closeTime)
    }

    private fun simulate(base: TradePlan, triggerClose: Long): TradePlan {
        val zone = base.zone
        val s0 = c15.indexOfFirst { it.openTime >= triggerClose }
        if (s0 < 0) return base.copy(candlesRemaining = config.retestWindow15m)

        // ENT02: retest within N closed 15M candles.
        var r = -1
        for (k in 0 until config.retestWindow15m) {
            val idx = s0 + k
            if (idx > c15.lastIndex) return base.copy(candlesRemaining = config.retestWindow15m - k)
            val c = c15[idx]
            if (c.close < zone.lower) return terminal(base, PlanState.INVALIDATED, ReasonCode.FAR_SIDE_CLOSE, c.closeTime)
            if (c.intersects(zone.lower, zone.upper) && c.close >= zone.upper) { r = idx; break }
        }
        if (r < 0) return terminal(base, PlanState.EXPIRED, ReasonCode.RETEST_TIMEOUT, c15[s0 + config.retestWindow15m - 1].closeTime)

        val retest = c15[r]
        val atrR = atr15[r] ?: return terminal(base, PlanState.EXPIRED, ReasonCode.INDICATOR_WARMUP, retest.closeTime)
        // Section 5: stop below the lower of retest low and zone lower bound, minus 0.25 x frozen 15M ATR.
        val stop = Dec.floorTo(Dec.min(retest.low, zone.lower) - atrR.multiply(config.stopBufferAtr), tick)
        // ENT04: entry band from the zone upper bound to bound + 0.25 x frozen 15M ATR (rounded inward).
        val bandUpper = Dec.max(zone.upper, Dec.floorTo(zone.upper + atrR.multiply(config.entryBandAtr), tick))
        val band = PriceBand(zone.upper, bandUpper)
        // Section 5: TP1/TP2 = near edges of the next two distinct zones beyond the whole band, minus one tick.
        val tpZones = base.frozenZones.filter { it.lower > band.upper }.sortedBy { it.lower }.take(2)
        val alloc2 = Dec.ONE - config.tp1Allocation
        val targets = tpZones.mapIndexed { i, z ->
            PlanTarget(z.lower - tick, if (i == 0) config.tp1Allocation else alloc2, z.zoneId)
        }
        var plan = base.copy(
            state = PlanState.WAITING_CONFIRMATION,
            stateChangedAt = retest.closeTime,
            retestOpenTime = retest.openTime,
            retestHigh = retest.high,
            retestLow = retest.low,
            atr15 = atrR,
            entryBand = band,
            stop = stop,
            targets = targets,
        )
        plan = plan.copy(metrics = metrics(plan), gateFailures = structuralGates(plan))

        // ENT03: confirmation within the next N closed 15M candles.
        val trigger = retest.high + tick
        var q = -1
        for (j in 1..config.confirmWindow15m) {
            val idx = r + j
            if (idx > c15.lastIndex) return live(plan.copy(candlesRemaining = config.confirmWindow15m - j + 1))
            val c = c15[idx]
            if (c.close < zone.lower) return terminal(plan, PlanState.INVALIDATED, ReasonCode.FAR_SIDE_CLOSE, c.closeTime)
            if (c.low <= stop) return terminal(plan, PlanState.INVALIDATED, ReasonCode.STOP_TOUCHED, c.closeTime)
            val rsi = rsi15[idx]
            if (c.close > trigger && rsi != null && rsi > config.rsiThreshold) { q = idx; break }
        }
        if (q < 0) return terminal(plan, PlanState.EXPIRED, ReasonCode.CONFIRMATION_TIMEOUT, c15[r + config.confirmWindow15m].closeTime)

        val conf = c15[q]
        plan = plan.copy(
            state = PlanState.ENTRY_READY,
            stateChangedAt = conf.closeTime,
            confirmationOpenTime = conf.openTime,
            confirmationClose = conf.close,
            confirmationRsi = rsi15[q],
            validUntil = conf.closeTime + config.entryWindow15m * Timeframe.M15.millis,
        )

        // ENT04 / EXIT03: entry validity window; far-side close or stop touch cancels.
        var touched = false
        for (j in 1..config.entryWindow15m) {
            val idx = q + j
            if (idx > c15.lastIndex) return live(plan.copy(candlesRemaining = config.entryWindow15m - j + 1, bandTouched = touched))
            val c = c15[idx]
            if (c.close < zone.lower) return terminal(plan.copy(bandTouched = touched), PlanState.INVALIDATED, ReasonCode.FAR_SIDE_CLOSE, c.closeTime)
            if (c.low <= stop) return terminal(plan.copy(bandTouched = touched), PlanState.INVALIDATED, ReasonCode.STOP_TOUCHED, c.closeTime)
            if (c.intersects(band.lower, band.upper)) touched = true
        }
        return terminal(plan.copy(bandTouched = touched), PlanState.EXPIRED, ReasonCode.ENTRY_WINDOW_EXPIRED, c15[q + config.entryWindow15m].closeTime)
    }

    /** Applies live-quote checks (virtual stop touch, band relation, spread gate) to a non-terminal plan. */
    private fun live(plan: TradePlan): TradePlan {
        val q = quote ?: return plan
        val stop = plan.stop
        if (stop != null && q.last <= stop) {
            return terminal(plan, PlanState.INVALIDATED, ReasonCode.STOP_TOUCHED_LIVE, q.receivedAt)
        }
        val band = plan.entryBand
        val relation = when {
            band == null -> null
            q.last > band.upper -> ReasonCode.PRICE_ABOVE_BAND
            q.last < band.lower -> ReasonCode.PRICE_BELOW_BAND
            else -> ReasonCode.PRICE_IN_BAND
        }
        val mid = q.mid.abs()
        val spreadPct = if (mid.signum() > 0) Dec.div((q.ask - q.bid).abs().multiply(Dec.HUNDRED), mid) else null
        val gates = plan.gateFailures.toMutableList()
        if (plan.state == PlanState.ENTRY_READY && (spreadPct == null || spreadPct > config.maxSpreadPercent)) {
            gates += ReasonCode.SPREAD_TOO_WIDE
        }
        return plan.copy(liveRelation = relation, spreadPercent = spreadPct, gateFailures = gates)
    }

    private fun terminal(plan: TradePlan, state: PlanState, reason: ReasonCode, at: Long) =
        plan.copy(state = state, terminalReason = reason, stateChangedAt = at, candlesRemaining = null)

    /** Gates that depend only on frozen levels: two ordered targets and net RR at TP2 >= minimum. */
    private fun structuralGates(plan: TradePlan): List<ReasonCode> {
        val out = ArrayList<ReasonCode>()
        val band = plan.entryBand ?: return out
        val stop = plan.stop ?: return out
        if (plan.targets.size < 2) {
            out += ReasonCode.MISSING_TARGETS
            return out
        }
        val tp1 = plan.targets[0].price
        val tp2 = plan.targets[1].price
        if (!(stop < band.lower && band.lower <= band.upper && band.upper < tp1 && tp1 < tp2)) {
            out += ReasonCode.PRICES_NOT_ORDERED
            return out
        }
        val rr = Costs.netRr(band.upper, stop, tp2, config.gateCosts)
        if (rr == null || rr < config.minNetRrTp2) out += ReasonCode.RR_TOO_LOW
        return out
    }

    private fun metrics(plan: TradePlan): PlanMetrics? {
        val band = plan.entryBand ?: return null
        val stop = plan.stop ?: return null
        val worst = band.upper
        val tp1 = plan.targets.getOrNull(0)?.price
        val tp2 = plan.targets.getOrNull(1)?.price
        val c = config.gateCosts
        val blended = if (tp1 != null && tp2 != null) {
            val risk = Costs.risk(worst, stop, c).total
            if (risk.signum() > 0) {
                val r1 = Costs.netReward(worst, tp1, c).multiply(plan.targets[0].allocation)
                val r2 = Costs.netReward(worst, tp2, c).multiply(plan.targets[1].allocation)
                Dec.div(r1 + r2, risk)
            } else null
        } else null
        return PlanMetrics(
            worstEntry = worst,
            grossRrTp1 = tp1?.let { Costs.grossRr(worst, stop, it) },
            grossRrTp2 = tp2?.let { Costs.grossRr(worst, stop, it) },
            netRrTp2 = tp2?.let { Costs.netRr(worst, stop, it, c) },
            blendedNetRr = blended,
        )
    }
}
