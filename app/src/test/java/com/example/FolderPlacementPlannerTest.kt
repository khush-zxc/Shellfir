package com.example

import com.example.domain.model.AppItem
import com.example.domain.model.FolderItem
import com.example.domain.placement.FolderMoveRequest
import com.example.domain.placement.WorkspacePlacementPlanner
import com.example.domain.validation.WidgetPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderPlacementPlannerTest {

    private val planner = WorkspacePlacementPlanner()

    private fun app(
        pkg: String,
        act: String = "$pkg.Main",
        page: Int = 0,
        slot: Int = 0,
        isFixed: Boolean = false,
        isPinnedToDock: Boolean = false,
        dockRank: Int = -1,
        folderId: String? = null,
        folderRank: Int = -1,
        isOnHomeScreen: Boolean = true,
        isInstalled: Boolean = true
    ): AppItem = AppItem(
        packageName = pkg,
        activityName = act,
        label = pkg,
        pageIndex = page,
        slotIndex = slot,
        isFixed = isFixed,
        isPinnedToDock = isPinnedToDock,
        dockRank = dockRank,
        folderId = folderId,
        folderRank = folderRank,
        isOnHomeScreen = isOnHomeScreen,
        isInstalled = isInstalled,
        homeItemId = if (isOnHomeScreen) "home_${pkg}_$act" else null,
        dockItemId = if (isPinnedToDock) "dock_${pkg}_$act" else null
    )

    @Test
    fun `canCreateFolderFromApps allows two eligible home apps and rejects invalid pairs`() {
        val alpha = app("com.alpha", slot = 0)
        val beta = app("com.beta", slot = 1)
        val fixed = app("com.fixed", slot = 2, isFixed = true)
        val inFolder = app("com.in", slot = -1, folderId = "f1", folderRank = 0, isOnHomeScreen = false)
        val uninstalled = app("com.gone", slot = 3, isInstalled = false)
        val dockOnly = app("com.dock", slot = -1, isPinnedToDock = true, dockRank = 0, isOnHomeScreen = false)

        assertTrue(planner.canCreateFolderFromApps(alpha, beta).first)
        assertFalse(planner.canCreateFolderFromApps(alpha, alpha).first)
        assertFalse(planner.canCreateFolderFromApps(alpha, fixed).first)
        assertFalse(planner.canCreateFolderFromApps(fixed, alpha).first)
        assertFalse(planner.canCreateFolderFromApps(alpha, inFolder).first)
        assertFalse(planner.canCreateFolderFromApps(alpha, uninstalled).first)
        assertFalse(planner.canCreateFolderFromApps(dockOnly, alpha).first)
    }

    @Test
    fun `canAddAppToFolder allows eligible home app and rejects fixed or existing folder members`() {
        val folder = FolderItem(id = "f1", name = "Folder", pageIndex = 0, slotIndex = 0)
        val eligible = app("com.alpha", slot = 1)
        val fixed = app("com.fixed", slot = 2, isFixed = true)
        val alreadyMember = app("com.member", slot = -1, folderId = "f1", folderRank = 0, isOnHomeScreen = false)
        val otherFolderMember = app("com.other", slot = -1, folderId = "f2", folderRank = 0, isOnHomeScreen = false)

        assertTrue(planner.canAddAppToFolder(eligible, folder).first)
        assertFalse(planner.canAddAppToFolder(fixed, folder).first)
        assertFalse(planner.canAddAppToFolder(alreadyMember, folder).first)
        assertFalse(planner.canAddAppToFolder(otherFolderMember, folder).first)
    }

    @Test
    fun `planFolderMove moves folder to empty slot across pages without disturbing members`() {
        val folder = FolderItem(id = "f1", name = "Tools", pageIndex = 0, slotIndex = 0)
        val member1 = app("com.m1", slot = -1, folderId = "f1", folderRank = 0, isOnHomeScreen = false)
        val member2 = app("com.m2", slot = -1, folderId = "f1", folderRank = 1, isOnHomeScreen = false)
        val homeApp = app("com.home", page = 0, slot = 2)

        val result = planner.planFolderMove(
            FolderMoveRequest(
                folderId = "f1",
                targetPageIndex = 1,
                targetSlotIndex = 3,
                gridColumns = 4,
                pageCount = 2,
                preserveEmptySpaces = true,
                apps = listOf(member1, member2, homeApp),
                folders = listOf(folder)
            )
        )

        assertTrue(result.isValid)
        val moved = result.updatedFolders.single()
        assertEquals(1, moved.pageIndex)
        assertEquals(3, moved.slotIndex)
        assertTrue(result.changedHomePlacements.isEmpty())
        val byPkg = result.updatedApps.associateBy { it.packageName }
        assertEquals("f1", byPkg["com.m1"]!!.folderId)
        assertEquals(0, byPkg["com.m1"]!!.folderRank)
        assertEquals("f1", byPkg["com.m2"]!!.folderId)
        assertEquals(1, byPkg["com.m2"]!!.folderRank)
        assertEquals(0, byPkg["com.home"]!!.pageIndex)
        assertEquals(2, byPkg["com.home"]!!.slotIndex)
    }

    @Test
    fun `planFolderMove shifts occupied apps while respecting fixed apps widgets and other folders`() {
        val moving = FolderItem(id = "f_move", name = "MoveMe", pageIndex = 0, slotIndex = 0)
        val fixedFolder = FolderItem(id = "f_other", name = "Other", pageIndex = 0, slotIndex = 5)
        val widget = WidgetPlacement(appWidgetId = 55, pageIndex = 0, slotIndex = 6, columnSpan = 2, rowSpan = 1)
        val fixedApp = app("com.fixed", page = 0, slot = 1, isFixed = true)
        val occupant = app("com.occ", page = 0, slot = 2)
        val member = app("com.member", slot = -1, folderId = "f_move", folderRank = 0, isOnHomeScreen = false)

        // Reject dropping onto fixed app, widget, or another folder
        assertFalse(
            planner.planFolderMove(
                FolderMoveRequest("f_move", 0, 1, 4, 1, apps = listOf(fixedApp, occupant, member), folders = listOf(moving, fixedFolder), widgets = listOf(widget))
            ).isValid
        )
        assertFalse(
            planner.planFolderMove(
                FolderMoveRequest("f_move", 0, 5, 4, 1, apps = listOf(fixedApp, occupant, member), folders = listOf(moving, fixedFolder), widgets = listOf(widget))
            ).isValid
        )
        assertFalse(
            planner.planFolderMove(
                FolderMoveRequest("f_move", 0, 7, 4, 1, apps = listOf(fixedApp, occupant, member), folders = listOf(moving, fixedFolder), widgets = listOf(widget))
            ).isValid
        )

        // Dropping onto occupant at slot 2 shifts occupant toward source skipping fixed app at 1 -> slot 0
        val validMove = planner.planFolderMove(
            FolderMoveRequest(
                folderId = "f_move",
                targetPageIndex = 0,
                targetSlotIndex = 2,
                gridColumns = 4,
                pageCount = 1,
                apps = listOf(fixedApp, occupant, member),
                folders = listOf(moving, fixedFolder),
                widgets = listOf(widget)
            )
        )
        assertTrue(validMove.isValid)
        assertNotNull(validMove.movedFolder)
        assertEquals(2, validMove.movedFolder!!.slotIndex)
        assertEquals(0, validMove.updatedApps.first { it.packageName == "com.occ" }.slotIndex)
        assertEquals(1, validMove.updatedApps.first { it.packageName == "com.fixed" }.slotIndex)
    }
}
