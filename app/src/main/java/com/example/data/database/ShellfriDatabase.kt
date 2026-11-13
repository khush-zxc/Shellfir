package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        InstalledAppEntity::class,
        WorkspacePageEntity::class,
        WorkspaceItemEntity::class,
        FolderEntity::class,
        FolderMemberEntity::class,
        DrawerLayoutEntity::class,
        FocusAppEntity::class,
        ShuffleHistoryEntity::class,
        ShuffleLayoutSnapshotEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class ShellfriDatabase : RoomDatabase() {
    abstract fun installedAppDao(): InstalledAppDao
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun shuffleHistoryDao(): ShuffleHistoryDao

    companion object {
        const val DATABASE_NAME = "shellfri_launcher.db"

        @Volatile
        private var INSTANCE: ShellfriDatabase? = null

        fun getInstance(context: Context): ShellfriDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = buildDatabase(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }

        fun buildDatabase(context: Context, dbName: String = DATABASE_NAME): ShellfriDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                ShellfriDatabase::class.java,
                dbName
            )
                .addMigrations(*DatabaseMigrations.ALL)
                .build()
        }
    }
}
