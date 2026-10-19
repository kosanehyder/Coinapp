package com.coinfutures.engine

import java.math.BigDecimal

/**
 * Indicators on closed candles (section 3, step 1). Each function returns an array aligned to
 * the input; entries before warmup completes are null and must be rejected by callers.
 */
object Indicators {

    /** EMA seeded with the simple average of the first [period] closes. */
    fun ema(values: List<BigDecimal>, period: Int): Array<BigDecimal?> {
        val out = arrayOfNulls<BigDecimal>(values.size)
        if (values.size < period) return out
        val k = Dec.div(BigDecimal(2), BigDecimal(period + 1))
        var sum = Dec.ZERO
        for (i in 0 until period) sum += values[i]
        var prev = Dec.div(sum, BigDecimal(period))
        out[period - 1] = prev
        for (i in period until values.size) {
            prev = ((values[i] - prev).multiply(k, Dec.MC) + prev).round(Dec.MC)
            out[i] = prev
        }
        return out
    }

    fun trueRange(c: Candle, prevClose: BigDecimal): BigDecimal {
        val hl = c.high - c.low
        val hc = (c.high - prevClose).abs()
        val lc = (c.low - prevClose).abs()
        return Dec.max(hl, Dec.max(hc, lc))
    }

    /**
     * Wilder ATR: first value at index [period] = mean of TR[1..period]; then
     * ATR = (prev * (period - 1) + TR) / period.
     */
    fun atr(candles: List<Candle>, period: Int): Array<BigDecimal?> {
        val out = arrayOfNulls<BigDecimal>(candles.size)
        if (candles.size <= period) return out
        var sum = Dec.ZERO
        for (i in 1..period) sum += trueRange(candles[i], candles[i - 1].close)
        var prev = Dec.div(sum, BigDecimal(period))
        out[period] = prev
        val p = BigDecimal(period)
        val pm1 = BigDecimal(period - 1)
        for (i in period + 1 until candles.size) {
            val tr = trueRange(candles[i], candles[i - 1].close)
            prev = Dec.div(prev.multiply(pm1) + tr, p)
            out[i] = prev
        }
        return out
    }

    /** Wilder RSI with seeding by the mean gain/loss of the first [period] changes. */
    fun rsi(closes: List<BigDecimal>, period: Int): Array<BigDecimal?> {
        val out = arrayOfNulls<BigDecimal>(closes.size)
        if (closes.size <= period) return out
        var gain = Dec.ZERO
        var loss = Dec.ZERO
        for (i in 1..period) {
            val ch = closes[i] - closes[i - 1]
            if (ch.signum() > 0) gain += ch else loss += ch.negate()
        }
        val p = BigDecimal(period)
        val pm1 = BigDecimal(period - 1)
        var avgGain = Dec.div(gain, p)
        var avgLoss = Dec.div(loss, p)
        out[period] = rsiValue(avgGain, avgLoss)
        for (i in period + 1 until closes.size) {
            val ch = closes[i] - closes[i - 1]
            val g = if (ch.signum() > 0) ch else Dec.ZERO
            val l = if (ch.signum() < 0) ch.negate() else Dec.ZERO
            avgGain = Dec.div(avgGain.multiply(pm1) + g, p)
            avgLoss = Dec.div(avgLoss.multiply(pm1) + l, p)
            out[i] = rsiValue(avgGain, avgLoss)
        }
        return out
    }

    private fun rsiValue(avgGain: BigDecimal, avgLoss: BigDecimal): BigDecimal {
        if (avgLoss.signum() == 0) return if (avgGain.signum() == 0) BigDecimal(50) else Dec.HUNDRED
        val rs = Dec.div(avgGain, avgLoss)
        return Dec.HUNDRED - Dec.div(Dec.HUNDRED, Dec.ONE + rs)
    }

    /** Mean volume of the [period] closed candles before [index], excluding the candle at [index]. */
    fun meanVolumeBefore(candles: List<Candle>, index: Int, period: Int): BigDecimal? {
        if (index - period < 0) return null
        var sum = Dec.ZERO
        for (i in index - period until index) sum += candles[i].volume
        return Dec.div(sum, BigDecimal(period))
    }
}
