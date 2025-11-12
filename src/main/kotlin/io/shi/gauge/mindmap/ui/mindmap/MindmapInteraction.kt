package io.shi.gauge.mindmap.ui.mindmap

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import java.nio.file.Paths
import javax.swing.SwingUtilities

/**
 * Handles user interactions (mouse, keyboard)
 */
class MindmapInteraction(
    private val project: Project,
    private val layout: MindmapLayout,
    private val viewport: MindmapViewport,
    private val onNodeClick: (NodeBounds) -> Unit,
    private val onNodeDoubleClick: (NodeBounds) -> Unit,
    private val onCollapseToggle: (String) -> Unit,
    private val onRepaint: () -> Unit
) {
    private var selectedNodeId: String? = null
    private var hoveredNodeId: String? = null
    private val hoveredChildrenIds = mutableSetOf<String>()
    private var isDragging = false
    private var lastMouseX = 0
    private var lastMouseY = 0
    private var singleClickTimer: javax.swing.Timer? = null
    private var pendingSingleClickNode: NodeBounds? = null

    fun getHoverState(): MindmapRenderer.HoverState {
        return MindmapRenderer.HoverState(hoveredNodeId, hoveredChildrenIds)
    }

    fun getSelectionState(): MindmapRenderer.SelectionState {
        return MindmapRenderer.SelectionState(selectedNodeId)
    }

    fun handleMousePressed(x: Int, y: Int, button: Int, rootBounds: NodeBounds?) {
        if (button == 1 || button == 2) { // Left or middle button
            isDragging = true
            lastMouseX = x
            lastMouseY = y

            if (button == 1) { // Left button
                val (worldX, worldY) = viewport.screenToWorld(x, y)
                layout.findNodeAt(rootBounds, worldX, worldY)?.let { node ->
                    selectedNodeId = node.node.id
                    onRepaint()
                }
            }
        }
    }

    fun handleMouseReleased() {
        isDragging = false
    }

    fun handleMouseDragged(x: Int, y: Int, rootBounds: NodeBounds?, viewportWidth: Int, viewportHeight: Int) {
        if (isDragging) {
            val dx = x - lastMouseX
            val dy = y - lastMouseY
            viewport.pan(dx.toDouble(), dy.toDouble())

            // Constrain pan bounds
            rootBounds?.let { bounds ->
                val contentBounds = calculateContentBounds(bounds)
                viewport.constrainPanBounds(
                    contentBounds.minX,
                    contentBounds.maxX,
                    contentBounds.minY,
                    contentBounds.maxY,
                    viewportWidth,
                    viewportHeight
                )
            }

            lastMouseX = x
            lastMouseY = y
            // Don't call onRepaint here - let MindmapView handle throttling
        }
    }

    private fun calculateContentBounds(bounds: NodeBounds): ContentBounds {
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = Double.MIN_VALUE
        var maxY = Double.MIN_VALUE

        fun calculate(currentBounds: NodeBounds) {
            minX = minOf(minX, currentBounds.x)
            minY = minOf(minY, currentBounds.y)
            maxX = maxOf(maxX, currentBounds.x + currentBounds.width)
            maxY = maxOf(maxY, currentBounds.y + currentBounds.height)
            currentBounds.childBounds.forEach { calculate(it) }
        }

        calculate(bounds)

        return ContentBounds(minX, maxX, minY, maxY)
    }

    private data class ContentBounds(
        val minX: Double,
        val maxX: Double,
        val minY: Double,
        val maxY: Double
    )

    fun handleMouseMoved(x: Int, y: Int, rootBounds: NodeBounds?) {
        val (worldX, worldY) = viewport.screenToWorld(x, y)
        val node = layout.findNodeAt(rootBounds, worldX, worldY)
        val newNodeId = node?.node?.id

        if (hoveredNodeId != newNodeId) {
            hoveredNodeId = newNodeId
            hoveredChildrenIds.clear()
            node?.let { collectAllChildrenIds(it, hoveredChildrenIds) }
        }

        onRepaint()
    }

    fun handleMouseExited() {
        hoveredNodeId = null
        hoveredChildrenIds.clear()
        onRepaint()
    }

    fun handleMouseWheel(rotation: Int, rootBounds: NodeBounds?, viewportWidth: Int, viewportHeight: Int) {
        if (rotation < 0) {
            viewport.zoomIn(viewportWidth, viewportHeight)
        } else {
            viewport.zoomOut(viewportWidth, viewportHeight)
        }

        // Constrain pan bounds after zoom
        rootBounds?.let { bounds ->
            val contentBounds = calculateContentBounds(bounds)
            viewport.constrainPanBounds(
                contentBounds.minX,
                contentBounds.maxX,
                contentBounds.minY,
                contentBounds.maxY,
                viewportWidth,
                viewportHeight
            )
        }

        // Don't call onRepaint here - let MindmapView handle throttling
    }

    fun handleMouseClicked(x: Int, y: Int, clickCount: Int, rootBounds: NodeBounds?) {
        if (clickCount == 0) return

        val (worldX, worldY) = viewport.screenToWorld(x, y)
        val node = layout.findNodeAt(rootBounds, worldX, worldY) ?: return

        if (clickCount == 2) {
            // Double click - open file
            singleClickTimer?.stop()
            singleClickTimer = null
            pendingSingleClickNode = null

            if (!node.isRoot) {
                selectedNodeId = node.node.id
                onRepaint()
                openFile(node)
            }
        } else if (clickCount == 1) {
            // Single click - collapse/expand
            if (node.node.children.isNotEmpty()) {
                singleClickTimer?.stop()
                pendingSingleClickNode = node

                singleClickTimer = javax.swing.Timer(MindmapConstants.SINGLE_CLICK_DELAY_MS) { _ ->
                    val clickedNode = pendingSingleClickNode
                    if (clickedNode != null && clickedNode.node.children.isNotEmpty()) {
                        onCollapseToggle(clickedNode.node.id)
                    }
                    singleClickTimer = null
                    pendingSingleClickNode = null
                }
                singleClickTimer?.isRepeats = false
                singleClickTimer?.start()
            }
        }
    }

    private fun openFile(node: NodeBounds) {
        val filePath = when (val data = node.node.data) {
            is io.shi.gauge.mindmap.model.Specification -> data.filePath
            is io.shi.gauge.mindmap.model.Scenario -> {
                findParentSpecification(node, node.node.id)?.filePath
            }

            else -> null
        }

        if (filePath != null) {
            val virtualFile = VirtualFileManager.getInstance().findFileByNioPath(
                Paths.get(filePath)
            ) ?: return

            val fileEditorManager = FileEditorManager.getInstance(project)
            val editors = fileEditorManager.openFile(virtualFile, true)

            if (node.node.data is io.shi.gauge.mindmap.model.Scenario) {
                val scenario = node.node.data as io.shi.gauge.mindmap.model.Scenario
                val targetLine = scenario.lineNumber

                SwingUtilities.invokeLater {
                    val actualEditors = fileEditorManager.selectedEditors
                    val editorToUse = actualEditors.firstOrNull { it is Editor } as? Editor
                        ?: editors.firstOrNull { it is Editor } as? Editor

                    if (editorToUse != null) {
                        val document = editorToUse.document
                        if (document.lineCount > 0) {
                            val line = (targetLine - 1).coerceIn(0, document.lineCount - 1)
                            val logicalPosition = LogicalPosition(line, 0)
                            editorToUse.caretModel.moveToLogicalPosition(logicalPosition)

                            val scrollTimer = javax.swing.Timer(100) { _ ->
                                editorToUse.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                            }
                            scrollTimer.isRepeats = false
                            scrollTimer.start()
                        } else {
                            val retryTimer = javax.swing.Timer(200) { _ ->
                                val line = (targetLine - 1).coerceIn(0, document.lineCount - 1)
                                val logicalPosition = LogicalPosition(line, 0)
                                editorToUse.caretModel.moveToLogicalPosition(logicalPosition)
                                editorToUse.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                            }
                            retryTimer.isRepeats = false
                            retryTimer.start()
                        }
                    }
                }
            }
        }
    }

    private fun findParentSpecification(node: NodeBounds, targetId: String): io.shi.gauge.mindmap.model.Specification? {
        fun searchRecursive(
            bounds: NodeBounds?,
            target: String,
            parentSpec: io.shi.gauge.mindmap.model.Specification?
        ): io.shi.gauge.mindmap.model.Specification? {
            if (bounds == null) return null

            if (bounds.node.id == target) {
                return parentSpec
            }

            val currentSpec = when (bounds.node.data) {
                is io.shi.gauge.mindmap.model.Specification -> bounds.node.data as io.shi.gauge.mindmap.model.Specification
                else -> parentSpec
            }

            bounds.childBounds.forEach { child ->
                val result = searchRecursive(child, target, currentSpec)
                if (result != null) return result
            }

            return null
        }

        return searchRecursive(node, targetId, null)
    }

    private fun collectAllChildrenIds(bounds: NodeBounds, childrenIds: MutableSet<String>) {
        bounds.childBounds.forEach { child ->
            childrenIds.add(child.node.id)
            collectAllChildrenIds(child, childrenIds)
        }
    }

    fun cleanup() {
        singleClickTimer?.stop()
        singleClickTimer = null
    }
}

