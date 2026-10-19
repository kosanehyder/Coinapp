package com.coinfutures.engine

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndicatorsAndPivotsTest {

    @Test
    fun emaIsSeededWithSimpleAverageAndRejectsWarmup() {
        val values = (1..10).map { BigDecimal(it) }
        val ema = Indicators.ema(values, 5)
        for (i in 0 until 4) assertNull(ema[i])
        assertBd("3", ema[4]) // SMA(1..5)
        // k = 2/6: (6 - 3) / 3 + 3 = 4
        assertBd("4", ema[5]!!.setScale(10, java.math.RoundingMode.HALF_EVEN))
    }

    @Test
    fun wilderAtrAndRsiOnKnownSeries() {
        // Constant 2.00 range, no gaps: TR = 2 every bar, so ATR = 2.
        val rows = (0 until 30).map { "100 101 99 100" }.toTypedArray()
        val c = candles(Timeframe.H1, 0, *rows)
        val atr = Indicators.atr(c, 14)
        assertNull(atr[13])
        assertBd("2", atr[14])
        assertBd("2", atr[29])
        // Strictly rising closes: RSI = 100; strictly falling: RSI = 0.
        val up = (0 until 20).map { BigDecimal(100 + it) }
        assertBd("100", Indicators.rsi(up, 14)[19])
        val down = (0 until 20).map { BigDecimal(100 - it) }
        assertBd("0", Indicators.rsi(down, 14)[19])
    }

    @Test
    fun volumeMeanExcludesTriggerCandle() {
        val c = candles(Timeframe.H1, 0, "1 2 0.5 1 10", "1 2 0.5 1 20", "1 2 0.5 1 1000")
        assertBd("15", Indicators.meanVolumeBefore(c, 2, 2))
    }

    /** AT02: a pivot becomes visible only after both right-hand candles close. */
    @Test
    fun at02PivotTiming() {
        val rows = arrayOf("10 11 9 10", "10 12 9.5 11", "11 15 10 14", "14 14.5 12 13")
        val partial = candles(Timeframe.H1, 0, *rows)
        assertTrue(Pivots.find(partial).none { it.index == 2 }, "only one right candle closed")
        val full = candles(Timeframe.H1, 0, *rows, "13 13.5 11 12")
        val p = Pivots.find(full).single { it.type == PivotType.HIGH }
        assertEquals(2, p.index)
        assertEquals(full[4].closeTime, p.confirmedAt)
        assertEquals(full[2].openTime, p.pivotTime)
    }

    @Test
    fun equalHighsDoNotCreatePivots() {
        val c = candles(Timeframe.H1, 0, "10 11 9 10", "10 12 9.5 11", "11 15 10 14", "14 15 12 13", "13 13.5 11 12", "12 12.5 10 11")
        assertTrue(Pivots.find(c).none { it.type == PivotType.HIGH })
    }

    @Test
    fun zonesMergeOverlapsAndCountDistinctVisits() {
        val cfg = StrategyConfig.DEFAULT
        // Three nearby pivot highs (12.00, 12.10, 12.00) merge into one band; three separated visits are counted.
        val rows = arrayOf(
            "10 10.5 9.5 10", "10 11 9.8 10.5", "10.5 12 10.2 11", "11 11.2 10 10.4", "10.4 10.6 9.9 10.1",
            "10.1 10.3 9.7 10", "10 10.2 9.6 9.9", "9.9 11.0 9.8 10.8", "10.8 12.1 10.5 11.2", "11.2 11.4 10.2 10.5",
            "10.5 10.7 10.0 10.2", "10.2 10.4 9.9 10.0", "10.0 10.3 9.8 10.1", "10.1 10.4 9.9 10.0", "10.0 12.0 9.9 10.2",
            "10.2 10.5 9.9 10.1", "10.1 10.4 9.8 10.0",
        )
        val c = candles(Timeframe.H1, 0, *rows)
        val zs = Zones.build(c, c.lastIndex, bd("0.4"), bd("0.01"), bd("10.0"), cfg)
        val top = zs.resistances.first()
        assertBd("11.90", top.lower)
        assertBd("12.20", top.upper)
        assertEquals(3, top.visitCount)
        assertEquals(3, top.pivotTimes.size)
    }
}
