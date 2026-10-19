package com.coinfutures.engine

import java.math.BigDecimal

internal fun bd(s: String) = BigDecimal(s)

internal fun assertBd(expected: String, actual: BigDecimal?, message: String = "") {
    kotlin.test.assertNotNull(actual, message)
    kotlin.test.assertTrue(BigDecimal(expected).compareTo(actual) == 0, "$message expected $expected but was ${actual.toPlainString()}")
}

/** Simple candle builder: one candle per [tf] interval starting at [t0]. */
internal fun candles(tf: Timeframe, t0: Long, vararg ohlc: String): List<Candle> = ohlc.mapIndexed { i, row ->
    val p = row.trim().split(Regex("\\s+")).map { BigDecimal(it) }
    val v = if (p.size > 4) p[4] else BigDecimal(100)
    Candle(t0 + i * tf.millis, t0 + (i + 1) * tf.millis, p[0], p[1], p[2], p[3], v)
}
