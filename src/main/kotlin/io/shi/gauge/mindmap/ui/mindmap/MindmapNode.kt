package io.shi.gauge.mindmap.ui.mindmap

import io.shi.gauge.mindmap.model.Scenario
import io.shi.gauge.mindmap.model.Specification

/**
 * Represents a node in the mindmap tree structure
 */
data class MindmapNode(
    val id: String,
    val text: String,
    val level: Int = 0,
    val children: List<MindmapNode> = emptyList(),
    val tags: List<String> = emptyList(),
    val data: Any? = null // Original data (Specification or Scenario)
) {
    val isRoot: Boolean get() = level == 0
    val isSpec: Boolean get() = "spec" in tags
    val isFolder: Boolean get() = "folder" in tags
    val isScenario: Boolean get() = "scenario" in tags

    fun getSpecification(): Specification? = data as? Specification
    fun getScenario(): Scenario? = data as? Scenario
}

/**
 * Represents the bounds and layout information of a node
 */
data class NodeBounds(
    val node: MindmapNode,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val childBounds: List<NodeBounds>,
    val colorIndex: Int,
    val isRoot: Boolean = false
) {
    val centerX: Double get() = x + width / 2
    val centerY: Double get() = y + height / 2
    val rightX: Double get() = x + width
    val bottomY: Double get() = y + height

    fun contains(worldX: Double, worldY: Double): Boolean {
        return worldX >= x && worldX <= rightX && worldY >= y && worldY <= bottomY
    }
}

