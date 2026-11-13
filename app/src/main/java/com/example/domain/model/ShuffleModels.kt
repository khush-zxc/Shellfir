package com.example.domain.model

enum class ShuffleInterval(val minutes: Int, val label: String) {
    MIN_15(15, "15m"),
    MIN_30(30, "30m"),
    MIN_60(60, "60m"),
    MIN_120(120, "2h"),
    MANUAL(0, "Manual");

    companion object {
        fun fromMinutes(minutes: Int): ShuffleInterval {
            return entries.find { it.minutes == minutes } ?: MIN_30
        }
    }
}

enum class ShuffleStrategy(val label: String, val description: String) {
    FULL_RANDOM("Full Random", "Randomize all eligible app positions"),
    SMART_SHUFFLE("Smart Shuffle", "Avoid repeating recent layouts & protect fixed apps"),
    PARTIAL_SHUFFLE("Partial (50%)", "Move only 50% of selected apps per shuffle"),
    POSITION_ROTATION("Position Rotation", "Rotate selected apps sequentially through slots")
}

enum class OccupancyOption(val label: String, val description: String) {
    PRESERVE_EMPTY_SPACES("Preserve Empty Spaces", "Keep gaps intact on home screen"),
    COMPACT_LAYOUT("Compact Layout", "Fill empty positions after shuffling"),
    PRESERVE_GRID_POSITIONS("Preserve Page Boundaries", "Shuffle within current home page")
}

enum class BlurLevel(val dpValue: Int, val label: String) {
    DISABLED(0, "Disabled"),
    SUBTLE(4, "Subtle"),
    MEDIUM(12, "Medium"),
    STRONG(24, "Strong")
}

enum class GridSpacing(val horizontalDp: Int, val verticalDp: Int, val label: String) {
    COMPACT(4, 8, "Compact"),
    COMFORTABLE(12, 16, "Comfortable"),
    SPACIOUS(20, 24, "Spacious")
}
