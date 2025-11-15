package io.shi.gauge.mindmap.ui.mindmap.layout

import io.shi.gauge.mindmap.ui.mindmap.constants.MindmapConstants
import io.shi.gauge.mindmap.ui.mindmap.model.MindmapNode
import io.shi.gauge.mindmap.ui.mindmap.model.NodeBounds
import io.shi.gauge.mindmap.ui.mindmap.util.MindmapTextMeasurer

/**
 * Calculates layout for mindmap nodes
 */
class MindmapLayout(private val textMeasurer: MindmapTextMeasurer) {

    private val collapsedNodeIds = mutableSetOf<String>()

    private companion object {
        const val ROOT_SPACING_MULTIPLIER = 0.8
        const val CHILD_SPACING_MULTIPLIER = 0.7
        const val ROOT_HORIZONTAL_SPACING_MULTIPLIER = 1.0
        const val CHILD_HORIZONTAL_SPACING_MULTIPLIER = 0.5
    }

    fun setCollapsed(nodeId: String, collapsed: Boolean) {
        if (collapsed) {
            collapsedNodeIds.add(nodeId)
        } else {
            collapsedNodeIds.remove(nodeId)
        }
    }

    fun isCollapsed(nodeId: String): Boolean = collapsedNodeIds.contains(nodeId)

    fun clearCollapsed() {
        collapsedNodeIds.clear()
    }

    fun collapseAllSpecs(rootNode: MindmapNode) {
        fun collectSpecNodes(node: MindmapNode) {
            node.children.forEach { child ->
                if (child.isSpec && child.children.isNotEmpty()) {
                    collapsedNodeIds.add(child.id)
                }
                collectSpecNodes(child)
            }
        }
        collectSpecNodes(rootNode)
    }

    fun expandAllSpecs(rootNode: MindmapNode) {
        fun collectSpecNodes(node: MindmapNode) {
            node.children.forEach { child ->
                if (child.isSpec) {
                    collapsedNodeIds.remove(child.id)
                }
                collectSpecNodes(child)
            }
        }
        collectSpecNodes(rootNode)
    }

    fun calculateLayout(rootNode: MindmapNode): NodeBounds? {
        if (rootNode.children.isEmpty()) return null

        val rootX = MindmapConstants.ROOT_X
        val baseStartY = MindmapConstants.BASE_START_Y

        // Calculate root node size
        val rootTextSize = textMeasurer.calculateTextSize(rootNode.text, 0, true)
        val rootRightX = rootX + rootTextSize.width
        val childrenStartX = rootRightX + MindmapConstants.HORIZONTAL_SPACING

        // First pass: calculate all children layouts to measure subtree heights
        val preliminaryChildBounds = mutableListOf<NodeBounds>()
        val subtreeHeights = mutableListOf<Double>()

        rootNode.children.forEachIndexed { index, child ->
            val childBounds = calculateNodeLayout(child, 1, childrenStartX, 0.0, index)
            preliminaryChildBounds.add(childBounds)
            val subtreeTop = calculateSubtreeTop(childBounds)
            val subtreeBottom = calculateSubtreeBottom(childBounds)
            subtreeHeights.add(subtreeBottom - subtreeTop)
        }

        // Calculate total children height
        val totalChildrenHeight = calculateTotalHeight(subtreeHeights, ROOT_SPACING_MULTIPLIER)

        // Center root vertically with all children
        val centerY = baseStartY + totalChildrenHeight / 2
        val childrenStartY = centerY - totalChildrenHeight / 2
        var currentChildTop = childrenStartY

        // Second pass: reposition all children with correct vertical positions
        val positionedChildren = mutableListOf<NodeBounds>()
        rootNode.children.forEachIndexed { index, child ->
            val subtreeHeight = subtreeHeights[index]
            val childCenterY = currentChildTop + subtreeHeight / 2

            val finalChildBounds = calculateNodeLayout(child, 1, childrenStartX, childCenterY, index)
            positionedChildren.add(finalChildBounds)

            val actualBottom = calculateSubtreeBottom(finalChildBounds)
            currentChildTop = actualBottom + MindmapConstants.VERTICAL_SPACING * ROOT_SPACING_MULTIPLIER
        }

        // Calculate actual children bounds
        val actualChildrenTop = positionedChildren.minOfOrNull { calculateSubtreeTop(it) }
            ?: positionedChildren.firstOrNull()?.y ?: centerY
        val actualChildrenBottom = positionedChildren.maxOfOrNull { calculateSubtreeBottom(it) }
            ?: centerY
        val actualChildrenCenter = (actualChildrenTop + actualChildrenBottom) / 2
        val rootY = actualChildrenCenter - rootTextSize.height / 2

        val rootBounds = NodeBounds(
            node = rootNode,
            x = rootX,
            y = rootY,
            width = rootTextSize.width,
            height = rootTextSize.height,
            childBounds = positionedChildren,
            colorIndex = 0,
            isRoot = true
        )

        // Link children to parent (game-style API)
        positionedChildren.forEach { child ->
            child.link(rootBounds)
            linkChildrenRecursive(child)
        }

        return rootBounds
    }

