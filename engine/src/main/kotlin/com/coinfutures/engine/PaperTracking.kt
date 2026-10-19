package com.coinfutures.engine

import java.math.BigDecimal

enum class TouchType { TARGET_TOUCHED, STOP_TOUCHED, AMBIGUOUS, REVIEW_TIME, REVIEW_DIRECTION, THESIS_INVALIDATED }

/** A touch observed in candle data. It never proves that a real order executed. */
data class TouchEvent(val type: TouchType, val time: Long, val price: BigDecimal?, val targetIndex: Int? = null)

data class PaperTrackResult(
    val events: List<TouchEvent>,
    val closedCandlesSinceEntry: Int,
    val stopTouched: Boolean,
    val targetsTouched: Set<Int>,
    val ambiguous: Boolean,
)

/**
 * Tracks a manually recorded paper trade against closed candles (EXIT02-EXIT04, AT11).
 * Conservative rule (disclosed in the UI): when one bar touches both stop and a target with no
 * lower-timeframe sequence, the bar is marked Ambiguous and the stop is assumed first.
 */
object PaperTracker {
    fun track(
        direction: Direction,
        entryAt: Long,
        stop: BigDecimal,
        targets: List<BigDecimal>,
        zoneFarEdge: BigDecimal?,
        c15: List<Candle>,
        c1h: List<Candle>,
        currentBias: Bias?,
        config: StrategyConfig = StrategyConfig.DEFAULT,
    ): PaperTrackResult {
        val long = direction == Direction.LONG
        val events = ArrayList<TouchEvent>()
        val after = c15.filter { it.openTime >= entryAt }
        val touched = HashSet<Int>()
        var stopHit = false
        var ambiguous = false
        for (c in after) {
            if (stopHit || touched.size == targets.size) break
            val stopNow = if (long) c.low <= stop else c.high >= stop
            val newTargets = targets.indices.filter { it !in touched && (if (long) c.high >= targets[it] else c.low <= targets[it]) }
            if (stopNow && newTargets.isNotEmpty()) {
                ambiguous = true
                events += TouchEvent(TouchType.AMBIGUOUS, c.closeTime, null)
                events += TouchEvent(TouchType.STOP_TOUCHED, c.closeTime, stop)
                stopHit = true
            } else if (stopNow) {
                events += TouchEvent(TouchType.STOP_TOUCHED, c.closeTime, stop)
                stopHit = true
            } else {
                for (i in newTargets) {
                    touched += i
                    events += TouchEvent(TouchType.TARGET_TOUCHED, c.closeTime, targets[i], i)
                }
            }
        }
        if (after.size >= config.reviewAfter15m) {
            events += TouchEvent(TouchType.REVIEW_TIME, after[config.reviewAfter15m - 1].closeTime, null)
        }
        if (zoneFarEdge != null) {
            c1h.firstOrNull { it.openTime >= entryAt && (if (long) it.close < zoneFarEdge else it.close > zoneFarEdge) }
                ?.let { events += TouchEvent(TouchType.THESIS_INVALIDATED, it.closeTime, it.close) }
        }
        val reversed = (long && currentBias == Bias.SHORT) || (!long && currentBias == Bias.LONG)
        if (reversed) events += TouchEvent(TouchType.REVIEW_DIRECTION, after.lastOrNull()?.closeTime ?: entryAt, null)
        return PaperTrackResult(events.sortedBy { it.time }, after.size, stopHit, touched, ambiguous)
    }
}
