package com.coinfutures.engine

import java.math.BigDecimal

data class BiasResult(
    val bias: Bias,
    val reasons: List<ReasonCode>,
    val swingHighs: List<Pivot>,
    val swingLows: List<Pivot>,
    val emaFast: BigDecimal?,
    val emaMid: BigDecimal?,
    val emaSlow: BigDecimal?,
    val close: BigDecimal?,
    val asOf: Long?,
) {
    fun mirrored(): BiasResult = copy(
        bias = when (bias) { Bias.LONG -> Bias.SHORT; Bias.SHORT -> Bias.LONG; else -> bias },
        swingHighs = swingLows.map { it.copy(type = PivotType.HIGH, price = it.price.negate()) },
        swingLows = swingHighs.map { it.copy(type = PivotType.LOW, price = it.price.negate()) },
        emaFast = emaFast?.negate(), emaMid = emaMid?.negate(), emaSlow = emaSlow?.negate(), close = close?.negate(),
        reasons = reasons.map { when (it) { ReasonCode.BIAS_LONG -> ReasonCode.BIAS_SHORT; ReasonCode.BIAS_SHORT -> ReasonCode.BIAS_LONG; else -> it } },
    )
}

/**
 * 4H direction filter (section 4). Long bias: higher highs and higher lows on the last two
 * confirmed swings, EMA20 > EMA50 and latest close > EMA200. Short bias is the exact inverse.
 */
class BiasEvaluator(private val candles: List<Candle>, private val config: StrategyConfig) {
    private val closes = candles.map { it.close }
    private val emaFast = Indicators.ema(closes, config.emaFast)
    private val emaMid = Indicators.ema(closes, config.emaMid)
    private val emaSlow = Indicators.ema(closes, config.emaSlow)
    private val pivots = Pivots.find(candles, config.pivotLeft, config.pivotRight)

    /** Bias using only 4H candles closed at or before [time]. */
    fun at(time: Long): BiasResult {
        val idx = candles.indexOfLast { it.closeTime <= time }
        if (idx < 0) return BiasResult(Bias.UNKNOWN, listOf(ReasonCode.INSUFFICIENT_HISTORY), emptyList(), emptyList(), null, null, null, null, null)
        val limit = candles[idx].closeTime
        val highs = pivots.filter { it.type == PivotType.HIGH && it.confirmedAt <= limit }.takeLast(2)
        val lows = pivots.filter { it.type == PivotType.LOW && it.confirmedAt <= limit }.takeLast(2)
        val f = emaFast[idx]
        val m = emaMid[idx]
        val s = emaSlow[idx]
        val close = candles[idx].close
        if (f == null || m == null || s == null) {
            return BiasResult(Bias.UNKNOWN, listOf(ReasonCode.INDICATOR_WARMUP), highs, lows, f, m, s, close, limit)
        }
        if (highs.size < 2 || lows.size < 2) {
            return BiasResult(Bias.NEUTRAL, listOf(ReasonCode.MIXED_STRUCTURE, ReasonCode.NOT_ENOUGH_SWINGS), highs, lows, f, m, s, close, limit)
        }
        val hh = highs[1].price > highs[0].price
        val hl = lows[1].price > lows[0].price
        val lh = highs[1].price < highs[0].price
        val ll = lows[1].price < lows[0].price
        val longOk = hh && hl && f > m && close > s
        val shortOk = lh && ll && f < m && close < s
        return when {
            longOk -> BiasResult(Bias.LONG, listOf(ReasonCode.BIAS_LONG), highs, lows, f, m, s, close, limit)
            shortOk -> BiasResult(Bias.SHORT, listOf(ReasonCode.BIAS_SHORT), highs, lows, f, m, s, close, limit)
            else -> {
                val r = mutableListOf(ReasonCode.MIXED_STRUCTURE)
                val structureUp = hh && hl
                val structureDown = lh && ll
                if (structureUp && f <= m || structureDown && f >= m) r += ReasonCode.EMA_NOT_ALIGNED
                if (structureUp && close <= s || structureDown && close >= s) r += ReasonCode.CLOSE_VS_EMA200
                BiasResult(Bias.NEUTRAL, r, highs, lows, f, m, s, close, limit)
            }
        }
    }
}
