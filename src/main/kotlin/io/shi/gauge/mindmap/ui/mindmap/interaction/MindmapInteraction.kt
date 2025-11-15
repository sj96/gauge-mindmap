package io.shi.gauge.mindmap.ui.mindmap.interaction

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import io.shi.gauge.mindmap.model.Scenario
import io.shi.gauge.mindmap.model.Specification
import io.shi.gauge.mindmap.ui.mindmap.constants.MindmapConstants
import io.shi.gauge.mindmap.ui.mindmap.layout.MindmapLayout
import io.shi.gauge.mindmap.ui.mindmap.layout.MindmapViewport
import io.shi.gauge.mindmap.ui.mindmap.model.HoverState
import io.shi.gauge.mindmap.ui.mindmap.model.NodeBounds
import io.shi.gauge.mindmap.ui.mindmap.model.SelectionState
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

    fun getHoverState(): HoverState {
        return HoverState(hoveredNodeId, hoveredChildrenIds)
    }

    fun getSelectionState(): SelectionState {
        return SelectionState(selectedNodeId)
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
            val mouseDeltaX = x - lastMouseX
            val mouseDeltaY = y - lastMouseY
            viewport.pan(mouseDeltaX.toDouble(), mouseDeltaY.toDouble())

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

    fun handleMouseWheel(rotation: Int, rootBounds: NodeBounds?, viewportWidth: Int, viewportHeight: Int, mouseX: Int, mouseY: Int) {
        if (rotation < 0) {
            viewport.zoomIn(viewportWidth, viewportHeight, mouseX.toDouble(), mouseY.toDouble())
        } else {
            viewport.zoomOut(viewportWidth, viewportHeight, mouseX.toDouble(), mouseY.toDouble())
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

        if (clickCount == 1) {
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

    fun handleRightClick(x: Int, y: Int, rootBounds: NodeBounds?): NodeBounds? {
        val (worldX, worldY) = viewport.screenToWorld(x, y)
        val node = layout.findNodeAt(rootBounds, worldX, worldY)
        if (node != null && !node.isRoot) {
            selectedNodeId = node.node.id
            onRepaint()
            return node
        }
        return null
    }

    fun openFile(node: NodeBounds) {
        val filePath = when (val data = node.node.data) {
            is Specification -> data.filePath
            is Scenario -> {
                // Find parent specification by traversing up the parent chain
                findParentSpecificationFromNode(node)?.filePath
            }

            else -> null
        }

        if (filePath != null) {
            val virtualFile = VirtualFileManager.getInstance().findFileByNioPath(
                Paths.get(filePath)
            ) ?: return

            val fileEditorManager = FileEditorManager.getInstance(project)
            val editors = fileEditorManager.openFile(virtualFile, true)

            if (node.node.data is Scenario) {
                val scenario = node.node.data as Scenario
                val targetLine = scenario.lineNumber

                // Navigate to line with retry mechanism
                fun navigateToLine(attempt: Int = 0) {
                    ApplicationManager.getApplication().invokeLater {
                        try {
                            val actualEditors = fileEditorManager.selectedEditors
                            var editorToUse = actualEditors.firstOrNull { it is Editor } as? Editor
                            
                            // If no selected editor, try to get from opened editors
                            if (editorToUse == null) {
                                editorToUse = editors.firstOrNull { it is Editor } as? Editor
                            }
                            
                            // If still no editor, try to get current editor for the file
                            if (editorToUse == null) {
                                val currentEditors = fileEditorManager.getEditors(virtualFile)
                                editorToUse = currentEditors.firstOrNull { it is Editor } as? Editor
                            }

                            if (editorToUse != null) {
                                try {
                                    val document = editorToUse.document
                                    if (document.lineCount > 0) {
                                        val line = (targetLine - 1).coerceIn(0, document.lineCount - 1)
                                        val logicalPosition = LogicalPosition(line, 0)
                                        
                                        // Move caret to the line
                                        editorToUse.caretModel.moveToLogicalPosition(logicalPosition)
                                        
                                        // Scroll to caret with a small delay to ensure editor is ready
                                        val scrollTimer = javax.swing.Timer(50) { _ ->
                                            try {
                                                editorToUse.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                                            } catch (e: Exception) {
                                                // Ignore exceptions during scroll
                                            }
                                        }
                                        scrollTimer.isRepeats = false
                                        scrollTimer.start()
                                    } else if (attempt < 10) {
                                        // Retry if document is not ready yet
                                        val retryTimer = javax.swing.Timer(100 * (attempt + 1)) { _ ->
                                            navigateToLine(attempt + 1)
                                        }
                                        retryTimer.isRepeats = false
                                        retryTimer.start()
                                    }
                                } catch (e: Exception) {
                                    // If we get any exception (including CannotReadException), retry
                                    if (attempt < 10) {
                                        val retryTimer = javax.swing.Timer(100 * (attempt + 1)) { _ ->
                                            navigateToLine(attempt + 1)
                                        }
                                        retryTimer.isRepeats = false
                                        retryTimer.start()
                                    }
                                }
                            } else if (attempt < 10) {
                                // Retry if editor is not ready yet
                                val retryTimer = javax.swing.Timer(100 * (attempt + 1)) { _ ->
                                    navigateToLine(attempt + 1)
                                }
                                retryTimer.isRepeats = false
                                retryTimer.start()
                            }
                        } catch (e: Exception) {
                            // If we get any other exception, retry
                            if (attempt < 10) {
                                val retryTimer = javax.swing.Timer(100 * (attempt + 1)) { _ ->
                                    navigateToLine(attempt + 1)
                                }
                                retryTimer.isRepeats = false
                                retryTimer.start()
                            }
                        }
                    }
                }
                
                // Start navigation
                navigateToLine()
            }
        }
    }

    private fun findParentSpecificationFromNode(node: NodeBounds): Specification? {
        // Traverse up the parent chain to find the specification node
        var current: NodeBounds? = node.parent
        
        while (current != null) {
            when (val data = current.node.data) {
                is Specification -> return data
                else -> current = current.parent
            }
        }
        
        return null
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

