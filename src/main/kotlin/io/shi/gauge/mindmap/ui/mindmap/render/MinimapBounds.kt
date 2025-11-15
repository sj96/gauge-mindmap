package io.shi.gauge.mindmap.ui.mindmap.render

/**
 * Represents the bounds of the mindmap content for minimap rendering
 */
data class MinimapBounds(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
    val contentWidth: Double,
    val contentHeight: Double
)

