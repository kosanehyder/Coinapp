package com.coinfutures.engine

import java.math.BigDecimal

enum class ZoneRole { SUPPORT, RESISTANCE, INTERACTION;
    fun mirrored() = when (this) { SUPPORT -> RESISTANCE; RESISTANCE -> SUPPORT; INTERACTION -> INTERACTION }
}

enum class BreakStatus { NONE, CANDIDATE_BREAK_UP, CANDIDATE_BREAK_DOWN;
    fun mirrored() = when (this) { NONE -> NONE; CANDIDATE_BREAK_UP -> CANDIDATE_BREAK_DOWN; CANDIDATE_BREAK_DOWN -> CANDIDATE_BREAK_UP }
}

/**
 * Support/resistance band. [visitCount] and [lastVisit] are the auditable evidence behind
 * [rank]; the rank is an ordering, not a win probability.
 */
data class Zone(
    val zoneId: String,
    val timeframe: Timeframe,
    val lower: BigDecimal,
    val upper: BigDecimal,
    val role: ZoneRole,
    val pivotTimes: List<Long>,
    val confirmedAt: Long,
    val visitCount: Int,
    val lastVisit: Long?,
    val rank: Int,
    val breakStatus: BreakStatus,
) {
    val mid: BigDecimal get() = Dec.div(lower + upper, BigDecimal(2))

    fun contains(price: BigDecimal) = price >= lower && price <= upper

    fun mirrored(): Zone = copy(
        lower = upper.negate(), upper = lower.negate(), role = role.mirrored(), breakStatus = breakStatus.mirrored(),
        zoneId = zoneIdOf(timeframe, upper.negate(), lower.negate()),
    )

    companion object {
        fun zoneIdOf(tf: Timeframe, lower: BigDecimal, upper: BigDecimal) =
            "${tf.code}:${lower.toPlainString()}..${upper.toPlainString()}"
    }
}

data class ZoneSet(
    val all: List<Zone>,
    val supports: List<Zone>,
    val resistances: List<Zone>,
    val interaction: Zone?,
    val atr: BigDecimal,
    val referencePrice: BigDecimal,
) {
    /** ZONE04: fewer than two levels on either side. */
    val limitedStructure: Boolean get() = supports.size < 2 || resistances.size < 2
}

object Zones {

    private class Band(var lower: BigDecimal, var upper: BigDecimal, val pivots: MutableList<Pivot>)

