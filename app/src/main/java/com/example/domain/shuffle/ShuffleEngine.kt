package com.example.domain.shuffle

import com.example.domain.model.AppItem
import com.example.domain.model.FolderItem
import com.example.domain.model.OccupancyOption
import com.example.domain.model.ShuffleStrategy
import com.example.domain.validation.WidgetPlacement
import com.example.domain.validation.WorkspaceValidator
import kotlin.random.Random

/**
 * Explicit configuration request for executing a home/drawer layout shuffle.
 */
data class ShuffleRequest(
    val apps: List<AppItem>,
    val folders: List<FolderItem> = emptyList(),
    val widgets: List<WidgetPlacement> = emptyList(),
    val strategy: ShuffleStrategy = ShuffleStrategy.FULL_RANDOM,
    val occupancy: OccupancyOption = OccupancyOption.PRESERVE_EMPTY_SPACES,
    val crossPageShuffle: Boolean = true,
    val pageCount: Int = 1,
    val includeDrawerShuffle: Boolean = false,
    val recentPositions: Map<String, List<Pair<Int, Int>>> = emptyMap()
)

/**
 * Result of executing and validating a ShuffleRequest.
 * If [isValid] is false, [apps] is guaranteed to equal the original input layout.
 */
data class ShuffleExecutionResult(
    val apps: List<AppItem>,
    val isValid: Boolean,
    val movedHomeCount: Int,
    val movedDrawerCount: Int,
    val validationErrors: List<String> = emptyList()
)

/**
 * Shellfri Shuffle Engine v0.3 (Phase A2 Stabilization).
 * - Explicit ShuffleRequest with strategy, occupancy, cross-page, and history inputs.
 * - Distinct SMART_SHUFFLE derangement + recent-slot avoidance vs FULL_RANDOM.
 * - Built-in invariant validation preserving original layout on any invalid candidate.
 */
