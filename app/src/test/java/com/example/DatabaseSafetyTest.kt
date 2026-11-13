package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.DatabaseMigrations
import com.example.data.database.ShellfriDatabase
import com.example.data.database.WorkspaceContainers
import com.example.data.database.WorkspaceItemTypes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DatabaseSafetyTest {

    @Test
    fun `migration from v1 to v4 preserves existing apps and populates defaults`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "test_migration_v1_v4.db"
        context.deleteDatabase(dbName)

        val legacyHelper = object : SQLiteOpenHelper(context, dbName, null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `apps` (
                        `packageName` TEXT NOT NULL PRIMARY KEY,
                        `activityName` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `isShuffled` INTEGER NOT NULL,
                        `isFixed` INTEGER NOT NULL,
                        `slotIndex` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `apps` (`packageName`, `activityName`, `label`, `isShuffled`, `isFixed`, `slotIndex`)
                    VALUES ('com.instagram.android', 'com.instagram.MainActivity', 'Instagram', 1, 0, 3)
                    """.trimIndent()
                )
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        legacyHelper.writableDatabase.close()

        val roomDb = ShellfriDatabase.buildDatabase(context, dbName)
        try {
            val apps = roomDb.installedAppDao().getAllAppsList()
            assertEquals(1, apps.size)
            val insta = apps.first()
            assertEquals("com.instagram.android", insta.packageName)
            assertEquals("com.instagram.MainActivity", insta.activityName)
            assertEquals("Instagram", insta.label)
            assertTrue(insta.isInstalled)

            val homeItems = roomDb.workspaceDao().getWorkspaceItemsList()
            assertEquals(1, homeItems.size)
            val instaItem = homeItems.first()
            assertEquals(WorkspaceItemTypes.APP, instaItem.itemType)
            assertEquals(WorkspaceContainers.HOME, instaItem.containerType)
            assertTrue(instaItem.isShuffled)
            assertFalse(instaItem.isFixed)
            assertEquals(3, instaItem.rank)

            val drawers = roomDb.workspaceDao().getDrawerLayoutsList()
            assertEquals(1, drawers.size)
            assertEquals(-1, drawers.first().drawerRank)
            assertFalse(drawers.first().isDrawerShuffled)

            assertEquals(0, roomDb.workspaceDao().getFoldersList().size)
        } finally {
            roomDb.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `migration from v2 to v4 preserves apps folders and history`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "test_migration_v2_v4.db"
        context.deleteDatabase(dbName)

        val v2Helper = object : SQLiteOpenHelper(context, dbName, null, 2) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `apps` (
                        `packageName` TEXT NOT NULL PRIMARY KEY,
                        `activityName` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `isShuffled` INTEGER NOT NULL,
                        `isFixed` INTEGER NOT NULL,
                        `slotIndex` INTEGER NOT NULL,
                        `pageIndex` INTEGER NOT NULL DEFAULT 0,
                        `folderId` TEXT,
                        `isPinnedToDock` INTEGER NOT NULL DEFAULT 0,
                        `isOnHomeScreen` INTEGER NOT NULL DEFAULT 1,
                        `isVisibleInFocus` INTEGER NOT NULL DEFAULT 0,
                        `isEssentialInFocus` INTEGER NOT NULL DEFAULT 0,
                        `isHidden` INTEGER NOT NULL DEFAULT 0,
                        `isSystemApp` INTEGER NOT NULL DEFAULT 0,
                        `customCategory` TEXT NOT NULL DEFAULT 'Default',
                        `lastUsedTimestamp` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `folders` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `name` TEXT NOT NULL,
                        `pageIndex` INTEGER NOT NULL DEFAULT 0,
                        `slotIndex` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `shuffle_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `shuffledAppCount` INTEGER NOT NULL,
                        `triggerType` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("INSERT INTO `folders` VALUES ('folder_1', 'Social', 1, 2, 1000)")
                db.execSQL(
                    """
                    INSERT INTO `apps` VALUES (
                        'com.reddit.frontpage', 'Main', 'Reddit', 1, 0, 5, 1, 'folder_1',
                        0, 0, 1, 0, 0, 0, 'Social', 5000
                    )
                    """.trimIndent()
                )
                db.execSQL("INSERT INTO `shuffle_history` (`timestamp`, `shuffledAppCount`, `triggerType`) VALUES (9999, 4, 'MANUAL')")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        v2Helper.writableDatabase.close()

        val roomDb = ShellfriDatabase.buildDatabase(context, dbName)
        try {
            val apps = roomDb.installedAppDao().getAllAppsList()
            val folders = roomDb.workspaceDao().getFoldersList()
            val members = roomDb.workspaceDao().getFolderMembersList()
            val pages = roomDb.workspaceDao().getPagesList()
            val items = roomDb.workspaceDao().getWorkspaceItemsList()
            val drawers = roomDb.workspaceDao().getDrawerLayoutsList()
            val focusApps = roomDb.workspaceDao().getFocusAppsList()
            val history = roomDb.shuffleHistoryDao().getRecentHistory().first()

            assertEquals(1, apps.size)
            assertEquals(2, pages.size)
            assertEquals(1, folders.size)
            assertEquals("Social", folders.first().name)
            assertEquals(1, members.size)
            assertEquals("folder_1", members.first().folderId)
            assertEquals("com.reddit.frontpage", members.first().packageName)
            assertEquals("Main", members.first().activityName)

            val folderPlacement = items.single()
            assertEquals(WorkspaceItemTypes.FOLDER, folderPlacement.itemType)
            assertEquals("page_1", folderPlacement.pageId)
            assertEquals(2, folderPlacement.rank)

            assertEquals(-1, drawers.first().drawerRank)
            assertFalse(drawers.first().isDrawerShuffled)
            assertTrue(focusApps.first().isVisibleInFocus)
            assertEquals(1, history.size)
            assertEquals(4, history.first().shuffledAppCount)
            assertEquals("FULL_RANDOM", history.first().strategy)
            assertEquals("HOME", history.first().scope)
        } finally {
            roomDb.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `database refuses destructive fallback on unknown future version`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "test_no_destructive_fallback.db"
        context.deleteDatabase(dbName)

        val futureHelper = object : SQLiteOpenHelper(context, dbName, null, 99) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `user_marker` (`id` INTEGER PRIMARY KEY)")
                db.execSQL("INSERT INTO `user_marker` VALUES (42)")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        futureHelper.writableDatabase.close()

        val roomDb = ShellfriDatabase.buildDatabase(context, dbName)
        try {
            roomDb.openHelper.writableDatabase
            fail("Expected IllegalStateException when no migration path exists rather than destructive fallback")
        } catch (expected: IllegalStateException) {
            assertNotNull(expected.message)
        } finally {
            roomDb.close()
            // Verify original data was NOT wiped
            val verifyDb = SQLiteDatabase.openDatabase(
                context.getDatabasePath(dbName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            verifyDb.rawQuery("SELECT id FROM user_marker", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(42, cursor.getInt(0))
            }
            verifyDb.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `explicit migrations array covers v1 to v4`() {
        assertEquals(3, DatabaseMigrations.ALL.size)
        assertEquals(1, DatabaseMigrations.MIGRATION_1_2.startVersion)
        assertEquals(2, DatabaseMigrations.MIGRATION_1_2.endVersion)
        assertEquals(2, DatabaseMigrations.MIGRATION_2_3.startVersion)
        assertEquals(3, DatabaseMigrations.MIGRATION_2_3.endVersion)
        assertEquals(3, DatabaseMigrations.MIGRATION_3_4.startVersion)
        assertEquals(4, DatabaseMigrations.MIGRATION_3_4.endVersion)
    }
}
