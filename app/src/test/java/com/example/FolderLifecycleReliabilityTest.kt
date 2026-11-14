package com.example

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.FolderEntity
import com.example.data.database.FolderMemberEntity
import com.example.data.database.ShellfriDatabase
import com.example.data.database.WorkspaceContainers
import com.example.data.database.WorkspaceItemEntity
import com.example.data.database.WorkspaceItemTypes
import com.example.data.preferences.ShellfriPreferences
import com.example.domain.concurrency.WorkspaceMutationCoordinator
import com.example.domain.repository.AppRepository
import com.example.domain.validation.WorkspaceValidator
import com.example.ui.home.HomeViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FolderLifecycleReliabilityTest {

    private lateinit var context: Context
    private lateinit var db: ShellfriDatabase
    private lateinit var preferences: ShellfriPreferences
    private lateinit var coordinator: WorkspaceMutationCoordinator
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
    }

    @After
    fun tearDown() {
        db.close()
    }

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

    private fun uninstallComponent(pkg: String, activityName: String) {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(pkg, activityName),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    private suspend fun seedAndSync(vararg comps: Pair<String, String>) {
        comps.forEachIndexed { idx, (pkg, act) ->
            registerLauncherActivity(pkg, act, "App$idx")
        }
        repository.discoverAndSyncApps()
        comps.forEachIndexed { idx, (pkg, act) ->
            repository.setAppPageAndSlot(pkg, act, 0, idx)
        }
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
    fun `uninstall member from 3-app folder repacks ranks cleanly and preserves folder and Dock`() = runBlocking {
        val a = "com.f3.a" to "com.f3.a.Main"
        val b = "com.f3.b" to "com.f3.b.Main"
        val c = "com.f3.c" to "com.f3.c.Main"
        seedAndSync(a, b, c)
        repository.toggleAppPinnedToDock(c.first, c.second, true)

        val folderId = repository.createFolderFromHomeApps(b.first, b.second, a.first, a.second, "TriFolder").folderId!!
        assertTrue(repository.addAppToFolderResult(c.first, c.second, folderId).success)

        // Uninstall rank 0 (a) -> remaining b and c pack contiguously to 0 and 1
        uninstallComponent(a.first, a.second)
        repository.discoverAndSyncApps()
        assertStrictWorkspaceValid()

        val members = db.workspaceDao().getFolderMembersList().filter { it.folderId == folderId }.sortedBy { it.rank }
        assertEquals(listOf(b.first to 0, c.first to 1), members.map { it.packageName to it.rank })
        assertTrue(repository.foldersFlow.first().any { it.id == folderId && it.name == "TriFolder" })
        val cApp = repository.allAppsFlow.first().single { it.packageName == c.first }
        assertTrue(cApp.isPinnedToDock)
        assertEquals(0, cApp.dockRank)
    }

    @Test
    fun `uninstall member from 2-app folder dissolves folder, unpacks survivor with flags and Dock, keeps sibling independent`() = runBlocking {
        val a = "com.multi" to "com.multi.Primary"
        val sibling = "com.multi" to "com.multi.Secondary"
        val b = "com.f2.b" to "com.f2.b.Main"
        seedAndSync(a, b, sibling)
        repository.toggleAppPinnedToDock(a.first, a.second, true)
        repository.toggleAppShuffled(a.first, a.second, true)

        val folderId = repository.createFolderFromHomeApps(b.first, b.second, a.first, a.second, "Pair").folderId!!
        val folderSlot = repository.foldersFlow.first().single { it.id == folderId }.slotIndex

        uninstallComponent(b.first, b.second)
        repository.discoverAndSyncApps()
        assertStrictWorkspaceValid()

        assertTrue(db.workspaceDao().getFoldersList().isEmpty())
        assertTrue(db.workspaceDao().getFolderMembersList().isEmpty())
        assertNull(db.workspaceDao().getWorkspaceItemsList().find { it.itemType == WorkspaceItemTypes.FOLDER })

        val appsByKey = repository.allAppsFlow.first().associateBy { it.componentKey }
        val survivor = appsByKey["${a.first}/${a.second}"]!!
        assertNull(survivor.folderId)
        assertTrue(survivor.isOnHomeScreen)
        assertEquals(folderSlot, survivor.slotIndex)
        assertTrue(survivor.isShuffled)
        assertTrue(survivor.isPinnedToDock)
        val sibApp = appsByKey["${sibling.first}/${sibling.second}"]!!
        assertTrue(sibApp.isOnHomeScreen)
        assertFalse(sibApp.isPinnedToDock)
    }

    @Test
    fun `uninstall all folder members removes folder and home item without orphans`() = runBlocking {
        val a = "com.all.a" to "com.all.a.Main"
        val b = "com.all.b" to "com.all.b.Main"
        val c = "com.all.c" to "com.all.c.Main"
        seedAndSync(a, b, c)
        val folderId = repository.createFolderFromHomeApps(b.first, b.second, a.first, a.second).folderId!!

        uninstallComponent(a.first, a.second)
        uninstallComponent(b.first, b.second)
        repository.discoverAndSyncApps()
        assertStrictWorkspaceValid()

        assertTrue(db.workspaceDao().getFoldersList().isEmpty())
        assertTrue(db.workspaceDao().getFolderMembersList().isEmpty())
        assertNull(db.workspaceDao().getWorkspaceItemsList().find { it.folderId == folderId })
        assertTrue(repository.allAppsFlow.first().any { it.packageName == c.first && it.isOnHomeScreen })
    }

    @Test
    fun `reinstall restores into surviving folder or safely to Home if dissolved without duplicates across cycles`() = runBlocking {
        val a = "com.re.a" to "com.re.a.Main"
        val b = "com.re.b" to "com.re.b.Main"
        val c = "com.re.c" to "com.re.c.Main"
        seedAndSync(a, b, c)
        repository.toggleAppPinnedToDock(b.first, b.second, true)

        // Part 1: 3-app folder survives -> reinstall puts b back into folder AND Dock across 2 cycles
        val survivingId = repository.createFolderFromHomeApps(b.first, b.second, a.first, a.second, "Surv").folderId!!
        assertTrue(repository.addAppToFolderResult(c.first, c.second, survivingId).success)

        repeat(2) {
            uninstallComponent(b.first, b.second)
            repository.discoverAndSyncApps()
            assertStrictWorkspaceValid()
            assertFalse(db.installedAppDao().getAllAppsList().single { it.packageName == b.first }.isInstalled)

            registerLauncherActivity(b.first, b.second, "B")
            repository.discoverAndSyncApps()
            assertStrictWorkspaceValid()

            val members = db.workspaceDao().getFolderMembersList().filter { it.folderId == survivingId }
            assertEquals(3, members.size)
            assertEquals(setOf(0, 1, 2), members.map { it.rank }.toSet())
            val bApp = repository.allAppsFlow.first().single { it.packageName == b.first }
            assertEquals(survivingId, bApp.folderId)
            assertTrue(bApp.isPinnedToDock)
            assertEquals(1, db.workspaceDao().getWorkspaceItemsList().count { it.packageName == b.first })
        }

        // Part 2: dissolve folder via 2nd uninstall -> delete surviving folder after another removal
        val d = "com.re.d" to "com.re.d.Main"
        val e = "com.re.e" to "com.re.e.Main"
        seedAndSync(d, e)
        repository.toggleAppPinnedToDock(d.first, d.second, true)
        val dissolvedId = repository.createFolderFromHomeApps(d.first, d.second, e.first, e.second, "Dis").folderId!!

        uninstallComponent(d.first, d.second)
        repository.discoverAndSyncApps()
        assertStrictWorkspaceValid()
        assertTrue(db.workspaceDao().getFoldersList().none { it.folderId == dissolvedId })

        registerLauncherActivity(d.first, d.second, "D")
        repository.discoverAndSyncApps()
        assertStrictWorkspaceValid()
        assertTrue("Dissolved folder must NOT be recreated", db.workspaceDao().getFoldersList().none { it.folderId == dissolvedId })

        val dApp = repository.allAppsFlow.first().single { it.packageName == d.first }
        assertNull(dApp.folderId)
        assertTrue(dApp.isOnHomeScreen)
        assertTrue(dApp.isPinnedToDock)
    }

    @Test
    fun `cross-folder move repacks 3-member source, dissolves 2-member source, deletes 1-member source, and rolls back on failure`() = runBlocking {
        val a1 = "com.cf.a1" to "com.cf.a1.Main"
        val a2 = "com.cf.a2" to "com.cf.a2.Main"
        val a3 = "com.cf.a3" to "com.cf.a3.Main"
        val b1 = "com.cf.b1" to "com.cf.b1.Main"
        val b2 = "com.cf.b2" to "com.cf.b2.Main"
        seedAndSync(a1, a2, a3, b1, b2)
        repository.toggleAppPinnedToDock(a2.first, a2.second, true)

        val srcId = repository.createFolderFromHomeApps(a2.first, a2.second, a1.first, a1.second, "Src").folderId!!
        assertTrue(repository.addAppToFolderResult(a3.first, a3.second, srcId).success)
        val dstId = repository.createFolderFromHomeApps(b2.first, b2.second, b1.first, b1.second, "Dst").folderId!!
        val srcSlot = repository.foldersFlow.first().single { it.id == srcId }.slotIndex

        // 1. Move a1 (rank 0) from 3-app src -> dst => src repacks [a2@0, a3@1], dst has [b1@0, b2@1, a1@2]
        assertTrue(repository.addAppToFolderResult(a1.first, a1.second, dstId).success)
        assertStrictWorkspaceValid()
        assertEquals(
            listOf(a2.first to 0, a3.first to 1),
            db.workspaceDao().getFolderMembersList().filter { it.folderId == srcId }.sortedBy { it.rank }.map { it.packageName to it.rank }
        )

        // 2. Nonexistent destination rejected safely without altering src
        assertFalse(repository.addAppToFolderResult(a3.first, a3.second, "missing_folder").success)
        assertStrictWorkspaceValid()

        // 3. Simulated SQLite failure rolls back cross-folder move cleanly
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER abort_cross_move BEFORE INSERT ON folder_members
            BEGIN SELECT RAISE(ABORT, 'abort cross move'); END;
            """.trimIndent()
        )
        val failedMove = repository.addAppToFolderResult(a3.first, a3.second, dstId)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS abort_cross_move")
        assertFalse(failedMove.success)
        assertStrictWorkspaceValid()
        assertEquals(2, db.workspaceDao().getFolderMembersList().count { it.folderId == srcId })

        // 4. Move a3 from 2-app src -> dst => src dissolves and a2 unpacks to srcSlot while staying pinned to Dock
        assertTrue(repository.addAppToFolderResult(a3.first, a3.second, dstId).success)
        assertStrictWorkspaceValid()
        assertTrue(db.workspaceDao().getFoldersList().none { it.folderId == srcId })
        val a2App = repository.allAppsFlow.first().single { it.packageName == a2.first }
        assertNull(a2App.folderId)
        assertTrue(a2App.isOnHomeScreen)
        assertEquals(srcSlot, a2App.slotIndex)
        assertTrue(a2App.isPinnedToDock)

        // 5. Legacy 1-member source folder deletes cleanly when its single member moves out,
        // and createFolder strictly rejects creating <2 member folders
        val c1 = "com.cf.c1" to "com.cf.c1.Main"
        seedAndSync(c1)
        val rejectedSingleCreate = runCatching {
            repository.createFolder("RejectedSingle", 1, 2, listOf(c1))
        }
        assertTrue(rejectedSingleCreate.isFailure)

        val singleId = "legacy_single_src"
        db.workspaceDao().upsertFolder(FolderEntity(singleId, "SingleSrc", 500L))
        db.workspaceDao().deleteWorkspaceItemsForComponentInContainer(c1.first, c1.second, WorkspaceContainers.HOME)
        db.workspaceDao().upsertWorkspaceItem(
            WorkspaceItemEntity("folder_$singleId", WorkspaceItemTypes.FOLDER, WorkspaceContainers.HOME, "page_1", folderId = singleId, rank = 2)
        )
        db.workspaceDao().upsertFolderMember(
            FolderMemberEntity(singleId, c1.first, c1.second, rank = 0)
        )
        assertTrue(repository.addAppToFolderResult(c1.first, c1.second, dstId).success)
        assertTrue(db.workspaceDao().getFoldersList().none { it.folderId == singleId })
        assertStrictWorkspaceValid()
    }

    @Test
    fun `deleteFolderResult unpacks multi-member and corrupt folders, preserves Dock, rejects missing, and rolls back on failure`() = runBlocking {
        val a = "com.del.a" to "com.del.a.Main"
        val b = "com.del.b" to "com.del.b.Main"
        val c = "com.del.c" to "com.del.c.Main"
        seedAndSync(a, b, c)
        repository.toggleAppPinnedToDock(a.first, a.second, true)
        repository.toggleAppShuffled(b.first, b.second, true)

        val folderId = repository.createFolderFromHomeApps(b.first, b.second, a.first, a.second, "DelMe").folderId!!
        assertTrue(repository.addAppToFolderResult(c.first, c.second, folderId).success)

        // Reject nonexistent folder
        assertFalse(repository.deleteFolderResult("missing").success)

        // Trigger failure during delete -> folder and members remain intact
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER abort_folder_delete BEFORE DELETE ON folders
            BEGIN SELECT RAISE(ABORT, 'abort delete'); END;
            """.trimIndent()
        )
        assertFalse(repository.deleteFolderResult(folderId).success)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS abort_folder_delete")
        assertStrictWorkspaceValid()
        assertEquals(3, db.workspaceDao().getFolderMembersList().count { it.folderId == folderId })

        // Clean deletion unpacks all 3 to distinct HOME slots and preserves Dock + flags
        assertTrue(repository.deleteFolderResult(folderId).success)
        assertStrictWorkspaceValid()
        assertTrue(db.workspaceDao().getFoldersList().isEmpty())
        val byPkg = repository.allAppsFlow.first().associateBy { it.packageName }
        assertTrue(byPkg[a.first]!!.isOnHomeScreen && byPkg[a.first]!!.isPinnedToDock)
        assertTrue(byPkg[b.first]!!.isOnHomeScreen && byPkg[b.first]!!.isShuffled)
        assertTrue(byPkg[c.first]!!.isOnHomeScreen)

        // Corrupt 0-member folder cleans up safely
        db.workspaceDao().upsertFolder(FolderEntity("empty_corrupt", "Corrupt", 100L))
        db.workspaceDao().upsertWorkspaceItem(
            WorkspaceItemEntity("folder_empty_corrupt", WorkspaceItemTypes.FOLDER, WorkspaceContainers.HOME, "page_0", folderId = "empty_corrupt", rank = 9)
        )
        assertTrue(repository.deleteFolderResult("empty_corrupt").success)
        assertStrictWorkspaceValid()
    }

    @Test
    fun `HomeViewModel deleteFolder preserves activeFolder on failure and dismisses cleanly on success`() = runBlocking {
        val app = context.applicationContext as Application
        val appDb = ShellfriDatabase.getInstance(app)
        val a = "com.vm.a" to "com.vm.a.Main"
        val b = "com.vm.b" to "com.vm.b.Main"
        registerLauncherActivity(a.first, a.second, "VMA")
        registerLauncherActivity(b.first, b.second, "VMB")

        val vm = HomeViewModel(app)
        val collectJob = launch(Dispatchers.Main.immediate) { vm.uiState.collect {} }
        try {
            withTimeout(5000) {
                while (
                    vm.uiState.value.allApps.none { it.packageName == a.first } ||
                    vm.uiState.value.allApps.none { it.packageName == b.first }
                ) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(15)
                }
            }
            val folderId = vm.repository.createFolderFromHomeApps(b.first, b.second, a.first, a.second, "VMFolder").folderId!!
            withTimeout(5000) {
                while (vm.uiState.value.folders.none { it.id == folderId }) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(15)
                }
            }
            val folder = vm.uiState.value.folders.single { it.id == folderId }
            vm.openFolder(folder)
            withTimeout(5000) {
                while (vm.uiState.value.activeFolder?.id != folderId) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(15)
                }
            }

            // Induce failure via SQLite trigger on singleton DB
            withContext(Dispatchers.IO) {
                appDb.openHelper.writableDatabase.execSQL(
                    """
                    CREATE TRIGGER abort_vm_delete BEFORE DELETE ON folders
                    BEGIN SELECT RAISE(ABORT, 'vm delete blocked'); END;
                    """.trimIndent()
                )
            }
            val failDeferred = CompletableDeferred<Boolean>()
            vm.deleteFolder(folderId) { success, _ -> failDeferred.complete(success) }
            withTimeout(5000) {
                while (!failDeferred.isCompleted) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(15)
                }
            }
            assertFalse(failDeferred.await())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(folderId, vm.uiState.value.activeFolder?.id)
            assertFalse(vm.uiState.value.messageToast?.contains("deleted", ignoreCase = true) == true)

            withContext(Dispatchers.IO) {
                appDb.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS abort_vm_delete")
            }
            val okDeferred = CompletableDeferred<Boolean>()
            vm.deleteFolder(folderId) { success, _ -> okDeferred.complete(success) }
            withTimeout(5000) {
                while (!okDeferred.isCompleted || vm.uiState.value.activeFolder != null) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(15)
                }
            }
            assertTrue(okDeferred.await())
            assertNull(vm.uiState.value.activeFolder)
            assertEquals("Folder deleted. Apps unpacked.", vm.uiState.value.messageToast)
        } finally {
            collectJob.cancel()
            withContext(Dispatchers.IO) {
                appDb.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS abort_vm_delete")
                appDb.clearAllTables()
            }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `cancellation mid-mutation and concurrent discovery plus folder lifecycle leave lock released and invariants valid`() = runBlocking {
        val comps = (0 until 6).map { i -> "com.conc.f$i" to "com.conc.f$i.Main" }
        comps.forEachIndexed { idx, (pkg, act) -> registerLauncherActivity(pkg, act, "C$idx") }
        repository.discoverAndSyncApps()
        val f1 = repository.createFolderFromHomeApps(comps[1].first, comps[1].second, comps[0].first, comps[0].second, "C1").folderId!!
        val f2 = repository.createFolderFromHomeApps(comps[3].first, comps[3].second, comps[2].first, comps[2].second, "C2").folderId!!

        // Cancel a waiter while lock is held and verify lock releases cleanly
        val lockHeld = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            coordinator.withMutationLock {
                lockHeld.complete(Unit)
                release.await()
            }
        }
        lockHeld.await()
        val cancelledDelete = launch(Dispatchers.IO) { repository.deleteFolderResult(f1) }
        cancelledDelete.cancelAndJoin()
        release.complete(Unit)
        holder.join()
        assertFalse(coordinator.isLocked)

        // Concurrent discovery, cross-folder move, add-to-folder, and folder deletion
        val barrier = CompletableDeferred<Unit>()
        val jobs = listOf(
            async(Dispatchers.IO) { barrier.await(); repository.discoverAndSyncApps() },
            async(Dispatchers.IO) { barrier.await(); repository.addAppToFolderResult(comps[4].first, comps[4].second, f1) },
            async(Dispatchers.IO) { barrier.await(); repository.addAppToFolderResult(comps[0].first, comps[0].second, f2) },
            async(Dispatchers.IO) { barrier.await(); repository.deleteFolderResult(f1) }
        )
        barrier.complete(Unit)
        jobs.awaitAll()

        assertFalse(coordinator.isLocked)
        assertStrictWorkspaceValid()
    }
}
