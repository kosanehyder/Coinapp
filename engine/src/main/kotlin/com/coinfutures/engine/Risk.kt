package com.coinfutures.engine

import java.math.BigDecimal

/** Adverse slippage per coin per side. */
sealed class Slippage {
    abstract fun perCoin(price: BigDecimal): BigDecimal

    data class Absolute(val perCoinPerSide: BigDecimal) : Slippage() {
        override fun perCoin(price: BigDecimal) = perCoinPerSide
    }

    data class Percent(val ratePerSide: BigDecimal) : Slippage() {
        override fun perCoin(price: BigDecimal): BigDecimal = price.abs().multiply(ratePerSide)
    }
}

data class CostModel(
    /** Taker fee rate per side, e.g. 0.0005 = 0.05%. */
    val feeRate: BigDecimal,
    val slippage: Slippage,
    /** Assumed funding debit per coin over the holding period (0 when not assumed). */
    val fundingDebitPerCoin: BigDecimal = BigDecimal.ZERO,
)

data class RiskBreakdown(
    val priceRisk: BigDecimal,
    val fees: BigDecimal,
    val slippage: BigDecimal,
    val funding: BigDecimal,
) {
    val total: BigDecimal get() = priceRisk + fees + slippage + funding
}

/** Cost-aware per-coin arithmetic shared by the entry gate and the risk calculator. */
object Costs {
    fun risk(entry: BigDecimal, stop: BigDecimal, c: CostModel) = RiskBreakdown(
        priceRisk = (entry - stop).abs(),
        fees = entry.abs().multiply(c.feeRate) + stop.abs().multiply(c.feeRate),
        slippage = c.slippage.perCoin(entry) + c.slippage.perCoin(stop),
        funding = c.fundingDebitPerCoin,
    )

    fun netReward(entry: BigDecimal, target: BigDecimal, c: CostModel): BigDecimal =
        (target - entry).abs() - entry.abs().multiply(c.feeRate) - target.abs().multiply(c.feeRate) -
            c.slippage.perCoin(entry) - c.slippage.perCoin(target) - c.fundingDebitPerCoin

    fun grossRr(entry: BigDecimal, stop: BigDecimal, target: BigDecimal): BigDecimal? {
        val d = (entry - stop).abs()
        if (d.signum() == 0) return null
        return Dec.div((target - entry).abs(), d)
    }

    fun netRr(entry: BigDecimal, stop: BigDecimal, target: BigDecimal, c: CostModel): BigDecimal? {
        val r = risk(entry, stop, c).total
        if (r.signum() <= 0) return null
        return Dec.div(netReward(entry, target, c), r)
    }
}

data class RiskInputs(
    val equity: BigDecimal?,
    val riskPercent: BigDecimal = BigDecimal("0.5"),
    val entryPrice: BigDecimal?,
    val leverage: BigDecimal = BigDecimal(3),
    val availableMargin: BigDecimal? = null,
    val costs: CostModel,
)

data class TargetSpec(val price: BigDecimal, val allocation: BigDecimal)

data class TargetOutcome(
    val price: BigDecimal,
    val allocation: BigDecimal,
    val quantity: BigDecimal,
    val grossRr: BigDecimal?,
    val netRr: BigDecimal?,
    val netProfit: BigDecimal,
)

enum class RiskError(val code: ErrorCode) {
    EQUITY_REQUIRED(ErrorCode.INVALID_RISK_INPUT),
    INVALID_EQUITY(ErrorCode.INVALID_RISK_INPUT),
    INVALID_RISK_PERCENT(ErrorCode.INVALID_RISK_INPUT),
    INVALID_LEVERAGE(ErrorCode.INVALID_RISK_INPUT),
    INVALID_ENTRY(ErrorCode.INVALID_RISK_INPUT),
    ZERO_DISTANCE(ErrorCode.INVALID_RISK_INPUT),
    WRONG_SIDE_STOP(ErrorCode.INVALID_RISK_INPUT),
    TARGETS_NOT_ORDERED(ErrorCode.INVALID_RISK_INPUT),
    MISSING_COSTS(ErrorCode.INVALID_RISK_INPUT),
    INSUFFICIENT_MARGIN(ErrorCode.INVALID_RISK_INPUT),
    LIQUIDATION_BEFORE_STOP(ErrorCode.INVALID_RISK_INPUT),
    MIN_SIZE_CONFLICT(ErrorCode.MIN_SIZE_CONFLICT),
}

data class RiskEstimate(
    val direction: Direction,
    val entry: BigDecimal,
    val stop: BigDecimal,
    val riskBudget: BigDecimal,
    val perCoin: RiskBreakdown,
    /** Quantity in contracts, rounded down to the quantity step. */
    val quantity: BigDecimal,
    val coins: BigDecimal,
    val notional: BigDecimal,
    val marginEstimate: BigDecimal,
    val plannedLoss: BigDecimal,
    val targets: List<TargetOutcome>,
    val splitAccepted: Boolean,
    val grossRrFinal: BigDecimal?,
    val netRrFinal: BigDecimal?,
    val blendedNetRr: BigDecimal?,
    /** RISK03: no verified exchange liquidation model is integrated in the MVP. */
    val liquidationCalculated: Boolean = false,
)

sealed class RiskResult {
    data class Ok(val estimate: RiskEstimate) : RiskResult()
    data class Invalid(val errors: List<RiskError>, val minimumBudgetHint: BigDecimal? = null) : RiskResult()
}

/**
 * Optional, cost-aware position sizing for a linear contract (section 5, RISK01-RISK03).
 * Not a suitability assessment; planned loss is an estimate, not a guaranteed cap.
 */
object RiskCalculator {
    private val MAX_LEVERAGE = BigDecimal(125)

