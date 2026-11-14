package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.backup.BackupRestoreHelper
import com.example.data.database.DrawerLayoutEntity
import com.example.data.database.FocusAppEntity
import com.example.data.database.FolderEntity
import com.example.data.database.FolderMemberEntity
import com.example.data.database.InstalledAppEntity
import com.example.data.database.ShellfriDatabase
import com.example.data.database.WorkspaceContainers
import com.example.data.database.WorkspaceItemEntity
import com.example.data.database.WorkspaceItemTypes
import com.example.data.database.WorkspacePageEntity
import com.example.data.preferences.ShellfriPreferences
import com.example.domain.model.ShuffleStrategy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupRestoreHelperTest {

    private lateinit var context: Context
    private lateinit var db: ShellfriDatabase
    private lateinit var preferences: ShellfriPreferences
    private lateinit var helper: BackupRestoreHelper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ShellfriDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferences = ShellfriPreferences(context)
        helper = BackupRestoreHelper(
            installedAppDao = db.installedAppDao(),
            workspaceDao = db.workspaceDao(),
            preferences = preferences,
            database = db
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedPages(count: Int, defaultIndex: Int = 0): List<WorkspacePageEntity> {
        val pages = (0 until count).map { idx ->
            WorkspacePageEntity(
                pageId = "page_$idx",
                pageIndex = idx,
                isDefault = idx == defaultIndex,
                createdAt = 1000L + idx
            )
        }
        db.workspaceDao().upsertPages(pages)
        return pages
    }

    private fun installedApp(
        pkg: String,
        activity: String = "$pkg.MainActivity",
        label: String = pkg.substringAfterLast('.')
    ) = InstalledAppEntity(
        packageName = pkg,
        activityName = activity,
        label = label,
        isInstalled = true
    )

    @Test
    fun `valid v2 export preview and restore round-trip preserves normalized workspace and multi-activity apps`() = runBlocking {
        seedPages(count = 3, defaultIndex = 1)
        val folder = FolderEntity(folderId = "f1", name = "Social", createdAt = 1234L)
        db.workspaceDao().upsertFolder(folder)

        val apps = listOf(
            installedApp("com.alpha", "com.alpha.Main", "Alpha"),
            installedApp("com.alpha", "com.alpha.Camera", "Alpha Cam"),
            installedApp("com.gamma", "com.gamma.Main", "Gamma"),
            installedApp("com.delta", "com.delta.Main", "Delta"),
            installedApp("com.dock", "com.dock.Main", "Dock")
        )
        db.installedAppDao().upsertApps(apps)

        db.workspaceDao().upsertWorkspaceItems(
            listOf(
                WorkspaceItemEntity(
                    itemId = "folder_f1",
                    itemType = WorkspaceItemTypes.FOLDER,
                    containerType = WorkspaceContainers.HOME,
                    pageId = "page_0",
                    folderId = "f1",
                    rank = 2
                ),
                WorkspaceItemEntity(
                    itemId = "home_alpha_main",
                    itemType = WorkspaceItemTypes.APP,
                    containerType = WorkspaceContainers.HOME,
                    pageId = "page_0",
                    packageName = "com.alpha",
                    activityName = "com.alpha.Main",
                    rank = 0,
                    isFixed = true
                ),
                WorkspaceItemEntity(
                    itemId = "home_alpha_cam",
                    itemType = WorkspaceItemTypes.APP,
                    containerType = WorkspaceContainers.HOME,
                    pageId = "page_1",
                    packageName = "com.alpha",
                    activityName = "com.alpha.Camera",
                    rank = 1,
                    isShuffled = true
                ),
                WorkspaceItemEntity(
                    itemId = "dock_main",
                    itemType = WorkspaceItemTypes.APP,
                    containerType = WorkspaceContainers.DOCK,
                    pageId = null,
                    packageName = "com.dock",
                    activityName = "com.dock.Main",
                    rank = 0
                )
            )
        )
        db.workspaceDao().upsertFolderMembers(
            listOf(
                FolderMemberEntity(
                    folderId = "f1",
                    packageName = "com.gamma",
                    activityName = "com.gamma.Main",
                    rank = 0
                ),
                FolderMemberEntity(
                    folderId = "f1",
                    packageName = "com.delta",
                    activityName = "com.delta.Main",
                    rank = 1
                )
            )
        )
        db.workspaceDao().upsertDrawerLayouts(
            listOf(
                DrawerLayoutEntity("com.alpha", "com.alpha.Main", drawerRank = 0, isDrawerShuffled = false, isFixed = true),
                DrawerLayoutEntity("com.alpha", "com.alpha.Camera", drawerRank = 1, isDrawerShuffled = true, isFixed = false)
            )
        )
        db.workspaceDao().upsertFocusApps(
            listOf(
                FocusAppEntity("com.alpha", "com.alpha.Main", isVisibleInFocus = true, isEssentialInFocus = true),
                FocusAppEntity("com.alpha", "com.alpha.Camera", isVisibleInFocus = false, isEssentialInFocus = false)
            )
        )

        preferences.setGridColumns(5)
        preferences.setThemeMode("LIGHT")
        preferences.setPageCount(3)
        preferences.setDefaultPageIndex(1)
        preferences.setShuffleStrategy(ShuffleStrategy.SMART_SHUFFLE)

        val backupJson = helper.generateBackupJson()
        val previewResult = helper.parseBackupSummary(backupJson)
        assertTrue(previewResult.isSuccess)
        val summary = previewResult.getOrThrow()
        assertEquals(2, summary.version)
        assertEquals(5, summary.appCount)
        assertEquals(1, summary.folderCount)
        assertEquals("LIGHT", summary.themeMode)
        assertEquals(5, summary.gridColumns)
        assertEquals(3, summary.pageCount)

        // Overwrite workspace completely
        db.workspaceDao().deleteAllFolderMembers()
        db.workspaceDao().deleteAllWorkspaceItems()
        db.workspaceDao().deleteAllFolders()
        db.workspaceDao().deleteAllPages()
        db.workspaceDao().deleteAllDrawerLayouts()
        db.workspaceDao().deleteAllFocusApps()
        seedPages(count = 1, defaultIndex = 0)
        db.workspaceDao().upsertWorkspaceItems(
            listOf(
                WorkspaceItemEntity(
                    itemId = "mutated",
                    itemType = WorkspaceItemTypes.APP,
                    containerType = WorkspaceContainers.HOME,
                    pageId = "page_0",
                    packageName = "com.alpha",
                    activityName = "com.alpha.Main",
                    rank = 5
                )
            )
        )
        preferences.setGridColumns(3)
        preferences.setThemeMode("DARK")
        preferences.setPageCount(1)
        preferences.setDefaultPageIndex(0)
        preferences.setShuffleStrategy(ShuffleStrategy.FULL_RANDOM)

        val restoreResult = helper.restoreBackup(backupJson)
        assertTrue("Restore failed: ${restoreResult.exceptionOrNull()}", restoreResult.isSuccess)

        assertEquals(3, db.workspaceDao().getPagesList().size)
        val restoredFolders = db.workspaceDao().getFoldersList()
        assertEquals(1, restoredFolders.size)
        assertEquals("Social", restoredFolders.first().name)

        val restoredItems = db.workspaceDao().getWorkspaceItemsList()
        val alphaMain = restoredItems.first { it.packageName == "com.alpha" && it.activityName == "com.alpha.Main" }
        val alphaCam = restoredItems.first { it.packageName == "com.alpha" && it.activityName == "com.alpha.Camera" }
        val dockMain = restoredItems.first { it.packageName == "com.dock" }
        assertTrue(alphaMain.isFixed)
        assertEquals(0, alphaMain.rank)
        assertEquals("page_1", alphaCam.pageId)
        assertTrue(alphaCam.isShuffled)
        assertEquals(WorkspaceContainers.DOCK, dockMain.containerType)
        assertNull(dockMain.pageId)
        assertEquals(0, dockMain.rank)

        val members = db.workspaceDao().getFolderMembersList().sortedBy { it.rank }
        assertEquals(2, members.size)
        assertEquals(listOf("com.gamma", "com.delta"), members.map { it.packageName })

        val drawers = db.workspaceDao().getDrawerLayoutsList().associateBy { it.activityName }
        assertTrue(drawers["com.alpha.Main"]!!.isFixed)
        assertFalse(drawers["com.alpha.Main"]!!.isDrawerShuffled)
        assertTrue(drawers["com.alpha.Camera"]!!.isDrawerShuffled)

        val focus = db.workspaceDao().getFocusAppsList().associateBy { it.activityName }
        assertTrue(focus["com.alpha.Main"]!!.isVisibleInFocus)
        assertTrue(focus["com.alpha.Main"]!!.isEssentialInFocus)
        assertFalse(focus["com.alpha.Camera"]!!.isVisibleInFocus)

        assertEquals(5, preferences.gridColumnsFlow.first())
        assertEquals("LIGHT", preferences.themeModeFlow.first())
        assertEquals(3, preferences.pageCountFlow.first())
        assertEquals(1, preferences.defaultPageIndexFlow.first())
        assertEquals(ShuffleStrategy.SMART_SHUFFLE, preferences.shuffleStrategyFlow.first())
    }

    @Test
    fun `legacy v1 backup imports cleanly into normalized v4 workspace`() = runBlocking {
        seedPages(count = 1)
        db.installedAppDao().upsertApps(
            listOf(
                installedApp("com.alpha", "com.alpha.Main", "Alpha"),
                installedApp("com.beta", "com.beta.Main", "Beta"),
                installedApp("com.gamma", "com.gamma.Main", "Gamma"),
                installedApp("com.dock", "com.dock.Main", "Dock")
            )
        )

        val v1Json = JSONObject().apply {
            put("schemaVersion", 1)
            put("timestamp", 1700000000L)
            put(
                "preferences",
                JSONObject().apply {
                    put("homeMode", "NORMAL")
                    put("shuffleInterval", 60)
                    put("shuffleEnabled", true)
                    put("shufflePaused", false)
                    put("shuffleStrategy", "SMART_SHUFFLE")
                    put("occupancyOption", "PRESERVE_EMPTY_SPACES")
                    put("drawerShuffleEnabled", false)
                    put("themeMode", "LIGHT")
                    put("gridColumns", 5)
                    put("gridSpacing", "COMFORTABLE")
                    put("iconSize", "MEDIUM")
                    put("showLabels", true)
                    put("blurLevel", "DISABLED")
                    put("blurOnShuffle", false)
                    put("blurOnDistracting", false)
                    put("blurOnFocus", false)
                    put("pageCount", 2)
                    put("defaultPageIndex", 0)
                    put("crossPageShuffle", true)
                    put("dockCount", 5)
                    put("dockShowBackground", true)
                    put("dockShowLabels", false)
                    put("hideDistractingInFocus", true)
                    put("focusSearchEnabled", true)
                    put("gestureSwipeUp", "DRAWER")
                    put("gestureSwipeDown", "NONE")
                    put("gestureDoubleTap", "NONE")
                }
            )
            put(
                "folders",
                JSONArray().put(
                    JSONObject().apply {
                        put("id", "f_legacy")
                        put("name", "Legacy Social")
                        put("pageIndex", 0)
                        put("slotIndex", 1)
                        put("createdAt", 5000L)
                    }
                )
            )
            put(
                "apps",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("packageName", "com.alpha")
                            put("activityName", "com.alpha.Main")
                            put("label", "Alpha")
                            put("isShuffled", true)
                            put("isFixed", false)
                            put("slotIndex", 0)
                            put("pageIndex", 1)
                            put("folderId", JSONObject.NULL)
                            put("isPinnedToDock", false)
                            put("isOnHomeScreen", true)
                            put("isVisibleInFocus", true)
                            put("isEssentialInFocus", false)
                            put("isHidden", false)
                            put("drawerSlotIndex", -1)
                            put("isDrawerShuffled", false)
                        }
                    )
                    put(
                        JSONObject().apply {
                            put("packageName", "com.beta")
                            put("activityName", "com.beta.Main")
                            put("label", "Beta")
                            put("isShuffled", false)
                            put("isFixed", true)
                            put("slotIndex", 0)
                            put("pageIndex", 0)
                            put("folderId", "f_legacy")
                            put("isPinnedToDock", false)
                            put("isOnHomeScreen", false)
                            put("isVisibleInFocus", false)
                            put("isEssentialInFocus", true)
                            put("isHidden", false)
                            put("drawerSlotIndex", -1)
                            put("isDrawerShuffled", false)
                        }
                    )
                    put(
                        JSONObject().apply {
                            put("packageName", "com.gamma")
                            put("activityName", "com.gamma.Main")
                            put("label", "Gamma")
                            put("isShuffled", false)
                            put("isFixed", false)
                            put("slotIndex", 1)
                            put("pageIndex", 0)
                            put("folderId", "f_legacy")
                            put("isPinnedToDock", false)
                            put("isOnHomeScreen", false)
                            put("isVisibleInFocus", false)
                            put("isEssentialInFocus", false)
                            put("isHidden", false)
                            put("drawerSlotIndex", -1)
                            put("isDrawerShuffled", false)
                        }
                    )
                    put(
                        JSONObject().apply {
                            put("packageName", "com.dock")
                            put("activityName", "com.dock.Main")
                            put("label", "Dock")
                            put("isShuffled", false)
                            put("isFixed", false)
                            put("slotIndex", 2)
                            put("pageIndex", 0)
                            put("folderId", JSONObject.NULL)
                            put("isPinnedToDock", true)
                            put("isOnHomeScreen", true)
                            put("isVisibleInFocus", false)
                            put("isEssentialInFocus", false)
                            put("isHidden", false)
                            put("drawerSlotIndex", -1)
                            put("isDrawerShuffled", false)
                        }
                    )
                }
            )
        }.toString()

        val summary = helper.parseBackupSummary(v1Json).getOrThrow()
        assertEquals(1, summary.version)
        assertEquals(4, summary.appCount)
        assertEquals(1, summary.folderCount)

        val result = helper.restoreBackup(v1Json)
        assertTrue("v1 restore failed: ${result.exceptionOrNull()}", result.isSuccess)

        val pages = db.workspaceDao().getPagesList().sortedBy { it.pageIndex }
        assertEquals(2, pages.size)

        val items = db.workspaceDao().getWorkspaceItemsList()
        val alphaHome = items.first { it.packageName == "com.alpha" }
        assertEquals(WorkspaceContainers.HOME, alphaHome.containerType)
        assertEquals("page_1", alphaHome.pageId)
        assertTrue(alphaHome.isShuffled)

        val dockItem = items.first { it.packageName == "com.dock" }
        assertEquals(WorkspaceContainers.DOCK, dockItem.containerType)
        assertNull(dockItem.pageId)
        assertEquals(0, dockItem.rank)

        val members = db.workspaceDao().getFolderMembersList().sortedBy { it.rank }
        assertEquals(2, members.size)
        assertEquals("f_legacy", members.first().folderId)
        assertEquals("com.beta", members.first().packageName)
        assertTrue(members.first().isFixed)

        val focusMap = db.workspaceDao().getFocusAppsList().associateBy { it.packageName }
        assertTrue(focusMap["com.alpha"]!!.isVisibleInFocus)
        assertTrue(focusMap["com.beta"]!!.isEssentialInFocus)
    }

    @Test
    fun `rejects unsupported or missing schemaVersion without modifying existing workspace`() = runBlocking {
        seedPages(1)
        db.installedAppDao().upsertApp(installedApp("com.keep"))
        db.workspaceDao().upsertWorkspaceItem(
            WorkspaceItemEntity(
                itemId = "home_keep",
                itemType = WorkspaceItemTypes.APP,
                containerType = WorkspaceContainers.HOME,
                pageId = "page_0",
                packageName = "com.keep",
                activityName = "com.keep.MainActivity",
                rank = 4
            )
        )
        preferences.setGridColumns(6)
        preferences.setThemeMode("DARK")

        val validJson = JSONObject(helper.generateBackupJson())

        val futureJson = JSONObject(validJson.toString()).put("schemaVersion", 99).toString()
        assertTrue(helper.parseBackupSummary(futureJson).isFailure)
        assertTrue(helper.restoreBackup(futureJson).isFailure)

        val missingVersionJson = JSONObject(validJson.toString()).apply { remove("schemaVersion") }.toString()
        assertTrue(helper.parseBackupSummary(missingVersionJson).isFailure)
        assertTrue(helper.restoreBackup(missingVersionJson).isFailure)

        assertEquals(6, preferences.gridColumnsFlow.first())
        assertEquals("DARK", preferences.themeModeFlow.first())
        assertEquals(4, db.workspaceDao().getWorkspaceItemsList().single().rank)
    }

    @Test
    fun `rejects malformed v2 backup missing required fields or invalid enums`() = runBlocking {
        seedPages(1)
        db.installedAppDao().upsertApp(installedApp("com.one"))
        db.workspaceDao().upsertWorkspaceItem(
            WorkspaceItemEntity(
                itemId = "home_one",
                itemType = WorkspaceItemTypes.APP,
                containerType = WorkspaceContainers.HOME,
                pageId = "page_0",
                packageName = "com.one",
                activityName = "com.one.MainActivity",
                rank = 0
            )
        )
        preferences.setGridColumns(4)
        val base = JSONObject(helper.generateBackupJson())

        val missingPrefs = JSONObject(base.toString()).apply { remove("preferences") }.toString()
        assertTrue(helper.restoreBackup(missingPrefs).isFailure)

        val badEnum = JSONObject(base.toString()).apply {
            getJSONObject("preferences").put("shuffleStrategy", "NOT_A_STRATEGY")
        }.toString()
        assertTrue(helper.parseBackupSummary(badEnum).isFailure)
        assertTrue(helper.restoreBackup(badEnum).isFailure)

        val missingItems = JSONObject(base.toString()).apply { remove("workspaceItems") }.toString()
        assertTrue(helper.parseBackupSummary(missingItems).isFailure)
        assertTrue(helper.restoreBackup(missingItems).isFailure)

        assertEquals(4, preferences.gridColumnsFlow.first())
    }

    @Test
    fun `rejects workspace invariant violations and preserves existing DB and preferences`() = runBlocking {
        seedPages(1)
        db.workspaceDao().upsertFolder(FolderEntity(folderId = "orig_f", name = "Orig"))
        db.installedAppDao().upsertApps(
            listOf(installedApp("com.one"), installedApp("com.two"), installedApp("com.three"), installedApp("com.four"))
        )
        db.workspaceDao().upsertWorkspaceItems(
            listOf(
                WorkspaceItemEntity(
                    itemId = "home_1",
                    itemType = WorkspaceItemTypes.APP,
                    containerType = WorkspaceContainers.HOME,
                    pageId = "page_0",
                    packageName = "com.one",
                    activityName = "com.one.MainActivity",
                    rank = 0
                ),
                WorkspaceItemEntity(
                    itemId = "home_2",
                    itemType = WorkspaceItemTypes.APP,
                    containerType = WorkspaceContainers.HOME,
                    pageId = "page_0",
                    packageName = "com.two",
                    activityName = "com.two.MainActivity",
                    rank = 1
                ),
                WorkspaceItemEntity(
                    itemId = "folder_orig_f",
                    itemType = WorkspaceItemTypes.FOLDER,
                    containerType = WorkspaceContainers.HOME,
                    pageId = "page_0",
                    folderId = "orig_f",
                    rank = 2
                )
            )
        )
        db.workspaceDao().upsertFolderMembers(
            listOf(
                FolderMemberEntity("orig_f", "com.three", "com.three.MainActivity", rank = 0),
                FolderMemberEntity("orig_f", "com.four", "com.four.MainActivity", rank = 1)
            )
        )
        preferences.setGridColumns(4)
        preferences.setThemeMode("DARK")

        val base = JSONObject(helper.generateBackupJson())
        // Corrupt backup: duplicate rank 0 on page_0 and modify prefs to check no partial mutation
        val corruptJson = JSONObject(base.toString()).apply {
            getJSONObject("preferences").put("gridColumns", 6)
            getJSONObject("preferences").put("themeMode", "LIGHT")
            val items = getJSONArray("workspaceItems")
            for (i in 0 until items.length()) {
                val obj = items.getJSONObject(i)
                if (obj.optString("itemId") == "home_2") {
                    obj.put("rank", 0)
                }
            }
            put("folders", JSONArray())
            put("folderMembers", JSONArray())
        }.toString()

        val result = helper.restoreBackup(corruptJson)
        assertTrue(result.isFailure)

        val currentItems = db.workspaceDao().getWorkspaceItemsList().associateBy { it.packageName }
        assertEquals(0, currentItems["com.one"]!!.rank)
        assertEquals(1, currentItems["com.two"]!!.rank)
        assertEquals(1, db.workspaceDao().getFoldersList().size)
        assertEquals(4, preferences.gridColumnsFlow.first())
        assertEquals("DARK", preferences.themeModeFlow.first())
    }

    @Test
    fun `restore strips missing uninstalled apps and reconciles unbacked installed apps without collision`() = runBlocking {
        seedPages(2)
        db.workspaceDao().upsertFolder(FolderEntity(folderId = "f1", name = "Work"))
        db.installedAppDao().upsertApps(
            listOf(
                installedApp("com.one"),
                installedApp("com.two"),
                installedApp("com.three"),
                installedApp("com.missing")
            )
        )
        db.workspaceDao().upsertWorkspaceItems(
            listOf(
                WorkspaceItemEntity(
                    itemId = "home_one",
                    itemType = WorkspaceItemTypes.APP,
                    containerType = WorkspaceContainers.HOME,
                    pageId = "page_0",
                    packageName = "com.one",
                    activityName = "com.one.MainActivity",
                    rank = 0
                ),
                WorkspaceItemEntity(
                    itemId = "home_missing",
                    itemType = WorkspaceItemTypes.APP,
                    containerType = WorkspaceContainers.HOME,
                    pageId = "page_0",
                    packageName = "com.missing",
                    activityName = "com.missing.MainActivity",
                    rank = 3
                ),
                WorkspaceItemEntity(
                    itemId = "folder_f1",
                    itemType = WorkspaceItemTypes.FOLDER,
                    containerType = WorkspaceContainers.HOME,
                    pageId = "page_0",
                    folderId = "f1",
                    rank = 1
                )
            )
        )
        db.workspaceDao().upsertFolderMembers(
            listOf(
                FolderMemberEntity("f1", "com.two", "com.two.MainActivity", rank = 0),
                FolderMemberEntity("f1", "com.three", "com.three.MainActivity", rank = 1)
            )
        )
        preferences.setPageCount(2)
        preferences.setGridColumns(4)

        val backupJson = helper.generateBackupJson()

        // Mark com.missing as uninstalled and add a newly installed app colliding on rank 0
        db.installedAppDao().upsertApp(
            InstalledAppEntity("com.missing", "com.missing.MainActivity", "Missing", isInstalled = false)
        )
        db.installedAppDao().upsertApp(installedApp("com.newapp"))
        db.workspaceDao().upsertWorkspaceItem(
            WorkspaceItemEntity(
                itemId = "home_newapp",
                itemType = WorkspaceItemTypes.APP,
                containerType = WorkspaceContainers.HOME,
                pageId = "page_0",
                packageName = "com.newapp",
                activityName = "com.newapp.MainActivity",
                rank = 0
            )
        )
        db.workspaceDao().upsertFolder(FolderEntity(folderId = "f_stale", name = "Stale"))

        val restoreResult = helper.restoreBackup(backupJson)
        assertTrue("Restore failed: ${restoreResult.exceptionOrNull()}", restoreResult.isSuccess)

        val folders = db.workspaceDao().getFoldersList()
        assertEquals(listOf("f1"), folders.map { it.folderId })

        val items = db.workspaceDao().getWorkspaceItemsList()
        assertNull(items.find { it.packageName == "com.missing" })
        assertEquals(0, items.first { it.packageName == "com.one" }.rank)
        val newAppItem = items.first { it.packageName == "com.newapp" }
        assertTrue("Expected non-colliding rank >= 2, got ${newAppItem.rank}", newAppItem.rank >= 2)
    }
}
