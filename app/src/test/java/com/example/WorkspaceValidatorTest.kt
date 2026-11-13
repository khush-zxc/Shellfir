package com.example

import com.example.domain.model.AppItem
import com.example.domain.model.FolderItem
import com.example.domain.validation.WidgetPlacement
import com.example.domain.validation.WorkspaceValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceValidatorTest {

    private val validator = WorkspaceValidator()

    private fun app(
        pkg: String,
        activity: String = "$pkg.MainActivity",
        page: Int = 0,
        slot: Int = 0,
        isShuffled: Boolean = false,
        isFixed: Boolean = false,
        folderId: String? = null,
        isPinnedToDock: Boolean = false,
        isOnHomeScreen: Boolean = true,
        drawerSlotIndex: Int = -1,
        isDrawerShuffled: Boolean = false
    ) = AppItem(
        packageName = pkg,
        activityName = activity,
        label = pkg.substringAfterLast('.'),
        isShuffled = isShuffled,
        isFixed = isFixed,
        slotIndex = slot,
        pageIndex = page,
        folderId = folderId,
        isPinnedToDock = isPinnedToDock,
        isOnHomeScreen = isOnHomeScreen,
        drawerSlotIndex = drawerSlotIndex,
        isDrawerShuffled = isDrawerShuffled
    )

    @Test
    fun `valid multi-page workspace with folders dock widgets and drawer slots passes`() {
        val folders = listOf(FolderItem(id = "f1", name = "Social", pageIndex = 0, slotIndex = 2))
        val widgets = listOf(WidgetPlacement(appWidgetId = 10, pageIndex = 1, slotIndex = 0, columnSpan = 2, rowSpan = 1))
        val apps = listOf(
            app("com.a", page = 0, slot = 0, isFixed = true, drawerSlotIndex = 0, isDrawerShuffled = false),
            app("com.b", page = 0, slot = 1, isShuffled = true, drawerSlotIndex = 0, isDrawerShuffled = true),
            app("com.c", page = 1, slot = 2, isShuffled = true, drawerSlotIndex = 1, isDrawerShuffled = true),
            app("com.d", folderId = "f1", isOnHomeScreen = false),
            app("com.dock", page = 0, slot = 0, isPinnedToDock = true, isOnHomeScreen = false).copy(dockRank = 0)
        )

        val result = validator.validateWorkspace(
            apps = apps,
            folders = folders,
            widgets = widgets,
            pageCount = 2,
            dockCount = 5,
            drawerShuffleEnabled = true
        )

        assertTrue("Expected valid workspace, got: ${result.errors}", result.isValid)
    }

    @Test
    fun `duplicate component identity fails validation`() {
        val apps = listOf(
            app("com.dup", activity = "com.dup.Main", slot = 0),
            app("com.dup", activity = "com.dup.Main", slot = 1)
        )

        val result = validator.validateWorkspace(apps = apps, pageCount = 1)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("Duplicate") })
    }

    @Test
    fun `fixed and shuffled simultaneous flags or fixed app movement fails validation`() {
        val invalidFlag = validator.validateWorkspace(
            apps = listOf(app("com.fixed", isFixed = true, isShuffled = true, slot = 0)),
            pageCount = 1
        )
        assertFalse(invalidFlag.isValid)

        val original = listOf(app("com.fixed", isFixed = true, page = 0, slot = 0))
        val movedCandidate = listOf(app("com.fixed", isFixed = true, page = 0, slot = 3))
        val transition = validator.validateLayoutTransition(
            original = original,
            candidate = movedCandidate,
            pageCount = 1
        )
        assertFalse(transition.isValid)
        assertTrue(transition.errors.any { it.contains("Fixed app moved") })
    }

    @Test
    fun `dock limit exceeded fails validation while folder plus dock coexistence is allowed`() {
        val overDock = listOf(
            app("com.d1", isPinnedToDock = true, isOnHomeScreen = false, slot = -1).copy(dockRank = 0),
            app("com.d2", isPinnedToDock = true, isOnHomeScreen = false, slot = -1).copy(dockRank = 1),
            app("com.d3", isPinnedToDock = true, isOnHomeScreen = false, slot = -1).copy(dockRank = 2)
        )
        val dockLimitResult = validator.validateWorkspace(apps = overDock, dockCount = 2)
        assertFalse(dockLimitResult.isValid)
        assertTrue(dockLimitResult.errors.any { it.contains("exceeds dockCount") })

        val folderAndDock = listOf(
            app("com.dual", folderId = "f1", isPinnedToDock = true, isOnHomeScreen = false, slot = -1).copy(dockRank = 0)
        )
        val dockFolderResult = validator.validateWorkspace(
            apps = folderAndDock,
            folders = listOf(FolderItem(id = "f1", name = "Work", pageIndex = 0, slotIndex = 0)),
            dockCount = 5
        )
        assertTrue("Expected folder + dock coexistence to pass: ${dockFolderResult.errors}", dockFolderResult.isValid)
    }

    @Test
    fun `missing folder reference or folder app marked on home screen fails validation`() {
        val missingFolder = validator.validateWorkspace(
            apps = listOf(app("com.orphan", folderId = "missing", isOnHomeScreen = false)),
            folders = emptyList()
        )
        assertFalse(missingFolder.isValid)

        val folderOnHome = validator.validateWorkspace(
            apps = listOf(app("com.ghost", folderId = "f1", isOnHomeScreen = true, slot = 1)),
            folders = listOf(FolderItem(id = "f1", name = "Media", pageIndex = 0, slotIndex = 0))
        )
        assertFalse(folderOnHome.isValid)
    }

    @Test
    fun `widget overlap and app collision with widget span fails validation`() {
        val widgets = listOf(
            WidgetPlacement(appWidgetId = 101, pageIndex = 0, slotIndex = 0, columnSpan = 2, rowSpan = 1)
        )
        val collidingApp = listOf(
            app("com.clash", page = 0, slot = 1)
        )
        val result = validator.validateWorkspace(
            apps = collidingApp,
            widgets = widgets,
            pageCount = 1
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("collided with widget") })
    }

    @Test
    fun `cross page violation and duplicate drawer slots fail validation`() {
        val original = listOf(
            app("com.a", page = 0, slot = 0, isShuffled = true, isDrawerShuffled = true, drawerSlotIndex = 0),
            app("com.b", page = 1, slot = 0, isShuffled = true, isDrawerShuffled = true, drawerSlotIndex = 1)
        )
        val crossed = listOf(
            app("com.a", page = 1, slot = 1, isShuffled = true, isDrawerShuffled = true, drawerSlotIndex = 0),
            app("com.b", page = 1, slot = 0, isShuffled = true, isDrawerShuffled = true, drawerSlotIndex = 1)
        )

        val crossPageFail = validator.validateLayoutTransition(
            original = original,
            candidate = crossed,
            pageCount = 2,
            crossPageAllowed = false,
            drawerShuffleEnabled = true
        )
        assertFalse(crossPageFail.isValid)

        val duplicateDrawer = validator.validateWorkspace(
            apps = listOf(
                app("com.a", page = 0, slot = 0, isDrawerShuffled = true, drawerSlotIndex = 2),
                app("com.b", page = 0, slot = 1, isDrawerShuffled = true, drawerSlotIndex = 2)
            ),
            drawerShuffleEnabled = true
        )
        assertFalse(duplicateDrawer.isValid)
        assertTrue(duplicateDrawer.errors.any { it.contains("Duplicate drawerSlotIndex") })
    }

    @Test
    fun `multiple launcher activities from same package are accepted with independent placements`() {
        val apps = listOf(
            app("com.multi", activity = "com.multi.Main", page = 0, slot = 0, isFixed = true),
            app("com.multi", activity = "com.multi.Camera", page = 0, slot = 1, isShuffled = true),
            app("com.multi", activity = "com.multi.QuickDock", isPinnedToDock = true, isOnHomeScreen = false).copy(dockRank = 0)
        )

        val result = validator.validateWorkspace(apps = apps, pageCount = 1, dockCount = 5)
        assertTrue("Expected multi-activity package to pass: ${result.errors}", result.isValid)
    }

    @Test
    fun `duplicate independent dockRank fails validation`() {
        val apps = listOf(
            app("com.d1", isPinnedToDock = true, isOnHomeScreen = false).copy(dockRank = 1),
            app("com.d2", isPinnedToDock = true, isOnHomeScreen = false).copy(dockRank = 1)
        )

        val result = validator.validateWorkspace(apps = apps, pageCount = 1, dockCount = 5)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("Duplicate dockRank") })
    }
}
