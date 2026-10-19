package com.coinfutures.engine

import java.math.BigDecimal

enum class PivotType { HIGH, LOW }

/**
 * A confirmed swing point. [pivotTime] is the open time of the pivot candle and [confirmedAt]
 * the close time of the last right-hand candle; using [confirmedAt] prevents future leakage (AT02).
 */
data class Pivot(
    val type: PivotType,
    val index: Int,
    val price: BigDecimal,
    val pivotTime: Long,
    val confirmedAt: Long,
)

object Pivots {
    /**
     * Strict two-left/two-right pivots. Equal-value ties do not create pivots. A pivot is returned
     * only when all right-hand candles exist (and are therefore closed).
     */
    fun find(candles: List<Candle>, left: Int = 2, right: Int = 2): List<Pivot> {
        val out = ArrayList<Pivot>()
        for (i in left until candles.size - right) {
            val c = candles[i]
            var isHigh = true
            var isLow = true
            for (j in i - left..i + right) {
                if (j == i) continue
                if (candles[j].high >= c.high) isHigh = false
                if (candles[j].low <= c.low) isLow = false
                if (!isHigh && !isLow) break
            }
            val confirmedAt = candles[i + right].closeTime
            if (isHigh) out += Pivot(PivotType.HIGH, i, c.high, c.openTime, confirmedAt)
            if (isLow) out += Pivot(PivotType.LOW, i, c.low, c.openTime, confirmedAt)
        }
        return out
    }
}