    /**
     * Builds zones from confirmed 1H pivots in the [StrategyConfig.zoneLookback1h] closed candles
     * ending at [endIndex] (inclusive). Only data up to [endIndex] is read.
     */
    fun build(
        candles: List<Candle>,
        endIndex: Int,
        atr: BigDecimal,
        tick: BigDecimal,
        referencePrice: BigDecimal,
        config: StrategyConfig,
    ): ZoneSet {
        val start = maxOf(0, endIndex - config.zoneLookback1h + 1)
        val visible = candles.subList(0, endIndex + 1)
        val pivots = Pivots.find(visible, config.pivotLeft, config.pivotRight).filter { it.index >= start }

        val halfWidth = Dec.max(atr.multiply(config.zoneHalfWidthAtr), tick.multiply(BigDecimal(config.zoneMinHalfWidthTicks)))
        val bands = pivots.map {
            Band(Dec.floorTo(it.price - halfWidth, tick), Dec.ceilTo(it.price + halfWidth, tick), mutableListOf(it))
        }.sortedWith(compareBy<Band> { it.lower + it.upper }.thenBy { it.lower })

        // Merge overlapping bands left to right using their union.
        val merged = ArrayList<Band>()
        for (b in bands) {
            val last = merged.lastOrNull()
            if (last != null && b.lower <= last.upper) {
                last.upper = Dec.max(last.upper, b.upper)
                last.lower = Dec.min(last.lower, b.lower)
                last.pivots += b.pivots
            } else {
                merged += Band(b.lower, b.upper, b.pivots.toMutableList())
            }
        }

        val buffer = atr.multiply(config.breakBufferAtr)
        val last = visible[endIndex]
        val prev = if (endIndex > 0) visible[endIndex - 1] else null

        data class Scored(val band: Band, val visits: Int, val lastVisit: Long?, val role: ZoneRole, val distance: BigDecimal, val brk: BreakStatus)

        val scored = merged.map { band ->
            // Distinct visits: an intersecting candle after >= visitGapCandles non-intersecting candles
            // (or the first intersection in the window). One continuous stay counts once.
            var visits = 0
            var lastVisit: Long? = null
            var nonIntersecting = Int.MAX_VALUE // no intersection seen yet
            var inStay = false
            for (i in start..endIndex) {
                val c = visible[i]
                if (c.intersects(band.lower, band.upper)) {
                    if (!inStay) {
                        if (nonIntersecting >= config.visitGapCandles) visits++
                        inStay = true
                    }
                    nonIntersecting = 0
                    lastVisit = c.openTime
                } else {
                    if (nonIntersecting != Int.MAX_VALUE) nonIntersecting++
                    if (nonIntersecting >= config.visitGapCandles) inStay = false
                }
            }
            val role = when {
                band.upper < referencePrice -> ZoneRole.SUPPORT
                band.lower > referencePrice -> ZoneRole.RESISTANCE
                else -> ZoneRole.INTERACTION
            }
            val distance = when (role) {
                ZoneRole.SUPPORT -> referencePrice - band.upper
                ZoneRole.RESISTANCE -> band.lower - referencePrice
                ZoneRole.INTERACTION -> Dec.ZERO
            }
            val brk = when {
                prev != null && last.close >= band.upper + buffer && prev.close <= band.upper -> BreakStatus.CANDIDATE_BREAK_UP
                prev != null && last.close <= band.lower - buffer && prev.close >= band.lower -> BreakStatus.CANDIDATE_BREAK_DOWN
                else -> BreakStatus.NONE
            }
            Scored(band, visits, lastVisit, role, distance, brk)
        }

        val ranked = scored.sortedWith(
            compareByDescending<Scored> { it.visits }
                .thenByDescending { it.lastVisit ?: Long.MIN_VALUE }
                .thenBy { it.distance }
                .thenBy { it.band.lower }
        )
        val rankOf = java.util.IdentityHashMap<Band, Int>()
        ranked.forEachIndexed { i, s -> rankOf[s.band] = i + 1 }

        val zones = scored.map { s ->
            Zone(
                zoneId = Zone.zoneIdOf(Timeframe.H1, s.band.lower, s.band.upper),
                timeframe = Timeframe.H1,
                lower = s.band.lower,
                upper = s.band.upper,
                role = s.role,
                pivotTimes = s.band.pivots.map { it.pivotTime }.sorted(),
                confirmedAt = s.band.pivots.maxOf { it.confirmedAt },
                visitCount = s.visits,
                lastVisit = s.lastVisit,
                rank = rankOf.getValue(s.band),
                breakStatus = s.brk,
            )
        }
        val supports = zones.filter { it.role == ZoneRole.SUPPORT }.sortedByDescending { it.upper }.take(config.zonesPerSide)
        val resistances = zones.filter { it.role == ZoneRole.RESISTANCE }.sortedBy { it.lower }.take(config.zonesPerSide)
        val interaction = zones.firstOrNull { it.role == ZoneRole.INTERACTION }
        return ZoneSet(zones, supports, resistances, interaction, atr, referencePrice)
    }

    /** UI strength label is always paired with the visit count and recency (ZONE02). */
    fun strengthLabel(visits: Int): String = when {
        visits >= 3 -> "STRONG"
        visits == 2 -> "MODERATE"
        else -> "LIGHT"
    }
}
