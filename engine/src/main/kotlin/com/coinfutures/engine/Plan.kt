package com.coinfutures.engine

import java.math.BigDecimal

data class PriceBand(val lower: BigDecimal, val upper: BigDecimal) {
    fun mirrored() = PriceBand(upper.negate(), lower.negate())
    fun contains(p: BigDecimal) = p >= lower && p <= upper
}

data class PlanTarget(val price: BigDecimal, val allocation: BigDecimal, val zoneId: String) {
    fun mirrored() = copy(price = price.negate())
}

data class PlanMetrics(
    /** Least favorable entry in the band, used for planning (RISK02). */
    val worstEntry: BigDecimal,
    val grossRrTp1: BigDecimal?,
    val grossRrTp2: BigDecimal?,
    val netRrTp2: BigDecimal?,
    val blendedNetRr: BigDecimal?,
) {
    fun mirrored() = copy(worstEntry = worstEntry.negate())
}

/**
 * A setup with all levels frozen at creation (section 3, "Freeze levels for each plan").
 * The same snapshot and strategy version always reproduce the same plan (AT03).
 */
data class TradePlan(
    val planKey: String,
    val instrumentId: String,
    val direction: Direction,
    val state: PlanState,
    val strategyVersion: String,
    /** Close time of the breakout candle (plan creation). */
    val createdAt: Long,
    /** Source time of the candle/quote that caused the current state. */
    val stateChangedAt: Long,
    // Breakout evidence (1H)
    val triggerOpenTime: Long,
    val triggerClose: BigDecimal,
    val triggerVolume: BigDecimal,
    val volumeMean: BigDecimal,
    val atr1h: BigDecimal,
    val breakBuffer: BigDecimal,
    val zone: Zone,
    val frozenZones: List<Zone>,
    // Retest (15M)
    val retestOpenTime: Long? = null,
    val retestHigh: BigDecimal? = null,
    val retestLow: BigDecimal? = null,
    val atr15: BigDecimal? = null,
    // Confirmation (15M)
    val confirmationOpenTime: Long? = null,
    val confirmationClose: BigDecimal? = null,
    val confirmationRsi: BigDecimal? = null,
    // Levels
    val entryBand: PriceBand? = null,
    val stop: BigDecimal? = null,
    val targets: List<PlanTarget> = emptyList(),
    val validUntil: Long? = null,
    val candlesRemaining: Int? = null,
    val terminalReason: ReasonCode? = null,
    val bandTouched: Boolean = false,
    val metrics: PlanMetrics? = null,
    val gateFailures: List<ReasonCode> = emptyList(),
    val liveRelation: ReasonCode? = null,
    val spreadPercent: BigDecimal? = null,
) {
    /** Entry Ready with every mandatory gate passed. Still not a fill. */
    val actionable: Boolean get() = state == PlanState.ENTRY_READY && gateFailures.isEmpty()

    fun mirrored(): TradePlan {
        val ids = (frozenZones + zone).associate { it.zoneId to it.mirrored().zoneId }
        return copy(
        direction = direction.opposite,
        planKey = keyOf(instrumentId, strategyVersion, direction.opposite, triggerOpenTime),
        triggerClose = triggerClose.negate(),
        zone = zone.mirrored(),
        frozenZones = frozenZones.map { it.mirrored() },
        retestHigh = retestLow?.negate(),
        retestLow = retestHigh?.negate(),
        confirmationClose = confirmationClose?.negate(),
        confirmationRsi = confirmationRsi?.let { Dec.HUNDRED - it },
        entryBand = entryBand?.mirrored(),
        stop = stop?.negate(),
        targets = targets.map { it.mirrored().copy(zoneId = ids[it.zoneId] ?: it.zoneId) },
        metrics = metrics?.mirrored(),
        liveRelation = when (liveRelation) {
            ReasonCode.PRICE_ABOVE_BAND -> ReasonCode.PRICE_BELOW_BAND
            ReasonCode.PRICE_BELOW_BAND -> ReasonCode.PRICE_ABOVE_BAND
            else -> liveRelation
        },
        )
    }

    companion object {
        fun keyOf(instrumentId: String, version: String, direction: Direction, triggerOpenTime: Long) =
            "$instrumentId|$version|$direction|$triggerOpenTime"
    }
}

data class NextCondition(
    val code: ReasonCode,
    val timeframe: Timeframe?,
    val price: BigDecimal? = null,
    val minVolume: BigDecimal? = null,
    val candlesRemaining: Int? = null,
    val direction: Direction? = null,
)
