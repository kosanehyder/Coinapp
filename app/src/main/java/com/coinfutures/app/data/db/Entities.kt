package com.coinfutures.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local persisted records (section 7). All prices and quantities are stored as decimal strings
 * (ARCH01). Every user-owned row carries [ownerId]; queries always filter by it (AT13).
 */

const val LOCAL_OWNER = "local-guest"

@Entity(tableName = "markets")
data class MarketEntity(
    @PrimaryKey val instrumentId: String,
    val venue: String,
    val venueName: String,
    val symbol: String,
    val base: String,
    val displayName: String?,
    val saved: Boolean,
    val lastViewedAt: Long?,
    val lastStatus: String?,
    val lastPrice: String?,
    val lastAnalysisAt: Long?,
    val ownerId: String = LOCAL_OWNER,
)

@Entity(
    tableName = "plan_revisions",
    indices = [Index(value = ["planKey", "revision"], unique = true), Index("instrumentId")],
)
data class PlanRevisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planKey: String,
    val revision: Int,
    val parentId: Long?,
    val instrumentId: String,
    val symbol: String,
    val strategyVersion: String,
    val direction: String,
    /** PlanState name, or SUPERSEDED when a newer plan replaced it. */
    val state: String,
    val actionable: Boolean,
    val reasonCode: String?,
    val createdAt: Long,
    val stateChangedAt: Long,
    val recordedAt: Long,
    val snapshotTime: Long,
    val analysisId: String,
    val zoneLower: String,
    val zoneUpper: String,
    val entryLower: String?,
    val entryUpper: String?,
    val stop: String?,
    val tp1: String?,
    val tp2: String?,
    val validUntil: Long?,
)

@Entity(tableName = "alerts", indices = [Index(value = ["ownerId", "instrumentId"], unique = true)])
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val alertId: Long = 0,
    val ownerId: String = LOCAL_OWNER,
    val instrumentId: String,
    val symbol: String,
    val venueName: String,
    /** Comma-separated AlertEventType names. */
    val eventTypes: String,
    val enabled: Boolean,
    val createdAt: Long,
)

@Entity(tableName = "alert_events", indices = [Index(value = ["dedupKey"], unique = true), Index("instrumentId")])
data class AlertEventEntity(
    @PrimaryKey(autoGenerate = true) val eventId: Long = 0,
    val ownerId: String = LOCAL_OWNER,
    val dedupKey: String,
    val eventType: String,
    val instrumentId: String,
    val symbol: String,
    val planKey: String?,
    val planRevision: Int?,
    val direction: String?,
    val triggerPrice: String?,
    val planTime: Long?,
    val validUntil: Long?,
    val triggeredAt: Long,
    val recordedAt: Long,
    /** RECORDED, SENT_TO_SYSTEM, OPENED, NOT_SENT_STALE, NOT_SENT_DISABLED, NOT_SENT_PERMISSION. */
    val deliveryStatus: String,
    val sentAt: Long?,
)

@Entity(tableName = "zone_approach", primaryKeys = ["instrumentId", "zoneId"])
data class ZoneApproachEntity(
    val instrumentId: String,
    val zoneId: String,
    val inside: Boolean,
    val lastFiredAt: Long?,
)

@Entity(tableName = "paper_trades", indices = [Index("ownerId"), Index("planKey")])
data class PaperTradeEntity(
    @PrimaryKey(autoGenerate = true) val tradeId: Long = 0,
    val ownerId: String = LOCAL_OWNER,
    val planKey: String,
    val planRevision: Int,
    val instrumentId: String,
    val symbol: String,
    val direction: String,
    val enteredByUserAt: Long,
    val entryPrice: String,
    val quantity: String,
    val remainingQuantity: String,
    val stop: String,
    val tp1: String?,
    val tp2: String?,
    val zoneFarEdge: String?,
    /** JSON list of RecordedExit. */
    val recordedExits: String,
    val notes: String,
    /** OPEN or CLOSED. */
    val status: String,
    val updatedAt: Long,
)
