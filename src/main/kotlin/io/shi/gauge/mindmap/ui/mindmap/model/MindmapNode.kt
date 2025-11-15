package io.shi.gauge.mindmap.ui.mindmap.model

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