class ShuffleEngine(
    private val randomProvider: () -> Random = { Random.Default },
    private val workspaceValidator: WorkspaceValidator = WorkspaceValidator()
) {

    fun executeShuffle(
        request: ShuffleRequest,
        random: Random = randomProvider()
    ): ShuffleExecutionResult {
        if (request.apps.isEmpty()) {
            return ShuffleExecutionResult(
                apps = emptyList(),
                isValid = true,
                movedHomeCount = 0,
                movedDrawerCount = 0
            )
        }

        val effectiveCrossPage = request.crossPageShuffle &&
            request.occupancy != OccupancyOption.PRESERVE_GRID_POSITIONS

        val homeCandidate = computeHomeLayout(
            currentApps = request.apps,
            folders = request.folders,
            widgets = request.widgets,
            crossPageShuffle = effectiveCrossPage,
            strategy = request.strategy,
            occupancy = request.occupancy,
            recentPositions = request.recentPositions,
            random = random
        )

        val finalCandidate = if (request.includeDrawerShuffle) {
            shuffleDrawerLayout(homeCandidate, random)
        } else {
            homeCandidate
        }

        val errors = validateLayoutTransition(
            original = request.apps,
            candidate = finalCandidate,
            folders = request.folders,
            widgets = request.widgets,
            pageCount = request.pageCount,
            crossPageAllowed = effectiveCrossPage,
            drawerShuffleEnabled = request.includeDrawerShuffle
        )

        if (errors.isNotEmpty()) {
            return ShuffleExecutionResult(
                apps = request.apps,
                isValid = false,
                movedHomeCount = 0,
                movedDrawerCount = 0,
                validationErrors = errors
            )
        }

        val origByIdentity = request.apps.associateBy { identityKey(it) }
        val movedHomeCount = finalCandidate.count { app ->
            val prev = origByIdentity[identityKey(app)] ?: return@count false
            app.pageIndex != prev.pageIndex || app.slotIndex != prev.slotIndex
        }
        val movedDrawerCount = finalCandidate.count { app ->
            val prev = origByIdentity[identityKey(app)] ?: return@count false
            app.drawerSlotIndex != prev.drawerSlotIndex
        }

        return ShuffleExecutionResult(
            apps = finalCandidate,
            isValid = true,
            movedHomeCount = movedHomeCount,
            movedDrawerCount = movedDrawerCount
        )
    }

    /**
     * Backward-compatible convenience API delegating through [executeShuffle].
     */
    fun shuffleLayout(
        currentApps: List<AppItem>,
        crossPageShuffle: Boolean = false,
        strategy: ShuffleStrategy = ShuffleStrategy.FULL_RANDOM,
        occupancy: OccupancyOption = OccupancyOption.PRESERVE_EMPTY_SPACES,
        random: Random = randomProvider(),
        folders: List<FolderItem> = emptyList(),
        recentPositions: Map<String, List<Pair<Int, Int>>> = emptyMap()
    ): List<AppItem> {
        val maxPage = (currentApps.maxOfOrNull { it.pageIndex } ?: 0) + 1
        return executeShuffle(
            request = ShuffleRequest(
                apps = currentApps,
                folders = folders,
                strategy = strategy,
                occupancy = occupancy,
                crossPageShuffle = crossPageShuffle,
                pageCount = maxPage.coerceAtLeast(1),
                recentPositions = recentPositions
            ),
            random = random
        ).apps
    }

    private fun computeHomeLayout(
        currentApps: List<AppItem>,
        folders: List<FolderItem>,
        widgets: List<WidgetPlacement>,
        crossPageShuffle: Boolean,
        strategy: ShuffleStrategy,
        occupancy: OccupancyOption,
        recentPositions: Map<String, List<Pair<Int, Int>>>,
        random: Random
    ): List<AppItem> {
        val (immuneApps, eligibleApps) = currentApps.partition { app ->
            app.folderId != null || app.isPinnedToDock || app.isFixed || !app.isShuffled || !app.isOnHomeScreen
        }

        val movedApps = if (eligibleApps.size <= 1) {
            eligibleApps
        } else {
            val targetAppsToMove = when (strategy) {
                ShuffleStrategy.PARTIAL_SHUFFLE -> {
                    val halfCount = (eligibleApps.size / 2).coerceAtLeast(2).coerceAtMost(eligibleApps.size)
                    eligibleApps.shuffled(random).take(halfCount)
                }
                else -> eligibleApps
            }

            val targetKeys = targetAppsToMove.map { identityKey(it) }.toSet()
            val untouchedEligible = eligibleApps.filter { identityKey(it) !in targetKeys }

            val updatedMoved = when (strategy) {
                ShuffleStrategy.POSITION_ROTATION -> {
                    if (crossPageShuffle) {
                        val pairs = targetAppsToMove.map { it.pageIndex to it.slotIndex }
                        val rotated = if (pairs.size > 1) listOf(pairs.last()) + pairs.dropLast(1) else pairs
                        targetAppsToMove.mapIndexed { idx, app ->
                            app.copy(pageIndex = rotated[idx].first, slotIndex = rotated[idx].second)
                        }
                    } else {
                        val byPage = targetAppsToMove.groupBy { it.pageIndex }
                        byPage.values.flatMap { pageApps ->
                            if (pageApps.size <= 1) pageApps
                            else {
                                val slots = pageApps.map { it.slotIndex }
                                val rotatedSlots = listOf(slots.last()) + slots.dropLast(1)
                                pageApps.mapIndexed { idx, app -> app.copy(slotIndex = rotatedSlots[idx]) }
                            }
                        }
                    }
                }

                ShuffleStrategy.SMART_SHUFFLE -> {
                    if (!crossPageShuffle) {
                        val byPage = targetAppsToMove.groupBy { it.pageIndex }
                        byPage.values.flatMap { pageApps ->
                            if (pageApps.size <= 1) pageApps
                            else smartShuffleGroup(pageApps, recentPositions, random)
                        }
                    } else {
                        smartShuffleGroup(targetAppsToMove, recentPositions, random)
                    }
                }

                else -> {
                    if (!crossPageShuffle) {
                        val byPage = targetAppsToMove.groupBy { it.pageIndex }
                        byPage.values.flatMap { pageApps ->
                            if (pageApps.size <= 1) pageApps
                            else fullRandomGroup(pageApps, random)
                        }
                    } else {
                        fullRandomGroup(targetAppsToMove, random)
                    }
                }
            }

            untouchedEligible + updatedMoved
        }

        val combined = immuneApps + movedApps
        val finalApps = if (occupancy == OccupancyOption.COMPACT_LAYOUT) {
            compactPageSlots(combined, folders, widgets)
        } else {
            combined
        }

        return finalApps.sortedWith(compareBy({ it.pageIndex }, { it.slotIndex }, { it.packageName }, { it.activityName }))
    }

    /**
     * Shuffles drawer items independently without altering home screen page/slot indices.
     */
    fun shuffleDrawerLayout(
        currentApps: List<AppItem>,
        random: Random = randomProvider()
    ): List<AppItem> {
        val (immuneApps, eligibleApps) = currentApps.partition { it.isFixed || !it.isDrawerShuffled }
        if (eligibleApps.size <= 1) return currentApps

        val existingPositive = eligibleApps.map { it.drawerSlotIndex }
        val hasDistinctAssigned = existingPositive.all { it >= 0 } &&
            existingPositive.distinct().size == eligibleApps.size

        val originalSlots = if (hasDistinctAssigned) {
            existingPositive
        } else {
            eligibleApps.indices.toList()
        }
        val newSlots = originalSlots.toMutableList()
        fisherYates(newSlots, random)
        if (newSlots == originalSlots && newSlots.distinct().size > 1) {
            val first = newSlots.removeAt(0)
            newSlots.add(first)
        }

        val updatedEligible = eligibleApps.mapIndexed { index, app ->
            app.copy(drawerSlotIndex = newSlots[index])
        }

        return (immuneApps + updatedEligible)
            .sortedWith(compareBy({ it.pageIndex }, { it.slotIndex }, { it.packageName }, { it.activityName }))
    }

    /**
     * SMART_SHUFFLE:
     * Enforces a full derangement (no app remains in its own slot when distinct slots exist)
     * and minimizes collisions with each app's recent slot history.
     */
    private fun smartShuffleGroup(
        apps: List<AppItem>,
        recentPositions: Map<String, List<Pair<Int, Int>>>,
        random: Random
    ): List<AppItem> {
        val originalPositions = apps.map { it.pageIndex to it.slotIndex }
        if (originalPositions.distinct().size <= 1) return apps

        // Build derangement via Sattolo-inspired randomized cycle / swap repair
        val assigned = originalPositions.toMutableList()
        fisherYates(assigned, random)

        // Break every self-loop (assigned[i] == originalPositions[i])
        for (i in assigned.indices) {
            if (assigned[i] == originalPositions[i]) {
                val swapCandidate = assigned.indices.firstOrNull { j ->
                    j != i &&
                        assigned[j] != originalPositions[i] &&
                        assigned[i] != originalPositions[j]
                } ?: assigned.indices.firstOrNull { j ->
                    j != i && assigned[j] != originalPositions[i]
                }
                if (swapCandidate != null) {
                    val tmp = assigned[i]
                    assigned[i] = assigned[swapCandidate]
                    assigned[swapCandidate] = tmp
                }
            }
        }

        // Greedy refinement to avoid recent positions while preserving derangement
        for (i in assigned.indices) {
            val app = apps[i]
            val history = recentPositions[identityKey(app)] ?: recentPositions[app.packageName].orEmpty()
            if (assigned[i] in history) {
                val betterIdx = assigned.indices.firstOrNull { j ->
                    val otherHistory = recentPositions[identityKey(apps[j])] ?: recentPositions[apps[j].packageName].orEmpty()
                    j != i &&
                        assigned[j] != originalPositions[i] &&
                        assigned[i] != originalPositions[j] &&
                        assigned[j] !in history &&
                        assigned[i] !in otherHistory
                }
                if (betterIdx != null) {
                    val tmp = assigned[i]
                    assigned[i] = assigned[betterIdx]
                    assigned[betterIdx] = tmp
                }
            }
        }

        return apps.mapIndexed { index, app ->
            val (newPage, newSlot) = assigned[index]
            app.copy(pageIndex = newPage, slotIndex = newSlot)
        }
    }

    private fun fullRandomGroup(apps: List<AppItem>, random: Random): List<AppItem> {
        val originalPositions = apps.map { it.pageIndex to it.slotIndex }
        if (originalPositions.distinct().size <= 1) return apps

        val newPositions = originalPositions.toMutableList()
        fisherYates(newPositions, random)

        if (newPositions == originalPositions) {
            for (i in 0 until newPositions.size - 1) {
                if (newPositions[i] != newPositions[i + 1]) {
                    val temp = newPositions[i]
                    newPositions[i] = newPositions[i + 1]
                    newPositions[i + 1] = temp
                    break
                }
            }
            if (newPositions == originalPositions) {
                val first = newPositions.removeAt(0)
                newPositions.add(first)
            }
        }

        return apps.mapIndexed { index, app ->
            val (newPage, newSlot) = newPositions[index]
            app.copy(pageIndex = newPage, slotIndex = newSlot)
        }
    }

    /**
     * Compacts movable grid apps per page without moving fixed apps or colliding with folders or widgets.
     */
    private fun compactPageSlots(
        apps: List<AppItem>,
        folders: List<FolderItem>,
        widgets: List<WidgetPlacement> = emptyList()
    ): List<AppItem> {
        val byPage = apps.groupBy { it.pageIndex }
        val foldersByPage = folders.groupBy { it.pageIndex }
        val widgetSlotsByPage = widgets.flatMap { it.occupiedCoords() }.groupBy({ it.first }, { it.second })
        val result = mutableListOf<AppItem>()

        for ((pageIdx, pageApps) in byPage) {
            val (immutableOnPage, movableGridApps) = pageApps.partition {
                it.folderId != null || it.isPinnedToDock || !it.isOnHomeScreen || it.isFixed
            }
            val occupiedSlots = mutableSetOf<Int>()
            foldersByPage[pageIdx].orEmpty().forEach { occupiedSlots.add(it.slotIndex) }
            widgetSlotsByPage[pageIdx].orEmpty().forEach { occupiedSlots.add(it) }
            immutableOnPage.filter { it.isOnHomeScreen && it.folderId == null }
                .forEach { occupiedSlots.add(it.slotIndex) }

            var nextSlot = 0
            val compacted = movableGridApps.sortedBy { it.slotIndex }.map { app ->
                while (nextSlot in occupiedSlots) {
                    nextSlot++
                }
                val assigned = nextSlot
                occupiedSlots.add(assigned)
                nextSlot++
                app.copy(slotIndex = assigned)
            }
            result.addAll(immutableOnPage + compacted)
        }
        return result
    }

    fun validateLayoutTransition(
        original: List<AppItem>,
        candidate: List<AppItem>,
        folders: List<FolderItem>,
        pageCount: Int,
        crossPageAllowed: Boolean,
        widgets: List<WidgetPlacement> = emptyList(),
        drawerShuffleEnabled: Boolean = false
    ): List<String> {
        return workspaceValidator.validateLayoutTransition(
            original = original,
            candidate = candidate,
            folders = folders,
            widgets = widgets,
            pageCount = pageCount,
            crossPageAllowed = crossPageAllowed,
            drawerShuffleEnabled = drawerShuffleEnabled
        ).errors
    }

    private fun identityKey(app: AppItem): String = "${app.packageName}/${app.activityName}"

    private fun <T> fisherYates(list: MutableList<T>, random: Random) {
        for (i in list.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val temp = list[i]
            list[i] = list[j]
            list[j] = temp
        }
    }
}
