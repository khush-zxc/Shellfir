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
import com.example.domain.repository.AppRepository
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FolderInvariantConsistencyTest {

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
        preferences.setDrawerShuffleEnabled(false)
        coordinator = WorkspaceMutationCoordinator.forDatabase(db)
        repository = AppRepository(
            context = context,
            installedAppDao = db.installedAppDao(),
            workspaceDao = db.workspaceDao(),
            shuffleHistoryDao = db.shuffleHistoryDao(),
            preferences = preferences,
            database = db,
            mutationCoordinator = coordinator
        )
        backupHelper = BackupRestoreHelper(
            installedAppDao = db.installedAppDao(),
            workspaceDao = db.workspaceDao(),
            preferences = preferences,
            database = db,
            mutationCoordinator = coordinator,
            shuffleHistoryDao = db.shuffleHistoryDao()
        )
    }

    @After
    fun tearDown() {
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

    private suspend fun assertStrictDefaultValid() {
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
        assertTrue("Expected strict default validation to pass: ${result.errors}", result.isValid)
    }

    private fun v1AppJson(
        pkg: String,
        folderId: String?,
        slotIndex: Int = 0,
        pageIndex: Int = 0,
        isFixed: Boolean = false,
        isShuffled: Boolean = false,
        isPinnedToDock: Boolean = false,
        isOnHomeScreen: Boolean = folderId == null && !isPinnedToDock,
        isHidden: Boolean = false
    ) = JSONObject().apply {
        put("packageName", pkg)
        put("activityName", "$pkg.Main")
        put("label", pkg.substringAfterLast('.'))
        put("isShuffled", isShuffled)
        put("isFixed", isFixed)
        put("slotIndex", slotIndex)
        put("pageIndex", pageIndex)
        put("folderId", folderId ?: JSONObject.NULL)
        put("isPinnedToDock", isPinnedToDock)
        put("isOnHomeScreen", isOnHomeScreen)
        put("isVisibleInFocus", false)
        put("isEssentialInFocus", false)
        put("isHidden", isHidden)
        put("drawerSlotIndex", -1)
        put("isDrawerShuffled", false)
    }

    @Test
    fun `default WorkspaceValidator strictly rejects 0-member and 1-member folders while accepting 2-member folder plus Dock`() {
        val pages = listOf(WorkspacePageEntity("p0", 0, true, 1L))
        val apps = listOf(
            InstalledAppEntity("com.a", "com.a.Main", "A", isInstalled = true),
            InstalledAppEntity("com.b", "com.b.Main", "B", isInstalled = true)
        )
        val folder = FolderEntity("f1", "Work", 1L)
        val folderItem = WorkspaceItemEntity("folder_f1", WorkspaceItemTypes.FOLDER, WorkspaceContainers.HOME, "p0", folderId = "f1", rank = 0)
        val dockItem = WorkspaceItemEntity("dock_a", WorkspaceItemTypes.APP, WorkspaceContainers.DOCK, null, "com.a", "com.a.Main", rank = 0)
        val memberA = FolderMemberEntity("f1", "com.a", "com.a.Main", rank = 0)
        val memberB = FolderMemberEntity("f1", "com.b", "com.b.Main", rank = 1)

        // Default minFolderMembers (2) rejects empty and single-member folders
        val emptyRes = validator.validateNormalizedWorkspace(apps, pages, listOf(folderItem), listOf(folder), emptyList(), emptyList())
        assertFalse(emptyRes.isValid)
        assertTrue(emptyRes.errors.any { it.contains("minimum required is 2") })

        val singleRes = validator.validateNormalizedWorkspace(apps, pages, listOf(folderItem), listOf(folder), listOf(memberA), emptyList())
        assertFalse(singleRes.isValid)
        assertTrue(singleRes.errors.any { it.contains("minimum required is 2") })

        // Valid 2-member folder with memberA also pinned to DOCK passes default strict validation
        val validRes = validator.validateNormalizedWorkspace(
            apps,
            pages,
            listOf(folderItem, dockItem),
            listOf(folder),
            listOf(memberA, memberB),
            emptyList()
        )
        assertTrue("Expected FOLDER + DOCK with 2 members to pass: ${validRes.errors}", validRes.isValid)
    }

    @Test
    fun `legacy v1 backup normalizes empty, single-member, and multi-member folders while preserving Dock`() = runBlocking {
        listOf("com.one", "com.multi1", "com.multi2", "com.multi3", "com.dockonly").forEach {
            registerLauncherActivity(it, "$it.Main", it)
        }
        repository.discoverAndSyncApps()

        val v1Json = JSONObject().apply {
            put("schemaVersion", 1)
            put("timestamp", 1700010000L)
            put("preferences", JSONObject().apply {
                put("themeMode", "DARK"); put("gridColumns", 4); put("pageCount", 2); put("dockCount", 5)
            })
            put("folders", JSONArray().apply {
                // 0 installed members (genuinely empty in v1 payload)
                put(JSONObject().apply { put("id", "v1_empty"); put("name", "Empty"); put("pageIndex", 0); put("slotIndex", 1); put("createdAt", 10L) })
                // 1 installed member (com.one, also pinned to Dock, fixed)
                put(JSONObject().apply { put("id", "v1_single"); put("name", "Single"); put("pageIndex", 1); put("slotIndex", 4); put("createdAt", 11L) })
                // 3 installed + 1 missing member -> shrinks to 3 contiguous (0, 1, 2), com.multi1 also in Dock
                put(JSONObject().apply { put("id", "v1_multi"); put("name", "Multi"); put("pageIndex", 0); put("slotIndex", 2); put("createdAt", 12L) })
            })
            put("apps", JSONArray().apply {
                put(v1AppJson("com.one", folderId = "v1_single", isFixed = true, isPinnedToDock = true))
                put(v1AppJson("com.multi1", folderId = "v1_multi", isPinnedToDock = true))
                put(v1AppJson("com.missing.app", folderId = "v1_multi"))
                put(v1AppJson("com.multi2", folderId = "v1_multi", isShuffled = true))
                put(v1AppJson("com.multi3", folderId = "v1_multi"))
                put(v1AppJson("com.dockonly", folderId = null, slotIndex = 0, isPinnedToDock = true, isOnHomeScreen = false))
            })
        }.toString()

        val report = backupHelper.restoreBackupDetailed(v1Json).getOrThrow()
        assertEquals(1, report.restoredFolderCount)
        assertStrictDefaultValid()

        // Empty and single-member folders were normalized out
        val folders = db.workspaceDao().getFoldersList()
        assertEquals(listOf("v1_multi"), folders.map { it.folderId })
        val items = db.workspaceDao().getWorkspaceItemsList()
        assertNull(items.find { it.folderId == "v1_empty" })
        assertNull(items.find { it.folderId == "v1_single" })

        // Multi-member folder retained with contiguous ranks 0, 1, 2
        val multiMembers = db.workspaceDao().getFolderMembersList().filter { it.folderId == "v1_multi" }.sortedBy { it.rank }
        assertEquals(listOf("com.multi1" to 0, "com.multi2" to 1, "com.multi3" to 2), multiMembers.map { it.packageName to it.rank })

        // Single-member survivor restored to HOME at v1_single's slot (page 1, rank 4) with isFixed & Dock intact
        val byPkg = repository.allAppsFlow.first().associateBy { it.packageName }
        val survivor = byPkg["com.one"]!!
        assertNull(survivor.folderId)
        assertTrue(survivor.isOnHomeScreen)
        assertEquals(1, survivor.pageIndex)
        assertEquals(4, survivor.slotIndex)
        assertTrue(survivor.isFixed)
        assertTrue(survivor.isPinnedToDock)

        // Multi-member app + dockonly app retain independent Dock placements
        assertTrue(byPkg["com.multi1"]!!.isPinnedToDock)
        assertEquals("v1_multi", byPkg["com.multi1"]!!.folderId)
        assertTrue(byPkg["com.dockonly"]!!.isPinnedToDock)
        assertFalse(byPkg["com.dockonly"]!!.isOnHomeScreen)
    }

    @Test
    fun `legacy v2 backup with pre-existing empty or single-member folders normalizes cleanly and failed restore rolls back`() = runBlocking {
        listOf("com.k1", "com.k2", "com.m1", "com.m2", "com.m3").forEach {
            registerLauncherActivity(it, "$it.Main", it)
        }
        repository.discoverAndSyncApps()
        repository.toggleAppPinnedToDock("com.k1", "com.k1.Main", true)
        val keepFolder = repository.createFolderFromHomeApps("com.k2", "com.k2.Main", "com.k1", "com.k1.Main", "Current").folderId!!
        val baselineBackup = backupHelper.generateBackupJson()

        // Craft a legacy-style v2 backup JSON containing:
        // - f_zero: 0 members
        // - f_single: 1 member (com.m1, shuffled + Dock)
        // - f_pair: 2 installed members (com.m2, com.m3) + 1 uninstalled member (com.missing.m4)
        val legacyV2Json = JSONObject(baselineBackup).apply {
            put("folders", JSONArray().apply {
                put(JSONObject().apply { put("folderId", "f_zero"); put("name", "Zero"); put("createdAt", 10L) })
                put(JSONObject().apply { put("folderId", "f_single"); put("name", "One"); put("createdAt", 11L) })
                put(JSONObject().apply { put("folderId", "f_pair"); put("name", "Two"); put("createdAt", 12L) })
            })
            put("workspaceItems", JSONArray().apply {
                put(JSONObject().apply {
                    put("itemId", "folder_f_zero"); put("itemType", WorkspaceItemTypes.FOLDER); put("containerType", WorkspaceContainers.HOME)
                    put("pageId", "page_0"); put("packageName", JSONObject.NULL); put("activityName", JSONObject.NULL)
                    put("folderId", "f_zero"); put("appWidgetId", JSONObject.NULL); put("columnSpan", 1); put("rowSpan", 1); put("rank", 1)
                })
                put(JSONObject().apply {
                    put("itemId", "folder_f_single"); put("itemType", WorkspaceItemTypes.FOLDER); put("containerType", WorkspaceContainers.HOME)
                    put("pageId", "page_1"); put("packageName", JSONObject.NULL); put("activityName", JSONObject.NULL)
                    put("folderId", "f_single"); put("appWidgetId", JSONObject.NULL); put("columnSpan", 1); put("rowSpan", 1); put("rank", 3)
                })
                put(JSONObject().apply {
                    put("itemId", "folder_f_pair"); put("itemType", WorkspaceItemTypes.FOLDER); put("containerType", WorkspaceContainers.HOME)
                    put("pageId", "page_0"); put("packageName", JSONObject.NULL); put("activityName", JSONObject.NULL)
                    put("folderId", "f_pair"); put("appWidgetId", JSONObject.NULL); put("columnSpan", 1); put("rowSpan", 1); put("rank", 2)
                })
                put(JSONObject().apply {
                    put("itemId", "dock_m1"); put("itemType", WorkspaceItemTypes.APP); put("containerType", WorkspaceContainers.DOCK)
                    put("pageId", JSONObject.NULL); put("packageName", "com.m1"); put("activityName", "com.m1.Main")
                    put("folderId", JSONObject.NULL); put("appWidgetId", JSONObject.NULL); put("columnSpan", 1); put("rowSpan", 1); put("rank", 0)
                })
                put(JSONObject().apply {
                    put("itemId", "dock_m2"); put("itemType", WorkspaceItemTypes.APP); put("containerType", WorkspaceContainers.DOCK)
                    put("pageId", JSONObject.NULL); put("packageName", "com.m2"); put("activityName", "com.m2.Main")
                    put("folderId", JSONObject.NULL); put("appWidgetId", JSONObject.NULL); put("columnSpan", 1); put("rowSpan", 1); put("rank", 1)
                })
            })
            put("folderMembers", JSONArray().apply {
                put(JSONObject().apply {
                    put("folderId", "f_single"); put("packageName", "com.m1"); put("activityName", "com.m1.Main")
                    put("rank", 0); put("isFixed", false); put("isShuffled", true)
                })
                put(JSONObject().apply {
                    put("folderId", "f_pair"); put("packageName", "com.missing.m4"); put("activityName", "com.missing.m4.Main")
                    put("rank", 0); put("isFixed", false); put("isShuffled", false)
                })
                put(JSONObject().apply {
                    put("folderId", "f_pair"); put("packageName", "com.m2"); put("activityName", "com.m2.Main")
                    put("rank", 1); put("isFixed", false); put("isShuffled", false)
                })
                put(JSONObject().apply {
                    put("folderId", "f_pair"); put("packageName", "com.m3"); put("activityName", "com.m3.Main")
                    put("rank", 2); put("isFixed", true); put("isShuffled", false)
                })
            })
        }.toString()

        // Failing corrupt restore (missing folderId reference) leaves previous workspace intact
        val corruptV2 = JSONObject(legacyV2Json).apply {
            getJSONArray("folderMembers").getJSONObject(0).put("folderId", "nonexistent_folder")
        }.toString()
        assertTrue(backupHelper.restoreBackupDetailed(corruptV2).isFailure)
        assertEquals(listOf(keepFolder), db.workspaceDao().getFoldersList().map { it.folderId })
        assertTrue(repository.allAppsFlow.first().single { it.packageName == "com.k1" }.isPinnedToDock)
        assertStrictDefaultValid()

        // Valid normalization of legacy v2: f_zero dropped, f_single dissolved to Home, f_pair repacked to ranks 0..1
        val report = backupHelper.restoreBackupDetailed(legacyV2Json).getOrThrow()
        assertEquals(1, report.restoredFolderCount)
        assertEquals(listOf("f_pair"), db.workspaceDao().getFoldersList().map { it.folderId })
        val pairMembers = db.workspaceDao().getFolderMembersList().filter { it.folderId == "f_pair" }.sortedBy { it.rank }
        assertEquals(listOf("com.m2" to 0, "com.m3" to 1), pairMembers.map { it.packageName to it.rank })

        val appsAfter = repository.allAppsFlow.first().associateBy { it.packageName }
        val m1 = appsAfter["com.m1"]!!
        assertNull(m1.folderId)
        assertTrue(m1.isOnHomeScreen && m1.pageIndex == 1 && m1.slotIndex == 3 && m1.isShuffled)
        assertTrue(m1.isPinnedToDock && m1.dockRank == 0)
        assertTrue(appsAfter["com.m2"]!!.isPinnedToDock && appsAfter["com.m2"]!!.dockRank == 1)
        assertEquals("f_pair", appsAfter["com.m2"]!!.folderId)
        assertStrictDefaultValid()
    }
}