    fun estimate(
        direction: Direction,
        stop: BigDecimal,
        targets: List<TargetSpec>,
        instrument: Instrument,
        inputs: RiskInputs,
    ): RiskResult {
        val errors = ArrayList<RiskError>()
        val equityIn = inputs.equity
        val entryIn = inputs.entryPrice
        if (equityIn == null) errors += RiskError.EQUITY_REQUIRED else if (equityIn.signum() <= 0) errors += RiskError.INVALID_EQUITY
        if (inputs.riskPercent.signum() <= 0 || inputs.riskPercent > Dec.HUNDRED) errors += RiskError.INVALID_RISK_PERCENT
        if (inputs.leverage < Dec.ONE || inputs.leverage > MAX_LEVERAGE) errors += RiskError.INVALID_LEVERAGE
        if (inputs.costs.feeRate.signum() < 0 || inputs.costs.slippage.perCoin(Dec.ONE).signum() < 0) errors += RiskError.MISSING_COSTS
        if (entryIn == null || entryIn.signum() <= 0) errors += RiskError.INVALID_ENTRY
        if (errors.isNotEmpty() || entryIn == null || equityIn == null) return RiskResult.Invalid(errors)
        val entry: BigDecimal = entryIn
        val equity: BigDecimal = equityIn

        val sign = if (direction == Direction.LONG) 1 else -1
        val distance = (entry - stop).multiply(BigDecimal(sign))
        if (distance.signum() == 0) return RiskResult.Invalid(listOf(RiskError.ZERO_DISTANCE))
        if (distance.signum() < 0) return RiskResult.Invalid(listOf(RiskError.WRONG_SIDE_STOP))
        var prev: BigDecimal = entry
        for (t in targets) {
            if ((t.price - prev).multiply(BigDecimal(sign)).signum() <= 0) return RiskResult.Invalid(listOf(RiskError.TARGETS_NOT_ORDERED))
            prev = t.price
        }

        val budget = equity.multiply(inputs.riskPercent).divide(Dec.HUNDRED, Dec.MC)
        val perCoin = Costs.risk(entry, stop, inputs.costs)
        val m = instrument.multiplier
        val perContract = perCoin.total.multiply(m)
        val qty = Dec.floorTo(Dec.div(budget, perContract), instrument.qtyStep)
        val coins = qty.multiply(m)
        val notional = coins.multiply(entry)

        if (qty.signum() <= 0 || qty < instrument.minQty || notional < instrument.minNotional) {
            val minQty = Dec.max(instrument.minQty, Dec.ceilTo(Dec.div(instrument.minNotional, entry.multiply(m)), instrument.qtyStep))
            return RiskResult.Invalid(listOf(RiskError.MIN_SIZE_CONFLICT), minimumBudgetHint = minQty.multiply(perContract))
        }

        // RISK03: a stop farther than the initial-margin move means the position is likely
        // liquidated before the stop. Exact liquidation needs a verified venue model.
        val stopMovePct = Dec.div(perCoin.priceRisk, entry)
        if (stopMovePct >= Dec.div(Dec.ONE, inputs.leverage)) errors += RiskError.LIQUIDATION_BEFORE_STOP

        val margin = Dec.div(notional, inputs.leverage)
        if (inputs.availableMargin != null && margin > inputs.availableMargin) errors += RiskError.INSUFFICIENT_MARGIN
        if (errors.isNotEmpty()) return RiskResult.Invalid(errors)

        // EXIT01: split exits; rounding preserves total quantity.
        var split = targets.size >= 2
        var quantities: List<BigDecimal> = emptyList()
        if (split) {
            val q1 = Dec.floorTo(qty.multiply(targets[0].allocation), instrument.qtyStep)
            val q2 = qty - q1
            val okQ1 = q1 >= instrument.minQty && q1.signum() > 0 && q1.multiply(m).multiply(targets[0].price) >= instrument.minNotional
            val okQ2 = q2 >= instrument.minQty && q2.signum() > 0 && q2.multiply(m).multiply(targets[1].price) >= instrument.minNotional
            if (okQ1 && okQ2) quantities = listOf(q1, q2) else split = false
        }
        val usedTargets: List<TargetSpec> = if (split) targets.take(2) else listOfNotNull(targets.lastOrNull()?.copy(allocation = Dec.ONE))
        if (!split) quantities = listOf(qty)

        val outcomes = usedTargets.mapIndexed { i, t ->
            val q = quantities[i]
            TargetOutcome(
                price = t.price,
                allocation = t.allocation,
                quantity = q,
                grossRr = Costs.grossRr(entry, stop, t.price),
                netRr = Costs.netRr(entry, stop, t.price, inputs.costs),
                netProfit = q.multiply(m).multiply(Costs.netReward(entry, t.price, inputs.costs)),
            )
        }
        val final = usedTargets.lastOrNull()
        val totalNetProfit = outcomes.fold(Dec.ZERO) { a, o -> a + o.netProfit }
        val plannedLoss = coins.multiply(perCoin.total)
        return RiskResult.Ok(
            RiskEstimate(
                direction = direction,
                entry = entry,
                stop = stop,
                riskBudget = budget,
                perCoin = perCoin,
                quantity = qty,
                coins = coins,
                notional = notional,
                marginEstimate = margin,
                plannedLoss = plannedLoss,
                targets = outcomes,
                splitAccepted = split,
                grossRrFinal = final?.let { Costs.grossRr(entry, stop, it.price) },
                netRrFinal = final?.let { Costs.netRr(entry, stop, it.price, inputs.costs) },
                blendedNetRr = if (plannedLoss.signum() > 0 && outcomes.isNotEmpty()) Dec.div(totalNetProfit, plannedLoss) else null,
            )
        )
    }
}
