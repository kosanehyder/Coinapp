package com.coinfutures.engine

import java.math.BigDecimal

enum class AlertEventType {
    ZONE_APPROACH, ENTRY_READY, SETUP_INVALIDATED, SETUP_EXPIRED, TARGET_TOUCHED, STOP_TOUCHED, REVIEW_NEEDED;

    val isTerminal get() = this == SETUP_INVALIDATED || this == SETUP_EXPIRED
}

data class AlertCandidate(
    val type: AlertEventType,
    val instrumentId: String,
    val planKey: String?,
    val revision: Int?,
    val thresholdId: String,
    val triggerPrice: BigDecimal?,
    val direction: Direction?,
    val planTime: Long?,
    val validUntil: Long?,
    val sourceTime: Long,
) {
    fun dedupKey(userId: String) = AlertRules.dedupKey(userId, instrumentId, planKey, revision, type, thresholdId)
}

/** Alert deduplication and transition rules (ALERT01-ALERT04). */
object AlertRules {

    /** ALERT02: user + instrument + plan revision + event type + threshold id. */
    fun dedupKey(userId: String, instrumentId: String, planKey: String?, revision: Int?, type: AlertEventType, thresholdId: String) =
        listOf(userId, instrumentId, planKey ?: "-", revision?.toString() ?: "-", type.name, thresholdId).joinToString("#")

    /** Plan state events use transition-based deduplication. */
    fun planTransition(previous: PlanState?, previousActionable: Boolean, current: TradePlan, revision: Int): AlertCandidate? {
        val type = when {
            current.actionable && !(previous == PlanState.ENTRY_READY && previousActionable) -> AlertEventType.ENTRY_READY
            current.state == PlanState.INVALIDATED && previous != PlanState.INVALIDATED && previous != null -> AlertEventType.SETUP_INVALIDATED
            current.state == PlanState.EXPIRED && previous != PlanState.EXPIRED && previous != null -> AlertEventType.SETUP_EXPIRED
            else -> null
        } ?: return null
        val price = when (type) {
            AlertEventType.ENTRY_READY -> current.metrics?.worstEntry
            else -> current.stop
        }
        return AlertCandidate(
            type, current.instrumentId, current.planKey, revision, current.state.name, price, current.direction,
            current.createdAt, current.validUntil, current.stateChangedAt,
        )
    }

    data class ZoneApproachState(val zoneId: String, val inside: Boolean, val lastFiredAt: Long?)

    /**
     * ALERT03: fire when price enters a zone band; 15-minute cooldown; re-arms only after price
     * exits and re-enters the band.
     */
    fun zoneApproach(prev: ZoneApproachState?, zone: Zone, price: BigDecimal, now: Long, cooldownMs: Long): Pair<Boolean, ZoneApproachState> {
        val insideNow = zone.contains(price)
        val wasInside = prev?.inside ?: false
        val cooled = prev?.lastFiredAt == null || now - prev.lastFiredAt >= cooldownMs
        val fire = insideNow && !wasInside && cooled
        val next = ZoneApproachState(zone.zoneId, insideNow, if (fire) now else prev?.lastFiredAt)
        return fire to next
    }

    /** ALERT04: an event is dispatched only while its plan is still valid and data is fresh. */
    fun dispatchable(c: AlertCandidate, now: Long, dataFresh: Boolean): Boolean {
        if (!dataFresh) return false
        if (c.type == AlertEventType.ENTRY_READY && c.validUntil != null && now > c.validUntil) return false
        return true
    }
}
