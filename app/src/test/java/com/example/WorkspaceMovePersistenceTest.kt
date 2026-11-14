package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.DrawerLayoutEntity
import com.example.data.database.FocusAppEntity
import com.example.data.database.InstalledAppEntity
import com.example.data.database.ShellfriDatabase
import com.example.data.database.WorkspaceContainers
import com.example.data.database.WorkspaceItemEntity
import com.example.data.database.WorkspaceItemTypes
import com.example.data.database.WorkspacePageEntity
import com.example.data.preferences.ShellfriPreferences
import com.example.domain.repository.AppRepository
import com.example.domain.validation.WorkspaceValidator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkspaceMovePersistenceTest {

    private lateinit var context: Context
    private lateinit var db: ShellfriDatabase
    private lateinit var preferences: ShellfriPreferences
    private lateinit var repository: AppRepository
    private val validator = WorkspaceValidator()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ShellfriDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferences = ShellfriPreferences(context)
        repository = AppRepository(
            context = context,
            installedAppDao = db.installedAppDao(),
            workspaceDao = db.workspaceDao(),
            shuffleHistoryDao = db.shuffleHistoryDao(),
            preferences = preferences,
            database = db
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
            IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        )
        context.packageManager.setComponentEnabledSetting(
            component,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    private suspend fun seedWorkspace(
        pageCount: Int = 2,
        apps: List<Triple<String, Int, Int>> // pkg to (pageIndex, slotIndex)
    ) {
        val pages = (0 until pageCount).map { idx ->
            WorkspacePageEntity(
                pageId = "page_$idx",
                pageIndex = idx,
                isDefault = idx == 0,
                createdAt = 1000L + idx
            )
        }
        db.workspaceDao().upsertPages(pages)
        preferences.setPageCount(pageCount)

        val installed = apps.map { (pkg, _, _) ->
            val act = "$pkg.Main"
            registerLauncherActivity(pkg, act, pkg)
            InstalledAppEntity(
                packageName = pkg,
                activityName = act,
                label = pkg,
                isInstalled = true
            )
        }
        db.installedAppDao().upsertApps(installed)

        val homeItems = apps.map { (pkg, page, slot) ->
            val act = "$pkg.Main"
            WorkspaceItemEntity(
                itemId = AppRepository.homeItemId(pkg, act),
                itemType = WorkspaceItemTypes.APP,
                containerType = WorkspaceContainers.HOME,
                pageId = "page_$page",
                packageName = pkg,
                activityName = act,
                rank = slot
            )
        }
        db.workspaceDao().upsertWorkspaceItems(homeItems)
        db.workspaceDao().upsertDrawerLayouts(apps.map { (pkg, _, _) -> DrawerLayoutEntity(pkg, "$pkg.Main") })
        db.workspaceDao().upsertFocusApps(apps.map { (pkg, _, _) -> FocusAppEntity(pkg, "$pkg.Main") })
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
            dockCount = preferences.dockCountFlow.first()
        )
        assertTrue("Normalized workspace invalid: ${result.errors}", result.isValid)
    }

    @Test
    fun `successful cross-page and collision moves persist and keep dock placement intact`() = runBlocking {
        seedWorkspace(
            pageCount = 2,
            apps = listOf(
                Triple("com.alpha", 0, 0),
                Triple("com.beta", 0, 1),
                Triple("com.gamma", 1, 2)
            )
        )
        repository.toggleAppPinnedToDock("com.alpha", "com.alpha.Main", true)

        val move1 = repository.moveAppOnHomeScreen(
            packageName = "com.alpha",
            activityName = "com.alpha.Main",
            targetPageIndex = 1,
            targetSlotIndex = 2,
            gridColumns = 4,
            preserveEmptySpaces = true
        )
        assertTrue("Expected move1 to succeed: ${move1.failureReason}", move1.success)
        assertNormalizedWorkspaceValid()

        val appsAfter = repository.allAppsFlow.first().associateBy { it.packageName }
        val alpha = appsAfter["com.alpha"]!!
        assertEquals(1, alpha.pageIndex)
        assertEquals(2, alpha.slotIndex)
        assertTrue(alpha.isPinnedToDock)
        assertEquals(0, alpha.dockRank)

        // com.gamma shifted to 3 without overwriting
        assertEquals(1, appsAfter["com.gamma"]!!.pageIndex)
        assertEquals(3, appsAfter["com.gamma"]!!.slotIndex)
        // com.beta preserved its slot on page 0
        assertEquals(0, appsAfter["com.beta"]!!.pageIndex)
        assertEquals(1, appsAfter["com.beta"]!!.slotIndex)
    }

    @Test
    fun `failed database write rolls back move transaction completely`() = runBlocking {
        seedWorkspace(
            pageCount = 2,
            apps = listOf(
                Triple("com.alpha", 0, 0),
                Triple("com.beta", 0, 1)
            )
        )
        val before = repository.allAppsFlow.first().associate { it.packageName to (it.pageIndex to it.slotIndex) }

        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER abort_move_write
            BEFORE INSERT ON workspace_items
            BEGIN
                SELECT RAISE(ABORT, 'simulated sqlite write failure');
            END;
            """.trimIndent()
        )

        val failedMove = repository.moveAppOnHomeScreen(
            packageName = "com.alpha",
            activityName = "com.alpha.Main",
            targetPageIndex = 0,
            targetSlotIndex = 1,
            gridColumns = 4,
            preserveEmptySpaces = true
        )
        assertFalse(failedMove.success)
        assertNotNull(failedMove.failureReason)

        db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS abort_move_write")

        val afterFail = repository.allAppsFlow.first().associate { it.packageName to (it.pageIndex to it.slotIndex) }
        assertEquals(before, afterFail)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `page deletion while drag pending fails cleanly without corrupting workspace`() = runBlocking {
        seedWorkspace(
            pageCount = 2,
            apps = listOf(
                Triple("com.alpha", 0, 0),
                Triple("com.beta", 1, 0)
            )
        )

        // Delete page 1 before move completes
        assertTrue(repository.removePage(1))

        val result = repository.moveAppOnHomeScreen(
            packageName = "com.alpha",
            activityName = "com.alpha.Main",
            targetPageIndex = 1,
            targetSlotIndex = 0,
            gridColumns = 4
        )
        assertFalse(result.success)
        assertNormalizedWorkspaceValid()

        val alpha = repository.allAppsFlow.first().first { it.packageName == "com.alpha" }
        assertEquals(0, alpha.pageIndex)
        assertEquals(0, alpha.slotIndex)
    }

    @Test
    fun `app uninstall while drag pending fails cleanly without corrupting workspace`() = runBlocking {
        seedWorkspace(
            pageCount = 1,
            apps = listOf(
                Triple("com.alpha", 0, 0),
                Triple("com.beta", 0, 1)
            )
        )

        // Disable launcher component and sync so com.alpha is archived as uninstalled
        context.packageManager.setComponentEnabledSetting(
            ComponentName("com.alpha", "com.alpha.Main"),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        repository.discoverAndSyncApps()

        val result = repository.moveAppOnHomeScreen(
            packageName = "com.alpha",
            activityName = "com.alpha.Main",
            targetPageIndex = 0,
            targetSlotIndex = 5,
            gridColumns = 4
        )
        assertFalse(result.success)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `fixed application cannot be moved or displaced in persistence`() = runBlocking {
        seedWorkspace(
            pageCount = 1,
            apps = listOf(
                Triple("com.fixed", 0, 0),
                Triple("com.mover", 0, 1)
            )
        )
        repository.toggleAppFixed("com.fixed", "com.fixed.Main", true)

        val moveFixed = repository.moveAppOnHomeScreen("com.fixed", "com.fixed.Main", 0, 4, gridColumns = 4)
        assertFalse(moveFixed.success)

        val dropOnFixed = repository.moveAppOnHomeScreen("com.mover", "com.mover.Main", 0, 0, gridColumns = 4)
        assertFalse(dropOnFixed.success)

        val apps = repository.allAppsFlow.first().associateBy { it.packageName }
        assertEquals(0, apps["com.fixed"]!!.slotIndex)
        assertEquals(1, apps["com.mover"]!!.slotIndex)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `concurrent move and shuffle serialize safely without duplicate ranks`() = runBlocking {
        seedWorkspace(
            pageCount = 2,
            apps = listOf(
                Triple("com.s1", 0, 0),
                Triple("com.s2", 0, 1),
                Triple("com.s3", 0, 2),
                Triple("com.m1", 0, 3),
                Triple("com.m2", 1, 0)
            )
        )
        repository.toggleAppShuffled("com.s1", "com.s1.Main", true)
        repository.toggleAppShuffled("com.s2", "com.s2.Main", true)
        repository.toggleAppShuffled("com.s3", "com.s3.Main", true)

        coroutineScope {
            val jobs = listOf(
                async { repository.performShuffle("MANUAL") },
                async { repository.moveAppOnHomeScreen("com.m1", "com.m1.Main", 1, 4, gridColumns = 4) },
                async { repository.moveAppOnHomeScreen("com.m2", "com.m2.Main", 0, 5, gridColumns = 4) },
                async { repository.performShuffle("MANUAL") }
            )
            jobs.awaitAll()
        }

        assertNormalizedWorkspaceValid()
    }
}
