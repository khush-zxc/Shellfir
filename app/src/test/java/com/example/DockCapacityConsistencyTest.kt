package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.components.AppDock
import com.example.data.database.InstalledAppEntity
import com.example.data.database.ShellfriDatabase
import com.example.data.database.WorkspacePageEntity
import com.example.data.preferences.ShellfriPreferences
import com.example.domain.model.AppItem
import com.example.domain.repository.AppRepository
import com.example.domain.repository.IconRepository
import com.example.ui.home.HomeUiState
import com.example.ui.home.NormalHomeScreen
import com.example.ui.home.SettingsScreen
import com.example.ui.theme.ShellfriTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DockCapacityConsistencyTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: ShellfriDatabase
    private lateinit var preferences: ShellfriPreferences
    private lateinit var repository: AppRepository
    private lateinit var iconRepository: IconRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ShellfriDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferences = ShellfriPreferences(context)
        iconRepository = IconRepository(context)
        repository = AppRepository(
            context = context,
            installedAppDao = db.installedAppDao(),
            workspaceDao = db.workspaceDao(),
            shuffleHistoryDao = db.shuffleHistoryDao(),
            preferences = preferences,
            database = db,
            iconRepository = iconRepository
        )
    }

    @After
    fun tearDown() = runBlocking {
        preferences.setDockCount(5)
        db.close()
    }

    private fun sampleDockApps(count: Int = 7): List<AppItem> {
        return (0 until count).map { idx ->
            val name = "dock$idx"
            AppItem(
                packageName = "com.$name",
                activityName = "com.$name.Main",
                label = "Dock $idx",
                isPinnedToDock = true,
                dockRank = idx,
                dockItemId = "dock_com.$name"
            )
        }
    }

    @Test
    fun `AppDock renders capacities 3 through 7 in dock rank order without clipping`() {
        val allSevenOrdered = sampleDockApps(7)
        // Deliberately supply items out of rank order to verify AppDock preserves dockRank ordering
        val unsortedSeven = listOf(
            allSevenOrdered[6],
            allSevenOrdered[2],
            allSevenOrdered[0],
            allSevenOrdered[4],
            allSevenOrdered[1],
            allSevenOrdered[5],
            allSevenOrdered[3]
        )

        var configuredCapacity by mutableIntStateOf(3)

        composeTestRule.setContent {
            ShellfriTheme {
                AppDock(
                    dockApps = unsortedSeven,
                    iconRepository = iconRepository,
                    onAppClick = {},
                    onAppLongClick = {},
                    onOpenDrawer = {},
                    dockCount = configuredCapacity
                )
            }
        }

        for (capacity in 3..7) {
            configuredCapacity = capacity
            composeTestRule.waitForIdle()

            // Verify exactly `capacity` items exist inside app_dock_row and are displayed
            for (idx in 0 until capacity) {
                val tag = "app_item_com.dock$idx/com.dock$idx.Main"
                composeTestRule.onNode(
                    hasTestTag(tag).and(hasAnyAncestor(hasTestTag("app_dock_row")))
                ).assertIsDisplayed()
            }
            for (idx in capacity until 7) {
                val tag = "app_item_com.dock$idx/com.dock$idx.Main"
                composeTestRule.onAllNodesWithTag(tag).assertCountEquals(0)
            }

            // Verify left-to-right visual order strictly matches dockRank 0..capacity-1
            val leftBounds = (0 until capacity).map { idx ->
                val tag = "app_item_com.dock$idx/com.dock$idx.Main"
                composeTestRule.onNodeWithTag(tag).getBoundsInRoot().left
            }
            for (i in 0 until leftBounds.size - 1) {
                assertTrue(
                    "Expected rank $i (${leftBounds[i]}) to render to the left of rank ${i + 1} (${leftBounds[i + 1]}) for capacity $capacity",
                    leftBounds[i] < leftBounds[i + 1]
                )
            }
        }
    }

    @Test
    fun `NormalHomeScreen forwards configured dockCount 3 through 7 to AppDock`() {
        val sevenApps = sampleDockApps(7)
        var configuredCapacity by mutableIntStateOf(3)

        composeTestRule.setContent {
            ShellfriTheme {
                val uiState = HomeUiState(
                    allApps = sevenApps,
                    homeApps = emptyList(),
                    dockApps = sevenApps.take(configuredCapacity),
                    dockCount = configuredCapacity,
                    pageCount = 1,
                    defaultPageIndex = 0
                )
                NormalHomeScreen(
                    uiState = uiState,
                    iconRepository = iconRepository,
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

        for (capacity in 3..7) {
            configuredCapacity = capacity
            composeTestRule.waitForIdle()

            for (idx in 0 until capacity) {
                composeTestRule.onNode(
                    hasTestTag("app_item_com.dock$idx/com.dock$idx.Main")
                        .and(hasAnyAncestor(hasTestTag("app_dock_row")))
                ).assertIsDisplayed()
            }
            for (idx in capacity until 7) {
                composeTestRule.onAllNodesWithTag("app_item_com.dock$idx/com.dock$idx.Main")
                    .assertCountEquals(0)
            }
        }
    }

    @Test
    fun `AppDock renders empty and partially populated dock positions consistently across capacities`() {
        val sparseDockApps = listOf(
            AppItem(
                packageName = "com.second",
                activityName = "com.second.Main",
                label = "Second",
                isPinnedToDock = true,
                dockRank = 2,
                dockItemId = "dock_com.second"
            ),
            AppItem(
                packageName = "com.first",
                activityName = "com.first.Main",
                label = "First",
                isPinnedToDock = true,
                dockRank = 0,
                dockItemId = "dock_com.first"
            )
        )
        var renderEmpty by mutableIntStateOf(0)

        composeTestRule.setContent {
            ShellfriTheme {
                AppDock(
                    dockApps = if (renderEmpty == 0) sparseDockApps else emptyList(),
                    iconRepository = iconRepository,
                    onAppClick = {},
                    onAppLongClick = {},
                    onOpenDrawer = {},
                    dockCount = 7
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("app_dock_row").assertExists()
        composeTestRule.onNodeWithTag("app_item_com.first/com.first.Main").assertIsDisplayed()
        composeTestRule.onNodeWithTag("app_item_com.second/com.second.Main").assertIsDisplayed()
        val firstLeft = composeTestRule.onNodeWithTag("app_item_com.first/com.first.Main").getBoundsInRoot().left
        val secondLeft = composeTestRule.onNodeWithTag("app_item_com.second/com.second.Main").getBoundsInRoot().left
        assertTrue(firstLeft < secondLeft)

        // Switching to empty dock leaves app_dock_row intact without dummy placeholder items
        renderEmpty = 1
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("app_dock_row").assertExists()
        composeTestRule.onAllNodesWithTag("app_item_com.first/com.first.Main").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("app_item_com.second/com.second.Main").assertCountEquals(0)
    }

    @Test
    fun `SettingsScreen exposes and selects dock capacities 3 through 7`() {
        val selectedCapacities = mutableListOf<Int>()

        composeTestRule.setContent {
            ShellfriTheme {
                SettingsScreen(
                    uiState = HomeUiState(dockCount = 5),
                    onDismiss = {},
                    onSetShuffleEnabled = {},
                    onSetShufflePaused = {},
                    onSetShuffleInterval = {},
                    onSetShuffleStrategy = {},
                    onSetOccupancyOption = {},
                    onSetCrossPageShuffle = {},
                    onSetDrawerShuffleEnabled = {},
                    onOpenManageShuffledApps = {},
                    onUndoLastShuffle = {},
                    onSetThemeMode = {},
                    onSetGridColumns = {},
                    onSetGridSpacing = {},
                    onSetIconSize = {},
                    onSetShowLabels = {},
                    onSetBlurLevel = {},
                    onSetBlurOnShuffle = {},
                    onSetDockCount = { selectedCapacities.add(it) },
                    onSetDockShowBackground = {},
                    onSetDockShowLabels = {},
                    onAddPage = {},
                    onSetDefaultPageIndex = {},
                    onOpenFocusConfig = {},
                    onSetHideDistractingInFocus = {},
                    onManualShuffle = {},
                    onOpenShuffleHistory = {},
                    onExportBackup = {},
                    onImportBackup = {},
                    onOpenOnboarding = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasScrollAction())
            .performScrollToNode(hasTestTag("dock_capacity_option_3"))

        for (capacity in 3..7) {
            composeTestRule.onNodeWithTag("dock_capacity_option_$capacity")
                .assertIsDisplayed()
                .performClick()
        }

        assertEquals(listOf(3, 4, 5, 6, 7), selectedCapacities)
    }

    @Test
    fun `repository pinning enforces capacities 3 through 7 and preserves persisted items on capacity change`() = runBlocking {
        db.workspaceDao().upsertPages(
            listOf(WorkspacePageEntity(pageId = "page_0", pageIndex = 0, isDefault = true))
        )
        db.installedAppDao().upsertApps(
            (0 until 8).map { idx ->
                InstalledAppEntity(
                    packageName = "com.app$idx",
                    activityName = "com.app$idx.Main",
                    label = "App $idx",
                    isInstalled = true
                )
            }
        )

        // Pin up to capacity 3 -> 4th pin is ignored by capacity check
        repository.setDockCount(3)
        for (idx in 0 until 4) {
            repository.toggleAppPinnedToDock("com.app$idx", "com.app$idx.Main", true)
        }
        assertEquals(listOf("com.app0", "com.app1", "com.app2"), repository.dockAppsFlow.first().map { it.packageName })

        // Expand through 4, 5, 6, 7 and verify one additional app can be pinned at each capacity
        for (capacity in 4..7) {
            repository.setDockCount(capacity)
            val newIdx = capacity - 1
            repository.toggleAppPinnedToDock("com.app$newIdx", "com.app$newIdx.Main", true)
            // Extra pin past current capacity must not be added
            repository.toggleAppPinnedToDock("com.app7", "com.app7.Main", true)

            val pinned = repository.dockAppsFlow.first()
            assertEquals(capacity, pinned.size)
            assertEquals((0 until capacity).map { "com.app$it" }, pinned.map { it.packageName })
            assertEquals((0 until capacity).toList(), pinned.map { it.dockRank })
        }

        // Lowering capacity back to 4 does not destroy persisted dock items in repository
        repository.setDockCount(4)
        assertEquals(7, repository.dockAppsFlow.first().size)
    }

    private fun assertTrue(message: String, condition: Boolean) {
        org.junit.Assert.assertTrue(message, condition)
    }
}
