package com.coinfutures.engine

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Decimal helpers (ARCH01). All prices, quantities and money use BigDecimal; binary
 * floating point is never used for final tick or quantity-step rounding.
 */
object Dec {
    /** 34 significant digits (IEEE decimal128) for intermediate indicator math. */
    val MC: MathContext = MathContext.DECIMAL128

    val ZERO: BigDecimal = BigDecimal.ZERO
    val ONE: BigDecimal = BigDecimal.ONE
    val HUNDRED: BigDecimal = BigDecimal(100)

    fun of(s: String): BigDecimal = BigDecimal(s)
    fun of(i: Int): BigDecimal = BigDecimal(i)

    /** Round down to a multiple of [step] (floor, also correct for negative values). */
    fun floorTo(value: BigDecimal, step: BigDecimal): BigDecimal {
        require(step.signum() > 0) { "step must be positive" }
        val units = value.divide(step, 0, RoundingMode.FLOOR)
        return units.multiply(step).stripTrailingZerosSafe(step)
    }

    /** Round up to a multiple of [step] (ceiling). */
    fun ceilTo(value: BigDecimal, step: BigDecimal): BigDecimal {
        require(step.signum() > 0) { "step must be positive" }
        val units = value.divide(step, 0, RoundingMode.CEILING)
        return units.multiply(step).stripTrailingZerosSafe(step)
    }

    fun div(a: BigDecimal, b: BigDecimal): BigDecimal = a.divide(b, MC)

    fun max(a: BigDecimal, b: BigDecimal): BigDecimal = if (a >= b) a else b
    fun min(a: BigDecimal, b: BigDecimal): BigDecimal = if (a <= b) a else b

    /** Keep the scale of the step so 104.6 with tick 0.01 prints as 104.60. */
    private fun BigDecimal.stripTrailingZerosSafe(step: BigDecimal): BigDecimal =
        setScale(maxOf(step.stripTrailingZeros().scale(), 0), RoundingMode.UNNECESSARY)

    /** Round a display/derived value to [scale] decimals (half-even). */
    fun scaled(value: BigDecimal, scale: Int): BigDecimal = value.setScale(scale, RoundingMode.HALF_EVEN)
}
