package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface InstalledAppDao {
    @Query("SELECT * FROM installed_apps ORDER BY label ASC")
    fun getAllApps(): Flow<List<InstalledAppEntity>>

    @Query("SELECT * FROM installed_apps WHERE isInstalled = 1 ORDER BY label ASC")
    fun getInstalledApps(): Flow<List<InstalledAppEntity>>

    @Query("SELECT * FROM installed_apps")
    suspend fun getAllAppsList(): List<InstalledAppEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertApps(apps: List<InstalledAppEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertApp(app: InstalledAppEntity)

    @Query("UPDATE installed_apps SET lastUsedTimestamp = :timestamp WHERE packageName = :packageName AND activityName = :activityName")
    suspend fun updateLastUsedTimestamp(packageName: String, activityName: String, timestamp: Long)

    @Query("DELETE FROM installed_apps WHERE packageName = :packageName AND activityName = :activityName")
    suspend fun deleteByComponent(packageName: String, activityName: String)

    @Query("DELETE FROM installed_apps")
    suspend fun deleteAll()
}
