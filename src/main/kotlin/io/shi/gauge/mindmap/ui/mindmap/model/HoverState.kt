package io.shi.gauge.mindmap.ui.mindmap.model

/**
 * Hover state for a node
 */
data class HoverState(
    var hoveredNodeId: String? = null,
    val hoveredChildrenIds: MutableSet<String> = mutableSetOf()
)

