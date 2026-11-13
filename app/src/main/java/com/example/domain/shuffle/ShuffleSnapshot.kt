package com.example.domain.shuffle

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class HomeSlotCoordinate(
    val pageId: String,
    val pageIndex: Int,
    val rank: Int
)

data class ShuffleDeltaEntry(
    val packageName: String,
    val activityName: String,
    val originalHome: HomeSlotCoordinate? = null,
    val resultingHome: HomeSlotCoordinate? = null,
    val originalDrawerRank: Int? = null,
    val resultingDrawerRank: Int? = null
)

data class ShuffleDeltaSnapshot(
    val snapshotVersion: Int = CURRENT_SNAPSHOT_VERSION,
    val snapshotUuid: String = UUID.randomUUID().toString(),
    val historyId: Long,
    val timestamp: Long,
    val strategy: String,
    val occupancy: String,
    val crossPageShuffle: Boolean,
    val drawerShuffleEnabled: Boolean,
    val pageCount: Int,
    val entries: List<ShuffleDeltaEntry>
) {
    fun toJson(): String {
        val root = JSONObject().apply {
            put("snapshotVersion", snapshotVersion)
            put("snapshotUuid", snapshotUuid)
            put("historyId", historyId)
            put("timestamp", timestamp)
            put(
                "config",
                JSONObject().apply {
                    put("strategy", strategy)
                    put("occupancy", occupancy)
                    put("crossPageShuffle", crossPageShuffle)
                    put("drawerShuffleEnabled", drawerShuffleEnabled)
                    put("pageCount", pageCount)
                }
            )
            val entriesArr = JSONArray()
            for (entry in entries) {
                entriesArr.put(
                    JSONObject().apply {
                        put("packageName", entry.packageName)
                        put("activityName", entry.activityName)
                        put("originalHome", entry.originalHome?.toJson() ?: JSONObject.NULL)
                        put("resultingHome", entry.resultingHome?.toJson() ?: JSONObject.NULL)
                        put("originalDrawerRank", entry.originalDrawerRank ?: JSONObject.NULL)
                        put("resultingDrawerRank", entry.resultingDrawerRank ?: JSONObject.NULL)
                    }
                )
            }
            put("entries", entriesArr)
        }
        return root.toString()
    }

    private fun HomeSlotCoordinate.toJson(): JSONObject = JSONObject().apply {
        put("pageId", pageId)
        put("pageIndex", pageIndex)
        put("rank", rank)
    }

    companion object {
        const val CURRENT_SNAPSHOT_VERSION = 2

        fun fromJson(rawJson: String): ShuffleDeltaSnapshot? = runCatching {
            if (rawJson.isBlank()) return@runCatching null
            val root = JSONObject(rawJson)
            if (!root.has("snapshotVersion")) return@runCatching null
            val version = root.getInt("snapshotVersion")
            if (version != CURRENT_SNAPSHOT_VERSION) return@runCatching null

            val uuid = root.optString("snapshotUuid", "")
            if (uuid.isBlank()) return@runCatching null

            val historyId = root.getLong("historyId")
            if (historyId <= 0L) return@runCatching null

            val timestamp = root.getLong("timestamp")
            val config = root.getJSONObject("config")
            val strategy = config.getString("strategy")
            val occupancy = config.getString("occupancy")
            val crossPage = config.getBoolean("crossPageShuffle")
            val drawerEnabled = config.getBoolean("drawerShuffleEnabled")
            val pageCount = config.getInt("pageCount").coerceAtLeast(1)

            val entriesArr = root.getJSONArray("entries")
            val entries = mutableListOf<ShuffleDeltaEntry>()
            val seenComponents = mutableSetOf<Pair<String, String>>()
            for (i in 0 until entriesArr.length()) {
                val obj = entriesArr.getJSONObject(i)
                val pkg = obj.getString("packageName")
                val act = obj.getString("activityName")
                if (pkg.isBlank()) return@runCatching null
                if (!seenComponents.add(pkg to act)) return@runCatching null

                val origHome = if (obj.isNull("originalHome")) null else parseCoord(obj.getJSONObject("originalHome"))
                val resHome = if (obj.isNull("resultingHome")) null else parseCoord(obj.getJSONObject("resultingHome"))
                if ((origHome == null) != (resHome == null)) return@runCatching null

                val origDrawer = if (obj.isNull("originalDrawerRank")) null else obj.getInt("originalDrawerRank")
                val resDrawer = if (obj.isNull("resultingDrawerRank")) null else obj.getInt("resultingDrawerRank")
                if ((origDrawer == null) != (resDrawer == null)) return@runCatching null

                if (origHome == null && origDrawer == null) return@runCatching null

                entries.add(
                    ShuffleDeltaEntry(
                        packageName = pkg,
                        activityName = act,
                        originalHome = origHome,
                        resultingHome = resHome,
                        originalDrawerRank = origDrawer,
                        resultingDrawerRank = resDrawer
                    )
                )
            }
            if (entries.isEmpty()) return@runCatching null

            ShuffleDeltaSnapshot(
                snapshotVersion = version,
                snapshotUuid = uuid,
                historyId = historyId,
                timestamp = timestamp,
                strategy = strategy,
                occupancy = occupancy,
                crossPageShuffle = crossPage,
                drawerShuffleEnabled = drawerEnabled,
                pageCount = pageCount,
                entries = entries
            )
        }.getOrNull()

        private fun parseCoord(obj: JSONObject): HomeSlotCoordinate {
            val pageId = obj.getString("pageId")
            require(pageId.isNotBlank())
            val pageIndex = obj.getInt("pageIndex")
            val rank = obj.getInt("rank")
            require(pageIndex >= 0 && rank >= 0)
            return HomeSlotCoordinate(pageId = pageId, pageIndex = pageIndex, rank = rank)
        }
    }
}

enum class UndoShuffleStatus {
    APPLIED,
    PARTIALLY_APPLIED,
    NO_SNAPSHOT,
    UNAVAILABLE,
    FAILED
}

data class UndoShuffleResult(
    val status: UndoShuffleStatus,
    val restoredHomeCount: Int = 0,
    val restoredDrawerCount: Int = 0,
    val skippedComponents: List<String> = emptyList(),
    val failureReason: String? = null
) {
    val isAppliedOrPartial: Boolean
        get() = status == UndoShuffleStatus.APPLIED || status == UndoShuffleStatus.PARTIALLY_APPLIED
}
