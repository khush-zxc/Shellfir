package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.data.backup.BackupRestoreHelper
import com.example.data.database.ShellfriDatabase
import com.example.data.database.WorkspaceContainers
import com.example.data.database.WorkspaceItemTypes
import com.example.data.preferences.ShellfriPreferences
import com.example.domain.concurrency.WorkspaceMutationCoordinator
import com.example.domain.repository.AppRepository
import com.example.domain.validation.WorkspaceValidator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkspaceConcurrencyTest {

    private lateinit var context: Context
    private lateinit var db: ShellfriDatabase
    private lateinit var preferences: ShellfriPreferences
    private lateinit var coordinator: WorkspaceMutationCoordinator
    private lateinit var repository: AppRepository
    private lateinit var backupRestoreHelper: BackupRestoreHelper
    private val validator = WorkspaceValidator()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ShellfriDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferences = ShellfriPreferences(context)
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
        backupRestoreHelper = BackupRestoreHelper(
            installedAppDao = db.installedAppDao(),
            workspaceDao = db.workspaceDao(),
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

    private suspend fun assertNormalizedWorkspaceValid(dockCount: Int = 5) {
        val result = validator.validateNormalizedWorkspace(
            installedApps = db.installedAppDao().getAllAppsList(),
            pages = db.workspaceDao().getPagesList(),
            workspaceItems = db.workspaceDao().getWorkspaceItemsList(),
            folders = db.workspaceDao().getFoldersList(),
            folderMembers = db.workspaceDao().getFolderMembersList(),
            drawerLayouts = db.workspaceDao().getDrawerLayoutsList(),
            focusApps = db.workspaceDao().getFocusAppsList(),
            dockCount = dockCount,
            drawerShuffleEnabled = preferences.drawerShuffleEnabledFlow.first()
        )
        assertTrue("Workspace invariants violated: ${result.errors.joinToString("; ")}", result.isValid)
    }

    @Test
    fun `simultaneous addPage calls preserve all pages with contiguous indices`() = runBlocking {
        preferences.setPageCount(1)
        val barrier = CompletableDeferred<Unit>()

        val jobs = (0 until 6).map {
            async(Dispatchers.IO) {
                barrier.await()
                repository.addPage()
            }
        }
        barrier.complete(Unit)
        jobs.awaitAll()

        val pages = db.workspaceDao().getPagesList().sortedBy { it.pageIndex }
        assertEquals(7, pages.size)
        assertEquals((0..6).toList(), pages.map { it.pageIndex })
        assertEquals(7, pages.map { it.pageId }.toSet().size)
        assertEquals(7, preferences.pageCountFlow.first())
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `simultaneous addAppToHomeScreen calls produce distinct non-colliding ranks`() = runBlocking {
        val count = 8
        for (i in 0 until count) {
            registerLauncherActivity("com.concurrent.add$i", "com.concurrent.add$i.Main", "App $i")
        }
        repository.discoverAndSyncApps()
        for (i in 0 until count) {
            repository.removeAppFromHomeScreen("com.concurrent.add$i", "com.concurrent.add$i.Main")
        }

        val barrier = CompletableDeferred<Unit>()
        val jobs = (0 until count).map { i ->
            async(Dispatchers.IO) {
                barrier.await()
                repository.addAppToHomeScreen("com.concurrent.add$i", "com.concurrent.add$i.Main", 0)
            }
        }
        barrier.complete(Unit)
        jobs.awaitAll()

        val homeItems = db.workspaceDao().getWorkspaceItemsList()
            .filter { it.containerType == WorkspaceContainers.HOME && it.itemType == WorkspaceItemTypes.APP }
        assertEquals(count, homeItems.size)
        assertEquals(count, homeItems.map { it.rank }.toSet().size)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `concurrent createFolder and app movement keep workspace and folder invariants intact`() = runBlocking {
        for (i in 0 until 6) {
            registerLauncherActivity("com.folder.race$i", "com.folder.race$i.Main", "Race $i")
        }
        repository.discoverAndSyncApps()

        val barrier = CompletableDeferred<Unit>()
        val createJob = async(Dispatchers.IO) {
            barrier.await()
            repository.createFolder(
                name = "RaceFolder",
                pageIndex = 0,
                slotIndex = 0,
                components = listOf(
                    "com.folder.race0" to "com.folder.race0.Main",
                    "com.folder.race1" to "com.folder.race1.Main"
                )
            )
        }
        val moveJobs = listOf(
            async(Dispatchers.IO) {
                barrier.await()
                repository.setAppPageAndSlot("com.folder.race0", "com.folder.race0.Main", 0, 2)
            },
            async(Dispatchers.IO) {
                barrier.await()
                repository.setAppPageAndSlot("com.folder.race2", "com.folder.race2.Main", 0, 0)
            },
            async(Dispatchers.IO) {
                barrier.await()
                repository.setAppPageAndSlot("com.folder.race3", "com.folder.race3.Main", 0, 1)
            }
        )

        barrier.complete(Unit)
        val folderId = createJob.await()
        moveJobs.awaitAll()

        // Also race addAppToFolder & removeAppFromFolder sequentially/concurrently against another move
        val barrier2 = CompletableDeferred<Unit>()
        val j1 = async(Dispatchers.IO) {
            barrier2.await()
            repository.addAppToFolder("com.folder.race4", "com.folder.race4.Main", folderId)
        }
        val j2 = async(Dispatchers.IO) {
            barrier2.await()
            repository.removeAppFromFolder("com.folder.race1", "com.folder.race1.Main", 0)
        }
        val j3 = async(Dispatchers.IO) {
            barrier2.await()
            repository.setAppPageAndSlot("com.folder.race5", "com.folder.race5.Main", 0, 0)
        }
        barrier2.complete(Unit)
        listOf(j1, j2, j3).awaitAll()

        assertNormalizedWorkspaceValid()
        assertFalse(coordinator.isLocked)
    }

    @Test
    fun `discoverAndSyncApps runs PackageManager IPC lock-free while addPage executes then reconciles cleanly`() = runBlocking {
        registerLauncherActivity("com.sync.one", "com.sync.one.Main", "Sync One")
        preferences.setPageCount(1)

        val discoveryEnteredPm = CountDownLatch(1)
        val allowDiscoveryToProceed = CountDownLatch(1)

        val hookingRepo = AppRepository(
            context = context,
            installedAppDao = db.installedAppDao(),
            workspaceDao = db.workspaceDao(),
            shuffleHistoryDao = db.shuffleHistoryDao(),
            preferences = preferences,
            database = db,
            mutationCoordinator = coordinator,
            onPackageManagerDiscoveryQuery = {
                discoveryEnteredPm.countDown()
                allowDiscoveryToProceed.await(5, TimeUnit.SECONDS)
            }
        )

        val discoverJob = async(Dispatchers.IO) {
            hookingRepo.discoverAndSyncApps()
        }

        // Wait until discoverAndSyncApps is paused inside lock-free PackageManager IPC
        assertTrue(discoveryEnteredPm.await(5, TimeUnit.SECONDS))
        assertFalse("Mutation lock must NOT be held during PackageManager IPC", coordinator.isLocked)

        // addPage must succeed immediately without waiting on discoverAndSyncApps
        withTimeout(3000L) {
            repository.addPage()
        }
        val pagesMidway = db.workspaceDao().getPagesList().sortedBy { it.pageIndex }
        assertEquals(2, pagesMidway.size)

        // Now release PackageManager phase so reconciliation runs
        allowDiscoveryToProceed.countDown()
        discoverJob.await()

        val pagesAfter = db.workspaceDao().getPagesList().sortedBy { it.pageIndex }
        assertEquals(2, pagesAfter.size)
        assertEquals(listOf(0, 1), pagesAfter.map { it.pageIndex })
        assertTrue(db.installedAppDao().getAllAppsList().any { it.packageName == "com.sync.one" && it.isInstalled })
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `concurrent performShuffle and Home placement changes preserve valid workspace and snapshot`() = runBlocking {
        for (i in 0 until 6) {
            registerLauncherActivity("com.shuf.race$i", "com.shuf.race$i.Main", "Shuf $i")
        }
        repository.discoverAndSyncApps()
        for (i in 0 until 5) {
            repository.toggleAppShuffled("com.shuf.race$i", "com.shuf.race$i.Main", true)
        }

        val barrier = CompletableDeferred<Unit>()
        val shuffleJob = async(Dispatchers.IO) {
            barrier.await()
            repository.performShuffle("MANUAL")
        }
        val moveJob = async(Dispatchers.IO) {
            barrier.await()
            repository.setAppPageAndSlot("com.shuf.race5", "com.shuf.race5.Main", 0, 2)
        }
        val removeJob = async(Dispatchers.IO) {
            barrier.await()
            repository.removeAppFromHomeScreen("com.shuf.race4", "com.shuf.race4.Main")
        }
        barrier.complete(Unit)
        shuffleJob.await()
        moveJob.await()
        removeJob.await()

        assertNormalizedWorkspaceValid()
        assertFalse(coordinator.isLocked)
    }

    @Test
    fun `concurrent reorderDockApps and toggleAppPinnedToDock keep valid contiguous dock ranks`() = runBlocking {
        preferences.setDockCount(5)
        for (i in 0 until 5) {
            registerLauncherActivity("com.dock.race$i", "com.dock.race$i.Main", "Dock $i")
        }
        repository.discoverAndSyncApps()
        repository.toggleAppPinnedToDock("com.dock.race0", "com.dock.race0.Main", true)
        repository.toggleAppPinnedToDock("com.dock.race1", "com.dock.race1.Main", true)
        repository.toggleAppPinnedToDock("com.dock.race2", "com.dock.race2.Main", true)

        val barrier = CompletableDeferred<Unit>()
        val pinJob = async(Dispatchers.IO) {
            barrier.await()
            repository.toggleAppPinnedToDock("com.dock.race3", "com.dock.race3.Main", true)
        }
        val unpinJob = async(Dispatchers.IO) {
            barrier.await()
            repository.toggleAppPinnedToDock("com.dock.race1", "com.dock.race1.Main", false)
        }
        val reorderJob = async(Dispatchers.IO) {
            barrier.await()
            repository.reorderDockApps(
                listOf(
                    "com.dock.race2" to "com.dock.race2.Main",
                    "com.dock.race0" to "com.dock.race0.Main"
                )
            )
        }
        barrier.complete(Unit)
        listOf(pinJob, unpinJob, reorderJob).awaitAll()

        val dockApps = repository.dockAppsFlow.first()
        assertTrue(dockApps.size in 2..5)
        assertEquals(dockApps.size, dockApps.map { it.dockRank }.toSet().size)
        assertNormalizedWorkspaceValid(dockCount = 5)
    }

    @Test
    fun `lock is released after thrown exception inside coordinator and Room transaction`() = runBlocking {
        preferences.setPageCount(1)
        val thrownDirect = runCatching {
            coordinator.withMutationLock {
                db.withTransaction {
                    db.workspaceDao().insertPage(
                        com.example.data.database.WorkspacePageEntity(
                            pageId = "rolled_back_page",
                            pageIndex = 99,
                            isDefault = false
                        )
                    )
                    throw IllegalStateException("simulated db failure")
                }
            }
        }.exceptionOrNull()
        assertNotNull(thrownDirect)
        assertFalse(coordinator.isLocked)
        assertTrue(db.workspaceDao().getPagesList().none { it.pageId == "rolled_back_page" })

        // Subsequent repository mutations must proceed cleanly
        withTimeout(3000L) {
            repository.addPage()
        }
        assertEquals(2, db.workspaceDao().getPagesList().size)
        assertFalse(coordinator.isLocked)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `lock is released when holding or waiting coroutine is cancelled`() = runBlocking {
        preferences.setPageCount(1)
        val lockAcquired = CompletableDeferred<Unit>()
        val allowFinish = CompletableDeferred<Unit>()

        val holderJob = launch(Dispatchers.IO) {
            coordinator.withMutationLock {
                lockAcquired.complete(Unit)
                allowFinish.await()
            }
        }
        lockAcquired.await()
        assertTrue(coordinator.isLocked)

        val waiterJob = launch(Dispatchers.IO) {
            repository.addPage()
        }

        // Cancel waiter then holder while suspended
        waiterJob.cancelAndJoin()
        holderJob.cancelAndJoin()

        assertFalse("Coordinator lock must be released after cancellation", coordinator.isLocked)

        // Next caller must succeed without hanging
        withTimeout(3000L) {
            repository.addPage()
        }
        assertEquals(2, db.workspaceDao().getPagesList().size)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `nested paths backup and reentrancy guard never deadlock`() = runBlocking {
        registerLauncherActivity("com.deadlock.app1", "com.deadlock.app1.Main", "App 1")
        registerLauncherActivity("com.deadlock.app2", "com.deadlock.app2.Main", "App 2")
        repository.discoverAndSyncApps()
        repository.toggleAppShuffled("com.deadlock.app1", true)
        repository.toggleAppFixed("com.deadlock.app2", false)
        repository.setAppPageAndSlot("com.deadlock.app2", 0, 3)

        // 1. Re-entrant lock acquisition throws IllegalStateException immediately instead of deadlocking
        val reentrantErr = runCatching {
            withTimeout(2000L) {
                coordinator.withMutationLock {
                    coordinator.withMutationLock {
                        "unreachable"
                    }
                }
            }
        }.exceptionOrNull()
        assertTrue(reentrantErr is IllegalStateException)
        assertFalse(coordinator.isLocked)

        // 2. Scheduled shuffle triggers performShuffleLocked inside coordinator without deadlocking
        preferences.setShuffleEnabled(true)
        preferences.setLastShuffleTime(0L)
        withTimeout(3000L) {
            assertTrue(repository.checkAndTriggerScheduledShuffle())
        }

        // 3. Shared-coordinator BackupRestoreHelper export + restore complete without deadlocking
        withTimeout(5000L) {
            val json = backupRestoreHelper.generateBackupJson()
            val restored = backupRestoreHelper.restoreBackup(json)
            assertTrue(restored.isSuccess)
        }
        assertFalse(coordinator.isLocked)
        assertNormalizedWorkspaceValid()
    }
}