    private fun calculateNodeLayout(
        node: MindmapNode,
        level: Int,
        startX: Double,
        startY: Double,
        colorIndex: Int
    ): NodeBounds {
        val nodeTextSize = textMeasurer.calculateTextSize(node.text, level, node.children.isNotEmpty())
        val isCollapsed = collapsedNodeIds.contains(node.id)

        val childBounds = mutableListOf<NodeBounds>()

        if (node.children.isNotEmpty() && !isCollapsed) {
            val parentRightX = startX + nodeTextSize.width
            val horizontalSpacingMultiplier =
                if (level == 0) ROOT_HORIZONTAL_SPACING_MULTIPLIER else CHILD_HORIZONTAL_SPACING_MULTIPLIER
            val childrenStartX = parentRightX + MindmapConstants.HORIZONTAL_SPACING * horizontalSpacingMultiplier

            // First pass: calculate all children layouts to measure subtree heights
            val preliminaryChildBounds = mutableListOf<NodeBounds>()
            val subtreeHeights = mutableListOf<Double>()

            node.children.forEachIndexed { _, child ->
                val childBounds = calculateNodeLayout(
                    child,
                    level + 1,
                    childrenStartX,
                    0.0,
                    colorIndex
                )
                preliminaryChildBounds.add(childBounds)

                val subtreeTop = calculateSubtreeTop(childBounds)
                val subtreeBottom = calculateSubtreeBottom(childBounds)
                subtreeHeights.add(subtreeBottom - subtreeTop)
            }

            // Calculate total children height
            val spacingMultiplier = if (level == 0) ROOT_SPACING_MULTIPLIER else CHILD_SPACING_MULTIPLIER
            val totalChildrenHeight = calculateTotalHeight(subtreeHeights, spacingMultiplier)

            // Center all children vertically
            val childrenCenterY = startY
            val childrenStartY = childrenCenterY - totalChildrenHeight / 2
            var currentChildTop = childrenStartY

            // Second pass: reposition all children with correct vertical positions
            node.children.forEachIndexed { index, child ->
                val subtreeHeight = subtreeHeights[index]
                val childCenterY = currentChildTop + subtreeHeight / 2

                val finalChildBounds = calculateNodeLayout(
                    child,
                    level + 1,
                    childrenStartX,
                    childCenterY,
                    colorIndex
                )

                childBounds.add(finalChildBounds)
                val actualBottom = calculateSubtreeBottom(finalChildBounds)
                currentChildTop = actualBottom + MindmapConstants.VERTICAL_SPACING * spacingMultiplier
            }
        }

        // Calculate node Y position - center with children
        val nodeY = if (childBounds.isNotEmpty()) {
            val actualChildrenTop = childBounds.minOfOrNull { calculateSubtreeTop(it) }
                ?: childBounds.first().y
            val actualChildrenBottom = childBounds.maxOfOrNull { calculateSubtreeBottom(it) }
                ?: (childBounds.last().y + childBounds.last().height)
            val childrenCenter = (actualChildrenTop + actualChildrenBottom) / 2
            childrenCenter - nodeTextSize.height / 2
        } else {
            startY - nodeTextSize.height / 2
        }

        val nodeBounds = NodeBounds(
            node = node,
            x = startX,
            y = nodeY,
            width = nodeTextSize.width,
            height = nodeTextSize.height,
            childBounds = childBounds,
            colorIndex = colorIndex,
            isRoot = level == 0
        )

        // Link children to parent (game-style API)
        childBounds.forEach { child ->
            child.link(nodeBounds)
            linkChildrenRecursive(child)
        }

        return nodeBounds
    }

    private fun calculateSubtreeTop(bounds: NodeBounds): Double {
        var top = bounds.y
        bounds.childBounds.forEach { child ->
            val childTop = calculateSubtreeTop(child)
            top = minOf(top, childTop)
        }
        return top
    }

    private fun calculateSubtreeBottom(bounds: NodeBounds): Double {
        var bottom = bounds.bottomY
        bounds.childBounds.forEach { child ->
            val childBottom = calculateSubtreeBottom(child)
            bottom = maxOf(bottom, childBottom)
        }
        return bottom
    }

    fun collectAllNodes(bounds: NodeBounds, result: MutableList<NodeBounds>) {
        result.add(bounds)
        bounds.childBounds.forEach { collectAllNodes(it, result) }
    }

    fun getCollapsedNodeIds(): Set<String> {
        return collapsedNodeIds.toSet()
    }

    /**
     * Helper function to recursively link children to their parent
     */
    private fun linkChildrenRecursive(parent: NodeBounds) {
        parent.childBounds.forEach { child ->
            child.link(parent)
            linkChildrenRecursive(child)
        }
    }

    fun findNodeAt(bounds: NodeBounds?, x: Double, y: Double): NodeBounds? {
        if (bounds == null) return null

        fun search(currentBounds: NodeBounds): NodeBounds? {
            // Search children first (they're on top)
            var deepestChild: NodeBounds? = null
            currentBounds.childBounds.forEach { child ->
                val foundChild = search(child)
                if (foundChild != null) {
                    deepestChild = foundChild
                }
            }

            if (deepestChild != null) {
                return deepestChild
            }

            // Check if this node contains the point
            return if (currentBounds.contains(x, y)) currentBounds else null
        }

        return search(bounds)
    }

    private fun calculateTotalHeight(subtreeHeights: List<Double>, spacingMultiplier: Double): Double {
        return if (subtreeHeights.isNotEmpty()) {
            subtreeHeights.sum() + (subtreeHeights.size - 1) * MindmapConstants.VERTICAL_SPACING * spacingMultiplier
        } else {
            0.0
        }
    }
}

