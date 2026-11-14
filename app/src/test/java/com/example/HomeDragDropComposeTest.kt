package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.AppItem
import com.example.domain.model.FolderItem
import com.example.domain.placement.PlacementMoveRequest
import com.example.domain.placement.WorkspacePlacementPlanner
import com.example.domain.repository.IconRepository
import com.example.ui.home.HomeUiState
import com.example.ui.home.NormalHomeScreen
import com.example.ui.theme.ShellfriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomeDragDropComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var iconRepository: IconRepository
    private val planner = WorkspacePlacementPlanner()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        iconRepository = IconRepository(context)
    }

    private fun app(
        pkg: String,
        label: String = pkg,
        page: Int = 0,
        slot: Int = 0,
        isFixed: Boolean = false
    ) = AppItem(
        packageName = pkg,
        activityName = "$pkg.Main",
        label = label,
        pageIndex = page,
        slotIndex = slot,
        isFixed = isFixed,
        isOnHomeScreen = true,
        homeItemId = "home_${pkg}_$pkg.Main"
    )

    private fun nodeCenterInRoot(tag: String): Offset {
        return composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center
    }

    private fun rootBounds(tag: String) =
        composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test
    fun `long press initiates drag with floating preview and drop commits without launching app`() {
        val alpha = app("com.alpha", "Alpha", page = 0, slot = 0)
        val beta = app("com.beta", "Beta", page = 0, slot = 1)
        val clickedApps = mutableListOf<String>()
        val committedMoves = mutableListOf<Triple<String, Int, Int>>()

        var uiState by mutableStateOf(
            HomeUiState(
                allApps = listOf(alpha, beta),
                homeApps = listOf(alpha, beta),
                pageCount = 1,
                defaultPageIndex = 0,
                gridColumns = 4,
                isEditMode = false
            )
        )

        composeTestRule.setContent {
            ShellfriTheme {
                NormalHomeScreen(
                    uiState = uiState,
                    iconRepository = iconRepository,
                    onAppClick = { clickedApps.add(it.packageName) },
                    onAppLongClick = {},
                    onFolderClick = {},
                    onFolderLongClick = {},
                    onModeToggle = {},
                    onManualShuffle = {},
                    onOpenDrawer = {},
                    onOpenSettings = {},
                    onToggleEditMode = { uiState = uiState.copy(isEditMode = it) },
                    onAddPage = {},
                    onRemovePage = {},
                    onSetGridColumns = {},
                    onMoveApp = { movedApp, targetPage, targetSlot, onResult ->
                        committedMoves.add(Triple(movedApp.packageName, targetPage, targetSlot))
                        val plan = planner.planMove(
                            PlacementMoveRequest(
                                packageName = movedApp.packageName,
                                activityName = movedApp.activityName,
                                targetPageIndex = targetPage,
                                targetSlotIndex = targetSlot,
                                gridColumns = uiState.gridColumns,
                                pageCount = uiState.pageCount,
                                apps = uiState.allApps
                            )
                        )
                        uiState = uiState.copy(allApps = plan.updatedApps, homeApps = plan.updatedApps)
                        onResult(plan.isValid, plan.rejectionReason)
                    }
                )
            }
        }
        composeTestRule.waitForIdle()

        val startCenter = nodeCenterInRoot("home_slot_0_0")
        val initialBetaCenter = nodeCenterInRoot("home_slot_0_1")

        // Long press alpha and move slightly to reveal drag preview
        composeTestRule.onNodeWithTag("normal_home_screen").performTouchInput {
            down(startCenter)
            advanceEventTime(650)
            moveTo(Offset(startCenter.x + 30f, startCenter.y))
        }
        composeTestRule.waitForIdle()
        assertTrue(uiState.isEditMode)
        composeTestRule.onNodeWithTag("drag_preview").assertIsDisplayed()
        composeTestRule.onNodeWithTag("edit_mode_banner").assertIsDisplayed()

        // Drag onto slot 1 (initially occupied by beta) and release
        composeTestRule.onNodeWithTag("normal_home_screen").performTouchInput {
            moveTo(initialBetaCenter)
            up()
        }
        composeTestRule.waitForIdle()

        assertTrue("Drag must never accidentally launch app", clickedApps.isEmpty())
        assertEquals(listOf(Triple("com.alpha", 0, 1)), committedMoves)
        composeTestRule.onAllNodesWithTag("drag_preview").assertCountEquals(0)

        val updatedByPkg = uiState.homeApps.associateBy { it.packageName }
        assertEquals(1, updatedByPkg["com.alpha"]!!.slotIndex)
        assertEquals(0, updatedByPkg["com.beta"]!!.slotIndex)
    }

    @Test
    fun `drag cancellation outside grid restores original position without committing`() {
        val alpha = app("com.alpha", "Alpha", page = 0, slot = 0)
        val beta = app("com.beta", "Beta", page = 0, slot = 1)
        val clickedApps = mutableListOf<String>()
        val committedMoves = mutableListOf<Triple<String, Int, Int>>()

        var uiState by mutableStateOf(
            HomeUiState(
                allApps = listOf(alpha, beta),
                homeApps = listOf(alpha, beta),
                pageCount = 1,
                defaultPageIndex = 0,
                gridColumns = 4,
                isEditMode = false
            )
        )

        composeTestRule.setContent {
            ShellfriTheme {
                NormalHomeScreen(
                    uiState = uiState,
                    iconRepository = iconRepository,
                    onAppClick = { clickedApps.add(it.packageName) },
                    onAppLongClick = {},
                    onFolderClick = {},
                    onFolderLongClick = {},
                    onModeToggle = {},
                    onManualShuffle = {},
                    onOpenDrawer = {},
                    onOpenSettings = {},
                    onToggleEditMode = { uiState = uiState.copy(isEditMode = it) },
                    onAddPage = {},
                    onRemovePage = {},
                    onSetGridColumns = {},
                    onMoveApp = { movedApp, targetPage, targetSlot, onResult ->
                        committedMoves.add(Triple(movedApp.packageName, targetPage, targetSlot))
                        onResult(true, null)
                    }
                )
            }
        }
        composeTestRule.waitForIdle()

        val startCenter = nodeCenterInRoot("home_slot_0_0")
        val dockTopCenter = nodeCenterInRoot("app_dock_row")

        // Long press alpha, move toward slot 1, then drag outside grid into dock cancel zone and release
        composeTestRule.onNodeWithTag("normal_home_screen").performTouchInput {
            down(startCenter)
            advanceEventTime(650)
            moveTo(nodeCenterInRoot("home_slot_0_1"))
            moveTo(dockTopCenter)
            up()
        }
        composeTestRule.waitForIdle()

        assertTrue(committedMoves.isEmpty())
        assertTrue(clickedApps.isEmpty())
        composeTestRule.onAllNodesWithTag("drag_preview").assertCountEquals(0)
        val afterByPkg = uiState.homeApps.associateBy { it.packageName }
        assertEquals(0, afterByPkg["com.alpha"]!!.slotIndex)
        assertEquals(1, afterByPkg["com.beta"]!!.slotIndex)
    }

    @Test
    fun `dropping onto fixed app or folder is rejected and restores original placement`() {
        val alpha = app("com.alpha", "Alpha", page = 0, slot = 0)
        val fixed = app("com.fixed", "Fixed", page = 0, slot = 1, isFixed = true)
        val folder = FolderItem(id = "fld_1", name = "Tools", pageIndex = 0, slotIndex = 2)
        val committedMoves = mutableListOf<Triple<String, Int, Int>>()
        val rejections = mutableListOf<String>()

        var uiState by mutableStateOf(
            HomeUiState(
                allApps = listOf(alpha, fixed),
                homeApps = listOf(alpha, fixed),
                folders = listOf(folder),
                pageCount = 1,
                defaultPageIndex = 0,
                gridColumns = 4,
                isEditMode = true
            )
        )

        composeTestRule.setContent {
            ShellfriTheme {
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
                    onToggleEditMode = { uiState = uiState.copy(isEditMode = it) },
                    onAddPage = {},
                    onRemovePage = {},
                    onSetGridColumns = {},
                    onMoveApp = { movedApp, targetPage, targetSlot, onResult ->
                        committedMoves.add(Triple(movedApp.packageName, targetPage, targetSlot))
                        onResult(false, "Rejected")
                    },
                    onDragRejected = { rejections.add(it) }
                )
            }
        }
        composeTestRule.waitForIdle()

        // 1. Try dropping alpha onto fixed app at slot 1
        composeTestRule.onNodeWithTag("normal_home_screen").performTouchInput {
            down(nodeCenterInRoot("home_slot_0_0"))
            advanceEventTime(650)
            moveTo(nodeCenterInRoot("home_slot_0_1"))
            up()
        }
        composeTestRule.waitForIdle()

        // 2. Try dropping alpha onto folder at slot 2
        composeTestRule.onNodeWithTag("normal_home_screen").performTouchInput {
            down(nodeCenterInRoot("home_slot_0_0"))
            advanceEventTime(650)
            moveTo(nodeCenterInRoot("home_slot_0_2"))
            up()
        }
        composeTestRule.waitForIdle()

        assertTrue(committedMoves.isEmpty())
        assertEquals(2, rejections.size)
        assertEquals(0, uiState.homeApps.first { it.packageName == "com.alpha" }.slotIndex)
    }

    @Test
    fun `hovering at right page edge switches to next page and commits cross-page drop`() {
        val alpha = app("com.alpha", "Alpha", page = 0, slot = 0)
        val page1App = app("com.p1", "Page1App", page = 1, slot = 0)
        val committedMoves = mutableListOf<Triple<String, Int, Int>>()

        var uiState by mutableStateOf(
            HomeUiState(
                allApps = listOf(alpha, page1App),
                homeApps = listOf(alpha, page1App),
                pageCount = 2,
                defaultPageIndex = 0,
                gridColumns = 4,
                isEditMode = false
            )
        )

        composeTestRule.setContent {
            ShellfriTheme {
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
                    onToggleEditMode = { uiState = uiState.copy(isEditMode = it) },
                    onAddPage = {},
                    onRemovePage = {},
                    onSetGridColumns = {},
                    onMoveApp = { movedApp, targetPage, targetSlot, onResult ->
                        committedMoves.add(Triple(movedApp.packageName, targetPage, targetSlot))
                        onResult(true, null)
                    }
                )
            }
        }
        composeTestRule.waitForIdle()

        val rootRect = rootBounds("normal_home_screen")
        val startCenter = nodeCenterInRoot("home_slot_0_0")
        val rightEdgePoint = Offset(rootRect.right - 8f, startCenter.y)

        // Long press alpha on page 0 and drag to right edge
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onNodeWithTag("normal_home_screen").performTouchInput {
            down(startCenter)
            advanceEventTime(650)
            moveTo(rightEdgePoint)
        }
        composeTestRule.mainClock.advanceTimeBy(100L)
        // Visual feedback appears and page has NOT accidentally switched yet
        composeTestRule.onNodeWithTag("page_edge_next").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_grid_page_0").assertIsDisplayed()

        // Hold beyond edgeHoverDelayMs (450ms) so pager animates to page 1
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.mainClock.advanceTimeBy(900L)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("home_grid_page_1").assertIsDisplayed()

        // Move to slot 1 on page 1 and drop
        val page1Slot1Center = nodeCenterInRoot("home_slot_1_1")
        composeTestRule.onNodeWithTag("normal_home_screen").performTouchInput {
            moveTo(page1Slot1Center)
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(listOf(Triple("com.alpha", 1, 1)), committedMoves)
    }

    @Test
    fun `normal tap launches app while persistence failure restores page and edit state`() {
        val alpha = app("com.alpha", "Alpha", page = 0, slot = 0)
        val clickedApps = mutableListOf<String>()

        var uiState by mutableStateOf(
            HomeUiState(
                allApps = listOf(alpha),
                homeApps = listOf(alpha),
                pageCount = 2,
                defaultPageIndex = 0,
                gridColumns = 4,
                isEditMode = false
            )
        )

        composeTestRule.setContent {
            ShellfriTheme {
                NormalHomeScreen(
                    uiState = uiState,
                    iconRepository = iconRepository,
                    onAppClick = { clickedApps.add(it.packageName) },
                    onAppLongClick = {},
                    onFolderClick = {},
                    onFolderLongClick = {},
                    onModeToggle = {},
                    onManualShuffle = {},
                    onOpenDrawer = {},
                    onOpenSettings = {},
                    onToggleEditMode = { uiState = uiState.copy(isEditMode = it) },
                    onAddPage = {},
                    onRemovePage = {},
                    onSetGridColumns = {},
                    onMoveApp = { _, _, _, onResult ->
                        // Simulate persistence failure
                        onResult(false, "Simulated persistence failure")
                    },
                    edgeHoverDelayMs = 150L
                )
            }
        }
        composeTestRule.waitForIdle()

        // Normal tap launches app
        composeTestRule.onNodeWithTag("app_item_com.alpha/com.alpha.Main").performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf("com.alpha"), clickedApps)

        // Drag to page 1 and drop with failing persistence -> restores page 0 and original slot 0
        val rootRect = rootBounds("normal_home_screen")
        val startCenter = nodeCenterInRoot("home_slot_0_0")
        composeTestRule.onNodeWithTag("normal_home_screen").performTouchInput {
            down(startCenter)
            advanceEventTime(650)
            moveTo(Offset(rootRect.right - 8f, startCenter.y))
        }
        composeTestRule.mainClock.advanceTimeBy(800L)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("normal_home_screen").performTouchInput {
            moveTo(nodeCenterInRoot("home_slot_1_0"))
            up()
        }
        composeTestRule.mainClock.advanceTimeBy(600L)
        composeTestRule.waitForIdle()

        // App stays at original slot 0 on page 0 and page 0 is restored
        composeTestRule.onNodeWithTag("home_grid_page_0").assertExists()
        assertEquals(0, uiState.homeApps.first().pageIndex)
        assertEquals(0, uiState.homeApps.first().slotIndex)
        assertEquals(1, clickedApps.size)
        assertFalse(uiState.homeApps.first().isPinnedToDock)
    }
}
