package com.example

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.backup.BackupRestoreHelper
import com.example.data.database.DrawerLayoutEntity
import com.example.data.database.FocusAppEntity
import com.example.data.database.InstalledAppEntity
import com.example.data.database.ShellfriDatabase
import com.example.data.database.WorkspaceContainers
import com.example.data.database.WorkspaceItemEntity
import com.example.data.database.WorkspaceItemTypes
import com.example.data.database.WorkspacePageEntity
import com.example.data.preferences.ShellfriPreferences
import com.example.domain.model.AppItem
import com.example.domain.repository.AppRepository
import com.example.domain.repository.IconRepository
import com.example.domain.validation.WorkspaceValidator
import com.example.ui.home.HomeUiState
import com.example.ui.home.NormalHomeScreen
import com.example.ui.theme.ShellfriTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomeDockIndependenceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: ShellfriDatabase
    private lateinit var preferences: ShellfriPreferences
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
        repository = AppRepository(
            context = context,
            installedAppDao = db.installedAppDao(),
            workspaceDao = db.workspaceDao(),
            shuffleHistoryDao = db.shuffleHistoryDao(),
            preferences = preferences,
            database = db
        )
        backupRestoreHelper = BackupRestoreHelper(
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

    private suspend fun seedAppsAndPages(
        pageCount: Int = 2,
        apps: List<Pair<String, String>> = listOf(
            "com.alpha" to "com.alpha.Main",
            "com.beta" to "com.beta.Main",
            "com.gamma" to "com.gamma.Main",
            "com.suite" to "com.suite.Primary",
            "com.suite" to "com.suite.Secondary"
        )
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

        val installed = apps.mapIndexed { idx, (pkg, act) ->
            InstalledAppEntity(
                packageName = pkg,
                activityName = act,
                label = "App $idx",
                isInstalled = true
            )
        }
        db.installedAppDao().upsertApps(installed)

        val homeItems = apps.mapIndexed { idx, (pkg, act) ->
            val pageId = if (idx < 3) "page_0" else "page_${1.coerceAtMost(pageCount - 1)}"
            val rank = if (idx < 3) idx else idx - 3
            WorkspaceItemEntity(
                itemId = AppRepository.homeItemId(pkg, act),
                itemType = WorkspaceItemTypes.APP,
                containerType = WorkspaceContainers.HOME,
                pageId = pageId,
                packageName = pkg,
                activityName = act,
                rank = rank
            )
        }
        db.workspaceDao().upsertWorkspaceItems(homeItems)
        db.workspaceDao().upsertDrawerLayouts(apps.map { (pkg, act) -> DrawerLayoutEntity(pkg, act) })
        db.workspaceDao().upsertFocusApps(apps.map { (pkg, act) -> FocusAppEntity(pkg, act) })
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
    fun `pinning and unpinning an app preserves its original home page and rank`() = runBlocking {
        seedAppsAndPages(pageCount = 2)
        // Put com.suite/Secondary on page 1, rank 4
        repository.setAppPageAndSlot("com.suite", "com.suite.Secondary", pageIndex = 1, slotIndex = 4)

        repository.toggleAppPinnedToDock("com.suite", "com.suite.Secondary", true)
        assertNormalizedWorkspaceValid()

        val pinnedState = repository.allAppsFlow.first().first {
            it.packageName == "com.suite" && it.activityName == "com.suite.Secondary"
        }
        assertTrue(pinnedState.isOnHomeScreen)
        assertTrue(pinnedState.isPinnedToDock)
        assertEquals(1, pinnedState.pageIndex)
        assertEquals(4, pinnedState.slotIndex)
        assertEquals(0, pinnedState.dockRank)
        assertEquals(AppRepository.homeItemId("com.suite", "com.suite.Secondary"), pinnedState.homeItemId)
        assertEquals(AppRepository.dockItemId("com.suite", "com.suite.Secondary"), pinnedState.dockItemId)

        // Ensure sibling activity from the same package was NOT pinned
        val sibling = repository.allAppsFlow.first().first {
            it.packageName == "com.suite" && it.activityName == "com.suite.Primary"
        }
        assertFalse(sibling.isPinnedToDock)
        assertTrue(sibling.isOnHomeScreen)

        // Unpinning removes only DOCK placement and keeps HOME page 1 rank 4 intact
        repository.toggleAppPinnedToDock("com.suite", "com.suite.Secondary", false)
        assertNormalizedWorkspaceValid()

        val unpinnedState = repository.allAppsFlow.first().first {
            it.packageName == "com.suite" && it.activityName == "com.suite.Secondary"
        }
        assertTrue(unpinnedState.isOnHomeScreen)
        assertFalse(unpinnedState.isPinnedToDock)
        assertEquals(1, unpinnedState.pageIndex)
        assertEquals(4, unpinnedState.slotIndex)
        assertEquals(-1, unpinnedState.dockRank)
        assertNotNull(unpinnedState.homeItemId)
        assertNull(unpinnedState.dockItemId)
    }

    @Test
    fun `app can exist on Home and Dock simultaneously and removing from Home leaves Dock intact`() = runBlocking {
        seedAppsAndPages()
        repository.toggleAppPinnedToDock("com.alpha", "com.alpha.Main", true)
        repository.toggleAppPinnedToDock("com.beta", "com.beta.Main", true)

        val dualApp = repository.allAppsFlow.first().first { it.packageName == "com.alpha" }
        assertTrue(dualApp.isOnHomeScreen)
        assertTrue(dualApp.isPinnedToDock)
        assertEquals(0, dualApp.pageIndex)
        assertEquals(0, dualApp.slotIndex)
        assertEquals(0, dualApp.dockRank)

        // Remove from Home -> Dock remains intact
        repository.removeAppFromHomeScreen("com.alpha", "com.alpha.Main")
        assertNormalizedWorkspaceValid()

        val dockOnlyApp = repository.allAppsFlow.first().first { it.packageName == "com.alpha" }
        assertFalse(dockOnlyApp.isOnHomeScreen)
        assertNull(dockOnlyApp.homeItemId)
        assertEquals(-1, dockOnlyApp.slotIndex)
        assertTrue(dockOnlyApp.isPinnedToDock)
        assertEquals(0, dockOnlyApp.dockRank)
        assertEquals(AppRepository.dockItemId("com.alpha", "com.alpha.Main"), dockOnlyApp.dockItemId)

        // Unpinning a Dock-only app removes DOCK without fabricating a new HOME item
        repository.toggleAppPinnedToDock("com.alpha", "com.alpha.Main", false)
        assertNormalizedWorkspaceValid()

        val drawerOnlyApp = repository.allAppsFlow.first().first { it.packageName == "com.alpha" }
        assertFalse(drawerOnlyApp.isOnHomeScreen)
        assertFalse(drawerOnlyApp.isPinnedToDock)
        assertNull(drawerOnlyApp.homeItemId)
        assertNull(drawerOnlyApp.dockItemId)

        // Remaining dock app compacted cleanly to dockRank 0 while preserving its Home placement
        val betaApp = repository.allAppsFlow.first().first { it.packageName == "com.beta" }
        assertTrue(betaApp.isOnHomeScreen)
        assertEquals(1, betaApp.slotIndex)
        assertTrue(betaApp.isPinnedToDock)
        assertEquals(0, betaApp.dockRank)
    }

    @Test
    fun `moving a Home app does not alter its Dock rank and adding Dock app to Home preserves Dock`() = runBlocking {
        seedAppsAndPages(pageCount = 2)
        repository.toggleAppPinnedToDock("com.alpha", "com.alpha.Main", true) // dockRank 0
        repository.toggleAppPinnedToDock("com.beta", "com.beta.Main", true)  // dockRank 1

        // Move com.beta on Home to page 1, slot 7
        repository.setAppPageAndSlot("com.beta", "com.beta.Main", pageIndex = 1, slotIndex = 7)
        assertNormalizedWorkspaceValid()

        val movedBeta = repository.allAppsFlow.first().first { it.packageName == "com.beta" }
        assertTrue(movedBeta.isOnHomeScreen)
        assertEquals(1, movedBeta.pageIndex)
        assertEquals(7, movedBeta.slotIndex)
        assertTrue(movedBeta.isPinnedToDock)
        assertEquals(1, movedBeta.dockRank)

        // Remove from Home, then addAppToHomeScreen back -> Dock still rank 1
        repository.removeAppFromHomeScreen("com.beta", "com.beta.Main")
        repository.addAppToHomeScreen("com.beta", "com.beta.Main", pageIndex = 0)
        assertNormalizedWorkspaceValid()

        val reAddedBeta = repository.allAppsFlow.first().first { it.packageName == "com.beta" }
        assertTrue(reAddedBeta.isOnHomeScreen)
        assertEquals(0, reAddedBeta.pageIndex)
        assertTrue(reAddedBeta.isPinnedToDock)
        assertEquals(1, reAddedBeta.dockRank)
    }

    @Test
    fun `folder operations preserve Dock placement`() = runBlocking {
        seedAppsAndPages(pageCount = 2)
        repository.toggleAppPinnedToDock("com.alpha", "com.alpha.Main", true) // dockRank 0
        repository.toggleAppPinnedToDock("com.beta", "com.beta.Main", true)  // dockRank 1

        // 1. createFolder includes com.alpha + com.gamma -> removes HOME items, preserves DOCK item
        val folderId = repository.createFolder(
            name = "Social",
            pageIndex = 0,
            slotIndex = 0,
            components = listOf(
                "com.alpha" to "com.alpha.Main",
                "com.gamma" to "com.gamma.Main"
            )
        )
        assertNormalizedWorkspaceValid()

        val alphaInFolder = repository.allAppsFlow.first().first { it.packageName == "com.alpha" }
        assertEquals(folderId, alphaInFolder.folderId)
        assertFalse(alphaInFolder.isOnHomeScreen)
        assertNull(alphaInFolder.homeItemId)
        assertTrue(alphaInFolder.isPinnedToDock)
        assertEquals(0, alphaInFolder.dockRank)
        assertEquals(AppRepository.dockItemId("com.alpha", "com.alpha.Main"), alphaInFolder.dockItemId)

        // 2. addAppToFolder for com.beta -> removes HOME item, preserves DOCK item
        repository.addAppToFolder("com.beta", "com.beta.Main", folderId)
        assertNormalizedWorkspaceValid()

        val betaInFolder = repository.allAppsFlow.first().first { it.packageName == "com.beta" }
        assertEquals(folderId, betaInFolder.folderId)
        assertFalse(betaInFolder.isOnHomeScreen)
        assertTrue(betaInFolder.isPinnedToDock)
        assertEquals(1, betaInFolder.dockRank)

        // 3. removeAppFromFolder for com.alpha -> restores HOME item, folder retains 2 members, preserves DOCK item
        repository.removeAppFromFolder("com.alpha", "com.alpha.Main", targetPage = 1)
        assertNormalizedWorkspaceValid()

        val alphaRemoved = repository.allAppsFlow.first().first { it.packageName == "com.alpha" }
        assertNull(alphaRemoved.folderId)
        assertTrue(alphaRemoved.isOnHomeScreen)
        assertEquals(1, alphaRemoved.pageIndex)
        assertTrue(alphaRemoved.isPinnedToDock)
        assertEquals(0, alphaRemoved.dockRank)

        // 4. deleteFolder -> unpacks com.beta and com.gamma to HOME, preserves DOCK item
        repository.deleteFolder(folderId)
        assertNormalizedWorkspaceValid()

        val betaUnpacked = repository.allAppsFlow.first().first { it.packageName == "com.beta" }
        assertNull(betaUnpacked.folderId)
        assertTrue(betaUnpacked.isOnHomeScreen)
        assertTrue(betaUnpacked.isPinnedToDock)
        assertEquals(1, betaUnpacked.dockRank)
    }

    @Test
    fun `dock reordering changes only dock ranks and does not affect Home positions`() = runBlocking {
        seedAppsAndPages(pageCount = 2)
        repository.setAppPageAndSlot("com.gamma", "com.gamma.Main", pageIndex = 1, slotIndex = 8)
        repository.setAppPageAndSlot("com.beta", "com.beta.Main", pageIndex = 1, slotIndex = 5)
        repository.setAppPageAndSlot("com.alpha", "com.alpha.Main", pageIndex = 0, slotIndex = 2)

        repository.toggleAppPinnedToDock("com.alpha", "com.alpha.Main", true)
        repository.toggleAppPinnedToDock("com.beta", "com.beta.Main", true)
        repository.toggleAppPinnedToDock("com.gamma", "com.gamma.Main", true)

        repository.reorderDockApps(
            listOf(
                "com.gamma" to "com.gamma.Main",
                "com.alpha" to "com.alpha.Main",
                "com.beta" to "com.beta.Main"
            )
        )
        assertNormalizedWorkspaceValid()

        val dockOrder = repository.dockAppsFlow.first().map { it.packageName to it.dockRank }
        assertEquals(
            listOf("com.gamma" to 0, "com.alpha" to 1, "com.beta" to 2),
            dockOrder
        )

        val appsByPkg = repository.allAppsFlow.first().associateBy { it.componentKey }
        assertEquals(0, appsByPkg["com.alpha/com.alpha.Main"]!!.pageIndex)
        assertEquals(2, appsByPkg["com.alpha/com.alpha.Main"]!!.slotIndex)
        assertEquals(1, appsByPkg["com.beta/com.beta.Main"]!!.pageIndex)
        assertEquals(5, appsByPkg["com.beta/com.beta.Main"]!!.slotIndex)
        assertEquals(1, appsByPkg["com.gamma/com.gamma.Main"]!!.pageIndex)
        assertEquals(8, appsByPkg["com.gamma/com.gamma.Main"]!!.slotIndex)
    }

    @Test
    fun `backup and restore preserve dual Home plus Dock and Folder plus Dock placements`() = runBlocking {
        seedAppsAndPages(pageCount = 2)
        repository.setAppPageAndSlot("com.alpha", "com.alpha.Main", pageIndex = 1, slotIndex = 6)
        repository.toggleAppPinnedToDock("com.alpha", "com.alpha.Main", true)
        repository.toggleAppPinnedToDock("com.beta", "com.beta.Main", true)
        val folderId = repository.createFolder(
            name = "Tools",
            pageIndex = 0,
            slotIndex = 3,
            components = listOf(
                "com.beta" to "com.beta.Main",
                "com.gamma" to "com.gamma.Main"
            )
        )

        val backupJson = backupRestoreHelper.generateBackupJson()

        // Mutate workspace completely before restoring
        db.workspaceDao().deleteAllFolderMembers()
        db.workspaceDao().deleteAllWorkspaceItems()
        db.workspaceDao().deleteAllFolders()
        repository.addAppToHomeScreen("com.suite", "com.suite.Primary", pageIndex = 0)

        val restoreResult = backupRestoreHelper.restoreBackup(backupJson)
        assertTrue("Restore failed: ${restoreResult.exceptionOrNull()}", restoreResult.isSuccess)
        assertNormalizedWorkspaceValid()

        val restored = repository.allAppsFlow.first().associateBy { it.componentKey }
        val alpha = restored["com.alpha/com.alpha.Main"]!!
        assertTrue(alpha.isOnHomeScreen)
        assertTrue(alpha.isPinnedToDock)
        assertEquals(1, alpha.pageIndex)
        assertEquals(6, alpha.slotIndex)
        assertEquals(0, alpha.dockRank)
        assertEquals(AppRepository.homeItemId("com.alpha", "com.alpha.Main"), alpha.homeItemId)
        assertEquals(AppRepository.dockItemId("com.alpha", "com.alpha.Main"), alpha.dockItemId)

        val beta = restored["com.beta/com.beta.Main"]!!
        assertEquals(folderId, beta.folderId)
        assertFalse(beta.isOnHomeScreen)
        assertTrue(beta.isPinnedToDock)
        assertEquals(1, beta.dockRank)

        val gamma = restored["com.gamma/com.gamma.Main"]!!
        assertEquals(folderId, gamma.folderId)
        assertFalse(gamma.isOnHomeScreen)
    }

    @Test
    fun `workspace projection exposes homeItemId dockItemId and independent flags`() {
        val pages = listOf(
            WorkspacePageEntity(pageId = "p0", pageIndex = 0, isDefault = true),
            WorkspacePageEntity(pageId = "p1", pageIndex = 1, isDefault = false)
        )
        val installed = listOf(
            InstalledAppEntity("com.dual", "com.dual.Act", "Dual", isInstalled = true),
            InstalledAppEntity("com.home", "com.home.Act", "HomeOnly", isInstalled = true),
            InstalledAppEntity("com.dock", "com.dock.Act", "DockOnly", isInstalled = true)
        )
        val items = listOf(
            WorkspaceItemEntity("home_dual", WorkspaceItemTypes.APP, WorkspaceContainers.HOME, "p1", "com.dual", "com.dual.Act", rank = 9),
            WorkspaceItemEntity("dock_dual", WorkspaceItemTypes.APP, WorkspaceContainers.DOCK, null, "com.dual", "com.dual.Act", rank = 2),
            WorkspaceItemEntity("home_only", WorkspaceItemTypes.APP, WorkspaceContainers.HOME, "p0", "com.home", "com.home.Act", rank = 1),
            WorkspaceItemEntity("dock_only", WorkspaceItemTypes.APP, WorkspaceContainers.DOCK, null, "com.dock", "com.dock.Act", rank = 0)
        )

        val projected = AppRepository.projectAppItems(installed, pages, items, emptyList(), emptyList(), emptyList())
            .associateBy { it.componentKey }

        val dual = projected["com.dual/com.dual.Act"]!!
        assertTrue(dual.isOnHomeScreen)
        assertTrue(dual.isPinnedToDock)
        assertEquals("home_dual", dual.homeItemId)
        assertEquals("dock_dual", dual.dockItemId)
        assertEquals(1, dual.pageIndex)
        assertEquals(9, dual.slotIndex)
        assertEquals(2, dual.dockRank)

        val homeOnly = projected["com.home/com.home.Act"]!!
        assertTrue(homeOnly.isOnHomeScreen)
        assertFalse(homeOnly.isPinnedToDock)
        assertEquals("home_only", homeOnly.homeItemId)
        assertNull(homeOnly.dockItemId)

        val dockOnly = projected["com.dock/com.dock.Act"]!!
        assertFalse(dockOnly.isOnHomeScreen)
        assertTrue(dockOnly.isPinnedToDock)
        assertNull(dockOnly.homeItemId)
        assertEquals("dock_only", dockOnly.dockItemId)
    }

    @Test
    fun `NormalHomeScreen and AppDock simultaneously render an app with both placements`() {
        val dualApp = AppItem(
            packageName = "com.dual",
            activityName = "com.dual.Main",
            label = "DualApp",
            slotIndex = 0,
            pageIndex = 0,
            isPinnedToDock = true,
            isOnHomeScreen = true,
            dockRank = 0,
            homeItemId = "home_com.dual_com.dual.Main",
            dockItemId = "dock_com.dual_com.dual.Main"
        )
        val uiState = HomeUiState(
            allApps = listOf(dualApp),
            homeApps = listOf(dualApp),
            dockApps = listOf(dualApp),
            pageCount = 1,
            defaultPageIndex = 0,
            gridColumns = 4
        )
        val iconRepo = IconRepository(context)

        composeTestRule.setContent {
            ShellfriTheme {
                NormalHomeScreen(
                    uiState = uiState,
                    iconRepository = iconRepo,
                    onAppClick = {},
                    onAppLongClick = {},
                    onFolderClick = {},
                    onFolderLongClick = {},
                    onModeToggle = {},
                    onManualShuffle = {},
                    onOpenDrawer = {},
                    onOpenSettings = {},
                    onToggleEditMode = {},
                    onAddPage = {},
                    onRemovePage = {},
                    onSetGridColumns = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("home_grid_page_0").assertExists()
        composeTestRule.onNodeWithTag("app_dock_row").assertExists()
        // Same app component renders twice: once inside Home grid and once inside Dock
        composeTestRule.onAllNodesWithTag("app_item_com.dual/com.dual.Main")
            .assertCountEquals(2)
        // Verify one instance lives under home_grid_page_0 and the other under app_dock_row
        composeTestRule.onNode(
            hasTestTag("app_item_com.dual/com.dual.Main")
                .and(androidx.compose.ui.test.hasAnyAncestor(hasTestTag("home_grid_page_0")))
        ).assertExists()
        composeTestRule.onNode(
            hasTestTag("app_item_com.dual/com.dual.Main")
                .and(androidx.compose.ui.test.hasAnyAncestor(hasTestTag("app_dock_row")))
        ).assertExists()
    }
}
