package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.ShellfriDatabase
import com.example.data.database.ShuffleHistoryEntity
import com.example.data.database.ShuffleLayoutSnapshotEntity
import com.example.data.database.WorkspaceContainers
import com.example.data.database.WorkspaceItemTypes
import com.example.data.preferences.ShellfriPreferences
import com.example.domain.concurrency.WorkspaceMutationCoordinator
import com.example.domain.model.ShuffleStrategy
import com.example.domain.repository.AppRepository
import com.example.domain.shuffle.ShuffleEngine
import com.example.domain.shuffle.UndoShuffleStatus
import com.example.domain.validation.WorkspaceValidator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShuffleUndoCorrectnessTest {

    private lateinit var context: Context
    private lateinit var db: ShellfriDatabase
    private lateinit var preferences: ShellfriPreferences
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
        preferences.setPageCount(1)
        preferences.setDockCount(5)
        preferences.setCrossPageShuffle(false)
        preferences.setDrawerShuffleEnabled(false)
        preferences.setShuffleStrategy(ShuffleStrategy.POSITION_ROTATION)

        repository = AppRepository(
            context = context,
            installedAppDao = db.installedAppDao(),
            workspaceDao = db.workspaceDao(),
            shuffleHistoryDao = db.shuffleHistoryDao(),
            preferences = preferences,
            shuffleEngine = ShuffleEngine(randomProvider = { Random(42) }),
            database = db,
            mutationCoordinator = WorkspaceMutationCoordinator.forDatabase(db)
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

    private fun uninstallPackage(pkg: String) {
        registeredComponentsByPkg[pkg].orEmpty().forEach { component ->
            context.packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
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
        assertTrue("Workspace invariants violated: ${result.errors.joinToString("; ")}", result.isValid)
    }

    private suspend fun seedFourApps(includeDrawerShuffle: Boolean = false): List<Pair<String, String>> {
        val comps = (1..4).map { idx -> "com.undo.app$idx" to "com.undo.app$idx.Main" }
        comps.forEachIndexed { idx, (pkg, act) ->
            registerLauncherActivity(pkg, act, "UndoApp ${idx + 1}")
        }
        repository.discoverAndSyncApps()
        comps.forEachIndexed { idx, (pkg, act) ->
            repository.setAppPageAndSlot(pkg, act, 0, idx)
            repository.toggleAppShuffled(pkg, act, true)
            if (includeDrawerShuffle) {
                repository.toggleAppDrawerShuffled(pkg, act, true)
            }
        }
        return comps
    }

    @Test
    fun `1 normal undo restores original home and drawer positions and consumes snapshot`() = runBlocking {
        preferences.setDrawerShuffleEnabled(true)
        val comps = seedFourApps(includeDrawerShuffle = true)
        val before = repository.allAppsFlow.first().associateBy { it.packageName }

        assertTrue(repository.performShuffle("MANUAL"))
        val afterShuffle = repository.allAppsFlow.first().associateBy { it.packageName }
        assertTrue(comps.any { (pkg, _) -> afterShuffle[pkg]!!.slotIndex != before[pkg]!!.slotIndex })

        val undoResult = repository.undoLastShuffleDetailed()
        assertEquals(UndoShuffleStatus.APPLIED, undoResult.status)
        assertEquals(4, undoResult.restoredHomeCount)

        val afterUndo = repository.allAppsFlow.first().associateBy { it.packageName }
        for ((pkg, _) in comps) {
            assertEquals(before[pkg]!!.slotIndex, afterUndo[pkg]!!.slotIndex)
            assertEquals(before[pkg]!!.drawerSlotIndex, afterUndo[pkg]!!.drawerSlotIndex)
        }

        val secondUndo = repository.undoLastShuffleDetailed()
        assertEquals(UndoShuffleStatus.NO_SNAPSHOT, secondUndo.status)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `2 folder created after shuffle is preserved while shuffled apps undo cleanly`() = runBlocking {
        val comps = seedFourApps()
        registerLauncherActivity("com.extra.one", "com.extra.one.Main", "Extra One")
        registerLauncherActivity("com.extra.two", "com.extra.two.Main", "Extra Two")
        repository.discoverAndSyncApps()
        repository.setAppPageAndSlot("com.extra.one", "com.extra.one.Main", 0, 10)
        repository.setAppPageAndSlot("com.extra.two", "com.extra.two.Main", 0, 11)

        val before = repository.allAppsFlow.first().associateBy { it.packageName }
        assertTrue(repository.performShuffle("MANUAL"))

        val folderId = repository.createFolder(
            name = "KeepFolder",
            pageIndex = 0,
            slotIndex = 12,
            components = listOf(
                "com.extra.one" to "com.extra.one.Main",
                "com.extra.two" to "com.extra.two.Main"
            )
        )

        val undoResult = repository.undoLastShuffleDetailed()
        assertEquals(UndoShuffleStatus.APPLIED, undoResult.status)

        val afterUndo = repository.allAppsFlow.first().associateBy { it.packageName }
        for ((pkg, _) in comps) {
            assertEquals(before[pkg]!!.slotIndex, afterUndo[pkg]!!.slotIndex)
        }
        assertEquals(1, db.workspaceDao().getFoldersList().size)
        assertEquals(folderId, afterUndo["com.extra.one"]!!.folderId)
        assertEquals(folderId, afterUndo["com.extra.two"]!!.folderId)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `3 folder deleted after shuffle is never resurrected during undo`() = runBlocking {
        val comps = seedFourApps()
        registerLauncherActivity("com.fld.a", "com.fld.a.Main", "Fld A")
        registerLauncherActivity("com.fld.b", "com.fld.b.Main", "Fld B")
        repository.discoverAndSyncApps()
        repository.setAppPageAndSlot("com.fld.a", "com.fld.a.Main", 0, 10)
        repository.setAppPageAndSlot("com.fld.b", "com.fld.b.Main", 0, 11)
        val folderId = repository.createFolder(
            name = "TransientFolder",
            pageIndex = 0,
            slotIndex = 12,
            components = listOf("com.fld.a" to "com.fld.a.Main", "com.fld.b" to "com.fld.b.Main")
        )

        val before = repository.allAppsFlow.first().associateBy { it.packageName }
        assertTrue(repository.performShuffle("MANUAL"))

        repository.deleteFolder(folderId)
        assertTrue(db.workspaceDao().getFoldersList().isEmpty())

        val undoResult = repository.undoLastShuffleDetailed()
        assertEquals(UndoShuffleStatus.APPLIED, undoResult.status)

        assertTrue("Deleted folder must NOT be resurrected", db.workspaceDao().getFoldersList().isEmpty())
        val afterUndo = repository.allAppsFlow.first().associateBy { it.packageName }
        assertNull(afterUndo["com.fld.a"]!!.folderId)
        assertTrue(afterUndo["com.fld.a"]!!.isOnHomeScreen)
        for ((pkg, _) in comps) {
            assertEquals(before[pkg]!!.slotIndex, afterUndo[pkg]!!.slotIndex)
        }
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `4 app moved into folder after shuffle remains in folder and is skipped safely`() = runBlocking {
        registerLauncherActivity("com.host.app", "com.host.app.Main", "Host")
        registerLauncherActivity("com.host.app2", "com.host.app2.Main", "Host2")
        registerLauncherActivity("com.shuf.a", "com.shuf.a.Main", "Shuf A")
        registerLauncherActivity("com.shuf.b", "com.shuf.b.Main", "Shuf B")
        repository.discoverAndSyncApps()
        repository.setAppPageAndSlot("com.host.app", "com.host.app.Main", 0, 5)
        repository.setAppPageAndSlot("com.host.app2", "com.host.app2.Main", 0, 6)
        repository.setAppPageAndSlot("com.shuf.a", "com.shuf.a.Main", 0, 0)
        repository.setAppPageAndSlot("com.shuf.b", "com.shuf.b.Main", 0, 1)
        repository.toggleAppShuffled("com.shuf.a", "com.shuf.a.Main", true)
        repository.toggleAppShuffled("com.shuf.b", "com.shuf.b.Main", true)

        val folderId = repository.createFolder(
            "TargetFolder",
            0,
            10,
            listOf("com.host.app" to "com.host.app.Main", "com.host.app2" to "com.host.app2.Main")
        )

        // Before shuffle: A@0, B@1. Rotation swaps A->1, B->0
        assertTrue(repository.performShuffle("MANUAL"))

        // Move com.shuf.a (now at slot 1) into TargetFolder
        repository.addAppToFolder("com.shuf.a", "com.shuf.a.Main", folderId)

        val undoResult = repository.undoLastShuffleDetailed()
        // A is in folder (skipped, freeing slot 1 for nothing or leaving 0 occupied? Wait: B moves back 0->1 since slot 1 is now FREE!)
        assertEquals(UndoShuffleStatus.PARTIALLY_APPLIED, undoResult.status)
        assertTrue(undoResult.skippedComponents.contains("com.shuf.a/com.shuf.a.Main"))

        val afterUndo = repository.allAppsFlow.first().associateBy { it.packageName }
        assertEquals(folderId, afterUndo["com.shuf.a"]!!.folderId)
        assertFalse(afterUndo["com.shuf.a"]!!.isOnHomeScreen)
        assertEquals(1, afterUndo["com.shuf.b"]!!.slotIndex)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `5 app uninstalled after shuffle is not recreated while remaining apps restore cleanly`() = runBlocking {
        registerLauncherActivity("com.uninst.a", "com.uninst.a.Main", "A")
        registerLauncherActivity("com.uninst.b", "com.uninst.b.Main", "B")
        repository.discoverAndSyncApps()
        repository.setAppPageAndSlot("com.uninst.a", "com.uninst.a.Main", 0, 0)
        repository.setAppPageAndSlot("com.uninst.b", "com.uninst.b.Main", 0, 1)
        repository.toggleAppShuffled("com.uninst.a", "com.uninst.a.Main", true)
        repository.toggleAppShuffled("com.uninst.b", "com.uninst.b.Main", true)

        assertTrue(repository.performShuffle("MANUAL"))

        // Uninstall A
        uninstallPackage("com.uninst.a")
        repository.discoverAndSyncApps()

        val undoResult = repository.undoLastShuffleDetailed()
        assertEquals(UndoShuffleStatus.PARTIALLY_APPLIED, undoResult.status)
        assertTrue(undoResult.skippedComponents.contains("com.uninst.a/com.uninst.a.Main"))

        assertNull(db.workspaceDao().getWorkspaceItemsList().find { it.packageName == "com.uninst.a" })
        val afterB = repository.allAppsFlow.first().first { it.packageName == "com.uninst.b" }
        assertEquals(1, afterB.slotIndex)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `6 newly installed app after shuffle is preserved and blocks colliding undo slot`() = runBlocking {
        registerLauncherActivity("com.inst.a", "com.inst.a.Main", "A")
        registerLauncherActivity("com.inst.b", "com.inst.b.Main", "B")
        repository.discoverAndSyncApps()
        repository.setAppPageAndSlot("com.inst.a", "com.inst.a.Main", 0, 0)
        repository.setAppPageAndSlot("com.inst.b", "com.inst.b.Main", 0, 1)
        repository.toggleAppShuffled("com.inst.a", "com.inst.a.Main", true)
        repository.toggleAppShuffled("com.inst.b", "com.inst.b.Main", true)

        assertTrue(repository.performShuffle("MANUAL"))
        // Now A@1, B@0. Install new app C (first free slot = 2, non-colliding) and D, then test free-slot undo
        registerLauncherActivity("com.inst.newfree", "com.inst.newfree.Main", "NewFree")
        repository.discoverAndSyncApps()
        assertNotNull(repository.allAppsFlow.first().find { it.packageName == "com.inst.newfree" })

        val cleanUndo = repository.undoLastShuffleDetailed()
        assertEquals(UndoShuffleStatus.APPLIED, cleanUndo.status)
        val appsAfterClean = repository.allAppsFlow.first().associateBy { it.packageName }
        assertNotNull(appsAfterClean["com.inst.newfree"])
        assertEquals(0, appsAfterClean["com.inst.a"]!!.slotIndex)
        assertEquals(1, appsAfterClean["com.inst.b"]!!.slotIndex)
        assertNormalizedWorkspaceValid()

        // Second scenario: shuffle again (A@1, B@0), then uninstall A and install new app into A's original slot 0
        assertTrue(repository.performShuffle("MANUAL"))
        uninstallPackage("com.inst.a")
        repository.discoverAndSyncApps()
        registerLauncherActivity("com.inst.blocker", "com.inst.blocker.Main", "Blocker")
        repository.discoverAndSyncApps()
        // Occupy slot 1 explicitly by moving blocker there (B is at slot 0, so blocker at slot 1 blocks B returning to 1!)
        repository.setAppPageAndSlot("com.inst.blocker", "com.inst.blocker.Main", 0, 1)

        val blockedUndo = repository.undoLastShuffleDetailed()
        assertEquals(UndoShuffleStatus.UNAVAILABLE, blockedUndo.status)
        val afterBlocked = repository.allAppsFlow.first().associateBy { it.packageName }
        assertEquals(1, afterBlocked["com.inst.blocker"]!!.slotIndex)
        assertEquals(0, afterBlocked["com.inst.b"]!!.slotIndex)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `7 deleting origin page after cross-page shuffle prevents undo from recreating deleted page`() = runBlocking {
        preferences.setPageCount(1)
        preferences.setCrossPageShuffle(true)
        repository.addPage() // creates pages 0 and 1 exactly
        val p0 = "com.pg.a"
        val p1 = "com.pg.b"
        registerLauncherActivity(p0, "$p0.Main", "Page0 App")
        registerLauncherActivity(p1, "$p1.Main", "Page1 App")
        repository.discoverAndSyncApps()
        repository.setAppPageAndSlot(p0, "$p0.Main", 0, 0)
        repository.setAppPageAndSlot(p1, "$p1.Main", 1, 0)
        repository.toggleAppShuffled(p0, "$p0.Main", true)
        repository.toggleAppShuffled(p1, "$p1.Main", true)

        assertTrue(repository.performShuffle("MANUAL"))
        // Rotation guarantees p0 moved to page 1 and p1 moved to page 0!
        val shuffledApps = repository.allAppsFlow.first().associateBy { it.packageName }
        assertEquals(1, shuffledApps[p0]!!.pageIndex)
        assertEquals(0, shuffledApps[p1]!!.pageIndex)

        // Delete page 1 (moves p0 onto page 0; p1's original pageId is deleted!)
        assertTrue(repository.removePage(1))
        assertEquals(1, db.workspaceDao().getPagesList().size)

        val undoResult = repository.undoLastShuffleDetailed()
        assertEquals(UndoShuffleStatus.UNAVAILABLE, undoResult.status)
        assertEquals(1, db.workspaceDao().getPagesList().size)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `8 dock reorder and pin after shuffle stays intact when HOME shuffle undo runs`() = runBlocking {
        val comps = seedFourApps()
        registerLauncherActivity("com.dock.one", "com.dock.one.Main", "Dock1")
        registerLauncherActivity("com.dock.two", "com.dock.two.Main", "Dock2")
        registerLauncherActivity("com.dock.three", "com.dock.three.Main", "Dock3")
        repository.discoverAndSyncApps()
        repository.toggleAppPinnedToDock("com.dock.one", "com.dock.one.Main", true)
        repository.toggleAppPinnedToDock("com.dock.two", "com.dock.two.Main", true)
        // Pin comp 0 (one of the shuffled apps!) to dock as well to verify Home+Dock independence during undo
        repository.toggleAppPinnedToDock(comps[0].first, comps[0].second, true)

        val beforeHomeSlots = repository.allAppsFlow.first()
            .filter { it.packageName.startsWith("com.undo.") }
            .associate { it.packageName to it.slotIndex }

        assertTrue(repository.performShuffle("MANUAL"))

        // Reorder dock & pin third app after shuffle
        repository.toggleAppPinnedToDock("com.dock.three", "com.dock.three.Main", true)
        repository.reorderDockApps(
            listOf(
                "com.dock.three" to "com.dock.three.Main",
                comps[0],
                "com.dock.two" to "com.dock.two.Main",
                "com.dock.one" to "com.dock.one.Main"
            )
        )

        val undoResult = repository.undoLastShuffleDetailed()
        assertEquals(UndoShuffleStatus.APPLIED, undoResult.status)

        val afterUndo = repository.allAppsFlow.first().associateBy { it.packageName }
        for ((pkg, expectedSlot) in beforeHomeSlots) {
            assertEquals(expectedSlot, afterUndo[pkg]!!.slotIndex)
        }
        val dockRanks = repository.dockAppsFlow.first().map { it.packageName }
        assertEquals(listOf("com.dock.three", comps[0].first, "com.dock.two", "com.dock.one"), dockRanks)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `9 second shuffle invalidates earlier snapshot and only allows undoing second shuffle`() = runBlocking {
        registerLauncherActivity("com.seq.a", "com.seq.a.Main", "Seq A")
        registerLauncherActivity("com.seq.b", "com.seq.b.Main", "Seq B")
        registerLauncherActivity("com.seq.c", "com.seq.c.Main", "Seq C")
        repository.discoverAndSyncApps()
        repository.setAppPageAndSlot("com.seq.a", "com.seq.a.Main", 0, 0)
        repository.setAppPageAndSlot("com.seq.b", "com.seq.b.Main", 0, 1)
        repository.setAppPageAndSlot("com.seq.c", "com.seq.c.Main", 0, 2)
        repository.toggleAppShuffled("com.seq.a", "com.seq.a.Main", true)
        repository.toggleAppShuffled("com.seq.b", "com.seq.b.Main", true)
        repository.toggleAppShuffled("com.seq.c", "com.seq.c.Main", true)

        assertTrue(repository.performShuffle("MANUAL"))
        val afterFirst = repository.allAppsFlow.first().associate { it.packageName to it.slotIndex }

        assertTrue(repository.performShuffle("MANUAL"))
        assertEquals(1, db.shuffleHistoryDao().getSnapshots().first().size)

        val undoResult = repository.undoLastShuffleDetailed()
        assertEquals(UndoShuffleStatus.APPLIED, undoResult.status)

        val afterUndo = repository.allAppsFlow.first().associate { it.packageName to it.slotIndex }
        assertEquals(afterFirst, afterUndo)

        // Prior snapshot is gone; second undo must return NO_SNAPSHOT rather than reverting shuffle #1
        assertEquals(UndoShuffleStatus.NO_SNAPSHOT, repository.undoLastShuffleDetailed().status)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `10 independent manual Home placement change after shuffle prevents conflicting overwrite`() = runBlocking {
        // Group 1 (slots 0,1) + Group 2 (slot 2 not shuffled)
        registerLauncherActivity("com.man.a", "com.man.a.Main", "Man A")
        registerLauncherActivity("com.man.b", "com.man.b.Main", "Man B")
        registerLauncherActivity("com.man.c", "com.man.c.Main", "Man C")
        repository.discoverAndSyncApps()
        repository.setAppPageAndSlot("com.man.a", "com.man.a.Main", 0, 0)
        repository.setAppPageAndSlot("com.man.b", "com.man.b.Main", 0, 1)
        repository.setAppPageAndSlot("com.man.c", "com.man.c.Main", 0, 5)
        repository.toggleAppShuffled("com.man.a", "com.man.a.Main", true)
        repository.toggleAppShuffled("com.man.b", "com.man.b.Main", true)

        assertTrue(repository.performShuffle("MANUAL"))
        // Now A@1, B@0. User manually moves A to slot 8!
        repository.setAppPageAndSlot("com.man.a", "com.man.a.Main", 0, 8)

        val undoResult = repository.undoLastShuffleDetailed()
        assertEquals(UndoShuffleStatus.PARTIALLY_APPLIED, undoResult.status)
        assertTrue(undoResult.skippedComponents.contains("com.man.a/com.man.a.Main"))

        val afterUndo = repository.allAppsFlow.first().associateBy { it.packageName }
        // Manually moved A keeps slot 8; B restores 0 -> 1 since slot 1 became free
        assertEquals(8, afterUndo["com.man.a"]!!.slotIndex)
        assertEquals(1, afterUndo["com.man.b"]!!.slotIndex)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `11 invalid or legacy v1 snapshot rejects cleanly without altering workspace`() = runBlocking {
        val comps = seedFourApps()
        val before = repository.allAppsFlow.first().associate { it.packageName to it.slotIndex }

        val hId = db.shuffleHistoryDao().insert(
            ShuffleHistoryEntity(timestamp = 1000L, shuffledAppCount = 4, triggerType = "MANUAL")
        )
        // Corrupt JSON
        db.shuffleHistoryDao().insertSnapshot(
            ShuffleLayoutSnapshotEntity(historyId = hId, timestamp = 1000L, snapshotJson = "{not valid json")
        )
        val corruptRes = repository.undoLastShuffleDetailed()
        assertEquals(UndoShuffleStatus.FAILED, corruptRes.status)
        assertFalse(repository.undoLastShuffle())

        // Legacy v1 full-dump schema (no snapshotVersion == 2)
        db.shuffleHistoryDao().clearSnapshots()
        db.shuffleHistoryDao().insertSnapshot(
            ShuffleLayoutSnapshotEntity(
                historyId = hId,
                timestamp = 1001L,
                snapshotJson = """{"workspaceItems":[],"drawerLayout":[]}"""
            )
        )
        val legacyRes = repository.undoLastShuffleDetailed()
        assertEquals(UndoShuffleStatus.FAILED, legacyRes.status)

        val after = repository.allAppsFlow.first().associate { it.packageName to it.slotIndex }
        assertEquals(before, after)
        assertEquals(comps.size, after.size)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `12 failed undo transaction rolls back completely without partial workspace changes`() = runBlocking {
        val comps = seedFourApps()
        assertTrue(repository.performShuffle("MANUAL"))
        val postShuffleSlots = repository.allAppsFlow.first().associate { it.packageName to it.slotIndex }

        // Force a SQLite write failure midway inside undo transaction
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER abort_undo_write
            BEFORE INSERT ON workspace_items
            BEGIN
                SELECT RAISE(ABORT, 'simulated db failure during undo');
            END;
            """.trimIndent()
        )

        val failedResult = repository.undoLastShuffleDetailed()
        assertEquals(UndoShuffleStatus.FAILED, failedResult.status)

        db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS abort_undo_write")

        // Verify entire workspace is untouched and snapshot was also rolled back intact
        val afterFailSlots = repository.allAppsFlow.first().associate { it.packageName to it.slotIndex }
        assertEquals(postShuffleSlots, afterFailSlots)
        assertEquals(1, db.shuffleHistoryDao().getSnapshots().first().size)

        // Removing the trigger lets undo succeed cleanly afterwards!
        val recoveryResult = repository.undoLastShuffleDetailed()
        assertEquals(UndoShuffleStatus.APPLIED, recoveryResult.status)
        assertEquals(comps.size, recoveryResult.restoredHomeCount)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `13 partial undo restores safe components and reports skipped conflicts accurately`() = runBlocking {
        val pApps = listOf("com.part.a", "com.part.b", "com.part.c", "com.part.d", "com.part.e", "com.part.anchor", "com.part.anchor2")
        pApps.forEach { registerLauncherActivity(it, "$it.Main", it) }
        repository.discoverAndSyncApps()
        repository.setAppPageAndSlot("com.part.a", "com.part.a.Main", 0, 0)
        repository.setAppPageAndSlot("com.part.b", "com.part.b.Main", 0, 1)
        repository.setAppPageAndSlot("com.part.c", "com.part.c.Main", 0, 10)
        repository.setAppPageAndSlot("com.part.d", "com.part.d.Main", 0, 11)
        repository.setAppPageAndSlot("com.part.e", "com.part.e.Main", 0, 12)
        repository.setAppPageAndSlot("com.part.anchor", "com.part.anchor.Main", 0, 20)
        repository.setAppPageAndSlot("com.part.anchor2", "com.part.anchor2.Main", 0, 21)

        for (pkg in pApps.take(5)) {
            repository.toggleAppShuffled(pkg, "$pkg.Main", true)
        }
        val before = repository.allAppsFlow.first().associate { it.packageName to it.slotIndex }

        // Rotation on [a@0, b@1, c@10, d@11, e@12] -> a@12, b@0, c@1, d@10, e@11
        assertTrue(repository.performShuffle("MANUAL"))
        val shuffled = repository.allAppsFlow.first().associate { it.packageName to it.slotIndex }

        // 1. Move 'a' (at 12) manually to 30 -> frees slot 12 and disqualifies 'a'
        // 2. Move 'c' (at 1) manually to 31 -> frees slot 1 and disqualifies 'c'
        // 3. Create BlockerFolder at slot 1 -> occupies 'b's origin slot 1, so 'b' is blocked
        repository.setAppPageAndSlot("com.part.a", "com.part.a.Main", 0, 30)
        repository.setAppPageAndSlot("com.part.c", "com.part.c.Main", 0, 31)
        val fId = repository.createFolder(
            name = "BlockerFolder",
            pageIndex = 0,
            slotIndex = before["com.part.b"]!!,
            components = listOf(
                "com.part.anchor" to "com.part.anchor.Main",
                "com.part.anchor2" to "com.part.anchor2.Main"
            )
        )
        assertNotNull(fId)

        val result = repository.undoLastShuffleDetailed()
        assertEquals(UndoShuffleStatus.PARTIALLY_APPLIED, result.status)
        assertEquals(2, result.restoredHomeCount)
        assertTrue(result.skippedComponents.contains("com.part.a/com.part.a.Main"))
        assertTrue(result.skippedComponents.contains("com.part.b/com.part.b.Main"))
        assertTrue(result.skippedComponents.contains("com.part.c/com.part.c.Main"))

        val afterUndo = repository.allAppsFlow.first().associateBy { it.packageName }
        assertEquals(30, afterUndo["com.part.a"]!!.slotIndex)
        assertEquals(shuffled["com.part.b"]!!, afterUndo["com.part.b"]!!.slotIndex)
        assertEquals(31, afterUndo["com.part.c"]!!.slotIndex)
        assertEquals(before["com.part.d"]!!, afterUndo["com.part.d"]!!.slotIndex)
        assertEquals(before["com.part.e"]!!, afterUndo["com.part.e"]!!.slotIndex)
        assertNormalizedWorkspaceValid()
    }
}
