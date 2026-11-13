package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ShuffleHistoryDao {
    @Query("SELECT * FROM shuffle_history ORDER BY timestamp DESC, id DESC LIMIT 50")
    fun getRecentHistory(): Flow<List<ShuffleHistoryEntity>>

    @Query("SELECT * FROM shuffle_history ORDER BY timestamp DESC, id DESC LIMIT 50")
    suspend fun getRecentHistoryList(): List<ShuffleHistoryEntity>

    @Insert
    suspend fun insert(history: ShuffleHistoryEntity): Long

    @Query("DELETE FROM shuffle_history")
    suspend fun clearHistory()

    @Insert
    suspend fun insertSnapshot(snapshot: ShuffleLayoutSnapshotEntity): Long

    @Query("SELECT * FROM shuffle_layout_snapshots ORDER BY timestamp DESC, snapshotId DESC LIMIT 1")
    suspend fun getLatestSnapshot(): ShuffleLayoutSnapshotEntity?

    @Query("SELECT * FROM shuffle_layout_snapshots ORDER BY timestamp DESC, snapshotId DESC")
    fun getSnapshots(): Flow<List<ShuffleLayoutSnapshotEntity>>

    @Query("SELECT * FROM shuffle_layout_snapshots ORDER BY timestamp DESC, snapshotId DESC")
    suspend fun getSnapshotsList(): List<ShuffleLayoutSnapshotEntity>

    @Query("DELETE FROM shuffle_layout_snapshots WHERE snapshotId = :snapshotId")
    suspend fun deleteSnapshotById(snapshotId: Long)

    @Query("DELETE FROM shuffle_layout_snapshots")
    suspend fun clearSnapshots()
}
