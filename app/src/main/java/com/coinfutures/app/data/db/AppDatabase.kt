package com.coinfutures.app.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MarketDao {
    @Query("SELECT * FROM markets WHERE ownerId = '$LOCAL_OWNER' AND saved = 1 ORDER BY symbol")
    fun saved(): Flow<List<MarketEntity>>

    @Query("SELECT * FROM markets WHERE ownerId = '$LOCAL_OWNER' AND lastViewedAt IS NOT NULL ORDER BY lastViewedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<MarketEntity>>

    @Query("SELECT * FROM markets WHERE instrumentId = :id AND ownerId = '$LOCAL_OWNER'")
    suspend fun get(id: String): MarketEntity?

    @Query("SELECT * FROM markets WHERE instrumentId = :id AND ownerId = '$LOCAL_OWNER'")
    fun observe(id: String): Flow<MarketEntity?>

    @Upsert
    suspend fun upsert(e: MarketEntity)
}

@Dao
interface PlanDao {
    @Query("SELECT * FROM plan_revisions WHERE planKey = :key ORDER BY revision DESC LIMIT 1")
    suspend fun latest(key: String): PlanRevisionEntity?

    @Query("SELECT * FROM plan_revisions WHERE instrumentId = :instrumentId ORDER BY recordedAt DESC, revision DESC LIMIT 1")
    suspend fun latestForInstrument(instrumentId: String): PlanRevisionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(e: PlanRevisionEntity): Long

    @Query("SELECT * FROM plan_revisions ORDER BY recordedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<PlanRevisionEntity>>

    @Query("SELECT * FROM plan_revisions WHERE planKey = :key ORDER BY revision ASC")
    fun revisions(key: String): Flow<List<PlanRevisionEntity>>
}

@Dao
interface AlertDao {
    @Query("SELECT * FROM alerts WHERE ownerId = '$LOCAL_OWNER' ORDER BY createdAt DESC")
    fun all(): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts WHERE ownerId = '$LOCAL_OWNER' AND enabled = 1")
    suspend fun enabled(): List<AlertEntity>

    @Query("SELECT * FROM alerts WHERE ownerId = '$LOCAL_OWNER' AND instrumentId = :instrumentId")
    suspend fun forInstrument(instrumentId: String): AlertEntity?

    @Query("SELECT * FROM alerts WHERE ownerId = '$LOCAL_OWNER' AND instrumentId = :instrumentId")
    fun observeForInstrument(instrumentId: String): Flow<AlertEntity?>

    @Upsert
    suspend fun upsert(e: AlertEntity)

    @Query("UPDATE alerts SET enabled = :enabled WHERE alertId = :id AND ownerId = '$LOCAL_OWNER'")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM alerts WHERE alertId = :id AND ownerId = '$LOCAL_OWNER'")
    suspend fun delete(id: Long)

    /** Returns -1 when the dedup key already exists (ALERT02). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(e: AlertEventEntity): Long

    @Query("UPDATE alert_events SET deliveryStatus = :status, sentAt = :sentAt WHERE eventId = :id AND ownerId = '$LOCAL_OWNER'")
    suspend fun setDelivery(id: Long, status: String, sentAt: Long?)

    @Query("SELECT * FROM alert_events WHERE ownerId = '$LOCAL_OWNER' ORDER BY recordedAt DESC LIMIT :limit")
    fun events(limit: Int): Flow<List<AlertEventEntity>>

    @Query("SELECT * FROM alert_events WHERE eventId = :id AND ownerId = '$LOCAL_OWNER'")
    suspend fun event(id: Long): AlertEventEntity?

    @Query("SELECT * FROM zone_approach WHERE instrumentId = :instrumentId")
    suspend fun zoneStates(instrumentId: String): List<ZoneApproachEntity>

    @Upsert
    suspend fun upsertZoneState(e: ZoneApproachEntity)
}

@Dao
interface PaperDao {
    @Query("SELECT * FROM paper_trades WHERE ownerId = '$LOCAL_OWNER' ORDER BY enteredByUserAt DESC")
    fun all(): Flow<List<PaperTradeEntity>>

    @Query("SELECT * FROM paper_trades WHERE ownerId = '$LOCAL_OWNER' AND status = 'OPEN'")
    suspend fun open(): List<PaperTradeEntity>

    @Query("SELECT * FROM paper_trades WHERE tradeId = :id AND ownerId = '$LOCAL_OWNER'")
    fun observe(id: Long): Flow<PaperTradeEntity?>

    @Query("SELECT * FROM paper_trades WHERE tradeId = :id AND ownerId = '$LOCAL_OWNER'")
    suspend fun get(id: Long): PaperTradeEntity?

    @Insert
    suspend fun insert(e: PaperTradeEntity): Long

    @Upsert
    suspend fun update(e: PaperTradeEntity)

    @Query("DELETE FROM paper_trades WHERE tradeId = :id AND ownerId = '$LOCAL_OWNER'")
    suspend fun delete(id: Long)
}

@Database(
    entities = [
        MarketEntity::class, PlanRevisionEntity::class, AlertEntity::class, AlertEventEntity::class,
        ZoneApproachEntity::class, PaperTradeEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun markets(): MarketDao
    abstract fun plans(): PlanDao
    abstract fun alerts(): AlertDao
    abstract fun paper(): PaperDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "coinfutures.db").build()
    }
}
