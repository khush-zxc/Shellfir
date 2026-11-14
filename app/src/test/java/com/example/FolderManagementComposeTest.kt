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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.AppItem
import com.example.domain.model.FolderItem
import com.example.domain.repository.AppRepository
import com.example.domain.repository.IconRepository
import com.example.ui.home.FolderDialog
import com.example.ui.home.HomeUiState
import com.example.ui.home.NormalHomeScreen
import com.example.ui.theme.ShellfriTheme
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
class FolderManagementComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var iconRepository: IconRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        iconRepository = IconRepository(context)
    }

    private fun homeApp(
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

    private fun folderMember(
        pkg: String,
        label: String = pkg,
        folderId: String = "fld_1",
        folderRank: Int = 0
    ) = AppItem(
        packageName = pkg,
        activityName = "$pkg.Main",
        label = label,
        pageIndex = 0,
        slotIndex = -1,
        folderId = folderId,
        folderRank = folderRank,
        isOnHomeScreen = false
    )

    private fun nodeCenterInRoot(tag: String): Offset {
        return composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center
    }

    @Test
    fun `hovering app over another app beyond folderHoverDelayMs creates folder while fast drop shifts slots`() {
        val alpha = homeApp("com.alpha", "Alpha", page = 0, slot = 0)
        val beta = homeApp("com.beta", "Beta", page = 0, slot = 1)
        val createdPairs = mutableListOf<Pair<String, String>>()
        val movedApps = mutableListOf<Pair<String, Int>>()

        val uiState = HomeUiState(
            allApps = listOf(alpha, beta),
            homeApps = listOf(alpha, beta),
            pageCount = 1,
            defaultPageIndex = 0,
            gridColumns = 4,
            isEditMode = false
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
                    onToggleEditMode = {},
                    onAddPage = {},
                    onRemovePage = {},
                    onSetGridColumns = {},
                    onMoveApp = { app, _, targetSlot, onResult ->
                        movedApps.add(app.packageName to targetSlot)
                        onResult(true, null)
                    },
                    onCreateFolderFromApps = { dragged, target, onResult ->
                        createdPairs.add(dragged.packageName to target.packageName)
                        onResult(true, null)
                    },
                    folderHoverDelayMs = 200L
                )
            }
        }
        composeTestRule.waitForIdle()

        val slot0 = nodeCenterInRoot("home_slot_0_0")
        val slot1 = nodeCenterInRoot("home_slot_0_1")

        // 1. Deliberate hover > 200ms arms folder creation preview and creates folder on release
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onNodeWithTag("normal_home_screen").performTouchInput {
            down(slot0)
            advanceEventTime(650)
            moveTo(slot1)
        }
        composeTestRule.mainClock.advanceTimeBy(300L)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("folder_create_preview").assertIsDisplayed()

        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.onNodeWithTag("normal_home_screen").performTouchInput { up() }
        composeTestRule.waitForIdle()

        assertEquals(listOf("com.alpha" to "com.beta"), createdPairs)
        assertTrue(movedApps.isEmpty())

        // 2. Fast drop without hovering shifts slots cleanly instead of creating folder
        composeTestRule.onNodeWithTag("normal_home_screen").performTouchInput {
            down(slot0)
            advanceEventTime(650)
            moveTo(slot1)
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(1, createdPairs.size)
        assertEquals(listOf("com.alpha" to 1), movedApps)
    }

    @Test
    fun `dragging app over existing folder arms add-to-folder and dragging folder moves it on Home`() {
        val alpha = homeApp("com.alpha", "Alpha", page = 0, slot = 0)
        val folder = FolderItem(id = "fld_1", name = "Tools", pageIndex = 0, slotIndex = 1)
        val m1 = folderMember("com.m1", "M1", "fld_1", 0)
        val addedApps = mutableListOf<Pair<String, String>>()
        val movedFolders = mutableListOf<Triple<String, Int, Int>>()

        val uiState = HomeUiState(
            allApps = listOf(alpha, m1),
            homeApps = listOf(alpha),
            folders = listOf(folder),
            pageCount = 1,
            defaultPageIndex = 0,
            gridColumns = 4,
            isEditMode = false
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
                    onToggleEditMode = {},
                    onAddPage = {},
                    onRemovePage = {},
                    onSetGridColumns = {},
                    onAddAppToFolder = { app, targetFolder, onResult ->
                        addedApps.add(app.packageName to targetFolder.id)
                        onResult(true, null)
                    },
                    onMoveFolder = { fld, targetPage, targetSlot, onResult ->
                        movedFolders.add(Triple(fld.id, targetPage, targetSlot))
                        onResult(true, null)
                    },
                    folderHoverDelayMs = 180L
                )
            }
        }
        composeTestRule.waitForIdle()

        val slot0 = nodeCenterInRoot("home_slot_0_0")
        val slot1 = nodeCenterInRoot("home_slot_0_1")

        // 1. Drag app onto folder and hover > 180ms
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onNodeWithTag("normal_home_screen").performTouchInput {
            down(slot0)
            advanceEventTime(650)
            moveTo(slot1)
        }
        composeTestRule.mainClock.advanceTimeBy(260L)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("folder_add_preview").assertIsDisplayed()

        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.onNodeWithTag("normal_home_screen").performTouchInput { up() }
        composeTestRule.waitForIdle()
        assertEquals(listOf("com.alpha" to "fld_1"), addedApps)

        // 2. Long press folder and drag to slot 2
        composeTestRule.onNodeWithTag("normal_home_screen").performTouchInput {
            down(slot1)
            advanceEventTime(650)
            moveTo(Offset(slot1.x + 25f, slot1.y))
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("folder_drag_preview").assertIsDisplayed()

        val slot2 = nodeCenterInRoot("home_slot_0_2")
        composeTestRule.onNodeWithTag("normal_home_screen").performTouchInput {
            moveTo(slot2)
            up()
        }
        composeTestRule.waitForIdle()
        assertEquals(listOf(Triple("fld_1", 0, 2)), movedFolders)
    }

    @Test
    fun `FolderDialog validates rename, handles failure, launches apps, and renders empty state safely`() {
        var folder by mutableStateOf(FolderItem(id = "fld_1", name = "Media", pageIndex = 0, slotIndex = 0))
        var apps by mutableStateOf(
            listOf(
                folderMember("com.one", "One", "fld_1", 0),
                folderMember("com.two", "Two", "fld_1", 1)
            )
        )
        var simulateRenameFailure = false
        val launched = mutableListOf<String>()
        var dismissed = false

        composeTestRule.setContent {
            ShellfriTheme {
                FolderDialog(
                    folder = folder,
                    appsInFolder = apps,
                    allApps = apps,
                    iconRepository = iconRepository,
                    onAppClick = { launched.add(it.packageName) },
                    onRemoveAppFromFolder = {},
                    onAddAppToFolder = {},
                    onRenameFolder = { newName, onResult ->
                        if (simulateRenameFailure) {
                            onResult(false, "Write failed")
                        } else {
                            folder = folder.copy(name = newName)
                            onResult(true, null)
                        }
                    },
                    onReorderApps = { _, onResult -> onResult(true, null) },
                    onDeleteFolder = {},
                    onDismiss = { dismissed = true }
                )
            }
        }
        composeTestRule.waitForIdle()

        // 1. Rename validation: blank rejected inline
        composeTestRule.onNodeWithTag("folder_rename_trigger").performClick()
        composeTestRule.onNodeWithTag("folder_rename_input").performTextReplacement("   ")
        composeTestRule.onNodeWithTag("folder_rename_save").performClick()
        composeTestRule.onNodeWithTag("folder_rename_error").assertIsDisplayed()

        // Over-length rejected inline
        val overLimit = "X".repeat(AppRepository.MAX_FOLDER_NAME_LENGTH + 1)
        composeTestRule.onNodeWithTag("folder_rename_input").performTextReplacement(overLimit)
        composeTestRule.onNodeWithTag("folder_rename_save").performClick()
        composeTestRule.onNodeWithTag("folder_rename_error").assertIsDisplayed()

        // Simulated persistence failure keeps valid previous name
        simulateRenameFailure = true
        composeTestRule.onNodeWithTag("folder_rename_input").performTextReplacement("Music")
        composeTestRule.onNodeWithTag("folder_rename_save").performClick()
        composeTestRule.onNodeWithTag("folder_rename_error").assertIsDisplayed()
        assertEquals("Media", folder.name)

        // Valid trimmed rename succeeds
        simulateRenameFailure = false
        composeTestRule.onNodeWithTag("folder_rename_input").performTextReplacement("  Audio Tools  ")
        composeTestRule.onNodeWithTag("folder_rename_save").performClick()
        composeTestRule.waitForIdle()
        assertEquals("Audio Tools", folder.name)
        composeTestRule.onNodeWithText("AUDIO TOOLS").assertIsDisplayed()

        // 2. Empty folder state renders safely without crashing
        apps = emptyList()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("folder_empty_state").assertIsDisplayed()

        // 3. Close button dismisses cleanly
        composeTestRule.onNodeWithTag("folder_close_button").performClick()
        composeTestRule.waitForIdle()
        assertTrue(dismissed)
    }

    @Test
    fun `FolderDialog internal drag reorders apps, cancels safely outside dialog, and removes via drop zone and manage mode`() {
        val m0 = folderMember("com.zero", "Zero", "fld_1", 0)
        val m1 = folderMember("com.one", "One", "fld_1", 1)
        val m2 = folderMember("com.two", "Two", "fld_1", 2)
        var apps by mutableStateOf(listOf(m0, m1, m2))
        val reorders = mutableListOf<List<String>>()
        val removed = mutableListOf<String>()

        composeTestRule.setContent {
            ShellfriTheme {
                FolderDialog(
                    folder = FolderItem(id = "fld_1", name = "Apps", pageIndex = 0, slotIndex = 0),
                    appsInFolder = apps,
                    allApps = apps,
                    iconRepository = iconRepository,
                    onAppClick = {},
                    onRemoveAppFromFolder = { removed.add(it.packageName) },
                    onAddAppToFolder = {},
                    onRenameFolder = { _, onResult -> onResult(true, null) },
                    onReorderApps = { newList, onResult ->
                        reorders.add(newList.map { it.packageName })
                        apps = newList.mapIndexed { idx, app -> app.copy(folderRank = idx) }
                        onResult(true, null)
                    },
                    onDeleteFolder = {}
                ) {}
            }
        }
        composeTestRule.waitForIdle()

        val slot0Center = nodeCenterInRoot("folder_app_slot_0")
        val slot2Center = nodeCenterInRoot("folder_app_slot_2")

        // 1. Cancel reorder by dragging outside grid/remove zone (above dialog)
        composeTestRule.onNodeWithTag("folder_dialog_fld_1").performTouchInput {
            down(slot0Center)
            advanceEventTime(650)
            moveTo(Offset(slot0Center.x, 2f))
            up()
        }
        composeTestRule.waitForIdle()
        assertTrue(reorders.isEmpty())
        assertEquals(listOf("com.zero", "com.one", "com.two"), apps.map { it.packageName })

        // 2. Drag slot 0 -> slot 2 commits reorder [com.one, com.two, com.zero]
        composeTestRule.onNodeWithTag("folder_dialog_fld_1").performTouchInput {
            down(slot0Center)
            advanceEventTime(650)
            moveTo(slot2Center)
            up()
        }
        composeTestRule.waitForIdle()
        assertEquals(listOf(listOf("com.one", "com.two", "com.zero")), reorders)

        // 3. Long-press slot 0 (now com.one) to show remove drop zone and drop into it
        val updatedSlot0 = nodeCenterInRoot("folder_app_slot_0")
        composeTestRule.onNodeWithTag("folder_dialog_fld_1").performTouchInput {
            down(updatedSlot0)
            advanceEventTime(650)
            moveTo(Offset(updatedSlot0.x + 12f, updatedSlot0.y))
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("folder_internal_drag_preview").assertIsDisplayed()
        composeTestRule.onNodeWithTag("folder_remove_drop_zone").assertIsDisplayed()
        val removeZoneCenter = nodeCenterInRoot("folder_remove_drop_zone")
        composeTestRule.onNodeWithTag("folder_dialog_fld_1").performTouchInput {
            moveTo(removeZoneCenter)
            up()
        }
        composeTestRule.waitForIdle()
        assertEquals(listOf("com.one"), removed)
        composeTestRule.onAllNodesWithTag("folder_internal_drag_preview").assertCountEquals(0)

        // 4. Manage mode one-tap removal badge also removes app
        composeTestRule.onNodeWithTag("folder_manage_button").performClick()
        composeTestRule.onNodeWithTag("folder_remove_app_com.two/com.two.Main").performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf("com.one", "com.two"), removed)
    }
}
