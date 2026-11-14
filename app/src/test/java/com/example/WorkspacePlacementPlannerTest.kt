package com.example

import com.example.domain.model.AppItem
import com.example.domain.model.FolderItem
import com.example.domain.placement.PlacementMoveRequest
import com.example.domain.placement.WorkspacePlacementPlanner
import com.example.domain.validation.WidgetPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePlacementPlannerTest {

    private val planner = WorkspacePlacementPlanner()

    private fun app(
        pkg: String,
        act: String = "$pkg.Main",
        page: Int = 0,
        slot: Int = 0,
        isFixed: Boolean = false,
        isShuffled: Boolean = false,
        isPinnedToDock: Boolean = false,
        dockRank: Int = -1,
        folderId: String? = null,
        isOnHomeScreen: Boolean = true,
        isInstalled: Boolean = true
    ): AppItem = AppItem(
        packageName = pkg,
        activityName = act,
        label = pkg,
        pageIndex = page,
        slotIndex = slot,
        isFixed = isFixed,
        isShuffled = isShuffled && !isFixed,
        isPinnedToDock = isPinnedToDock,
        dockRank = dockRank,
        folderId = folderId,
        isOnHomeScreen = isOnHomeScreen,
        isInstalled = isInstalled,
        homeItemId = if (isOnHomeScreen) "home_${pkg}_$act" else null,
        dockItemId = if (isPinnedToDock) "dock_${pkg}_$act" else null
    )

    @Test
    fun `move app to empty slot succeeds and preserves empty spaces`() {
        val a = app("com.a", slot = 0)
        val b = app("com.b", slot = 2)

        val result = planner.planMove(
            PlacementMoveRequest(
                packageName = "com.a",
                activityName = "com.a.Main",
                targetPageIndex = 0,
                targetSlotIndex = 6,
                gridColumns = 4,
                pageCount = 2,
                preserveEmptySpaces = true,
                apps = listOf(a, b)
            )
        )

        assertTrue(result.isValid)
        val byPkg = result.updatedApps.associateBy { it.packageName }
        assertEquals(6, byPkg["com.a"]!!.slotIndex)
        assertEquals(0, byPkg["com.a"]!!.pageIndex)
        // com.b keeps its sparse slot 2 untouched (empty spaces preserved)
        assertEquals(2, byPkg["com.b"]!!.slotIndex)
        assertEquals(mapOf(("com.a" to "com.a.Main") to (0 to 6)), result.changedHomePlacements)
    }

    @Test
    fun `move app to occupied slot shifts intervening apps predictably toward vacated slot`() {
        val a = app("com.a", slot = 0)
        val b = app("com.b", slot = 1)
        val c = app("com.c", slot = 2)
        val d = app("com.d", slot = 3)

        // Move a(0) -> d's slot(3): b,c,d should shift left to 0,1,2 and a lands on 3
        val forward = planner.planMove(
            PlacementMoveRequest(
                packageName = "com.a",
                activityName = "com.a.Main",
                targetPageIndex = 0,
                targetSlotIndex = 3,
                gridColumns = 4,
                pageCount = 1,
                apps = listOf(a, b, c, d)
            )
        )
        assertTrue(forward.isValid)
        val forwardMap = forward.updatedApps.associateBy { it.packageName }
        assertEquals(3, forwardMap["com.a"]!!.slotIndex)
        assertEquals(0, forwardMap["com.b"]!!.slotIndex)
        assertEquals(1, forwardMap["com.c"]!!.slotIndex)
        assertEquals(2, forwardMap["com.d"]!!.slotIndex)

        // Move d(3) back -> slot(0): a,b,c should shift right to 1,2,3 and d lands on 0
        val backward = planner.planMove(
            PlacementMoveRequest(
                packageName = "com.d",
                activityName = "com.d.Main",
                targetPageIndex = 0,
                targetSlotIndex = 0,
                gridColumns = 4,
                pageCount = 1,
                apps = forward.updatedApps
            )
        )
        assertTrue(backward.isValid)
        val backwardMap = backward.updatedApps.associateBy { it.packageName }
        assertEquals(0, backwardMap["com.d"]!!.slotIndex)
        assertEquals(1, backwardMap["com.b"]!!.slotIndex)
        assertEquals(2, backwardMap["com.c"]!!.slotIndex)
        assertEquals(3, backwardMap["com.a"]!!.slotIndex)
    }

    @Test
    fun `move fixed app attempt is rejected and leaves layout unchanged`() {
        val fixed = app("com.fixed", slot = 0, isFixed = true)
        val other = app("com.other", slot = 1)

        val result = planner.planMove(
            PlacementMoveRequest(
                packageName = "com.fixed",
                activityName = "com.fixed.Main",
                targetPageIndex = 0,
                targetSlotIndex = 4,
                gridColumns = 4,
                pageCount = 1,
                apps = listOf(fixed, other)
            )
        )

        assertFalse(result.isValid)
        assertNotNull(result.rejectionReason)
        assertTrue(result.changedHomePlacements.isEmpty())
        assertEquals(0, result.updatedApps.first { it.packageName == "com.fixed" }.slotIndex)
    }

    @Test
    fun `drop onto slot occupied by fixed app is rejected`() {
        val mover = app("com.mover", slot = 0)
        val fixed = app("com.fixed", slot = 2, isFixed = true)

        val result = planner.planMove(
            PlacementMoveRequest(
                packageName = "com.mover",
                activityName = "com.mover.Main",
                targetPageIndex = 0,
                targetSlotIndex = 2,
                gridColumns = 4,
                pageCount = 1,
                apps = listOf(mover, fixed)
            )
        )

        assertFalse(result.isValid)
        assertEquals(0, result.updatedApps.first { it.packageName == "com.mover" }.slotIndex)
        assertEquals(2, result.updatedApps.first { it.packageName == "com.fixed" }.slotIndex)
    }

    @Test
    fun `shifting skips fixed app without displacing it`() {
        val mover = app("com.mover", slot = 0)
        val fixed = app("com.fixed", slot = 1, isFixed = true)
        val target = app("com.target", slot = 2)

        // Move mover(0) onto target(2); step toward source goes 2 -> skips fixed(1) -> lands target at 0!
        val result = planner.planMove(
            PlacementMoveRequest(
                packageName = "com.mover",
                activityName = "com.mover.Main",
                targetPageIndex = 0,
                targetSlotIndex = 2,
                gridColumns = 4,
                pageCount = 1,
                apps = listOf(mover, fixed, target)
            )
        )

        assertTrue(result.isValid)
        val byPkg = result.updatedApps.associateBy { it.packageName }
        assertEquals(2, byPkg["com.mover"]!!.slotIndex)
        assertEquals(1, byPkg["com.fixed"]!!.slotIndex)
        assertEquals(0, byPkg["com.target"]!!.slotIndex)
    }

    @Test
    fun `move across pages onto empty and occupied slots preserves empty spaces and dock`() {
        val p0a = app("com.p0a", page = 0, slot = 1, isPinnedToDock = true, dockRank = 0)
        val p0b = app("com.p0b", page = 0, slot = 3)
        val p1a = app("com.p1a", page = 1, slot = 2)
        val p1fixed = app("com.p1fixed", page = 1, slot = 3, isFixed = true)
        val p1b = app("com.p1b", page = 1, slot = 4)

        // Move p0a from (0,1) onto occupied (1,2) with preserveEmptySpaces = true
        val result = planner.planMove(
            PlacementMoveRequest(
                packageName = "com.p0a",
                activityName = "com.p0a.Main",
                targetPageIndex = 1,
                targetSlotIndex = 2,
                gridColumns = 4,
                pageCount = 2,
                preserveEmptySpaces = true,
                apps = listOf(p0a, p0b, p1a, p1fixed, p1b)
            )
        )

        assertTrue(result.isValid)
        val byPkg = result.updatedApps.associateBy { it.packageName }
        // Moved app placed on page 1 slot 2, dock preserved
        assertEquals(1, byPkg["com.p0a"]!!.pageIndex)
        assertEquals(2, byPkg["com.p0a"]!!.slotIndex)
        assertTrue(byPkg["com.p0a"]!!.isPinnedToDock)
        assertEquals(0, byPkg["com.p0a"]!!.dockRank)

        // p1fixed stayed at (1,3); p1a pushed forward skipping fixed(3) to (1,4), and p1b pushed to (1,5)
        assertEquals(3, byPkg["com.p1fixed"]!!.slotIndex)
        assertEquals(4, byPkg["com.p1a"]!!.slotIndex)
        assertEquals(5, byPkg["com.p1b"]!!.slotIndex)

        // p0b kept its original empty gap at slot 3 on page 0
        assertEquals(0, byPkg["com.p0b"]!!.pageIndex)
        assertEquals(3, byPkg["com.p0b"]!!.slotIndex)
    }

    @Test
    fun `cross page move with preserveEmptySpaces false compacts source page gaps safely`() {
        val p0a = app("com.p0a", page = 0, slot = 0)
        val p0b = app("com.p0b", page = 0, slot = 3)
        val folderOnP0 = FolderItem(id = "f1", name = "Work", pageIndex = 0, slotIndex = 1)

        val result = planner.planMove(
            PlacementMoveRequest(
                packageName = "com.p0a",
                activityName = "com.p0a.Main",
                targetPageIndex = 1,
                targetSlotIndex = 0,
                gridColumns = 4,
                pageCount = 2,
                preserveEmptySpaces = false,
                apps = listOf(p0a, p0b),
                folders = listOf(folderOnP0)
            )
        )

        assertTrue(result.isValid)
        val byPkg = result.updatedApps.associateBy { it.packageName }
        assertEquals(1, byPkg["com.p0a"]!!.pageIndex)
        assertEquals(0, byPkg["com.p0a"]!!.slotIndex)
        // p0b compacts to slot 0 (before folder at slot 1)
        assertEquals(0, byPkg["com.p0b"]!!.pageIndex)
        assertEquals(0, byPkg["com.p0b"]!!.slotIndex)
    }

    @Test
    fun `preserves folders and rejects dropping directly onto a folder`() {
        val mover = app("com.mover", page = 0, slot = 0)
        val occupant = app("com.occ", page = 0, slot = 2)
        val folderMember = app("com.member", page = 0, slot = -1, folderId = "fld_1", isOnHomeScreen = false)
        val folder = FolderItem(id = "fld_1", name = "Media", pageIndex = 0, slotIndex = 1)

        val dropOnFolder = planner.planMove(
            PlacementMoveRequest(
                packageName = "com.mover",
                activityName = "com.mover.Main",
                targetPageIndex = 0,
                targetSlotIndex = 1,
                gridColumns = 4,
                pageCount = 1,
                apps = listOf(mover, occupant, folderMember),
                folders = listOf(folder)
            )
        )
        assertFalse(dropOnFolder.isValid)

        // Move mover(0) -> slot(2); occupant shifts skipping folder(1) into slot 0; folder & member stay untouched
        val shiftOverFolder = planner.planMove(
            PlacementMoveRequest(
                packageName = "com.mover",
                activityName = "com.mover.Main",
                targetPageIndex = 0,
                targetSlotIndex = 2,
                gridColumns = 4,
                pageCount = 1,
                apps = listOf(mover, occupant, folderMember),
                folders = listOf(folder)
            )
        )
        assertTrue(shiftOverFolder.isValid)
        val byPkg = shiftOverFolder.updatedApps.associateBy { it.packageName }
        assertEquals(2, byPkg["com.mover"]!!.slotIndex)
        assertEquals(0, byPkg["com.occ"]!!.slotIndex)
        assertEquals("fld_1", byPkg["com.member"]!!.folderId)
        assertFalse(byPkg["com.member"]!!.isOnHomeScreen)
    }

    @Test
    fun `preserves widget span and rejects dropping onto any cell of a widget`() {
        val mover = app("com.mover", page = 0, slot = 0)
        val afterWidget = app("com.after", page = 0, slot = 4)
        val widget = WidgetPlacement(appWidgetId = 101, pageIndex = 0, slotIndex = 1, columnSpan = 2, rowSpan = 1) // occupies 1, 2

        val dropOnWidget = planner.planMove(
            PlacementMoveRequest(
                packageName = "com.mover",
                activityName = "com.mover.Main",
                targetPageIndex = 0,
                targetSlotIndex = 2,
                gridColumns = 4,
                pageCount = 1,
                apps = listOf(mover, afterWidget),
                widgets = listOf(widget)
            )
        )
        assertFalse(dropOnWidget.isValid)

        // Move mover(0) -> afterWidget(4): shift skips widget slots 1..2 cleanly and moves afterWidget to 3
        val shiftAroundWidget = planner.planMove(
            PlacementMoveRequest(
                packageName = "com.mover",
                activityName = "com.mover.Main",
                targetPageIndex = 0,
                targetSlotIndex = 4,
                gridColumns = 4,
                pageCount = 1,
                apps = listOf(mover, afterWidget),
                widgets = listOf(widget)
            )
        )
        assertTrue(shiftAroundWidget.isValid)
        val byPkg = shiftAroundWidget.updatedApps.associateBy { it.packageName }
        assertEquals(4, byPkg["com.mover"]!!.slotIndex)
        assertEquals(3, byPkg["com.after"]!!.slotIndex)
    }

    @Test
    fun `multi-activity package components move independently without duplicate ranks`() {
        val act1 = app("com.suite", act = "com.suite.Primary", page = 0, slot = 0)
        val act2 = app("com.suite", act = "com.suite.Secondary", page = 0, slot = 1)

        val result = planner.planMove(
            PlacementMoveRequest(
                packageName = "com.suite",
                activityName = "com.suite.Primary",
                targetPageIndex = 0,
                targetSlotIndex = 1,
                gridColumns = 4,
                pageCount = 1,
                apps = listOf(act1, act2)
            )
        )

        assertTrue(result.isValid)
        val byComp = result.updatedApps.associateBy { it.componentKey }
        assertEquals(1, byComp["com.suite/com.suite.Primary"]!!.slotIndex)
        assertEquals(0, byComp["com.suite/com.suite.Secondary"]!!.slotIndex)
        assertEquals(2, result.updatedApps.map { it.pageIndex to it.slotIndex }.toSet().size)
    }

    @Test
    fun `invalid targets and ineligible apps are rejected`() {
        val homeApp = app("com.home", page = 0, slot = 0)
        val folderApp = app("com.fld", page = 0, slot = -1, folderId = "f1", isOnHomeScreen = false)
        val uninstalled = app("com.gone", page = 0, slot = -1, isOnHomeScreen = false, isInstalled = false)
        val folder = FolderItem(id = "f1", name = "F", pageIndex = 0, slotIndex = 5)

        val baseApps = listOf(homeApp, folderApp, uninstalled)

        assertFalse(
            planner.planMove(
                PlacementMoveRequest("com.home", "com.home.Main", targetPageIndex = -1, targetSlotIndex = 0, gridColumns = 4, pageCount = 2, apps = baseApps, folders = listOf(folder))
            ).isValid
        )
        assertFalse(
            planner.planMove(
                PlacementMoveRequest("com.home", "com.home.Main", targetPageIndex = 2, targetSlotIndex = 0, gridColumns = 4, pageCount = 2, apps = baseApps, folders = listOf(folder))
            ).isValid
        )
        assertFalse(
            planner.planMove(
                PlacementMoveRequest("com.home", "com.home.Main", targetPageIndex = 0, targetSlotIndex = -1, gridColumns = 4, pageCount = 2, apps = baseApps, folders = listOf(folder))
            ).isValid
        )
        assertFalse(
            planner.planMove(
                PlacementMoveRequest("com.home", "com.home.Main", targetPageIndex = 0, targetSlotIndex = 16, gridColumns = 4, pageCount = 2, apps = baseApps, folders = listOf(folder), maxSlotsPerPage = 16)
            ).isValid
        )
        assertFalse(
            planner.planMove(
                PlacementMoveRequest("com.fld", "com.fld.Main", targetPageIndex = 0, targetSlotIndex = 2, gridColumns = 4, pageCount = 2, apps = baseApps, folders = listOf(folder))
            ).isValid
        )
        assertFalse(
            planner.planMove(
                PlacementMoveRequest("com.gone", "com.gone.Main", targetPageIndex = 0, targetSlotIndex = 2, gridColumns = 4, pageCount = 2, apps = baseApps, folders = listOf(folder))
            ).isValid
        )
    }
}
