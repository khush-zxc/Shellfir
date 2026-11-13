package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.ShellfriDatabase
import com.example.data.database.WorkspaceContainers
import com.example.data.database.WorkspaceItemTypes
import com.example.domain.validation.WorkspaceValidator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Migration3To4Test {

    private fun createV3Db(
        context: Context,
        dbName: String,
        seedBlock: (SQLiteDatabase) -> Unit
    ) {
        context.deleteDatabase(dbName)
        val helper = object : SQLiteOpenHelper(context, dbName, null, 3) {
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
                        `pageIndex` INTEGER NOT NULL,
                        `folderId` TEXT,
                        `isPinnedToDock` INTEGER NOT NULL,
                        `isOnHomeScreen` INTEGER NOT NULL,
                        `isVisibleInFocus` INTEGER NOT NULL,
                        `isEssentialInFocus` INTEGER NOT NULL,
                        `isHidden` INTEGER NOT NULL,
                        `isSystemApp` INTEGER NOT NULL,
                        `customCategory` TEXT NOT NULL,
                        `lastUsedTimestamp` INTEGER NOT NULL,
                        `drawerSlotIndex` INTEGER NOT NULL,
                        `isDrawerShuffled` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `folders` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `name` TEXT NOT NULL,
                        `pageIndex` INTEGER NOT NULL,
                        `slotIndex` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
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
                seedBlock(db)
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        helper.writableDatabase.close()
    }

    @Test
    fun `1 empty v3 database migrates cleanly with one default page`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "mig_3_4_empty.db"
        createV3Db(context, dbName) {}

        val db = ShellfriDatabase.buildDatabase(context, dbName)
        try {
            val pages = db.workspaceDao().getPagesList()
            assertEquals(1, pages.size)
            assertEquals(0, pages.first().pageIndex)
            assertTrue(pages.first().isDefault)
            assertTrue(db.installedAppDao().getAllAppsList().isEmpty())
            assertTrue(db.workspaceDao().getWorkspaceItemsList().isEmpty())
            assertTrue(db.workspaceDao().getFoldersList().isEmpty())
            assertTrue(db.workspaceDao().getFolderMembersList().isEmpty())
            assertTrue(db.workspaceDao().getDrawerLayoutsList().isEmpty())
            assertTrue(db.workspaceDao().getFocusAppsList().isEmpty())
            assertTrue(db.shuffleHistoryDao().getRecentHistory().first().isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `2 populated v3 database preserves apps placements flags and blank activity names without inventing names`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "mig_3_4_populated.db"
        createV3Db(context, dbName) { db ->
            db.execSQL(
                """
                INSERT INTO `apps` VALUES (
                    'com.instagram.android', 'com.instagram.MainActivity', 'Instagram',
                    1, 0, 2, 0, NULL, 0, 1, 0, 0, 0, 0, 'Social', 12000, -1, 0
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO `apps` VALUES (
                    'com.blank.activity', '', 'BlankActApp',
                    0, 1, 0, 2, NULL, 0, 1, 0, 0, 1, 1, 'Tools', 9000, -1, 0
                )
                """.trimIndent()
            )
        }

        val db = ShellfriDatabase.buildDatabase(context, dbName)
        try {
            val installed = db.installedAppDao().getAllAppsList().associateBy { it.packageName }
            assertEquals(2, installed.size)
            assertEquals("com.instagram.MainActivity", installed["com.instagram.android"]!!.activityName)
            assertEquals("", installed["com.blank.activity"]!!.activityName)
            assertTrue(installed["com.blank.activity"]!!.isSystemApp)
            assertEquals("Tools", installed["com.blank.activity"]!!.customCategory)
            assertEquals(9000L, installed["com.blank.activity"]!!.lastUsedTimestamp)

            val pages = db.workspaceDao().getPagesList().sortedBy { it.pageIndex }
            assertEquals(3, pages.size)

            val items = db.workspaceDao().getWorkspaceItemsList().associateBy { it.packageName }
            val insta = items["com.instagram.android"]!!
            assertEquals("page_0", insta.pageId)
            assertEquals(2, insta.rank)
            assertTrue(insta.isShuffled)
            assertFalse(insta.isFixed)

            val blankAct = items["com.blank.activity"]!!
            assertEquals("", blankAct.activityName)
            assertEquals("page_2", blankAct.pageId)
            assertEquals(0, blankAct.rank)
            assertTrue(blankAct.isFixed)
            assertTrue(blankAct.isHidden)

            val validation = WorkspaceValidator().validateNormalizedWorkspace(
                installedApps = db.installedAppDao().getAllAppsList(),
                pages = pages,
                workspaceItems = db.workspaceDao().getWorkspaceItemsList(),
                folders = db.workspaceDao().getFoldersList(),
                folderMembers = db.workspaceDao().getFolderMembersList(),
                drawerLayouts = db.workspaceDao().getDrawerLayoutsList(),
                focusApps = db.workspaceDao().getFocusAppsList()
            )
            assertTrue("Migrated workspace invalid: ${validation.errors}", validation.isValid)
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `3 4 5 6 7 8 folder members dock placements focus drawer and history migrate accurately`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "mig_3_4_full_features.db"
        createV3Db(context, dbName) { db ->
            db.execSQL("INSERT INTO `folders` VALUES ('f_social', 'Social', 0, 1, 5555)")
            // App inside folder
            db.execSQL(
                """
                INSERT INTO `apps` VALUES (
                    'com.reddit.frontpage', 'com.reddit.Home', 'Reddit',
                    1, 0, 0, 0, 'f_social', 0, 0, 0, 0, 0, 0, 'Social', 100, 3, 1
                )
                """.trimIndent()
            )
            // Dock app 1 (notice v3 legacy kept isOnHomeScreen = 1 while UI filtered !isPinnedToDock)
            db.execSQL(
                """
                INSERT INTO `apps` VALUES (
                    'com.android.dialer', 'com.android.dialer.Main', 'Phone',
                    0, 1, 2, 0, NULL, 1, 1, 1, 1, 0, 1, 'Communication', 200, 0, 0
                )
                """.trimIndent()
            )
            // Dock app 2
            db.execSQL(
                """
                INSERT INTO `apps` VALUES (
                    'com.android.mms', 'com.android.mms.Main', 'Messages',
                    0, 0, 5, 0, NULL, 1, 1, 1, 0, 0, 1, 'Communication', 300, 1, 0
                )
                """.trimIndent()
            )
            // Home app with drawer shuffle config
            db.execSQL(
                """
                INSERT INTO `apps` VALUES (
                    'com.google.android.youtube', 'com.google.android.youtube.HomeActivity', 'YouTube',
                    1, 0, 3, 1, NULL, 0, 1, 0, 0, 1, 0, 'Video', 400, 4, 1
                )
                """.trimIndent()
            )
            db.execSQL("INSERT INTO `shuffle_history` (`id`, `timestamp`, `shuffledAppCount`, `triggerType`) VALUES (7, 88888, 6, 'SCHEDULED')")
        }

        val db = ShellfriDatabase.buildDatabase(context, dbName)
        try {
            // 4. Existing folder membership
            val folders = db.workspaceDao().getFoldersList()
            assertEquals(1, folders.size)
            assertEquals("f_social", folders.first().folderId)
            assertEquals("Social", folders.first().name)
            val folderPlacement = db.workspaceDao().getWorkspaceItemsList()
                .first { it.itemType == WorkspaceItemTypes.FOLDER }
            assertEquals("page_0", folderPlacement.pageId)
            assertEquals(1, folderPlacement.rank)

            val members = db.workspaceDao().getFolderMembersList()
            assertEquals(1, members.size)
            assertEquals("f_social", members.first().folderId)
            assertEquals("com.reddit.frontpage", members.first().packageName)
            assertEquals("com.reddit.Home", members.first().activityName)
            assertTrue(members.first().isShuffled)

            // 5. Existing dock placements: migrated to DOCK only with pageId = null and sequential rank
            val dockItems = db.workspaceDao().getWorkspaceItemsList()
                .filter { it.containerType == WorkspaceContainers.DOCK }
                .sortedBy { it.rank }
            assertEquals(2, dockItems.size)
            assertEquals("com.android.dialer", dockItems[0].packageName)
            assertEquals(0, dockItems[0].rank)
            assertNull(dockItems[0].pageId)
            assertEquals("com.android.mms", dockItems[1].packageName)
            assertEquals(1, dockItems[1].rank)
            assertNull(dockItems[1].pageId)
            // Ensure dock apps were NOT duplicated as HOME items
            assertEquals(0, db.workspaceDao().getWorkspaceItemsList().count {
                it.containerType == WorkspaceContainers.HOME &&
                    (it.packageName == "com.android.dialer" || it.packageName == "com.android.mms")
            })

            // 6. Existing Focus configuration
            val focusMap = db.workspaceDao().getFocusAppsList().associateBy { it.packageName }
            assertTrue(focusMap["com.android.dialer"]!!.isVisibleInFocus)
            assertTrue(focusMap["com.android.dialer"]!!.isEssentialInFocus)
            assertTrue(focusMap["com.android.mms"]!!.isVisibleInFocus)
            assertFalse(focusMap["com.android.mms"]!!.isEssentialInFocus)
            assertFalse(focusMap["com.google.android.youtube"]!!.isVisibleInFocus)

            // 7. Existing Drawer configuration
            val drawerMap = db.workspaceDao().getDrawerLayoutsList().associateBy { it.packageName }
            assertEquals(4, drawerMap["com.google.android.youtube"]!!.drawerRank)
            assertTrue(drawerMap["com.google.android.youtube"]!!.isDrawerShuffled)
            assertTrue(drawerMap["com.google.android.youtube"]!!.isHidden)
            assertTrue(drawerMap["com.android.dialer"]!!.isFixed)
            assertEquals(3, drawerMap["com.reddit.frontpage"]!!.drawerRank)
            assertTrue(drawerMap["com.reddit.frontpage"]!!.isDrawerShuffled)

            // 8. Existing shuffle history
            val history = db.shuffleHistoryDao().getRecentHistory().first()
            assertEquals(1, history.size)
            assertEquals(7L, history.first().id)
            assertEquals(88888L, history.first().timestamp)
            assertEquals(6, history.first().shuffledAppCount)
            assertEquals("SCHEDULED", history.first().triggerType)
            assertEquals("FULL_RANDOM", history.first().strategy)
            assertEquals("HOME", history.first().scope)

            // Also verify v4 schema supports storing a second launcher activity from the same package post-migration
            val pkgSecondActivity = com.example.data.database.InstalledAppEntity(
                packageName = "com.reddit.frontpage",
                activityName = "com.reddit.AltLauncher",
                label = "Reddit Alt",
                isInstalled = true
            )
            db.installedAppDao().upsertApp(pkgSecondActivity)
            val redditComponents = db.installedAppDao().getAllAppsList().filter { it.packageName == "com.reddit.frontpage" }
            assertEquals(2, redditComponents.size)
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }
}
