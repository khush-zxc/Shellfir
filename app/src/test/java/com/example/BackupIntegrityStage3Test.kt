package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.backup.BackupRestoreHelper
import com.example.data.backup.BackupRestoreRollbackException
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
import com.example.data.preferences.BackupPreferencesConfig
import com.example.data.preferences.ShellfriPreferences
import com.example.domain.concurrency.WorkspaceMutationCoordinator
import com.example.domain.model.HomeMode
import com.example.domain.model.ShuffleStrategy
import com.example.domain.repository.AppRepository
import com.example.domain.shuffle.ShuffleEngine
import com.example.domain.shuffle.UndoShuffleStatus
import com.example.domain.validation.WorkspaceValidator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupIntegrityStage3Test {

    private lateinit var context: Context
    private lateinit var db: ShellfriDatabase
    private lateinit var preferences: ShellfriPreferences
    private lateinit var helper: BackupRestoreHelper
    private lateinit var repository: AppRepository
    private val validator = WorkspaceValidator()
    private val registeredComponentsByPkg = mutableMapOf<String, MutableSet<ComponentName>>()

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ShellfriDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferences = ShellfriPreferences(context)
        preferences.setHomeMode(HomeMode.NORMAL)
        preferences.setPageCount(2)
        preferences.setDefaultPageIndex(0)
        preferences.setDockCount(5)
        preferences.setGridColumns(4)
        preferences.setThemeMode("DARK")
        preferences.setDrawerShuffleEnabled(false)
        preferences.setCrossPageShuffle(false)
        preferences.setShuffleStrategy(ShuffleStrategy.POSITION_ROTATION)
        preferences.setHideDistractingInFocus(false)
        preferences.setFocusSearchEnabled(true)

        helper = BackupRestoreHelper(
            installedAppDao = db.installedAppDao(),
            workspaceDao = db.workspaceDao(),
            preferences = preferences,
            database = db,
            shuffleHistoryDao = db.shuffleHistoryDao(),
            isWidgetIdValid = { false }
        )

        repository = AppRepository(
            context = context,
            installedAppDao = db.installedAppDao(),
            workspaceDao = db.workspaceDao(),
            shuffleHistoryDao = db.shuffleHistoryDao(),
            preferences = preferences,
            shuffleEngine = ShuffleEngine(randomProvider = { Random(7) }),
            database = db,
            mutationCoordinator = WorkspaceMutationCoordinator.forDatabase(db)
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun appEntity(
        pkg: String,
        act: String = "$pkg.MainActivity",
        label: String = pkg.substringAfterLast('.')
    ) = InstalledAppEntity(
        packageName = pkg,
        activityName = act,
        label = label,
        isInstalled = true
    )

    private fun registerLauncherActivity(pkg: String, activityName: String, label: String) {
        val shadowPm = shadowOf(context.packageManager)
        val component = ComponentName(pkg, activityName)
        registeredComponentsByPkg.getOrPut(pkg) { mutableSetOf() }.add(component)
        shadowPm.addActivityIfNotPresent(component)
        runCatching {
            val info = context.packageManager.getActivityInfo(component, 0)
            info.nonLocalizedLabel = label
            info.applicationInfo?.nonLocalizedLabel = label
            shadowPm.addOrUpdateActivity(info)
        }
        shadowPm.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        )
        context.packageManager.setComponentEnabledSetting(
            component,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    private suspend fun seedPages(count: Int, defaultIndex: Int = 0) {
        val pages = (0 until count).map { idx ->
            WorkspacePageEntity(
                pageId = "page_$idx",
                pageIndex = idx,
                isDefault = idx == defaultIndex,
                createdAt = 1000L + idx
            )
        }
        db.workspaceDao().upsertPages(pages)
        preferences.setPageCount(count)
        preferences.setDefaultPageIndex(defaultIndex)
    }

    private suspend fun assertNormalizedWorkspaceValid() {
        val result = validator.validateNormalizedWorkspace(
            installedApps = db.installedAppDao().getAllAppsList(),
            pages = db.workspaceDao().getPagesList(),
            workspaceItems = db.workspaceDao().getWorkspaceItemsList(),
            folders = db.workspaceDao().getFoldersList(),
            folderMembers = db.workspaceDao().getFolderMembersList(),
            drawerLayouts = db.workspaceDao().getDrawerLayoutsList(),
            focusApps = db.workspaceDao().getFocusAppsList(),
            dockCount = preferences.dockCountFlow.first().coerceIn(3, 7),
            drawerShuffleEnabled = preferences.drawerShuffleEnabledFlow.first()
        )
        assertTrue("Workspace invariants violated: ${result.errors}", result.isValid)
    }

    @Test
    fun `1_v1 backup import preserves placements folders focus and drawer state`() = runBlocking {
        seedPages(1)
        db.installedAppDao().upsertApps(
            listOf(
                appEntity("com.v1.alpha", "com.v1.alpha.Main", "Alpha"),
                appEntity("com.v1.beta", "com.v1.beta.Main", "Beta"),
                appEntity("com.v1.dock", "com.v1.dock.Main", "Dock")
            )
        )

        val v1Json = JSONObject().apply {
            put("schemaVersion", 1)
            put("timestamp", 1700001000L)
            put(
                "preferences",
                JSONObject().apply {
                    put("themeMode", "LIGHT")
                    put("gridColumns", 5)
                    put("pageCount", 2)
                    put("defaultPageIndex", 1)
                    put("dockCount", 4)
                    put("drawerShuffleEnabled", true)
                }
            )
            put(
                "folders",
                JSONArray().put(
                    JSONObject().apply {
                        put("id", "v1_folder")
                        put("name", "Legacy Folder")
                        put("pageIndex", 1)
                        put("slotIndex", 2)
                        put("createdAt", 2000L)
                    }
                )
            )
            put(
                "apps",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("packageName", "com.v1.alpha")
                            put("activityName", "") // Legacy blank activity reconciled deterministically
                            put("label", "Alpha")
                            put("isShuffled", true)
                            put("isFixed", false)
                            put("slotIndex", 3)
                            put("pageIndex", 0)
                            put("folderId", JSONObject.NULL)
                            put("isPinnedToDock", false)
                            put("isOnHomeScreen", true)
                            put("isVisibleInFocus", true)
                            put("isEssentialInFocus", true)
                            put("isHidden", false)
                            put("drawerSlotIndex", 4)
                            put("isDrawerShuffled", true)
                        }
                    )
                    put(
                        JSONObject().apply {
                            put("packageName", "com.v1.beta")
                            put("activityName", "com.v1.beta.Main")
                            put("label", "Beta")
                            put("isShuffled", false)
                            put("isFixed", true)
                            put("slotIndex", 0)
                            put("pageIndex", 1)
                            put("folderId", "v1_folder")
                            put("isPinnedToDock", false)
                            put("isOnHomeScreen", false)
                            put("isVisibleInFocus", false)
                            put("isEssentialInFocus", false)
                            put("isHidden", true)
                            put("drawerSlotIndex", 1)
                            put("isDrawerShuffled", false)
                        }
                    )
                    put(
                        JSONObject().apply {
                            put("packageName", "com.v1.dock")
                            put("activityName", "com.v1.dock.Main")
                            put("label", "Dock")
                            put("isShuffled", false)
                            put("isFixed", false)
                            put("slotIndex", 0)
                            put("pageIndex", 0)
                            put("folderId", JSONObject.NULL)
                            put("isPinnedToDock", true)
                            put("isOnHomeScreen", true)
                            put("isVisibleInFocus", true)
                            put("isEssentialInFocus", false)
                            put("isHidden", false)
                            put("drawerSlotIndex", -1)
                            put("isDrawerShuffled", false)
                        }
                    )
                }
            )
        }.toString()

        val report = helper.restoreBackupDetailed(v1Json).getOrThrow()
        assertEquals(1, report.sourceVersion)
        assertEquals(2, report.restoredPageCount)
        assertEquals(1, report.restoredFolderCount)
        assertEquals(4, preferences.dockCountFlow.first())
        assertEquals(1, preferences.defaultPageIndexFlow.first())

        val items = db.workspaceDao().getWorkspaceItemsList()
        val alphaHome = items.first { it.packageName == "com.v1.alpha" }
        assertEquals("com.v1.alpha.Main", alphaHome.activityName)
        assertEquals(3, alphaHome.rank)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `2_v2 export and restore roundtrip includes summary validation`() = runBlocking {
        seedPages(count = 2, defaultIndex = 1)
        db.installedAppDao().upsertApps(listOf(appEntity("com.one"), appEntity("com.two")))
        db.workspaceDao().upsertWorkspaceItems(
            listOf(
                WorkspaceItemEntity("h1", WorkspaceItemTypes.APP, WorkspaceContainers.HOME, "page_0", "com.one", "com.one.MainActivity", rank = 2, isFixed = true),
                WorkspaceItemEntity("h2", WorkspaceItemTypes.APP, WorkspaceContainers.HOME, "page_1", "com.two", "com.two.MainActivity", rank = 5, isShuffled = true)
            )
        )
        preferences.setGridColumns(6)
        preferences.setDockCount(6)

        val json = helper.generateBackupJson()
        val summary = helper.parseBackupSummary(json).getOrThrow()
        assertEquals(2, summary.version)
        assertEquals(6, summary.gridColumns)
        assertEquals(6, summary.dockCount)
        assertEquals(2, summary.pageCount)

        // Mutate workspace and restore
        db.workspaceDao().deleteAllWorkspaceItems()
        preferences.setGridColumns(3)
        preferences.setDockCount(3)

        val report = helper.restoreBackupDetailed(json).getOrThrow()
        assertEquals(2, report.sourceVersion)
        assertEquals(2, report.restoredPageCount)
        assertEquals(6, preferences.gridColumnsFlow.first())
        assertEquals(6, preferences.dockCountFlow.first())
        val items = db.workspaceDao().getWorkspaceItemsList().associateBy { it.packageName }
        assertEquals(2, items["com.one"]!!.rank)
        assertTrue(items["com.one"]!!.isFixed)
        assertEquals("page_1", items["com.two"]!!.pageId)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `3_multiple activities from one package preserved independently across export and restore`() = runBlocking {
        seedPages(2)
        val multiActs = listOf(
            appEntity("com.suite", "com.suite.NotesActivity", "Suite Notes"),
            appEntity("com.suite", "com.suite.TasksActivity", "Suite Tasks"),
            appEntity("com.suite", "com.suite.CalendarActivity", "Suite Calendar")
        )
        db.installedAppDao().upsertApps(multiActs)
        db.workspaceDao().upsertWorkspaceItems(
            listOf(
                WorkspaceItemEntity("notes_h", WorkspaceItemTypes.APP, WorkspaceContainers.HOME, "page_0", "com.suite", "com.suite.NotesActivity", rank = 0, isFixed = true),
                WorkspaceItemEntity("tasks_h", WorkspaceItemTypes.APP, WorkspaceContainers.HOME, "page_1", "com.suite", "com.suite.TasksActivity", rank = 4, isShuffled = true),
                WorkspaceItemEntity("cal_d", WorkspaceItemTypes.APP, WorkspaceContainers.DOCK, null, "com.suite", "com.suite.CalendarActivity", rank = 0)
            )
        )

        val backup = helper.generateBackupJson()
        db.workspaceDao().deleteAllWorkspaceItems()
        helper.restoreBackupDetailed(backup).getOrThrow()

        val items = db.workspaceDao().getWorkspaceItemsList()
        val notes = items.single { it.activityName == "com.suite.NotesActivity" }
        val tasks = items.single { it.activityName == "com.suite.TasksActivity" }
        val cal = items.single { it.activityName == "com.suite.CalendarActivity" }

        assertEquals(WorkspaceContainers.HOME, notes.containerType)
        assertEquals("page_0", notes.pageId)
        assertEquals(0, notes.rank)
        assertEquals(WorkspaceContainers.HOME, tasks.containerType)
        assertEquals("page_1", tasks.pageId)
        assertEquals(4, tasks.rank)
        assertEquals(WorkspaceContainers.DOCK, cal.containerType)
        assertEquals(0, cal.rank)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `4_dual HOME and DOCK placement for same component preserved across export and restore`() = runBlocking {
        seedPages(1)
        db.installedAppDao().upsertApp(appEntity("com.dual", "com.dual.Main"))
        db.workspaceDao().upsertWorkspaceItems(
            listOf(
                WorkspaceItemEntity("dual_home", WorkspaceItemTypes.APP, WorkspaceContainers.HOME, "page_0", "com.dual", "com.dual.Main", rank = 7, isFixed = true),
                WorkspaceItemEntity("dual_dock", WorkspaceItemTypes.APP, WorkspaceContainers.DOCK, null, "com.dual", "com.dual.Main", rank = 2, isFixed = false)
            )
        )

        val backup = helper.generateBackupJson()
        db.workspaceDao().deleteAllWorkspaceItems()
        helper.restoreBackupDetailed(backup).getOrThrow()

        val dualItems = db.workspaceDao().getWorkspaceItemsList().filter { it.packageName == "com.dual" }
        assertEquals(2, dualItems.size)
        val homeItem = dualItems.single { it.containerType == WorkspaceContainers.HOME }
        val dockItem = dualItems.single { it.containerType == WorkspaceContainers.DOCK }
        assertEquals(7, homeItem.rank)
        assertEquals("page_0", homeItem.pageId)
        assertEquals(2, dockItem.rank)
        assertNull(dockItem.pageId)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `5_folder membership and member ordering rank preserved`() = runBlocking {
        seedPages(1)
        db.workspaceDao().upsertFolder(FolderEntity("folder_work", "Work", createdAt = 999L))
        db.installedAppDao().upsertApps(
            listOf(appEntity("com.m1"), appEntity("com.m2"), appEntity("com.m3"))
        )
        db.workspaceDao().upsertWorkspaceItem(
            WorkspaceItemEntity("fi_work", WorkspaceItemTypes.FOLDER, WorkspaceContainers.HOME, "page_0", folderId = "folder_work", rank = 3)
        )
        db.workspaceDao().upsertFolderMembers(
            listOf(
                FolderMemberEntity("folder_work", "com.m3", "com.m3.MainActivity", rank = 0, isFixed = true),
                FolderMemberEntity("folder_work", "com.m1", "com.m1.MainActivity", rank = 1, isShuffled = true),
                FolderMemberEntity("folder_work", "com.m2", "com.m2.MainActivity", rank = 2, isShuffled = false)
            )
        )

        val backup = helper.generateBackupJson()
        db.workspaceDao().deleteAllFolderMembers()
        db.workspaceDao().deleteAllWorkspaceItems()
        db.workspaceDao().deleteAllFolders()

        helper.restoreBackupDetailed(backup).getOrThrow()

        val members = db.workspaceDao().getFolderMembersList().sortedBy { it.rank }
        assertEquals(listOf("com.m3", "com.m1", "com.m2"), members.map { it.packageName })
        assertEquals(listOf(0, 1, 2), members.map { it.rank })
        assertTrue(members[0].isFixed)
        assertTrue(members[1].isShuffled)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `6_focus configuration visible essential customOrder and preferences preserved`() = runBlocking {
        seedPages(1)
        db.installedAppDao().upsertApps(listOf(appEntity("com.f1"), appEntity("com.f2"), appEntity("com.f3")))
        db.workspaceDao().upsertFocusApps(
            listOf(
                FocusAppEntity("com.f1", "com.f1.MainActivity", isVisibleInFocus = true, isEssentialInFocus = true, customOrder = 5),
                FocusAppEntity("com.f2", "com.f2.MainActivity", isVisibleInFocus = true, isEssentialInFocus = false, customOrder = 12),
                FocusAppEntity("com.f3", "com.f3.MainActivity", isVisibleInFocus = false, isEssentialInFocus = false, customOrder = 0)
            )
        )
        preferences.setHomeMode(HomeMode.FOCUS)
        preferences.setHideDistractingInFocus(true)
        preferences.setFocusSearchEnabled(false)

        val backup = helper.generateBackupJson()
        db.workspaceDao().deleteAllFocusApps()
        preferences.setHomeMode(HomeMode.NORMAL)
        preferences.setHideDistractingInFocus(false)
        preferences.setFocusSearchEnabled(true)

        helper.restoreBackupDetailed(backup).getOrThrow()

        val focusByPkg = db.workspaceDao().getFocusAppsList().associateBy { it.packageName }
        assertTrue(focusByPkg["com.f1"]!!.isVisibleInFocus)
        assertTrue(focusByPkg["com.f1"]!!.isEssentialInFocus)
        assertEquals(5, focusByPkg["com.f1"]!!.customOrder)
        assertTrue(focusByPkg["com.f2"]!!.isVisibleInFocus)
        assertFalse(focusByPkg["com.f2"]!!.isEssentialInFocus)
        assertEquals(12, focusByPkg["com.f2"]!!.customOrder)
        assertEquals(HomeMode.FOCUS, preferences.homeModeFlow.first())
        assertTrue(preferences.hideDistractingInFocusFlow.first())
        assertFalse(preferences.focusSearchEnabledFlow.first())
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `7_drawer configuration drawerRank isDrawerShuffled isHidden isFixed preserved`() = runBlocking {
        seedPages(1)
        db.installedAppDao().upsertApps(listOf(appEntity("com.d1"), appEntity("com.d2"), appEntity("com.d3")))
        db.workspaceDao().upsertDrawerLayouts(
            listOf(
                DrawerLayoutEntity("com.d1", "com.d1.MainActivity", drawerRank = 2, isDrawerShuffled = true, isHidden = false, isFixed = false),
                DrawerLayoutEntity("com.d2", "com.d2.MainActivity", drawerRank = 0, isDrawerShuffled = false, isHidden = true, isFixed = true),
                DrawerLayoutEntity("com.d3", "com.d3.MainActivity", drawerRank = 5, isDrawerShuffled = true, isHidden = false, isFixed = false)
            )
        )
        preferences.setDrawerShuffleEnabled(true)

        val backup = helper.generateBackupJson()
        db.workspaceDao().deleteAllDrawerLayouts()
        preferences.setDrawerShuffleEnabled(false)

        helper.restoreBackupDetailed(backup).getOrThrow()

        assertTrue(preferences.drawerShuffleEnabledFlow.first())
        val drawers = db.workspaceDao().getDrawerLayoutsList().associateBy { it.packageName }
        assertEquals(2, drawers["com.d1"]!!.drawerRank)
        assertTrue(drawers["com.d1"]!!.isDrawerShuffled)
        assertTrue(drawers["com.d2"]!!.isHidden)
        assertTrue(drawers["com.d2"]!!.isFixed)
        assertEquals(5, drawers["com.d3"]!!.drawerRank)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `8_cross-device invalid widgets quarantined while valid widgets preserved`() = runBlocking {
        seedPages(1)
        db.installedAppDao().upsertApp(appEntity("com.app"))
        db.workspaceDao().upsertWorkspaceItems(
            listOf(
                WorkspaceItemEntity("h_app", WorkspaceItemTypes.APP, WorkspaceContainers.HOME, "page_0", "com.app", "com.app.MainActivity", rank = 0),
                WorkspaceItemEntity("w_valid", WorkspaceItemTypes.WIDGET, WorkspaceContainers.HOME, "page_0", appWidgetId = 101, columnSpan = 2, rowSpan = 1, rank = 2),
                WorkspaceItemEntity("w_stale_a", WorkspaceItemTypes.WIDGET, WorkspaceContainers.HOME, "page_0", appWidgetId = 555, columnSpan = 2, rowSpan = 2, rank = 4),
                WorkspaceItemEntity("w_stale_b", WorkspaceItemTypes.WIDGET, WorkspaceContainers.HOME, "page_0", appWidgetId = 999, columnSpan = 1, rowSpan = 1, rank = 9)
            )
        )

        // Export with all widgets present on device A
        val deviceAHelper = BackupRestoreHelper(
            installedAppDao = db.installedAppDao(),
            workspaceDao = db.workspaceDao(),
            preferences = preferences,
            database = db,
            shuffleHistoryDao = db.shuffleHistoryDao(),
            isWidgetIdValid = { true }
        )
        val backupJson = deviceAHelper.generateBackupJson()

        // Restore on device B where only appWidgetId=101 exists
        db.workspaceDao().deleteAllWorkspaceItems()
        val deviceBHelper = BackupRestoreHelper(
            installedAppDao = db.installedAppDao(),
            workspaceDao = db.workspaceDao(),
            preferences = preferences,
            database = db,
            shuffleHistoryDao = db.shuffleHistoryDao(),
            isWidgetIdValid = { id -> id == 101 }
        )

        val report = deviceBHelper.restoreBackupDetailed(backupJson).getOrThrow()
        assertEquals(1, report.restoredWidgetCount)
        assertEquals(listOf(555, 999), report.skippedInvalidWidgets)

        val widgetIdsInDb = db.workspaceDao().getWorkspaceItemsList()
            .filter { it.itemType == WorkspaceItemTypes.WIDGET }
            .map { it.appWidgetId }
        assertEquals(listOf(101), widgetIdsInDb)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `9_rejects missing or nonexistent pages without mutating existing workspace`() = runBlocking {
        seedPages(1)
        db.installedAppDao().upsertApp(appEntity("com.keep"))
        db.workspaceDao().upsertWorkspaceItem(
            WorkspaceItemEntity("keep", WorkspaceItemTypes.APP, WorkspaceContainers.HOME, "page_0", "com.keep", "com.keep.MainActivity", rank = 2)
        )
        val base = JSONObject(helper.generateBackupJson())

        val emptyPages = JSONObject(base.toString()).put("pages", JSONArray()).toString()
        assertTrue(helper.restoreBackupDetailed(emptyPages).isFailure)

        val missingPageRef = JSONObject(base.toString()).apply {
            getJSONArray("workspaceItems").getJSONObject(0).put("pageId", "page_nonexistent")
        }.toString()
        assertTrue(helper.restoreBackupDetailed(missingPageRef).isFailure)

        assertEquals(listOf("keep"), db.workspaceDao().getWorkspaceItemsList().map { it.itemId })
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `10_rejects missing folder references and orphan unplaced folders without mutating workspace`() = runBlocking {
        seedPages(1)
        db.workspaceDao().upsertFolder(FolderEntity("existing_f", "Existing"))
        db.installedAppDao().upsertApps(listOf(appEntity("com.a"), appEntity("com.b")))
        db.workspaceDao().upsertWorkspaceItems(
            listOf(
                WorkspaceItemEntity("f_item", WorkspaceItemTypes.FOLDER, WorkspaceContainers.HOME, "page_0", folderId = "existing_f", rank = 0),
                WorkspaceItemEntity("app_item", WorkspaceItemTypes.APP, WorkspaceContainers.HOME, "page_0", "com.a", "com.a.MainActivity", rank = 1)
            )
        )
        db.workspaceDao().upsertFolderMembers(
            listOf(FolderMemberEntity("existing_f", "com.b", "com.b.MainActivity", rank = 0))
        )
        val base = JSONObject(helper.generateBackupJson())

        // Case A: folderMember points to missing folder
        val missingMemberFolder = JSONObject(base.toString()).apply {
            getJSONArray("folderMembers").getJSONObject(0).put("folderId", "ghost_folder")
        }.toString()
        assertTrue(helper.restoreBackupDetailed(missingMemberFolder).isFailure)

        // Case B: workspaceItem(FOLDER) references a missing folderId
        val missingItemFolder = JSONObject(base.toString()).apply {
            put("folders", JSONArray())
            put("folderMembers", JSONArray())
        }.toString()
        assertTrue(helper.restoreBackupDetailed(missingItemFolder).isFailure)

        assertEquals(listOf("existing_f"), db.workspaceDao().getFoldersList().map { it.folderId })
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `11_rejects duplicate HOME DOCK folder-member and drawer ranks without partial writes`() = runBlocking {
        seedPages(1)
        db.workspaceDao().upsertFolder(FolderEntity("f1", "Folder"))
        db.installedAppDao().upsertApps(
            listOf(appEntity("com.a"), appEntity("com.b"), appEntity("com.c"), appEntity("com.d"), appEntity("com.e"))
        )
        db.workspaceDao().upsertWorkspaceItems(
            listOf(
                WorkspaceItemEntity("fi", WorkspaceItemTypes.FOLDER, WorkspaceContainers.HOME, "page_0", folderId = "f1", rank = 0),
                WorkspaceItemEntity("ha", WorkspaceItemTypes.APP, WorkspaceContainers.HOME, "page_0", "com.a", "com.a.MainActivity", rank = 1),
                WorkspaceItemEntity("da", WorkspaceItemTypes.APP, WorkspaceContainers.DOCK, null, "com.b", "com.b.MainActivity", rank = 0),
                WorkspaceItemEntity("db_item", WorkspaceItemTypes.APP, WorkspaceContainers.DOCK, null, "com.c", "com.c.MainActivity", rank = 1)
            )
        )
        db.workspaceDao().upsertFolderMembers(
            listOf(
                FolderMemberEntity("f1", "com.d", "com.d.MainActivity", rank = 0),
                FolderMemberEntity("f1", "com.e", "com.e.MainActivity", rank = 1)
            )
        )
        db.workspaceDao().upsertDrawerLayouts(
            listOf(
                DrawerLayoutEntity("com.a", "com.a.MainActivity", drawerRank = 0, isDrawerShuffled = true),
                DrawerLayoutEntity("com.b", "com.b.MainActivity", drawerRank = 1, isDrawerShuffled = true)
            )
        )
        preferences.setDrawerShuffleEnabled(true)

        val base = JSONObject(helper.generateBackupJson())

        // Duplicate DOCK rank
        val dupDock = JSONObject(base.toString()).apply {
            val items = getJSONArray("workspaceItems")
            for (i in 0 until items.length()) {
                val obj = items.getJSONObject(i)
                if (obj.getString("itemId") == "db_item") obj.put("rank", 0)
            }
        }.toString()
        assertTrue(helper.restoreBackupDetailed(dupDock).isFailure)

        // Duplicate folder member rank
        val dupMember = JSONObject(base.toString()).apply {
            getJSONArray("folderMembers").getJSONObject(1).put("rank", 0)
        }.toString()
        assertTrue(helper.restoreBackupDetailed(dupMember).isFailure)

        // Duplicate drawer rank while drawerShuffleEnabled = true
        val dupDrawer = JSONObject(base.toString()).apply {
            getJSONArray("drawerLayout").getJSONObject(1).put("drawerRank", 0)
        }.toString()
        assertTrue(helper.restoreBackupDetailed(dupDrawer).isFailure)

        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `12_rejects out-of-bounds multiple or conflicting defaultPageIndex`() = runBlocking {
        seedPages(count = 2, defaultIndex = 1)
        val base = JSONObject(helper.generateBackupJson())

        val outOfBounds = JSONObject(base.toString()).apply {
            getJSONObject("preferences").put("defaultPageIndex", 5)
        }.toString()
        assertTrue(helper.restoreBackupDetailed(outOfBounds).isFailure)

        val conflictingDefaultFlag = JSONObject(base.toString()).apply {
            // page_0 isDefault=true while preferences.defaultPageIndex=1
            getJSONArray("pages").getJSONObject(0).put("isDefault", true)
            getJSONArray("pages").getJSONObject(1).put("isDefault", false)
        }.toString()
        assertTrue(helper.restoreBackupDetailed(conflictingDefaultFlag).isFailure)

        val multipleDefaultFlags = JSONObject(base.toString()).apply {
            getJSONArray("pages").getJSONObject(0).put("isDefault", true)
            getJSONArray("pages").getJSONObject(1).put("isDefault", true)
        }.toString()
        assertTrue(helper.restoreBackupDetailed(multipleDefaultFlags).isFailure)

        assertEquals(1, preferences.defaultPageIndexFlow.first())
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `13_datastore write failure rolls back Room tables pre-restore shuffle snapshots and prefs`() = runBlocking {
        seedPages(1)
        db.installedAppDao().upsertApps(listOf(appEntity("com.orig"), appEntity("com.alt")))
        db.workspaceDao().upsertWorkspaceItem(
            WorkspaceItemEntity("orig_item", WorkspaceItemTypes.APP, WorkspaceContainers.HOME, "page_0", "com.orig", "com.orig.MainActivity", rank = 2)
        )
        preferences.setGridColumns(4)

        val backupJson = helper.generateBackupJson()
        // Mutate workspace after taking backup, plus seed a shuffle snapshot
        db.workspaceDao().deleteAllWorkspaceItems()
        db.workspaceDao().upsertWorkspaceItem(
            WorkspaceItemEntity("alt_item", WorkspaceItemTypes.APP, WorkspaceContainers.HOME, "page_0", "com.alt", "com.alt.MainActivity", rank = 5)
        )
        db.shuffleHistoryDao().insertSnapshot(
            com.example.data.database.ShuffleLayoutSnapshotEntity(
                timestamp = 999L,
                historyId = 1L,
                snapshotJson = "{\"snapshotVersion\":2,\"deltas\":[]}"
            )
        )
        preferences.setGridColumns(5)

        val failingPrefs = object : ShellfriPreferences(context) {
            override suspend fun applyBackupConfig(
                config: BackupPreferencesConfig,
                previousLayoutBackupJson: String?
            ) {
                if (previousLayoutBackupJson != null) {
                    throw IOException("Simulated DataStore write failure")
                }
                super.applyBackupConfig(config, previousLayoutBackupJson)
            }
        }
        val failingHelper = BackupRestoreHelper(
            installedAppDao = db.installedAppDao(),
            workspaceDao = db.workspaceDao(),
            preferences = failingPrefs,
            database = db,
            shuffleHistoryDao = db.shuffleHistoryDao()
        )

        val result = failingHelper.restoreBackupDetailed(backupJson)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)

        // Room workspace, snapshots, and prefs are rolled back cleanly to pre-restore state
        val rolledBackItems = db.workspaceDao().getWorkspaceItemsList()
        assertEquals(listOf("alt_item"), rolledBackItems.map { it.itemId })
        assertEquals(5, preferences.gridColumnsFlow.first())
        val snapshots = db.shuffleHistoryDao().getSnapshotsList()
        assertEquals(1, snapshots.size)
        assertEquals(999L, snapshots.single().timestamp)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `14_room transaction failure during restore aborts cleanly without mutating DB snapshots or prefs`() = runBlocking {
        seedPages(1)
        db.installedAppDao().upsertApps(listOf(appEntity("com.orig"), appEntity("com.alt")))
        db.workspaceDao().upsertWorkspaceItem(
            WorkspaceItemEntity("orig_item", WorkspaceItemTypes.APP, WorkspaceContainers.HOME, "page_0", "com.orig", "com.orig.MainActivity", rank = 1)
        )
        preferences.setGridColumns(4)
        val backupJson = helper.generateBackupJson()

        // Mutate workspace and seed snapshot
        db.workspaceDao().deleteAllWorkspaceItems()
        db.workspaceDao().upsertWorkspaceItem(
            WorkspaceItemEntity("alt_item", WorkspaceItemTypes.APP, WorkspaceContainers.HOME, "page_0", "com.alt", "com.alt.MainActivity", rank = 3)
        )
        db.shuffleHistoryDao().insertSnapshot(
            com.example.data.database.ShuffleLayoutSnapshotEntity(
                timestamp = 888L,
                historyId = 1L,
                snapshotJson = "{\"snapshotVersion\":2,\"deltas\":[]}"
            )
        )
        preferences.setGridColumns(6)

        // Inject SQLite trigger causing Room transaction insert to fail
        val sqliteDb = db.openHelper.writableDatabase
        sqliteDb.execSQL(
            """
            CREATE TRIGGER fail_restore_insert
            BEFORE INSERT ON workspace_items
            WHEN NEW.itemId = 'orig_item'
            BEGIN
                SELECT RAISE(ABORT, 'Simulated Room restore failure');
            END;
            """.trimIndent()
        )

        val result = try {
            helper.restoreBackupDetailed(backupJson)
        } finally {
            sqliteDb.execSQL("DROP TRIGGER IF EXISTS fail_restore_insert")
        }

        assertTrue(result.isFailure)
        assertEquals(listOf("alt_item"), db.workspaceDao().getWorkspaceItemsList().map { it.itemId })
        assertEquals(6, preferences.gridColumnsFlow.first())
        assertEquals(1, db.shuffleHistoryDao().getSnapshotsList().size)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `15_rollback failure throws BackupRestoreRollbackException surfacing both errors`() = runBlocking {
        seedPages(1)
        db.installedAppDao().upsertApp(appEntity("com.orig"))
        db.workspaceDao().upsertWorkspaceItem(
            WorkspaceItemEntity("orig_item", WorkspaceItemTypes.APP, WorkspaceContainers.HOME, "page_0", "com.orig", "com.orig.MainActivity", rank = 0)
        )
        preferences.setGridColumns(4)
        val backupJson = helper.generateBackupJson()

        var callCount = 0
        val doubleFailingPrefs = object : ShellfriPreferences(context) {
            override suspend fun applyBackupConfig(
                config: BackupPreferencesConfig,
                previousLayoutBackupJson: String?
            ) {
                callCount++
                if (callCount == 1) {
                    throw IOException("Primary DataStore write failure")
                } else {
                    throw IllegalStateException("Rollback DataStore recovery failure")
                }
            }
        }
        val failingHelper = BackupRestoreHelper(
            installedAppDao = db.installedAppDao(),
            workspaceDao = db.workspaceDao(),
            preferences = doubleFailingPrefs,
            database = db,
            shuffleHistoryDao = db.shuffleHistoryDao()
        )

        val result = failingHelper.restoreBackupDetailed(backupJson)
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("Expected BackupRestoreRollbackException, got $ex", ex is BackupRestoreRollbackException)
        val rollbackEx = ex as BackupRestoreRollbackException
        assertTrue(rollbackEx.primaryError is IOException)
        assertTrue(rollbackEx.rollbackError is IllegalStateException)
        assertTrue(rollbackEx.suppressed.any { it is IllegalStateException })
    }

    @Test
    fun `16_restore clears pre-restore shuffle snapshots so subsequent undo returns NO_SNAPSHOT`() = runBlocking {
        seedPages(1)
        registerLauncherActivity("com.s1", "com.s1.Main", "S1")
        registerLauncherActivity("com.s2", "com.s2.Main", "S2")
        registerLauncherActivity("com.s3", "com.s3.Main", "S3")
        repository.discoverAndSyncApps()
        repository.setAppPageAndSlot("com.s1", "com.s1.Main", 0, 0)
        repository.setAppPageAndSlot("com.s2", "com.s2.Main", 0, 1)
        repository.setAppPageAndSlot("com.s3", "com.s3.Main", 0, 2)
        repository.toggleAppShuffled("com.s1", "com.s1.Main", true)
        repository.toggleAppShuffled("com.s2", "com.s2.Main", true)
        repository.toggleAppShuffled("com.s3", "com.s3.Main", true)

        val backupBeforeShuffle = helper.generateBackupJson()

        assertTrue(repository.performShuffle("MANUAL"))
        assertNotNull(db.shuffleHistoryDao().getLatestSnapshot())

        val report = helper.restoreBackupDetailed(backupBeforeShuffle).getOrThrow()
        assertTrue(report.clearedPreRestoreShuffleSnapshots)
        assertNull(db.shuffleHistoryDao().getLatestSnapshot())

        val undoResult = repository.undoLastShuffleDetailed()
        assertEquals(UndoShuffleStatus.NO_SNAPSHOT, undoResult.status)

        val items = db.workspaceDao().getWorkspaceItemsList().associateBy { it.packageName }
        assertEquals(0, items["com.s1"]!!.rank)
        assertEquals(1, items["com.s2"]!!.rank)
        assertEquals(2, items["com.s3"]!!.rank)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `17_backup export during concurrent shuffle produces consistent valid JSON`() = runBlocking {
        seedPages(2)
        for (i in 1..6) {
            val pkg = "com.race$i"
            val act = "$pkg.Main"
            registerLauncherActivity(pkg, act, "Race $i")
        }
        repository.discoverAndSyncApps()
        for (i in 1..6) {
            val pkg = "com.race$i"
            repository.setAppPageAndSlot(pkg, "$pkg.Main", (i - 1) / 3, (i - 1) % 3)
            repository.toggleAppShuffled(pkg, "$pkg.Main", true)
        }
        preferences.setCrossPageShuffle(true)

        val barrier = CompletableDeferred<Unit>()
        val shuffleJob = async(Dispatchers.IO) {
            barrier.await()
            repeat(5) { repository.performShuffle("MANUAL") }
        }
        val exportJob = async(Dispatchers.IO) {
            barrier.await()
            (1..5).map { helper.generateBackupJson() }
        }
        barrier.complete(Unit)

        shuffleJob.await()
        val exportedJsons = exportJob.await()
        assertEquals(5, exportedJsons.size)
        for (json in exportedJsons) {
            val summary = helper.parseBackupSummary(json)
            assertTrue("Concurrent backup export failed self-validation: ${summary.exceptionOrNull()}", summary.isSuccess)
        }
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `18_backup restore during concurrent app discovery completes without corruption or lock leak`() = runBlocking {
        seedPages(1)
        for (i in 1..4) {
            val pkg = "com.disc$i"
            registerLauncherActivity(pkg, "$pkg.Main", "Disc $i")
        }
        repository.discoverAndSyncApps()
        for (i in 1..4) {
            val pkg = "com.disc$i"
            repository.setAppPageAndSlot(pkg, "$pkg.Main", 0, i - 1)
        }
        val backupJson = helper.generateBackupJson()

        // Register an additional app so discovery has active reconciliation work to do
        registerLauncherActivity("com.disc5", "com.disc5.Main", "Disc 5")

        val barrier = CompletableDeferred<Unit>()
        val restoreJobs = listOf(
            async(Dispatchers.IO) {
                barrier.await()
                helper.restoreBackupDetailed(backupJson).getOrThrow()
            },
            async(Dispatchers.IO) {
                barrier.await()
                repository.discoverAndSyncApps()
            }
        )
        barrier.complete(Unit)
        restoreJobs.awaitAll()

        // Verify coordinator lock is completely released by performing another mutation immediately
        val pageCountBeforeAdd = db.workspaceDao().getPagesList().size
        repository.addPage()
        assertEquals(pageCountBeforeAdd + 1, db.workspaceDao().getPagesList().size)
        assertNormalizedWorkspaceValid()
    }
}
