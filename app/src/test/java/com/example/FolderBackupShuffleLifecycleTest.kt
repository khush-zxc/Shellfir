package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.backup.BackupRestoreHelper
import com.example.data.database.FolderEntity
import com.example.data.database.FolderMemberEntity
import com.example.data.database.InstalledAppEntity
import com.example.data.database.ShellfriDatabase
import com.example.data.database.WorkspaceContainers
import com.example.data.database.WorkspaceItemEntity
import com.example.data.database.WorkspaceItemTypes
import com.example.data.database.WorkspacePageEntity
import com.example.data.preferences.ShellfriPreferences
import com.example.domain.concurrency.WorkspaceMutationCoordinator
import com.example.domain.model.ShuffleStrategy
import com.example.domain.repository.AppRepository
import com.example.domain.shuffle.ShuffleEngine
import com.example.domain.shuffle.UndoShuffleStatus
import com.example.domain.validation.WorkspaceValidator
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FolderBackupShuffleLifecycleTest {

    private lateinit var context: Context
    private lateinit var db: ShellfriDatabase
    private lateinit var preferences: ShellfriPreferences
    private lateinit var coordinator: WorkspaceMutationCoordinator
    private lateinit var repository: AppRepository
    private lateinit var backupHelper: BackupRestoreHelper
    private val validator = WorkspaceValidator()

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ShellfriDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferences = ShellfriPreferences(context)
        preferences.setPageCount(2)
        preferences.setDockCount(5)
        preferences.setCrossPageShuffle(false)
        preferences.setDrawerShuffleEnabled(false)
        preferences.setShuffleStrategy(ShuffleStrategy.POSITION_ROTATION)
        coordinator = WorkspaceMutationCoordinator.forDatabase(db)
        repository = AppRepository(
            context = context,
            installedAppDao = db.installedAppDao(),
            workspaceDao = db.workspaceDao(),
            shuffleHistoryDao = db.shuffleHistoryDao(),
            preferences = preferences,
            shuffleEngine = ShuffleEngine(randomProvider = { Random(11) }),
            database = db,
            mutationCoordinator = coordinator
        )
        backupHelper = BackupRestoreHelper(
            installedAppDao = db.installedAppDao(),
            workspaceDao = db.workspaceDao(),
            preferences = preferences,
            database = db,
            mutationCoordinator = coordinator,
            shuffleHistoryDao = db.shuffleHistoryDao(),
            isWidgetIdValid = { it == 77 }
        )
    }

    @After
    fun tearDown() = runBlocking {
        preferences.setCrossPageShuffle(true)
        preferences.setShuffleStrategy(ShuffleStrategy.FULL_RANDOM)
        preferences.setDrawerShuffleEnabled(false)
        db.close()
    }

    private fun registerLauncherActivity(pkg: String, activityName: String, label: String) {
        val shadowPm = shadowOf(context.packageManager)
        val component = ComponentName(pkg, activityName)
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

    private fun uninstallComponent(pkg: String, activityName: String) {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(pkg, activityName),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    private suspend fun seedAndSync(vararg comps: Pair<String, String>) {
        comps.forEachIndexed { idx, (pkg, act) -> registerLauncherActivity(pkg, act, "App$idx") }
        repository.discoverAndSyncApps()
        comps.forEachIndexed { idx, (pkg, act) -> repository.setAppPageAndSlot(pkg, act, 0, idx) }
    }

    private suspend fun assertStrictWorkspaceValid() {
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
        assertTrue("Workspace invalid: ${result.errors.joinToString("; ")}", result.isValid)
    }

    @Test
    fun `WorkspaceValidator rejects invalid folder structures and orphaned or conflicting memberships`() {
        val pages = listOf(WorkspacePageEntity("p0", 0, true, 1L))
        val apps = listOf(
            InstalledAppEntity("com.a", "com.a.Main", "A", isInstalled = true),
            InstalledAppEntity("com.b", "com.b.Main", "B", isInstalled = true),
            InstalledAppEntity("com.uninst", "com.uninst.Main", "U", isInstalled = false)
        )
        val validFolder = FolderEntity("f1", "Work", 1L)
        val validFolderItem = WorkspaceItemEntity("folder_f1", WorkspaceItemTypes.FOLDER, WorkspaceContainers.HOME, "p0", folderId = "f1", rank = 0)
        val validMembers = listOf(
            FolderMemberEntity("f1", "com.a", "com.a.Main", rank = 0),
            FolderMemberEntity("f1", "com.b", "com.b.Main", rank = 1)
        )

        // 1. 0-member and 1-member folders fail strict default validation (minFolderMembers = 2)
        assertFalse(
            validator.validateNormalizedWorkspace(apps, pages, listOf(validFolderItem), listOf(validFolder), emptyList(), emptyList()).isValid
        )
        assertFalse(
            validator.validateNormalizedWorkspace(apps, pages, listOf(validFolderItem), listOf(validFolder), listOf(validMembers.first()), emptyList()).isValid
        )
        // 2. Unplaced FolderEntity fails
        assertFalse(
            validator.validateNormalizedWorkspace(apps, pages, emptyList(), listOf(validFolder), validMembers, emptyList()).isValid
        )
        // 3. Non-contiguous ranks (0, 2) fails
        assertFalse(
            validator.validateNormalizedWorkspace(
                apps, pages, listOf(validFolderItem), listOf(validFolder),
                listOf(validMembers[0], validMembers[1].copy(rank = 2)), emptyList()
            ).isValid
        )
        // 4. FOLDER placed in DOCK or non-1x1 span fails
        assertFalse(
            validator.validateNormalizedWorkspace(
                apps, pages, listOf(validFolderItem.copy(containerType = WorkspaceContainers.DOCK, pageId = null)),
                listOf(validFolder), validMembers, emptyList()
            ).isValid
        )
        assertFalse(
            validator.validateNormalizedWorkspace(
                apps, pages, listOf(validFolderItem.copy(columnSpan = 2)),
                listOf(validFolder), validMembers, emptyList()
            ).isValid
        )
        // 5. Duplicate component across two folders fails
        val folder2 = FolderEntity("f2", "Two", 2L)
        val folder2Item = WorkspaceItemEntity("folder_f2", WorkspaceItemTypes.FOLDER, WorkspaceContainers.HOME, "p0", folderId = "f2", rank = 1)
        assertFalse(
            validator.validateNormalizedWorkspace(
                apps, pages, listOf(validFolderItem, folder2Item), listOf(validFolder, folder2),
                validMembers + FolderMemberEntity("f2", "com.a", "com.a.Main", rank = 0), emptyList()
            ).isValid
        )
        // 6. Member also placed directly on HOME fails, and uninstalled member fails
        assertFalse(
            validator.validateNormalizedWorkspace(
                apps, pages,
                listOf(validFolderItem, WorkspaceItemEntity("home_a", WorkspaceItemTypes.APP, WorkspaceContainers.HOME, "p0", "com.a", "com.a.Main", rank = 2)),
                listOf(validFolder), validMembers, emptyList()
            ).isValid
        )
        assertFalse(
            validator.validateNormalizedWorkspace(
                apps, pages, listOf(validFolderItem), listOf(validFolder),
                listOf(validMembers[0], FolderMemberEntity("f1", "com.uninst", "com.uninst.Main", rank = 1)), emptyList()
            ).isValid
        )
    }

    @Test
    fun `shuffle and undo preserve folders, handle post-shuffle dissolution, deletion, cross-folder move, and uninstall-reinstall`() = runBlocking {
        val s1 = "com.s.a1" to "com.s.a1.Main"
        val s2 = "com.s.a2" to "com.s.a2.Main"
        val f1 = "com.s.f1" to "com.s.f1.Main"
        val f2 = "com.s.f2" to "com.s.f2.Main"
        val f3 = "com.s.f3" to "com.s.f3.Main"
        seedAndSync(s1, s2, f1, f2, f3)
        repository.toggleAppShuffled(s1.first, s1.second, true)
        repository.toggleAppShuffled(s2.first, s2.second, true)

        // Scenario A: Folder created before shuffle survives shuffle & undo without Home slot collisions
        val fldId = repository.createFolderFromHomeApps(f2.first, f2.second, f1.first, f1.second, "ShuffleSafe").folderId!!
        assertTrue(repository.addAppToFolderResult(f3.first, f3.second, fldId).success)
        val fldSlot = repository.foldersFlow.first().single { it.id == fldId }.slotIndex
        val s1Orig = repository.allAppsFlow.first().single { it.packageName == s1.first }.slotIndex
        val s2Orig = repository.allAppsFlow.first().single { it.packageName == s2.first }.slotIndex

        assertTrue(repository.performShuffle("MANUAL"))
        assertEquals(fldSlot, repository.foldersFlow.first().single { it.id == fldId }.slotIndex)
        assertEquals(UndoShuffleStatus.APPLIED, repository.undoLastShuffleDetailed().status)
        assertEquals(s1Orig, repository.allAppsFlow.first().single { it.packageName == s1.first }.slotIndex)
        assertEquals(s2Orig, repository.allAppsFlow.first().single { it.packageName == s2.first }.slotIndex)
        assertStrictWorkspaceValid()

        // Scenario B: Remove/dissolve folder after shuffle does not collide on undo
        assertTrue(repository.performShuffle("MANUAL"))
        assertTrue(repository.removeAppFromFolderResult(f3.first, f3.second, targetPage = 0).success)
        assertTrue(repository.removeAppFromFolderResult(f2.first, f2.second, targetPage = 0).success)
        assertTrue(db.workspaceDao().getFoldersList().isEmpty())
        val undoAfterDissolve = repository.undoLastShuffleDetailed()
        assertTrue(undoAfterDissolve.status != UndoShuffleStatus.FAILED)
        assertStrictWorkspaceValid()

        // Scenario C: Delete folder after shuffle stays deleted on undo
        val fld2 = repository.createFolderFromHomeApps(f2.first, f2.second, f1.first, f1.second, "DelAfterShuffle").folderId!!
        assertTrue(repository.performShuffle("MANUAL"))
        assertTrue(repository.deleteFolderResult(fld2).success)
        assertTrue(repository.undoLastShuffleDetailed().status != UndoShuffleStatus.FAILED)
        assertTrue("Deleted folder must not be resurrected by undo", db.workspaceDao().getFoldersList().none { it.folderId == fld2 })
        assertStrictWorkspaceValid()

        // Scenario D: Cross-folder move + uninstall/reinstall after shuffle stay valid
        val dstA = repository.createFolderFromHomeApps(f2.first, f2.second, f1.first, f1.second, "DstA").folderId!!
        val extra1 = "com.s.e1" to "com.s.e1.Main"
        val extra2 = "com.s.e2" to "com.s.e2.Main"
        seedAndSync(extra1, extra2)
        val srcB = repository.createFolderFromHomeApps(extra2.first, extra2.second, extra1.first, extra1.second, "SrcB").folderId!!
        assertTrue(repository.performShuffle("MANUAL"))
        assertTrue(repository.addAppToFolderResult(extra1.first, extra1.second, dstA).success)
        uninstallComponent(f3.first, f3.second)
        repository.discoverAndSyncApps()
        registerLauncherActivity(f3.first, f3.second, "F3")
        repository.discoverAndSyncApps()
        assertTrue(repository.undoLastShuffleDetailed().status != UndoShuffleStatus.FAILED)
        assertStrictWorkspaceValid()
    }

    @Test
    fun `v2 backup and restore handle intact, shrunk, dissolved, dropped, deleted, widget-filtered, and corrupt folders`() = runBlocking {
        val a1 = "com.bk.a1" to "com.bk.a1.Main"
        val a2 = "com.bk.a2" to "com.bk.a2.Main"
        val a3 = "com.bk.a3" to "com.bk.a3.Main"
        val b1 = "com.bk.b1" to "com.bk.b1.Main"
        val b2 = "com.bk.b2" to "com.bk.b2.Main"
        val del1 = "com.bk.d1" to "com.bk.d1.Main"
        val del2 = "com.bk.d2" to "com.bk.d2.Main"
        seedAndSync(a1, a2, a3, b1, b2, del1, del2)

        repository.toggleAppPinnedToDock(b1.first, b1.second, true)
        repository.toggleAppShuffled(b1.first, b1.second, true)

        val triId = repository.createFolderFromHomeApps(a2.first, a2.second, a1.first, a1.second, "Tri").folderId!!
        assertTrue(repository.addAppToFolderResult(a3.first, a3.second, triId).success)
        assertTrue(repository.reorderFolderMembers(triId, listOf(a3, a1, a2)).success)

        val pairId = repository.createFolderFromHomeApps(b2.first, b2.second, b1.first, b1.second, "Pair").folderId!!
        val deletedId = repository.createFolderFromHomeApps(del2.first, del2.second, del1.first, del1.second, "DeletedPre").folderId!!
        assertTrue(repository.deleteFolderResult(deletedId).success)

        // Add invalid widget alongside folders so restore filters invalid widget without breaking folders
        db.workspaceDao().upsertWorkspaceItem(
            WorkspaceItemEntity("widget_bad", WorkspaceItemTypes.WIDGET, WorkspaceContainers.HOME, "page_1", appWidgetId = 999, rank = 0, columnSpan = 2, rowSpan = 1)
        )
        val backupJson = backupHelper.generateBackupJson()

        // 1. Intact restore preserves order, names, deleted folder absence, and filters widget
        db.workspaceDao().deleteAllFolderMembers()
        db.workspaceDao().deleteAllWorkspaceItems()
        db.workspaceDao().deleteAllFolders()
        val intactReport = backupHelper.restoreBackupDetailed(backupJson).getOrThrow()
        assertEquals(listOf(999), intactReport.skippedInvalidWidgets)
        assertEquals(2, intactReport.restoredFolderCount)
        assertTrue(db.workspaceDao().getFoldersList().none { it.folderId == deletedId })
        assertEquals(listOf(a3.first, a1.first, a2.first), db.workspaceDao().getFolderMembersList().filter { it.folderId == triId }.sortedBy { it.rank }.map { it.packageName })
        assertStrictWorkspaceValid()

        // 2. Partial target uninstalls: a3 missing shrinks Tri 3->2 (packed 0..1), b2 missing dissolves Pair 2->1 (b1 unpacked to HOME + Dock preserved)
        db.installedAppDao().upsertApp(InstalledAppEntity(a3.first, a3.second, "A3", isInstalled = false))
        db.installedAppDao().upsertApp(InstalledAppEntity(b2.first, b2.second, "B2", isInstalled = false))
        val partialReport = backupHelper.restoreBackupDetailed(backupJson).getOrThrow()
        assertEquals(1, partialReport.restoredFolderCount)
        assertEquals(listOf(a1.first to 0, a2.first to 1), db.workspaceDao().getFolderMembersList().filter { it.folderId == triId }.sortedBy { it.rank }.map { it.packageName to it.rank })
        val b1App = repository.allAppsFlow.first().single { it.packageName == b1.first }
        assertNull(b1App.folderId)
        assertTrue(b1App.isOnHomeScreen && b1App.isShuffled && b1App.isPinnedToDock)
        assertStrictWorkspaceValid()

        // 3. All members of Tri missing drops Tri completely cleanly
        db.installedAppDao().upsertApp(InstalledAppEntity(a1.first, a1.second, "A1", isInstalled = false))
        db.installedAppDao().upsertApp(InstalledAppEntity(a2.first, a2.second, "A2", isInstalled = false))
        val emptyDropReport = backupHelper.restoreBackupDetailed(backupJson).getOrThrow()
        assertEquals(0, emptyDropReport.restoredFolderCount)
        assertTrue(db.workspaceDao().getFoldersList().isEmpty())
        assertStrictWorkspaceValid()

        // 4. Restore installed apps, verify corrupted folder JSON payloads fail atomically without mutating DB
        db.installedAppDao().upsertApps(listOf(a1, a2, a3, b1, b2).map { InstalledAppEntity(it.first, it.second, it.first, isInstalled = true) })
        backupHelper.restoreBackupDetailed(backupJson).getOrThrow()

        val corruptDupMember = JSONObject(backupJson).apply {
            val members = getJSONArray("folderMembers")
            members.put(JSONObject(members.getJSONObject(0).toString()).put("folderId", pairId).put("rank", 9))
        }.toString()
        assertTrue(backupHelper.restoreBackupDetailed(corruptDupMember).isFailure)

        val corruptDockFolder = JSONObject(backupJson).apply {
            val items = getJSONArray("workspaceItems")
            for (i in 0 until items.length()) {
                val obj = items.getJSONObject(i)
                if (obj.optString("itemType") == WorkspaceItemTypes.FOLDER) {
                    obj.put("containerType", WorkspaceContainers.DOCK).put("pageId", JSONObject.NULL)
                    break
                }
            }
        }.toString()
        assertTrue(backupHelper.restoreBackupDetailed(corruptDockFolder).isFailure)
        assertEquals(2, db.workspaceDao().getFoldersList().size)
        assertStrictWorkspaceValid()
    }

    @Test
    fun `v1 backup with folders and partial uninstalls reconciles cleanly`() = runBlocking {
        seedAndSync("com.v1.a" to "com.v1.a.Main", "com.v1.c" to "com.v1.c.Main")
        val v1Json = JSONObject().apply {
            put("schemaVersion", 1)
            put("timestamp", 1700005000L)
            put("preferences", JSONObject().apply {
                put("themeMode", "DARK"); put("gridColumns", 4); put("pageCount", 2); put("dockCount", 5)
            })
            put("folders", JSONArray().apply {
                put(JSONObject().apply { put("id", "v1_pair"); put("name", "Pair"); put("pageIndex", 0); put("slotIndex", 3); put("createdAt", 10L) })
                put(JSONObject().apply { put("id", "v1_empty"); put("name", "Gone"); put("pageIndex", 0); put("slotIndex", 4); put("createdAt", 11L) })
            })
            put("apps", JSONArray().apply {
                put(JSONObject().apply {
                    put("packageName", "com.v1.a"); put("activityName", "com.v1.a.Main"); put("label", "A")
                    put("isShuffled", true); put("isFixed", false); put("slotIndex", 0); put("pageIndex", 0)
                    put("folderId", "v1_pair"); put("isPinnedToDock", true); put("isOnHomeScreen", false)
                    put("isVisibleInFocus", false); put("isEssentialInFocus", false); put("isHidden", false)
                    put("drawerSlotIndex", -1); put("isDrawerShuffled", false)
                })
                put(JSONObject().apply {
                    put("packageName", "com.v1.missing"); put("activityName", "com.v1.missing.Main"); put("label", "Missing")
                    put("isShuffled", false); put("isFixed", false); put("slotIndex", 0); put("pageIndex", 0)
                    put("folderId", "v1_pair"); put("isPinnedToDock", false); put("isOnHomeScreen", false)
                    put("isVisibleInFocus", false); put("isEssentialInFocus", false); put("isHidden", false)
                    put("drawerSlotIndex", -1); put("isDrawerShuffled", false)
                })
                put(JSONObject().apply {
                    put("packageName", "com.v1.gone"); put("activityName", "com.v1.gone.Main"); put("label", "GoneApp")
                    put("isShuffled", false); put("isFixed", false); put("slotIndex", 0); put("pageIndex", 0)
                    put("folderId", "v1_empty"); put("isPinnedToDock", false); put("isOnHomeScreen", false)
                    put("isVisibleInFocus", false); put("isEssentialInFocus", false); put("isHidden", false)
                    put("drawerSlotIndex", -1); put("isDrawerShuffled", false)
                })
            })
        }.toString()

        val report = backupHelper.restoreBackupDetailed(v1Json).getOrThrow()
        assertEquals(0, report.restoredFolderCount)
        assertTrue(db.workspaceDao().getFoldersList().isEmpty())
        val aApp = repository.allAppsFlow.first().single { it.packageName == "com.v1.a" }
        assertTrue(aApp.isOnHomeScreen && aApp.slotIndex == 3 && aApp.isShuffled && aApp.isPinnedToDock)
        assertStrictWorkspaceValid()
    }
}
