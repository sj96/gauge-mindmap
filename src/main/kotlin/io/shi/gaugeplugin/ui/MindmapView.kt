package io.shi.gaugeplugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileEvent
import com.intellij.openapi.vfs.VirtualFilePropertyEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.util.messages.MessageBusConnection
import com.intellij.ui.JBColor
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.ui.Messages
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import io.shi.gaugeplugin.model.Scenario
import io.shi.gaugeplugin.model.Specification
import io.shi.gaugeplugin.service.GaugeSpecScanner
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.AffineTransform
import java.awt.geom.CubicCurve2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.max

class MindmapView(private val project: Project) : JPanel() {
    private val specifications = mutableListOf<Specification>()
    private val scanner = GaugeSpecScanner(project)
    
    // Track current directory/file being displayed
    private var currentFiles: MutableList<VirtualFile> = mutableListOf()
    
    // File change listener connections
    private var fileListenerConnection: MessageBusConnection? = null
    private var vfsListener: VirtualFileListener? = null
    private val documentListeners = mutableMapOf<Document, DocumentListener>()
    
    // Reload with debouncing and delay to avoid too many reloads while typing
    private var reloadPending = false
    private var reloadTimer: javax.swing.Timer? = null
    
    // Helper function to check if a file should trigger reload
    private fun shouldReloadForFile(file: VirtualFile?): Boolean {
        if (file == null || !file.name.endsWith(".spec") || currentFiles.isEmpty()) {
            return false
        }
        
        // Check if file is within any of the current files/folders
        return currentFiles.any { currentFile ->
            if (currentFile.isDirectory) {
                // Check if file is within this directory
                var parent = file.parent
                while (parent != null) {
                    if (parent == currentFile) {
                        return@any true
                    }
                    parent = parent.parent
                }
                false
            } else {
                // Current file is a single file - check if it's the same file
                file == currentFile
            }
        }
    }
    
    private fun scheduleReload(delayMs: Long = 500) {
        // Cancel existing timer if any
        reloadTimer?.stop()
        
        // Create new timer with delay
        reloadTimer = javax.swing.Timer(delayMs.toInt()) {
            if (!reloadPending) {
                reloadPending = true
                SwingUtilities.invokeLater {
                    reloadPending = false
                    reloadCurrentView()
                }
            }
            reloadTimer?.stop()
        }.apply {
            isRepeats = false
            start()
        }
    }

    // Pan and zoom
    private var offsetX = 0.0
    private var offsetY = 0.0
    private var scale = 1.0
    
    // Content bounds for pan/zoom constraints
    private var contentMinX = 0.0
    private var contentMinY = 0.0
    private var contentMaxX = 0.0
    private var contentMaxY = 0.0
    private var lastMouseX = 0
    private var lastMouseY = 0
    private var isDragging = false

    // Filter
    private var filterText: String = ""

    // Layout constants - parent nodes are more prominent
    private val rootNodeMinHeight = 60.0
    private val rootNodePadding = 18.0
    private val rootNodeMinWidth = 120.0
    private val rootNodeMaxWidth = 450.0

    private val specNodeMinHeight = 50.0
    private val specNodePadding = 12.0
    private val specNodeMinWidth = 100.0 // Minimum width for spec nodes
    private val specNodeMaxWidth = 400.0 // Maximum width for spec nodes

    private val scenarioNodeMinHeight = 40.0
    private val scenarioNodePadding = 10.0
    private val scenarioNodeMinWidth = 80.0 // Minimum width for scenario nodes
    private val scenarioNodeMaxWidth = 350.0 // Maximum width for scenario nodes

    private val horizontalSpacing = 180.0 // Fixed horizontal spacing between levels
    private val verticalSpacing = 40.0 // Vertical spacing between nodes - optimized for readability
    private val cornerRadius = 12.0

    // Helper function to create JBColor from RGB (same color for dark and light theme)
    private fun jbColor(r: Int, g: Int, b: Int): JBColor = JBColor(Color(r, g, b), Color(r, g, b))
    private fun jbColor(r: Int, g: Int, b: Int, a: Int): JBColor = JBColor(Color(r, g, b, a), Color(r, g, b, a))

    // Colors - Dark theme with bright text
    private val rootBgColor = jbColor(70, 130, 180) // Steel blue
    private val rootBorderColor = jbColor(100, 149, 237) // Cornflower blue
    private val rootTextColor = JBColor.WHITE

    // Line colors optimized for dark theme - eye-friendly colors
    private val branchLineColors = listOf(
        jbColor(64, 224, 208),   // Turquoise/Cyan - very visible on dark
        jbColor(100, 181, 246),  // Light Blue - easy on eyes
        jbColor(129, 199, 132),  // Light Green - natural and comfortable
        jbColor(255, 183, 77),   // Amber/Orange - warm and visible
        jbColor(186, 104, 200),  // Purple - distinct
        jbColor(239, 154, 154),  // Light Pink/Red - soft and visible
        jbColor(255, 213, 79),   // Light Yellow - bright but not harsh
        jbColor(144, 202, 249)   // Sky Blue - calming
    )
    private val specTextColor = JBColor.WHITE
    private val scenarioTextColor = jbColor(240, 240, 240) // Light gray text
    private val backgroundColor = jbColor(30, 30, 35) // Dark background

    // Selection and hover tracking - use node IDs for reliable comparison
    private var selectedNodeId: String? = null
    private var hoveredNodeId: String? = null

    // Track hovered node's children IDs for highlighting
    private val hoveredChildrenIds = mutableSetOf<String>()
    
    // Track collapsed nodes - nodes in this set are collapsed (children hidden)
    private val collapsedNodeIds = mutableSetOf<String>()
    
    // Timer to delay single click handling (to distinguish from double click)
    private var singleClickTimer: javax.swing.Timer? = null
    private var pendingSingleClickNode: NodeBounds? = null

    // Node data for layout calculation - supports multiple levels
    private data class TreeNode(
        val id: String,
        val text: String,
        val icon: String? = null,
        val badge: String? = null,
        val tags: List<String> = emptyList(),
        val level: Int = 0,
        val children: List<TreeNode> = emptyList(),
        val data: Any? = null // Original data (Specification or Scenario)
    )

    private data class NodeBounds(
        val node: TreeNode,
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
        val childBounds: List<NodeBounds>,
        val colorIndex: Int,
        val isRoot: Boolean = false
    )

    private var rootNodeBounds: NodeBounds? = null
    private val allNodeBounds = mutableListOf<NodeBounds>()

    // Performance optimization: double buffering and viewport culling
    private var lastRepaintTime = 0L
    private val repaintThrottleMs = 16L // ~60 FPS max
    
    // Minimap component
    private lateinit var minimap: MinimapView
    private var minimapVisible = true

    init {
        background = backgroundColor
        isOpaque = true
        // Enable double buffering for smooth rendering
        isDoubleBuffered = true

        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                // Check if click is inside minimap - if so, don't handle pan
                // Also check if event was already consumed by minimap
                if (e.isConsumed || (minimapVisible && ::minimap.isInitialized && isPointInMinimap(e.x, e.y))) {
                    return // Let minimap handle the event
                }
                
                if (SwingUtilities.isLeftMouseButton(e) || SwingUtilities.isMiddleMouseButton(e)) {
                    isDragging = true
                    lastMouseX = e.x
                    lastMouseY = e.y

                    // Check for node selection
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        // Transform screen coordinates to world coordinates
                        // Transform order: translate(offsetX, offsetY) then scale(scale, scale)
                        // So world point (x, y) maps to screen: (x * scale + offsetX, y * scale + offsetY)
                        // Reverse: screen (sx, sy) maps to world: ((sx - offsetX) / scale, (sy - offsetY) / scale)
                        val mouseX = (e.x - offsetX) / scale
                        val mouseY = (e.y - offsetY) / scale
                        findNodeAt(mouseX, mouseY)?.let {
                            selectedNodeId = it.node.id
                            repaint()
                        }
                    }
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                isDragging = false
            }

            override fun mouseExited(e: MouseEvent) {
                hoveredNodeId = null
                hoveredChildrenIds.clear()
                repaint()
            }

            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                
                // Transform screen coordinates to world coordinates
                val mouseX = (e.x - offsetX) / scale
                val mouseY = (e.y - offsetY) / scale
                
                val node = findNodeAt(mouseX, mouseY)
                if (node == null) return
                
                // Handle double click to open file (priority - cancel single click timer)
                if (e.clickCount == 2) {
                    // Cancel pending single click
                    singleClickTimer?.stop()
                    singleClickTimer = null
                    pendingSingleClickNode = null
                    
                    if (!node.isRoot) {
                        // Set selected node for highlight
                        selectedNodeId = node.node.id
                        repaint()
                        
                        // Get file path from node data
                        val filePath = when (val data = node.node.data) {
                            is Specification -> data.filePath
                            is Scenario -> {
                                // For scenario, find parent specification
                                findParentSpecification(node)?.filePath
                            }
                            else -> null
                        }
                        
                        if (filePath != null) {
                            // Open file in editor
                            val virtualFile = VirtualFileManager.getInstance().findFileByNioPath(
                                java.nio.file.Paths.get(filePath)
                            ) ?: return
                            
                            val fileEditorManager = FileEditorManager.getInstance(project)
                            val editors = fileEditorManager.openFile(virtualFile, true)
                            
                            // If node is a Scenario, navigate to its line number
                            if (node.node.data is Scenario) {
                                val scenario = node.node.data as Scenario
                                val targetLine = scenario.lineNumber // 1-based line number
                                
                                // Wait for editor to be ready and document to be loaded
                                SwingUtilities.invokeLater {
                                    // Get the actual editor from FileEditorManager (may be different from returned editors)
                                    val actualEditors = fileEditorManager.getSelectedEditors()
                                    val editorToUse = actualEditors.firstOrNull { it is Editor } as? Editor
                                        ?: editors.firstOrNull { it is Editor } as? Editor
                                    
                                    if (editorToUse != null) {
                                        // Wait for document to be loaded
                                        val document = editorToUse.document
                                        if (document.lineCount > 0) {
                                            // Navigate to line (lineNumber is 1-based, LogicalPosition is 0-based)
                                            val line = (targetLine - 1).coerceIn(0, document.lineCount - 1)
                                            val logicalPosition = LogicalPosition(line, 0)
                                            
                                            // Move caret first
                                            editorToUse.caretModel.moveToLogicalPosition(logicalPosition)
                                            
                                            // Scroll to caret position with a small delay to ensure editor is fully rendered
                                            val scrollTimer = javax.swing.Timer(100) { _ ->
                                                editorToUse.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                                            }
                                            scrollTimer.isRepeats = false
                                            scrollTimer.start()
                                        } else {
                                            // Document not ready yet, try again after a delay
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
                }
                // Handle single click for collapse/expand (delay to distinguish from double click)
                else if (e.clickCount == 1) {
                    // Only allow collapse/expand for nodes with children
                    // Use node.node.children (original data) instead of childBounds (layout data)
                    // because childBounds will be empty when collapsed
                    if (node.node.children.isNotEmpty()) {
                        // Cancel previous pending single click
                        singleClickTimer?.stop()
                        
                        // Store node for delayed processing
                        pendingSingleClickNode = node
                        
                        // Create timer to delay single click handling
                        // This allows double click to cancel it
                        singleClickTimer = javax.swing.Timer(300) { _ -> // 300ms delay (standard double-click timeout)
                            val clickedNode = pendingSingleClickNode
                            if (clickedNode != null && clickedNode.node.children.isNotEmpty()) {
                                // Store node ID and its current screen position to maintain position after layout update
                                val nodeIdToMaintain = clickedNode.node.id
                                // Calculate current screen Y position of the node center
                                // Screen Y = (World Y + offsetY) * scale
                                val nodeWorldCenterY = clickedNode.y + clickedNode.height / 2
                                val nodeScreenCenterY = (nodeWorldCenterY + offsetY) * scale
                                
                                // Toggle collapse state
                                if (collapsedNodeIds.contains(clickedNode.node.id)) {
                                    collapsedNodeIds.remove(clickedNode.node.id)
                                } else {
                                    collapsedNodeIds.add(clickedNode.node.id)
                                }
                                
                                // Recalculate layout and repaint
                                calculateLayout()
                                
                                // Maintain the node's screen position after layout update
                                SwingUtilities.invokeLater {
                                    maintainNodeScreenPosition(nodeIdToMaintain, nodeScreenCenterY)
                                }
                                
                                repaint()
                            }
                            
                            // Cleanup
                            singleClickTimer = null
                            pendingSingleClickNode = null
                        }
                        singleClickTimer?.isRepeats = false
                        singleClickTimer?.start()
                    }
                }
            }
        })

        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                // Check if event was consumed by minimap or if drag started in minimap
                if (e.isConsumed || (minimapVisible && ::minimap.isInitialized && isPointInMinimap(e.x, e.y))) {
                    return // Let minimap handle the event
                }
                
                if (isDragging) {
                    val dx = e.x - lastMouseX
                    val dy = e.y - lastMouseY
                    val newOffsetX = offsetX + dx / scale
                    val newOffsetY = offsetY + dy / scale
                    
                    // Apply bounds constraints to prevent panning too far from content
                    val constrainedOffsets = constrainPanBounds(newOffsetX, newOffsetY)
                    offsetX = constrainedOffsets.first
                    offsetY = constrainedOffsets.second
                    
                    lastMouseX = e.x
                    lastMouseY = e.y
                    repaint()
                    if (::minimap.isInitialized) {
                        minimap.repaint()
                    }
                }
            }

            override fun mouseMoved(e: MouseEvent) {
                // Throttle hover repaints for performance
                val now = System.currentTimeMillis()
                if (now - lastRepaintTime < repaintThrottleMs) return

                // Transform screen coordinates to world coordinates
                // Transform order: translate(offsetX, offsetY) then scale(scale, scale)
                // So world point (x, y) maps to screen: (x * scale + offsetX, y * scale + offsetY)
                // Reverse: screen (sx, sy) maps to world: ((sx - offsetX) / scale, (sy - offsetY) / scale)
                val mouseX = (e.x - offsetX) / scale
                val mouseY = (e.y - offsetY) / scale
                
                val node = findNodeAt(mouseX, mouseY)
                val newNodeId = node?.node?.id
                
                // Always update hover state and repaint, even if hovering the same node
                // This ensures the node highlights correctly when hovered
                val hoverStateChanged = hoveredNodeId != newNodeId
                if (hoverStateChanged) {
                    hoveredNodeId = newNodeId

                    // Collect all children IDs of hovered node (including nested children) for highlighting
                    hoveredChildrenIds.clear()
                    if (node != null) {
                        collectAllChildrenIds(node, hoveredChildrenIds)
                    }
                }
                
                // Always repaint on mouse move to ensure hover highlight is visible
                // This ensures the node highlights correctly even when hovering the same node
                lastRepaintTime = now
                repaint()
            }
        })

        addMouseWheelListener { e ->
            val rotation = e.wheelRotation
            val zoomFactor = if (rotation < 0) 1.1 else 0.9
            scale *= zoomFactor
            scale = scale.coerceIn(0.1, 5.0)
            repaint()
            if (::minimap.isInitialized) {
                minimap.repaint()
            }
        }

        // Add component listener for responsive reflow
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                // Reflow layout when component is resized
                if (rootNode != null) {
                    calculateLayout()
                    updatePreferredSize()
                    repaint()
                }
            }
            
            override fun componentShown(e: ComponentEvent?) {
                // Auto-fit to center when view is first shown
                // Use a small delay to ensure layout is complete
                SwingUtilities.invokeLater {
                    if (rootNodeBounds != null && width > 0 && height > 0) {
                        fitToCenter()
                    } else {
                        // Retry after a short delay if not ready yet
                        val retryTimer = javax.swing.Timer(100) { _ ->
                            if (rootNodeBounds != null && width > 0 && height > 0) {
                                fitToCenter()
                            }
                        }
                        retryTimer.isRepeats = false
                        retryTimer.start()
                    }
                }
            }
        })

        // Register file change listener to auto-update mindmap when spec files change
        setupFileChangeListener()

        // Create and add minimap
        minimap = MinimapView()
        layout = null // Use absolute layout for overlay
        add(minimap)
        // Set component order to ensure minimap is on top and receives mouse events first
        setComponentZOrder(minimap, 0) // 0 = topmost
        
        // Don't set preferredSize here - let getPreferredSize() handle it
    }
    
    override fun doLayout() {
        super.doLayout()
        // Position minimap at top-right corner
        if (::minimap.isInitialized && rootNodeBounds != null) {
            // Calculate minimap size dynamically based on content
            val margin = 10
            val maxMinimapWidth = 300
            val maxMinimapHeight = 200
            val minMinimapWidth = 150
            val minMinimapHeight = 100
            
            // Calculate content bounds
            var minX = Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxX = Double.MIN_VALUE
            var maxY = Double.MIN_VALUE
            
            fun calculateBounds(bounds: NodeBounds) {
                minX = minOf(minX, bounds.x)
                minY = minOf(minY, bounds.y)
                maxX = maxOf(maxX, bounds.x + bounds.width)
                maxY = maxOf(maxY, bounds.y + bounds.height)
                bounds.childBounds.forEach { calculateBounds(it) }
            }
            
            calculateBounds(rootNodeBounds!!)
            
            val contentWidth = maxX - minX
            val contentHeight = maxY - minY
            
            if (contentWidth > 0 && contentHeight > 0) {
                // Fixed width, calculate height based on content aspect ratio
                // Height is limited to viewport height
                val minimapWidth = 83 // Fixed width (1/3 of 250)
                
                val contentAspectRatio = contentWidth / contentHeight
                val calculatedHeight = minimapWidth / contentAspectRatio
                val minimapHeight = minOf(calculatedHeight.toInt(), height - margin * 2) // Limit to viewport height
                
                minimap.setBounds(width - minimapWidth - margin, margin, minimapWidth, minimapHeight)
            } else {
                // Fallback to default size
                minimap.setBounds(width - 83 - margin, margin, 83, 50)
            }
            minimap.isVisible = minimapVisible
        } else if (::minimap.isInitialized) {
            // Fallback when no content
            minimap.setBounds(width - 83 - 10, 10, 83, 50)
            minimap.isVisible = minimapVisible
        }
    }
    
    fun setMinimapVisible(visible: Boolean) {
        minimapVisible = visible
        if (::minimap.isInitialized) {
            minimap.isVisible = visible
            repaint()
        }
    }
    
    // Inner class for minimap view
    private inner class MinimapView : JPanel() {
        private var isDraggingViewport = false
        private var dragStartX = 0
        private var dragStartY = 0
        private var dragStartOffsetX = 0.0
        private var dragStartOffsetY = 0.0
        
        init {
            // Make component transparent but still receive mouse events
            isOpaque = false
            // Ensure component can receive mouse events
            isEnabled = true
            // No border on component - border will be drawn in overlay to move with content
            // Fixed width, height will be set dynamically in doLayout() based on content
            // Height is limited to viewport height
            preferredSize = java.awt.Dimension(83, 50)
            minimumSize = java.awt.Dimension(83, 50)
            maximumSize = java.awt.Dimension(83, 2000) // Reasonable max, actual limit is viewport height
            isVisible = minimapVisible
            
            // Set cursor to indicate interactivity
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e) && rootNodeBounds != null) {
                        // Consume event immediately to prevent main view from handling it
                        e.consume()
                        
                        // Convert to screen coordinates
                        val screenPoint = SwingUtilities.convertPoint(this@MinimapView, e.x, e.y, this@MindmapView)
                        val screenX = screenPoint.x
                        val screenY = screenPoint.y
                        
                        // Check if clicking on viewport rectangle
                        val viewportRect = getViewportRectInMinimap()
                        if (viewportRect != null && viewportRect.contains(screenX.toDouble(), screenY.toDouble())) {
                            // Start dragging viewport rectangle
                            isDraggingViewport = true
                            dragStartX = screenX
                            dragStartY = screenY
                            dragStartOffsetX = offsetX
                            dragStartOffsetY = offsetY
                        } else {
                            // Click outside viewport - move viewport to clicked position
                            moveViewportFromMinimap(screenX, screenY)
                        }
                    }
                }
                
                override fun mouseReleased(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        e.consume() // Consume to prevent main view from handling
                    }
                    isDraggingViewport = false
                }
            })
            
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    if (isDraggingViewport && rootNodeBounds != null) {
                        // Consume event immediately to prevent main view from handling it
                        e.consume()
                        
                        // Convert to screen coordinates
                        val screenPoint = SwingUtilities.convertPoint(this@MinimapView, e.x, e.y, this@MindmapView)
                        val screenX = screenPoint.x
                        val screenY = screenPoint.y
                        
                        // Drag viewport rectangle
                        val dx = screenX - dragStartX
                        val dy = screenY - dragStartY
                        
                        // Calculate minimap scale
                        var minX = Double.MAX_VALUE
                        var minY = Double.MAX_VALUE
                        var maxX = Double.MIN_VALUE
                        var maxY = Double.MIN_VALUE
                        
                        fun calculateBounds(bounds: NodeBounds) {
                            minX = minOf(minX, bounds.x)
                            minY = minOf(minY, bounds.y)
                            maxX = maxOf(maxX, bounds.x + bounds.width)
                            maxY = maxOf(maxY, bounds.y + bounds.height)
                            bounds.childBounds.forEach { calculateBounds(it) }
                        }
                        
                        calculateBounds(rootNodeBounds!!)
                        
                        val contentWidth = maxX - minX
                        val contentHeight = maxY - minY
                        if (contentWidth > 0 && contentHeight > 0) {
                            // Fixed width minimap, height limited to viewport
                            val minimapWidth = 83.0 // Fixed width (1/3 of 250)
                            val margin = 10
                            val padding = 5.0
                            
                            val contentAspectRatio = contentWidth / contentHeight
                            val calculatedHeight = minimapWidth / contentAspectRatio
                            val minimapHeight = minOf(calculatedHeight, (this@MindmapView.height - margin * 2).toDouble()) // Limit to viewport height
                            
                            val availableWidth = minimapWidth - padding * 2
                            val availableHeight = minimapHeight - padding * 2
                            val scaleX = availableWidth / contentWidth
                            val scaleY = availableHeight / contentHeight
                            val minimapScale = minOf(scaleX, scaleY)
                            
                            // Convert minimap delta to world delta
                            val worldDx = dx / minimapScale
                            val worldDy = dy / minimapScale
                            
                            // Update offset
                            val newOffsetX = dragStartOffsetX - worldDx * scale
                            val newOffsetY = dragStartOffsetY - worldDy * scale
                            
                            // Apply constraints
                            val constrainedOffsets = constrainPanBounds(newOffsetX, newOffsetY)
                            offsetX = constrainedOffsets.first
                            offsetY = constrainedOffsets.second
                            
                            this@MindmapView.repaint()
                        }
                    }
                }
            })
        }
        
        private fun getViewportRectInMinimap(): Rectangle2D.Double? {
            if (rootNodeBounds == null) return null
            
            // Calculate bounds
            var minX = Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxX = Double.MIN_VALUE
            var maxY = Double.MIN_VALUE
            
            fun calculateBounds(bounds: NodeBounds) {
                minX = minOf(minX, bounds.x)
                minY = minOf(minY, bounds.y)
                maxX = maxOf(maxX, bounds.x + bounds.width)
                maxY = maxOf(maxY, bounds.y + bounds.height)
                bounds.childBounds.forEach { calculateBounds(it) }
            }
            
            calculateBounds(rootNodeBounds!!)
            
            val contentWidth = maxX - minX
            val contentHeight = maxY - minY
            if (contentWidth <= 0 || contentHeight <= 0) return null
            
            // Fixed width minimap, height limited to viewport
            val minimapWidth = 83.0 // Fixed width (1/3 of 250)
            val margin = 10
            val padding = 5.0
            
            val contentAspectRatio = contentWidth / contentHeight
            val calculatedHeight = minimapWidth / contentAspectRatio
            val minimapHeight = minOf(calculatedHeight, (this@MindmapView.height - margin * 2).toDouble()) // Limit to viewport height
            
            val minimapX = this@MindmapView.width - minimapWidth.toInt() - margin
            
            val availableWidth = minimapWidth - padding * 2
            val availableHeight = minimapHeight - padding * 2
            val scaleX = availableWidth / contentWidth
            val scaleY = availableHeight / contentHeight
            val minimapScale = minOf(scaleX, scaleY)
            
            val viewportWorldX = -offsetX / scale
            val viewportWorldY = -offsetY / scale
            val viewportWorldWidth = this@MindmapView.width / scale
            val viewportWorldHeight = this@MindmapView.height / scale
            
            val viewportMinimapX = (viewportWorldX - minX) * minimapScale + minimapX + padding
            val viewportMinimapY = (viewportWorldY - minY) * minimapScale + margin + padding
            val viewportMinimapWidth = viewportWorldWidth * minimapScale
            val viewportMinimapHeight = viewportWorldHeight * minimapScale
            
            return Rectangle2D.Double(viewportMinimapX, viewportMinimapY, viewportMinimapWidth, viewportMinimapHeight)
        }
        
        private fun moveViewportFromMinimap(screenX: Int, screenY: Int) {
            if (rootNodeBounds == null) return
            
            // Calculate bounds of entire mindmap
            var minX = Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxX = Double.MIN_VALUE
            var maxY = Double.MIN_VALUE
            
            fun calculateBounds(bounds: NodeBounds) {
                minX = minOf(minX, bounds.x)
                minY = minOf(minY, bounds.y)
                maxX = maxOf(maxX, bounds.x + bounds.width)
                maxY = maxOf(maxY, bounds.y + bounds.height)
                bounds.childBounds.forEach { calculateBounds(it) }
            }
            
            calculateBounds(rootNodeBounds!!)
            
            val contentWidth = maxX - minX
            val contentHeight = maxY - minY
            if (contentWidth <= 0 || contentHeight <= 0) return
            
            // Fixed width minimap, height limited to viewport
            val minimapWidth = 83.0 // Fixed width (1/3 of 250)
            val margin = 10
            val padding = 5.0
            
            val contentAspectRatio = contentWidth / contentHeight
            val calculatedHeight = minimapWidth / contentAspectRatio
            val minimapHeight = minOf(calculatedHeight, (this@MindmapView.height - margin * 2).toDouble()) // Limit to viewport height
            
            val minimapX = this@MindmapView.width - minimapWidth.toInt() - margin
            val minimapY = margin
            
            val relativeX = screenX - minimapX - padding
            val relativeY = screenY - minimapY - padding
            
            // Calculate minimap scale
            val availableWidth = minimapWidth - padding * 2
            val availableHeight = minimapHeight - padding * 2
            val scaleX = availableWidth / contentWidth
            val scaleY = availableHeight / contentHeight
            val minimapScale = minOf(scaleX, scaleY)
            
            // Convert minimap coordinates to world coordinates
            val worldX = relativeX / minimapScale + minX
            val worldY = relativeY / minimapScale + minY
            
            // Center viewport on clicked position
            val newOffsetX = (this@MindmapView.width / 2.0 / scale - worldX) * scale
            val newOffsetY = (this@MindmapView.height / 2.0 / scale - worldY) * scale
            
            // Apply constraints
            val constrainedOffsets = constrainPanBounds(newOffsetX, newOffsetY)
            offsetX = constrainedOffsets.first
            offsetY = constrainedOffsets.second
            
            this@MindmapView.repaint()
        }
        
        // MinimapView is now only used for mouse event handling
        // Actual rendering is done in MindmapView.paintComponent via drawMinimapOverlay
        override fun paintComponent(g: Graphics) {
            // Don't draw anything - minimap is drawn in parent's paintComponent
            // This component is only for mouse event handling
            // But we need to ensure it can receive mouse events even when transparent
        }
        
        // Override contains to ensure component always receives mouse events within its bounds
        override fun contains(x: Int, y: Int): Boolean {
            // Always return true if point is within bounds, even if component is transparent
            // This ensures mouse events are delivered to this component
            return x >= 0 && x < width && y >= 0 && y < height
        }
    }
    
    private fun setupFileChangeListener() {
        // Use VirtualFileManager listener for file system events
        vfsListener = object : VirtualFileListener {
            override fun contentsChanged(event: VirtualFileEvent) {
                if (shouldReloadForFile(event.file)) {
                    scheduleReload()
                }
            }
            
            override fun fileCreated(event: VirtualFileEvent) {
                if (shouldReloadForFile(event.file)) {
                    scheduleReload()
                }
            }
            
            override fun fileDeleted(event: VirtualFileEvent) {
                if (shouldReloadForFile(event.file)) {
                    scheduleReload()
                }
            }
            
            override fun propertyChanged(event: VirtualFilePropertyEvent) {
                if (event.propertyName == VirtualFile.PROP_NAME && shouldReloadForFile(event.file)) {
                    scheduleReload()
                }
            }
        }
        VirtualFileManager.getInstance().addVirtualFileListener(vfsListener!!, project)
        
        // Also listen to FileDocumentManager for when files are saved (more real-time)
        fileListenerConnection = project.messageBus.connect()
        fileListenerConnection?.subscribe(
            FileDocumentManagerListener.TOPIC,
            object : FileDocumentManagerListener {
                override fun fileContentReloaded(file: com.intellij.openapi.vfs.VirtualFile, document: com.intellij.openapi.editor.Document) {
                    if (shouldReloadForFile(file)) {
                        scheduleReload(100) // Quick reload after file reload
                    }
                }
                
                override fun fileWithNoDocumentChanged(file: com.intellij.openapi.vfs.VirtualFile) {
                    if (shouldReloadForFile(file)) {
                        scheduleReload(100)
                    }
                }
                
                override fun beforeDocumentSaving(document: com.intellij.openapi.editor.Document) {
                    // Don't reload before saving
                }
                
                override fun beforeAllDocumentsSaving() {
                    // Don't reload before saving
                }
                
                override fun beforeFileContentReload(file: com.intellij.openapi.vfs.VirtualFile, document: com.intellij.openapi.editor.Document) {
                    // Don't reload before reload
                }
            }
        )
        
        // Listen to document changes in editors (real-time updates while typing)
        EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorCreated(event: com.intellij.openapi.editor.event.EditorFactoryEvent) {
                    val editor = event.editor
                    val file = FileDocumentManager.getInstance().getFile(editor.document)
                    
                    if (shouldReloadForFile(file)) {
                        // Add document listener to track changes
                        val documentListener = object : DocumentListener {
                            override fun documentChanged(event: DocumentEvent) {
                                // Schedule reload with delay to avoid too many reloads while typing
                                scheduleReload(800) // 800ms delay after typing stops
                            }
                        }
                        editor.document.addDocumentListener(documentListener)
                        documentListeners[editor.document] = documentListener
                    }
                }
                
                override fun editorReleased(event: com.intellij.openapi.editor.event.EditorFactoryEvent) {
                    val document = event.editor.document
                    documentListeners.remove(document)?.let {
                        document.removeDocumentListener(it)
                    }
                }
            },
            project
        )
        
        // Also add listeners to existing editors
        // Get all open documents from FileDocumentManager
        val fileDocumentManager = FileDocumentManager.getInstance()
        EditorFactory.getInstance().allEditors.forEach { editor ->
            val document = editor.document
            val file = fileDocumentManager.getFile(document)
            if (shouldReloadForFile(file) && !documentListeners.containsKey(document)) {
                val documentListener = object : DocumentListener {
                    override fun documentChanged(event: DocumentEvent) {
                        scheduleReload(800) // 800ms delay after typing stops
                    }
                }
                document.addDocumentListener(documentListener)
                documentListeners[document] = documentListener
            }
        }
    }
    
    private fun reloadCurrentView() {
        // Reload the current view (without auto-fit, as this is a content change reload)
        loadSpecifications(currentFiles, autoFit = false)
    }
    
    override fun removeNotify() {
        super.removeNotify()
        // Cleanup file listeners when component is removed
        fileListenerConnection?.disconnect()
        fileListenerConnection = null
        vfsListener?.let {
            VirtualFileManager.getInstance().removeVirtualFileListener(it)
        }
        vfsListener = null
        
        // Cleanup document listeners
        documentListeners.forEach { (document, listener) ->
            document.removeDocumentListener(listener)
        }
        documentListeners.clear()
        
        // Stop reload timer
        reloadTimer?.stop()
        reloadTimer = null
    }

    // Find node at mouse position
    // Returns the deepest (most nested) node that contains the point
    private fun findNodeAt(x: Double, y: Double): NodeBounds? {
        fun search(bounds: NodeBounds): NodeBounds? {
            // First, recursively search all children to find the deepest matching node
            // Children are on top and should have priority
            var deepestChild: NodeBounds? = null
            bounds.childBounds.forEach { child ->
                val foundChild = search(child)
                if (foundChild != null) {
                    // Prefer deeper (more nested) nodes
                    deepestChild = foundChild
                }
            }
            
            // If we found a child node, return it (child has priority)
            if (deepestChild != null) {
                return deepestChild
            }
            
            // If no child contains the point, check if this node contains it
            val isInBounds = x >= bounds.x && x <= bounds.x + bounds.width &&
                    y >= bounds.y && y <= bounds.y + bounds.height
            
            return if (isInBounds) bounds else null
        }

        return rootNodeBounds?.let { search(it) }
    }

    // Find parent specification node for a given node (used for Scenario nodes)
    private fun findParentSpecification(node: NodeBounds): Specification? {
        // Search from root to find the specification parent of the target node
        fun searchRecursive(bounds: NodeBounds?, targetId: String, parentSpec: Specification?): Specification? {
            if (bounds == null) return null
            
            // If this is the target node, return the parent spec we've been tracking
            if (bounds.node.id == targetId) {
                return parentSpec
            }
            
            // Track if this node is a Specification (update parentSpec)
            val currentSpec = when (bounds.node.data) {
                is Specification -> bounds.node.data as Specification
                else -> parentSpec
            }
            
            // Search in children
            bounds.childBounds.forEach { child ->
                val result = searchRecursive(child, targetId, currentSpec)
                if (result != null) return result
            }
            
            return null
        }
        
        return searchRecursive(rootNodeBounds, node.node.id, null)
    }

    fun loadSpecifications(directory: VirtualFile? = null, autoFit: Boolean = true) {
        // Support single file/folder (backward compatibility)
        val files = if (directory != null) listOf(directory) else emptyList()
        loadSpecifications(files, autoFit)
    }
    
    fun loadSpecifications(files: List<VirtualFile>, autoFit: Boolean = true) {
        // Save current files for file change listener
        val previousFiles = currentFiles.toList()
        val isFirstLoad = previousFiles.isEmpty() && rootNodeBounds == null
        currentFiles = files.toMutableList()
        
        // Clear collapsed nodes when loading new files/folders to ensure all nodes are expanded
        collapsedNodeIds.clear()
        
        // Run scan in background with progress indicator
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Scanning Gauge Specifications", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Scanning specification files..."
                    indicator.fraction = 0.0
                    
                    specifications.clear()
                    
                    if (files.isNotEmpty()) {
                        // Count total files to scan for progress calculation
                        var totalFiles = 0
                        val filesToScan = mutableListOf<VirtualFile>()
                        
                        files.forEach { file ->
                            if (file.isDirectory) {
                                // Count spec files in directory
                                fun countSpecFiles(dir: VirtualFile): Int {
                                    var count = 0
                                    for (child in dir.children) {
                                        if (child.isDirectory) {
                                            count += countSpecFiles(child)
                                        } else if (child.name.endsWith(".spec")) {
                                            count++
                                            filesToScan.add(child)
                                        }
                                    }
                                    return count
                                }
                                totalFiles += countSpecFiles(file)
                            } else if (file.name.endsWith(".spec")) {
                                totalFiles++
                                filesToScan.add(file)
                            } else {
                                // If it's not a spec file, scan the parent directory
                                file.parent?.let { parent ->
                                    fun countSpecFiles(dir: VirtualFile): Int {
                                        var count = 0
                                        for (child in dir.children) {
                                            if (child.isDirectory) {
                                                count += countSpecFiles(child)
                                            } else if (child.name.endsWith(".spec")) {
                                                count++
                                                filesToScan.add(child)
                                            }
                                        }
                                        return count
                                    }
                                    totalFiles += countSpecFiles(parent)
                                }
                            }
                        }
                        
                        // Scan files with progress
                        var scannedFiles = 0
                        files.forEach { file ->
                            if (file.isDirectory) {
                                indicator.text = "Scanning directory: ${file.name}..."
                                val specs = scanner.scanDirectory(file)
                                specifications.addAll(specs)
                                scannedFiles += specs.size
                                if (totalFiles > 0) {
                                    indicator.fraction = scannedFiles.toDouble() / totalFiles * 0.7
                                }
                            } else if (file.name.endsWith(".spec")) {
                                indicator.text = "Scanning file: ${file.name}..."
                                val specs = scanner.scanFile(file)
                                specifications.addAll(specs)
                                scannedFiles++
                                if (totalFiles > 0) {
                                    indicator.fraction = scannedFiles.toDouble() / totalFiles * 0.7
                                }
                            } else {
                                file.parent?.let { parent ->
                                    indicator.text = "Scanning directory: ${parent.name}..."
                                    val specs = scanner.scanDirectory(parent)
                                    specifications.addAll(specs)
                                    scannedFiles += specs.size
                                    if (totalFiles > 0) {
                                        indicator.fraction = scannedFiles.toDouble() / totalFiles * 0.7
                                    }
                                }
                            }
                        }
                    } else {
                        indicator.text = "Scanning entire project..."
                        specifications.addAll(scanner.scanProject())
                        indicator.fraction = 0.7
                    }
                    
                    indicator.text = "Building tree structure..."
                    indicator.fraction = 0.8
                    buildTreeStructure()
                    
                    indicator.text = "Calculating layout..."
                    indicator.fraction = 0.9
                    calculateLayout()
                    
                    indicator.fraction = 1.0
                    
                    // Update UI on EDT
                    SwingUtilities.invokeLater {
                        updatePreferredSize()
                        repaint()
                        
                        // Auto-fit to center when:
                        // 1. Loading for the first time (isFirstLoad)
                        // 2. Loading new files/folders (files changed)
                        // But NOT when reloading due to content changes (autoFit = false)
                        val filesChanged = previousFiles.size != files.size || 
                                          previousFiles.zip(files).any { (prev, curr) -> prev != curr }
                        if (autoFit && (isFirstLoad || filesChanged)) {
                            // Use invokeLater with retry mechanism to ensure component is visible and has valid size
                            fun tryFitToCenter(attempt: Int = 0) {
                                if (rootNodeBounds != null && width > 0 && height > 0) {
                                    fitToCenter()
                                } else if (attempt < 5) {
                                    // Retry up to 5 times with increasing delay
                                    val delay = 50 * (attempt + 1)
                                    val retryTimer = javax.swing.Timer(delay) { _ ->
                                        tryFitToCenter(attempt + 1)
                                    }
                                    retryTimer.isRepeats = false
                                    retryTimer.start()
                                }
                            }
                            tryFitToCenter()
                        }
                    }
                } catch (e: Exception) {
                    // Show error notification
                    SwingUtilities.invokeLater {
                        val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("Gauge Mindmap")
                        notificationGroup.createNotification(
                            "Scan Failed",
                            "Failed to scan specifications: ${e.message}",
                            NotificationType.ERROR
                        ).notify(project)
                    }
                }
            }
        })
    }

    // Convert specifications to tree structure (no icons/emojis for performance)
    private fun buildTreeStructure() {
        val rootChildren = specifications.mapIndexed { index, spec ->
            TreeNode(
                id = "spec_${spec.filePath}",
                text = spec.name,
                icon = null, // No icon for performance
                badge = null, // No badge for performance
                tags = listOf("spec"),
                level = 1,
                children = spec.scenarios.map { scenario ->
                    TreeNode(
                        id = "scenario_${spec.filePath}_${scenario.lineNumber}",
                        text = scenario.name,
                        icon = null, // No icon for performance
                        tags = listOf("scenario"),
                        level = 2,
                        data = scenario
                    )
                },
                data = spec
            )
        }

        // Root node
        rootNode = TreeNode(
            id = "root",
            text = "Gauge",
            icon = null, // No icon for performance
            tags = listOf("root"),
            level = 0,
            children = rootChildren
        )
    }

    private var rootNode: TreeNode? = null

    private fun calculateLayout() {
        allNodeBounds.clear()
        rootNodeBounds = null
        if (rootNode == null) return

        // Filter tree nodes
        val filteredRoot = filterTree(rootNode!!, filterText)
        if (filteredRoot == null) return

        // Calculate layout recursively - root starts at left
        val rootX = 150.0
        val baseStartY = 200.0

        // Calculate root node size first (larger, more prominent)
        val rootText = buildNodeText(filteredRoot)
        val rootNodeSize = calculateTextSize(rootText, 0.0, rootNodeMinHeight, rootNodePadding, true)

        // Calculate root's right edge for children positioning
        val rootRightX = rootX + rootNodeSize.width
        val childrenStartX = rootRightX + horizontalSpacing

        // First pass: calculate all children layouts to get actual subtree heights
        // Each child gets unique colorIndex (0, 1, 2, ...) for unique branch colors
        val tempChildren = mutableListOf<NodeBounds>()
        val tempSubtreeHeights = mutableListOf<Double>() // Store actual subtree heights
        
        filteredRoot.children.forEachIndexed { index, child ->
            val childLayout = calculateNodeLayout(child, 1, childrenStartX, 0.0, index)
            if (childLayout != null) {
                tempChildren.add(childLayout)
                
                // Calculate actual subtree height: from topmost node to bottommost node in this branch
                val subtreeTop = calculateSubtreeTop(childLayout)
                val subtreeBottom = calculateSubtreeBottom(childLayout)
                val subtreeHeight = subtreeBottom - subtreeTop
                tempSubtreeHeights.add(subtreeHeight)
            }
        }

        // Calculate total children height: sum of all subtree heights + spacing between them
        // Use consistent spacing multiplier
        val spacingMultiplier = 0.8
        val totalChildrenHeight = if (tempSubtreeHeights.isNotEmpty()) {
            tempSubtreeHeights.sum() + (tempSubtreeHeights.size - 1) * verticalSpacing * spacingMultiplier
        } else {
            0.0
        }

        // Perfectly center root vertically with all children
        // Center point is at baseStartY + totalChildrenHeight / 2
        val centerY = baseStartY + totalChildrenHeight / 2

        // Second pass: reposition all children with correct center point
        val positionedChildren = mutableListOf<NodeBounds>()
        val childrenStartY = centerY - totalChildrenHeight / 2
        var currentChildTop = childrenStartY

        filteredRoot.children.forEachIndexed { index, child ->
            // Get child's actual subtree height (from topmost to bottommost node)
            val subtreeHeight = tempSubtreeHeights[index]
            
            // Center Y of this child subtree (including all nested children)
            // Place child so its center is at currentChildTop + subtreeHeight / 2
            val childCenterY = currentChildTop + subtreeHeight / 2

            val childLayout = calculateNodeLayout(child, 1, childrenStartX, childCenterY, index)
            positionedChildren.add(childLayout)

            // Calculate the actual bottom of this child subtree (including ALL nested children recursively)
            val actualBottom = calculateSubtreeBottom(childLayout)

            // Move to next child's top position: actual bottom of current child + spacing
            // This ensures no overlap - each child starts after the previous one completely ends
            currentChildTop = actualBottom + verticalSpacing * spacingMultiplier
        }

        // Recalculate actual children bounds after repositioning (including all nested children)
        val actualChildrenTop = if (positionedChildren.isNotEmpty()) {
            // Use helper function to calculate actual top recursively
            positionedChildren.minOfOrNull { child ->
                calculateSubtreeTop(child)
            } ?: positionedChildren.first().y
        } else {
            centerY
        }

        val actualChildrenBottom = if (positionedChildren.isNotEmpty()) {
            // Use helper function to calculate actual bottom recursively
            positionedChildren.maxOfOrNull { child ->
                calculateSubtreeBottom(child)
            } ?: centerY
        } else {
            centerY
        }

        // ALWAYS center root vertically with all children (including nested children)
        // This is the rule: parent node is always centered with all its children
        val actualChildrenCenter = (actualChildrenTop + actualChildrenBottom) / 2
        val rootY = actualChildrenCenter - rootNodeSize.height / 2

        rootNodeBounds = NodeBounds(
            node = filteredRoot,
            x = rootX,
            y = rootY,
            width = rootNodeSize.width,
            height = rootNodeSize.height, // Only root node's own height
            childBounds = positionedChildren,
            colorIndex = 0,
            isRoot = true
        )

        // Collect all nodes for rendering
        if (rootNodeBounds != null) {
            collectAllNodes(rootNodeBounds!!)
        }
    }

    // Filter tree nodes based on filter text
    private fun filterTree(node: TreeNode, filter: String): TreeNode? {
        if (filter.isBlank()) return node

        val matchesFilter = node.text.contains(filter, ignoreCase = true) ||
                node.tags.any { it.contains(filter, ignoreCase = true) } ||
                node.icon?.contains(filter, ignoreCase = true) == true ||
                node.badge?.contains(filter, ignoreCase = true) == true

        val filteredChildren = node.children.mapNotNull { child ->
            filterTree(child, filter)
        }

        return if (matchesFilter || filteredChildren.isNotEmpty()) {
            node.copy(children = filteredChildren)
        } else {
            null
        }
    }

    // Helper function to calculate the actual top of a subtree (including all nested children recursively)
    private fun calculateSubtreeTop(bounds: NodeBounds): Double {
        // Start with the node's own top
        var top = bounds.y
        
        // Recursively check all children and their nested children
        bounds.childBounds.forEach { child ->
            val childTop = calculateSubtreeTop(child)
            top = minOf(top, childTop)
        }
        
        return top
    }

    // Helper function to calculate the actual bottom of a subtree (including all nested children recursively)
    private fun calculateSubtreeBottom(bounds: NodeBounds): Double {
        // Start with the node's own bottom
        var bottom = bounds.y + bounds.height
        
        // Recursively check all children and their nested children
        bounds.childBounds.forEach { child ->
            val childBottom = calculateSubtreeBottom(child)
            bottom = maxOf(bottom, childBottom)
        }
        
        return bottom
    }

    // Recursive layout calculation - supports multiple levels, parent perfectly centered with all children
    // Each branch from root has unique color (colorIndex is unique per branch)
    private fun calculateNodeLayout(
        node: TreeNode,
        level: Int,
        startX: Double,
        startY: Double,
        colorIndex: Int // Unique color index for this branch from root
    ): NodeBounds {
        // Calculate node size (including icon, badge, text)
        val nodeText = buildNodeText(node)
        // Parent nodes are more prominent (larger size, bold)
        val padding = when {
            level == 0 -> rootNodePadding // Root - largest
            level == 1 -> specNodePadding // Spec - medium
            else -> scenarioNodePadding // Scenario and deeper - smaller
        }
        val minHeight = when {
            level == 0 -> rootNodeMinHeight
            level == 1 -> specNodeMinHeight
            else -> scenarioNodeMinHeight
        }
        val isBold = level <= 1 // Root and spec nodes are bold

        val nodeSize = calculateTextSize(nodeText, 0.0, minHeight, padding, isBold)

        // Calculate children layouts first (recursive) - supports unlimited levels
        val childBounds = mutableListOf<NodeBounds>()
        var totalChildrenHeight: Double

        // Check if node is collapsed - if so, don't calculate children
        val isCollapsed = collapsedNodeIds.contains(node.id)
        
        if (node.children.isNotEmpty() && !isCollapsed) {
            // Calculate children start position (to the right of parent)
            val parentRightX = startX + nodeSize.width
            val childrenStartX = parentRightX + horizontalSpacing * (if (level == 0) 1.0 else 0.5)

            // First pass: calculate all children layouts recursively to get actual subtree heights
            // Each child keeps the same colorIndex (same branch color from root)
            val tempChildBounds = mutableListOf<NodeBounds>()
            val tempSubtreeHeights = mutableListOf<Double>() // Store actual subtree heights
            
            node.children.forEachIndexed { _, child ->
                // Each child in the same branch keeps the same colorIndex
                val childBoundsResult = calculateNodeLayout(
                    child,
                    level + 1,
                    childrenStartX,
                    0.0, // Temporary Y, will recalculate
                    colorIndex // Same branch color - all children inherit parent's color
                )

                tempChildBounds.add(childBoundsResult)
                
                // Calculate actual subtree height: from topmost node to bottommost node in this branch
                val subtreeTop = calculateSubtreeTop(childBoundsResult)
                val subtreeBottom = calculateSubtreeBottom(childBoundsResult)
                val subtreeHeight = subtreeBottom - subtreeTop
                tempSubtreeHeights.add(subtreeHeight)
            }

            // Calculate total children height: sum of all subtree heights + spacing between them
            // Use consistent spacing multiplier for all levels
            val spacingMultiplier = if (level == 0) 0.8 else 0.7
            totalChildrenHeight = if (tempSubtreeHeights.isNotEmpty()) {
                tempSubtreeHeights.sum() + (tempSubtreeHeights.size - 1) * verticalSpacing * spacingMultiplier
            } else {
                0.0
            }

            // Center all children vertically relative to startY (parent center)
            val childrenCenterY = startY
            val childrenStartY = childrenCenterY - totalChildrenHeight / 2

            // Second pass: reposition all children sequentially to avoid overlap
            // Place each child right after the previous one with proper spacing
            var currentChildTop = childrenStartY
            node.children.forEachIndexed { index, child ->
                // Get child's actual subtree height (from topmost to bottommost node)
                val subtreeHeight = tempSubtreeHeights[index]

                // Calculate where this child's top should be
                // The child subtree (including all nested children) should start at currentChildTop
                // The center of this child subtree should be at currentChildTop + subtreeHeight / 2
                val childCenterY = currentChildTop + subtreeHeight / 2

                // Recalculate child layout with correct center Y position
                // This will position the child node and all its nested children
                val repositionedChild = calculateNodeLayout(
                    child,
                    level + 1,
                    childrenStartX,
                    childCenterY, // Center Y of entire child subtree
                    colorIndex // Same branch color
                )

                if (repositionedChild != null) {
                    childBounds.add(repositionedChild)
                    // Calculate the actual bottom of this child subtree (including ALL nested children recursively)
                    val actualBottom = calculateSubtreeBottom(repositionedChild)

                    // Move to next child's top position: actual bottom of current child + spacing
                    // This ensures no overlap - each child starts after the previous one completely ends
                    currentChildTop = actualBottom + verticalSpacing * spacingMultiplier
                }
            }

            // Recalculate total children height from actual repositioned bounds
            // Use actual top and bottom of all subtrees
            if (childBounds.isNotEmpty()) {
                val actualChildrenTop = childBounds.minOfOrNull { calculateSubtreeTop(it) } ?: childBounds.first().y
                val actualChildrenBottom = childBounds.maxOfOrNull { calculateSubtreeBottom(it) } ?: (childBounds.last().y + childBounds.last().height)
                totalChildrenHeight = actualChildrenBottom - actualChildrenTop
            }
        }

        // Node height is ONLY the node's own height, not including children
        // Children are positioned separately
        val nodeHeight = nodeSize.height

        // Calculate node Y position - ALWAYS center node vertically with all children
        var nodeY: Double
        if (childBounds.isNotEmpty()) {
            // Node has children - ALWAYS center node with all children (including nested children)
            // Find the actual top and bottom including all nested children
            // Use helper functions to calculate actual top and bottom recursively
            val actualChildrenTop = childBounds.minOfOrNull { child ->
                calculateSubtreeTop(child)
            } ?: childBounds.first().y

            val actualChildrenBottom = childBounds.maxOfOrNull { child ->
                calculateSubtreeBottom(child)
            } ?: (childBounds.last().y + childBounds.last().height)

            // Calculate center of all children (including nested)
            val childrenCenter = (actualChildrenTop + actualChildrenBottom) / 2

            // ALWAYS center node with children - this is the rule
            nodeY = childrenCenter - nodeHeight / 2
        } else {
            // No children - center node at startY
            nodeY = startY - nodeHeight / 2
        }

        return NodeBounds(
            node = node,
            x = startX,
            y = nodeY,
            width = nodeSize.width,
            height = nodeHeight, // Only node's own height
            childBounds = childBounds,
            colorIndex = colorIndex, // Unique color for this branch from root
            isRoot = level == 0
        )
    }

    // Build display text (no icons/emojis for performance)
    private fun buildNodeText(node: TreeNode): String {
        return node.text
    }

    // Collect all nodes for rendering and interaction
    private fun collectAllNodes(bounds: NodeBounds) {
        allNodeBounds.add(bounds)
        bounds.childBounds.forEach { collectAllNodes(it) }
    }

    // Collect all children IDs of a node (including nested children) for highlighting
    private fun collectAllChildrenIds(bounds: NodeBounds, childrenIds: MutableSet<String>) {
        bounds.childBounds.forEach { child ->
            childrenIds.add(child.node.id)
            collectAllChildrenIds(child, childrenIds) // Recursive for nested children
        }
    }
    
    // Update content bounds based on current layout
    private fun updateContentBounds() {
        if (rootNodeBounds == null) {
            contentMinX = 0.0
            contentMinY = 0.0
            contentMaxX = 0.0
            contentMaxY = 0.0
            return
        }
        
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = Double.MIN_VALUE
        var maxY = Double.MIN_VALUE
        
        fun calculateBounds(bounds: NodeBounds) {
            minX = minOf(minX, bounds.x)
            minY = minOf(minY, bounds.y)
            maxX = maxOf(maxX, bounds.x + bounds.width)
            maxY = maxOf(maxY, bounds.y + bounds.height)
            bounds.childBounds.forEach { calculateBounds(it) }
        }
        
        calculateBounds(rootNodeBounds!!)
        
        // Add padding to allow some panning beyond content
        // Use larger padding to allow more freedom when panning
        val padding = 200.0
        contentMinX = minX - padding
        contentMinY = minY - padding
        contentMaxX = maxX + padding
        contentMaxY = maxY + padding
    }
    
    // Constrain pan offsets - DISABLED: Allow free panning without constraints
    private fun constrainPanBounds(newOffsetX: Double, newOffsetY: Double): Pair<Double, Double> {
        // Return offsets unchanged - no constraints applied
        return Pair(newOffsetX, newOffsetY)
    }
    
    // Check if a point (in screen coordinates) is inside the minimap bounds
    private fun isPointInMinimap(screenX: Int, screenY: Int): Boolean {
        if (rootNodeBounds == null || !minimapVisible) return false
        
        // Calculate minimap bounds
        val margin = 10
        val minimapWidth = 83.0
        
        // Calculate content bounds to determine minimap height
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = Double.MIN_VALUE
        var maxY = Double.MIN_VALUE
        
        fun calculateBounds(bounds: NodeBounds) {
            minX = minOf(minX, bounds.x)
            minY = minOf(minY, bounds.y)
            maxX = maxOf(maxX, bounds.x + bounds.width)
            maxY = maxOf(maxY, bounds.y + bounds.height)
            bounds.childBounds.forEach { calculateBounds(it) }
        }
        
        calculateBounds(rootNodeBounds!!)
        
        val contentWidth = maxX - minX
        val contentHeight = maxY - minY
        if (contentWidth <= 0 || contentHeight <= 0) return false
        
        val contentAspectRatio = contentWidth / contentHeight
        val calculatedHeight = minimapWidth / contentAspectRatio
        val minimapHeight = minOf(calculatedHeight, (height - margin * 2).toDouble())
        
        val minimapX = width - minimapWidth.toInt() - margin
        val minimapY = margin
        
        // Check if point is inside minimap bounds
        return screenX >= minimapX && screenX <= minimapX + minimapWidth.toInt() &&
               screenY >= minimapY && screenY <= minimapY + minimapHeight.toInt()
    }

    private data class TextSize(val width: Double, val height: Double)

    private fun getJetBrainsFont(size: Int, isBold: Boolean): Font {
        // Try JetBrains Mono first, fallback to JetBrains, then system default
        val fontNames = listOf("JetBrains Mono", "JetBrainsMono", "JetBrains")
        val style = if (isBold) Font.BOLD else Font.PLAIN

        for (fontName in fontNames) {
            val font = Font(fontName, style, size)
            if (font.family == fontName || font.family.contains("JetBrains", ignoreCase = true)) {
                return font
            }
        }

        // Fallback to monospaced font if JetBrains not available
        return Font(Font.MONOSPACED, style, size)
    }

    private fun calculateTextSize(
        text: String,
        minWidth: Double,
        minHeight: Double,
        padding: Double,
        isBold: Boolean
    ): TextSize {
        // Use the same font sizes as in drawNodeContent for consistency
        // Root: 18, Spec (level 1): 15, Scenario (level 2+): 12
        val fontSize = when {
            padding == rootNodePadding -> 18
            isBold -> 15  // Spec node
            else -> 12    // Scenario node
        }
        val font = getJetBrainsFont(fontSize, isBold)

        val tempGraphics = createImage(1, 1).graphics as Graphics2D
        tempGraphics.font = font
        val fm = tempGraphics.fontMetrics

        // Reserve space for collapse/expand indicator if node might have children
        // Spec nodes (isBold && padding == specNodePadding) typically have children
        // Indicator is at center-right, so reserve space on the right side
        val indicatorSpace = if (isBold && padding == specNodePadding) 20.0 else 0.0
        
        // Determine max width for text wrapping (accounting for padding and indicator space on right)
        val maxTextWidth = when {
            padding == rootNodePadding -> rootNodeMaxWidth - padding * 2
            isBold -> specNodeMaxWidth - padding * 2 - indicatorSpace
            else -> scenarioNodeMaxWidth - padding * 2
        }

        // Only wrap text if it actually exceeds max width
        val actualTextWidth = fm.stringWidth(text).toDouble()
        val lines = if (actualTextWidth > maxTextWidth) {
            wrapText(text, fm, maxTextWidth)
        } else {
            listOf(text)
        }

        // Calculate actual text dimensions
        val textWidth = lines.maxOfOrNull { fm.stringWidth(it) }?.toDouble() ?: 0.0
        val textHeight = lines.size * fm.height.toDouble()

        // Calculate node width: text width + padding on both sides + indicator space
        val calculatedWidth = textWidth + padding * 2 + indicatorSpace

        // Apply min/max constraints - parent nodes can be larger
        val width = when {
            isBold && padding == rootNodePadding -> calculatedWidth.coerceIn(rootNodeMinWidth, rootNodeMaxWidth)
            isBold -> calculatedWidth.coerceIn(specNodeMinWidth, specNodeMaxWidth)
            else -> calculatedWidth.coerceIn(scenarioNodeMinWidth, scenarioNodeMaxWidth)
        }

        // Calculate node height: text height + padding on top and bottom
        // Padding must always be applied correctly
        val calculatedHeight = textHeight + padding * 2
        // Ensure minimum height is respected, but padding is always included
        val height = max(minHeight, calculatedHeight)

        // Verify padding is correct: height should be at least textHeight + padding * 2
        val finalHeight = max(height, textHeight + padding * 2)

        return TextSize(width, finalHeight)
    }

    private fun wrapText(text: String, fm: FontMetrics, maxWidth: Double): List<String> {
        if (fm.stringWidth(text) <= maxWidth) {
            return listOf(text)
        }

        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = ""

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (fm.stringWidth(testLine) <= maxWidth) {
                currentLine = testLine
            } else {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine)
                }
                currentLine = word
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine)
        }

        return lines
    }

    private fun updatePreferredSize() {
        // Don't set preferredSize - let getPreferredSize() handle it based on parent size
        // This ensures the view fits exactly in the viewport without scrolling
        revalidate()
    }
    
    override fun getPreferredSize(): Dimension {
        val parent = parent
        if (parent != null && parent.width > 0 && parent.height > 0) {
            // Return parent size to fit viewport exactly - no scrolling
            return Dimension(parent.width, parent.height)
        }
        // Fallback: return a reasonable default size
        return Dimension(800, 600)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2d = g as Graphics2D

        // Performance: only enable antialiasing if scale is reasonable
        if (scale >= 0.5) {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        } else {
            // Disable antialiasing for very small scale (far zoomed out) for performance
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        }

        // Apply transform for pan and zoom
        val transform = AffineTransform()
        transform.translate(offsetX, offsetY)
        transform.scale(scale, scale)
        g2d.transform(transform)

        // Viewport culling: calculate visible bounds for performance
        val viewportBounds = Rectangle2D.Double(
            -offsetX / scale,
            -offsetY / scale,
            width / scale,
            height / scale
        )

        if (allNodeBounds.isEmpty() && rootNodeBounds == null) {
            g2d.color = jbColor(200, 200, 200) // Light gray for dark background
            g2d.font = getJetBrainsFont(16, false)
            val message = "No Gauge specifications found"
            val fm = g2d.fontMetrics
            val x = (width / scale / 2 - fm.stringWidth(message) / 2).toInt()
            val y = (height / scale / 2).toInt()
            g2d.drawString(message, x, y)
            return
        }

        // Recursive rendering: draw connections first (behind nodes), then nodes
        // Only render nodes in viewport for performance
        rootNodeBounds?.let { rootBounds ->
            drawNodeRecursive(g2d, rootBounds, null, viewportBounds)
        }
        
        // Draw minimap overlay if visible (draw directly on main view to ensure it's on top)
        if (minimapVisible && rootNodeBounds != null) {
            drawMinimapOverlay(g2d)
        }
    }
    
    private fun drawMinimapOverlay(g2d: Graphics2D) {
        // Save current transform
        val savedTransform = g2d.transform
        
        // Reset transform for minimap (draw in screen coordinates)
        g2d.setTransform(AffineTransform())
        
        if (rootNodeBounds == null) {
            g2d.transform = savedTransform
            return
        }
        
        // Calculate bounds of entire mindmap
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = Double.MIN_VALUE
        var maxY = Double.MIN_VALUE
        
        fun calculateBounds(bounds: NodeBounds) {
            minX = minOf(minX, bounds.x)
            minY = minOf(minY, bounds.y)
            maxX = maxOf(maxX, bounds.x + bounds.width)
            maxY = maxOf(maxY, bounds.y + bounds.height)
            bounds.childBounds.forEach { calculateBounds(it) }
        }
        
        calculateBounds(rootNodeBounds!!)
        
        val contentWidth = maxX - minX
        val contentHeight = maxY - minY
        if (contentWidth <= 0 || contentHeight <= 0) {
            g2d.transform = savedTransform
            return
        }
        
        // Minimap with fixed width, calculate height based on content aspect ratio
        // Height is limited to viewport height
        val margin = 10
        val minimapWidth = 83.0 // Fixed width (1/3 of 250)
        val padding = 5.0
        
        // Calculate height based on content aspect ratio, but limit to viewport height
        val contentAspectRatio = contentWidth / contentHeight
        val calculatedHeight = minimapWidth / contentAspectRatio
        val minimapHeight = minOf(calculatedHeight, (height - margin * 2).toDouble()) // Limit to viewport height
        
        val minimapX = width - minimapWidth.toInt() - margin
        val minimapY = margin
        
        // Draw semi-transparent background
        g2d.color = jbColor(60, 60, 70, 220) // Lighter semi-transparent background
        g2d.fillRect(minimapX, minimapY, minimapWidth.toInt(), minimapHeight.toInt())
        
        // Draw border (moves with content since it's drawn in overlay)
        g2d.color = jbColor(80, 80, 90)
        g2d.stroke = BasicStroke(1.0f)
        g2d.drawRect(minimapX, minimapY, minimapWidth.toInt(), minimapHeight.toInt())
        
        // Calculate minimap scale
        val availableWidth = minimapWidth - padding * 2
        val availableHeight = minimapHeight - padding * 2
        val scaleX = availableWidth / contentWidth
        val scaleY = availableHeight / contentHeight
        // Use min scale to fit content
        val minimapScale = minOf(scaleX, scaleY)
        
        // Apply transform to center content in minimap
        val transform = AffineTransform()
        transform.translate(minimapX + padding, minimapY + padding)
        transform.scale(minimapScale, minimapScale)
        transform.translate(-minX, -minY)
        g2d.transform(transform)
        
        // Enable antialiasing for minimap
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        
        // Draw simplified mindmap (just nodes, no text, thinner lines)
        rootNodeBounds?.let { rootBounds ->
            drawMinimapRecursive(g2d, rootBounds, null)
        }
        
        // Reset transform for viewport rectangle
        g2d.setTransform(AffineTransform())
        
        // Draw viewport rectangle (VSCode style)
        val viewportWorldX = -offsetX / scale
        val viewportWorldY = -offsetY / scale
        val viewportWorldWidth = width / scale
        val viewportWorldHeight = height / scale
        
        val viewportMinimapX = (viewportWorldX - minX) * minimapScale + minimapX + padding
        val viewportMinimapY = (viewportWorldY - minY) * minimapScale + minimapY + padding
        val viewportMinimapWidth = viewportWorldWidth * minimapScale
        val viewportMinimapHeight = viewportWorldHeight * minimapScale
        
        val viewportRect = Rectangle2D.Double(viewportMinimapX, viewportMinimapY, viewportMinimapWidth, viewportMinimapHeight)
        
        // Fill with semi-transparent white/blue (VSCode style)
        g2d.color = jbColor(255, 255, 255, 30) // Very transparent white
        g2d.fill(viewportRect)
        
        // Border - bright blue/white (VSCode style)
        g2d.color = jbColor(200, 220, 255) // Light blue border
        g2d.stroke = BasicStroke(1.5f)
        g2d.draw(viewportRect)
        
        // Inner highlight
        g2d.color = jbColor(255, 255, 255, 60) // More visible inner highlight
        g2d.stroke = BasicStroke(1.0f)
        val innerRect = Rectangle2D.Double(
            viewportMinimapX + 1,
            viewportMinimapY + 1,
            viewportMinimapWidth - 2,
            viewportMinimapHeight - 2
        )
        g2d.draw(innerRect)
        
        // Restore transform
        g2d.transform = savedTransform
    }
    
    private fun drawMinimapRecursive(g2d: Graphics2D, bounds: NodeBounds, parentBounds: NodeBounds?) {
        // Draw connection line (simplified)
        parentBounds?.let { parent ->
            val parentRightX = parent.x + parent.width
            val parentCenterY = parent.y + parent.height / 2
            val childLeftX = bounds.x
            val childCenterY = bounds.y + bounds.height / 2
            
            val branchColor = branchLineColors[bounds.colorIndex % branchLineColors.size]
            g2d.color = branchColor
            g2d.stroke = BasicStroke(0.5f)
            g2d.drawLine(
                parentRightX.toInt(),
                parentCenterY.toInt(),
                childLeftX.toInt(),
                childCenterY.toInt()
            )
        }
        
        // Draw node (simplified - just rectangle)
        val nodeColor = getNodeColors(bounds).first
        g2d.color = nodeColor
        g2d.fill(Rectangle2D.Double(bounds.x, bounds.y, bounds.width, bounds.height))
        
        // Draw children
        if (!collapsedNodeIds.contains(bounds.node.id)) {
            bounds.childBounds.forEach { child ->
                drawMinimapRecursive(g2d, child, bounds)
            }
        }
    }

    // Recursive rendering function - draws connections and nodes with viewport culling
    private fun drawNodeRecursive(
        g2d: Graphics2D,
        bounds: NodeBounds,
        parentBounds: NodeBounds?,
        viewport: Rectangle2D.Double
    ) {
        // Step 1: Draw connection lines from parent to this node (behind nodes)
        // Always draw lines even if nodes are outside viewport, as lines may pass through viewport
        parentBounds?.let { parent ->
            val parentRightX = parent.x + parent.width
            val parentCenterY = parent.y + parent.height / 2
            val childLeftX = bounds.x
            val childCenterY = bounds.y + bounds.height / 2

            // Calculate curve points first
            val midX = (parentRightX + childLeftX) / 2
            val dy = childCenterY - parentCenterY

            val curve = CubicCurve2D.Double(
                parentRightX, parentCenterY,
                midX + (childLeftX - parentRightX) * 0.2, parentCenterY + dy * 0.3,
                midX - (childLeftX - parentRightX) * 0.2, childCenterY - dy * 0.3,
                childLeftX, childCenterY
            )

            // Check if line should be drawn:
            // 1. Either parent or child node intersects viewport
            // 2. Or the curve's bounding box intersects viewport (line may pass through viewport)
            val parentRect = Rectangle2D.Double(parent.x, parent.y, parent.width, parent.height)
            val childRect = Rectangle2D.Double(bounds.x, bounds.y, bounds.width, bounds.height)
            val parentInViewport = viewport.intersects(parentRect)
            val childInViewport = viewport.intersects(childRect)
            
            // Get curve's bounding box to check if line passes through viewport
            val curveBounds = curve.bounds2D
            val curveInViewport = viewport.intersects(curveBounds)
            
            // Draw line if any part is visible
            if (parentInViewport || childInViewport || curveInViewport) {
                // Get branch color
                val branchColor = branchLineColors[bounds.colorIndex % branchLineColors.size]

                // Check if this line should be highlighted
                // Highlight line if:
                // 1. Parent is the hovered node (line from hovered node to its child) - MOST IMPORTANT
                // 2. Child is the hovered node (line from parent to hovered node)
                // 3. Both parent and child are children of hovered node (line within hovered subtree)
                val isParentHovered = hoveredNodeId == parent.node.id
                val isChildHovered = hoveredNodeId == bounds.node.id
                val isParentInSubtree = hoveredChildrenIds.contains(parent.node.id)
                val isChildInSubtree = hoveredChildrenIds.contains(bounds.node.id)
                val isLineHighlighted = hoveredNodeId != null && (
                    isParentHovered || // Line from hovered node to its direct child - highlight this!
                    isChildHovered || // Line from parent to hovered node
                    (isParentInSubtree && isChildInSubtree) // Line within hovered subtree (both are children)
                )

                // Highlight line: brighter and thicker
                if (isLineHighlighted) {
                    g2d.color = jbColor(
                        (branchColor.red + 100).coerceIn(0, 255),
                        (branchColor.green + 100).coerceIn(0, 255),
                        (branchColor.blue + 100).coerceIn(0, 255)
                    )
                    val strokeWidth = if (bounds.node.level == 1) 2.5f else 2.0f
                    val dashPattern = if (bounds.node.level == 1) {
                        floatArrayOf(8.0f, 4.0f)
                    } else {
                        floatArrayOf(6.0f, 3.0f)
                    }
                    g2d.stroke =
                        BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0f, dashPattern, 0.0f)
                } else {
                    g2d.color = branchColor
                    val strokeWidth = if (bounds.node.level == 1) 1.5f else 1.2f
                    val dashPattern = if (bounds.node.level == 1) {
                        floatArrayOf(8.0f, 4.0f)
                    } else {
                        floatArrayOf(6.0f, 3.0f)
                    }
                    g2d.stroke =
                        BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0f, dashPattern, 0.0f)
                }

                // Draw smooth curved line
                g2d.draw(curve)
            }
        }
        
        // Check if node is collapsed - if so, don't draw children
        val isCollapsed = collapsedNodeIds.contains(bounds.node.id)
        
        // Viewport culling: skip node rendering if completely outside viewport
        val nodeBoundsRect = Rectangle2D.Double(bounds.x, bounds.y, bounds.width, bounds.height)
        if (!viewport.intersects(nodeBoundsRect)) {
            // Still need to draw children that might be visible (only if not collapsed)
            if (!isCollapsed) {
                bounds.childBounds.forEach { child ->
                    drawNodeRecursive(g2d, child, bounds, viewport)
                }
            }
            return
        }

        // Step 2: Draw children connections first (recursive, behind nodes) - only if not collapsed
        if (!isCollapsed) {
            bounds.childBounds.forEach { child ->
                drawNodeRecursive(g2d, child, bounds, viewport)
            }
        }

        // Step 3: Draw this node (on top of connections)
        val nodeRect = RoundRectangle2D.Double(
            bounds.x,
            bounds.y,
            bounds.width,
            bounds.height,
            if (bounds.isRoot) cornerRadius * 1.5 else cornerRadius,
            if (bounds.isRoot) cornerRadius * 1.5 else cornerRadius
        )

        // Get node colors based on level and tags
        val (bgColor, borderColor, textColor) = getNodeColors(bounds)

        // Apply hover/selection highlight - enhanced visibility
        val isHovered = hoveredNodeId == bounds.node.id
        val isSelected = selectedNodeId == bounds.node.id
        val isChildOfHovered = hoveredChildrenIds.contains(bounds.node.id) // Highlight children of hovered node

        val finalBgColor = when {
            isSelected -> brightenColor(bgColor, 0.3f) // More prominent when selected
            isHovered -> brightenColor(bgColor, 0.2f) // More visible when hovered
            isChildOfHovered -> brightenColor(bgColor, 0.15f) // Highlight children of hovered node
            else -> bgColor
        }

        // Draw node with shadow
        drawNodeWithShadow(g2d, nodeRect, finalBgColor, borderColor)

        // Draw selection/hover/child highlight border
        if (isSelected || isHovered || isChildOfHovered) {
            val highlightColor = when {
                isSelected -> JBColor.WHITE // Bright white for selection
                isHovered -> {
                    // Bright version of branch color for hover
                    val branchColor = branchLineColors[bounds.colorIndex % branchLineColors.size]
                    jbColor(
                        (branchColor.red + 100).coerceIn(0, 255),
                        (branchColor.green + 100).coerceIn(0, 255),
                        (branchColor.blue + 100).coerceIn(0, 255)
                    )
                }

                else -> {
                    // Highlight color for children of hovered node
                    val branchColor = branchLineColors[bounds.colorIndex % branchLineColors.size]
                    jbColor(
                        (branchColor.red + 80).coerceIn(0, 255),
                        (branchColor.green + 80).coerceIn(0, 255),
                        (branchColor.blue + 80).coerceIn(0, 255)
                    )
                }
            }
            g2d.color = highlightColor
            val strokeWidth = when {
                isSelected -> 3.0f
                isHovered -> 2.5f
                else -> 2.0f // Thinner for children
            }
            g2d.stroke = BasicStroke(strokeWidth)
            g2d.draw(nodeRect)

            // Add glow effect for hover/selection/children
            if (isHovered || isSelected || isChildOfHovered) {
                g2d.color = jbColor(highlightColor.red, highlightColor.green, highlightColor.blue, 50)
                g2d.stroke = BasicStroke(when {
                    isSelected -> 5.0f
                    isHovered -> 4.0f
                    else -> 3.0f // Glow for children
                })
                g2d.draw(nodeRect)
            }
        }

        // Draw node content (icon, text, badge, tags)
        drawNodeContent(g2d, bounds, nodeRect, textColor)
        
        // Draw collapse/expand indicator if node has children
        // Use node.node.children (original data) instead of childBounds (layout data)
        // because childBounds will be empty when collapsed
        if (bounds.node.children.isNotEmpty()) {
            val isCollapsed = collapsedNodeIds.contains(bounds.node.id)
            val indicatorSize = 14.0
            // Position indicator at center-right of the node
            val indicatorX = bounds.x + bounds.width - indicatorSize - 6
            val indicatorY = bounds.y + (bounds.height - indicatorSize) / 2 // Center vertically
            
            // Use a more visible color scheme for the indicator
            // Background: semi-transparent white/light gray for better contrast
            val indicatorBgColor = jbColor(255, 255, 255, 180) // White with good opacity
            val indicatorBorderColor = jbColor(200, 200, 200, 220) // Light gray border
            val indicatorIconColor = jbColor(60, 60, 70) // Dark gray/black for icon
            
            g2d.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            
            // Draw circle background with better contrast
            val circle = java.awt.geom.Ellipse2D.Double(indicatorX, indicatorY, indicatorSize, indicatorSize)
            g2d.color = indicatorBgColor
            g2d.fill(circle)
            g2d.color = indicatorBorderColor
            g2d.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2d.draw(circle)
            
            // Draw + or - sign with better color
            val centerX = indicatorX + indicatorSize / 2
            val centerY = indicatorY + indicatorSize / 2
            val lineLength = indicatorSize / 3.5
            
            g2d.color = indicatorIconColor
            g2d.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            
            // Horizontal line (always present)
            g2d.drawLine(
                (centerX - lineLength).toInt(),
                centerY.toInt(),
                (centerX + lineLength).toInt(),
                centerY.toInt()
            )
            
            // Vertical line (only if expanded)
            if (!isCollapsed) {
                g2d.drawLine(
                    centerX.toInt(),
                    (centerY - lineLength).toInt(),
                    centerX.toInt(),
                    (centerY + lineLength).toInt()
                )
            }
        }
    }

    // Get node colors - node color is brighter than line, different colors for different levels
    private fun getNodeColors(bounds: NodeBounds): Triple<JBColor, JBColor, JBColor> {
        return when {
            bounds.isRoot -> {
                // Root: Bright blue, prominent
                Triple(rootBgColor, rootBorderColor, rootTextColor)
            }

            bounds.node.level == 1 -> {
                // Level 1 (Spec): Bright, vibrant colors based on branch
                val branchColor = branchLineColors[bounds.colorIndex % branchLineColors.size]
                // Make node brighter and more saturated than line
                val bgColor = jbColor(
                    (branchColor.red * 0.7 + 50).toInt().coerceIn(0, 255),
                    (branchColor.green * 0.7 + 50).toInt().coerceIn(0, 255),
                    (branchColor.blue * 0.7 + 50).toInt().coerceIn(0, 255)
                )
                val borderColor = jbColor(
                    branchColor.red.coerceIn(0, 255),
                    branchColor.green.coerceIn(0, 255),
                    branchColor.blue.coerceIn(0, 255)
                )
                Triple(bgColor, borderColor, specTextColor)
            }

            else -> {
                // Level 2+ (Scenario): Softer, distinct colors but still brighter than line
                val branchColor = branchLineColors[bounds.colorIndex % branchLineColors.size]
                // Softer but still visible - different from level 1
                val bgColor = jbColor(
                    (branchColor.red * 0.5 + 30).toInt().coerceIn(0, 255),
                    (branchColor.green * 0.5 + 30).toInt().coerceIn(0, 255),
                    (branchColor.blue * 0.5 + 30).toInt().coerceIn(0, 255)
                )
                val borderColor = jbColor(
                    (branchColor.red * 0.8).toInt().coerceIn(0, 255),
                    (branchColor.green * 0.8).toInt().coerceIn(0, 255),
                    (branchColor.blue * 0.8).toInt().coerceIn(0, 255)
                )
                Triple(bgColor, borderColor, scenarioTextColor)
            }
        }
    }

    // Brighten color for hover/selection
    private fun brightenColor(color: JBColor, factor: Float): JBColor {
        val r = (color.red + (255 - color.red) * factor).toInt().coerceIn(0, 255)
        val g = (color.green + (255 - color.green) * factor).toInt().coerceIn(0, 255)
        val b = (color.blue + (255 - color.blue) * factor).toInt().coerceIn(0, 255)
        return jbColor(r, g, b)
    }

    // Draw node content: icon, text, badge, tags
    private fun drawNodeContent(g2d: Graphics2D, bounds: NodeBounds, rect: RoundRectangle2D.Double, textColor: Color) {
        val node = bounds.node
        // Parent nodes have larger font size
        val padding = when {
            bounds.isRoot -> rootNodePadding
            node.level == 1 -> specNodePadding
            else -> scenarioNodePadding
        }

        g2d.color = textColor
        // Parent nodes are more prominent with larger font
        g2d.font = getJetBrainsFont(
            if (bounds.isRoot) 18 else if (node.level == 1) 15 else 12,
            bounds.isRoot || node.level == 1
        )

        // Build display text
        val displayText = buildNodeText(node)
        val textRect = Rectangle2D.Double(rect.x, rect.y, rect.width, rect.height)
        // Pass information about whether node has children (for indicator space)
        val hasChildren = node.children.isNotEmpty()
        drawWrappedText(g2d, displayText, textRect, padding, hasChildren)

        // Draw tags if any (small badges at bottom right)
        if (node.tags.isNotEmpty() && node.tags.size > 1) {
            val tagFont = getJetBrainsFont(9, false)
            g2d.font = tagFont
            val fm = g2d.fontMetrics
            var tagX = rect.x + rect.width - padding
            val tagY = rect.y + rect.height - padding / 2

            node.tags.take(3).reversed().forEach { tag ->
                val tagText = "#$tag"
                val tagWidth = fm.stringWidth(tagText)
                tagX -= tagWidth + 4

                // Draw tag background
                val tagBg =
                    Rectangle2D.Double(tagX - 2, tagY - fm.height, tagWidth.toDouble() + 4, fm.height.toDouble())
                g2d.color = jbColor(0, 0, 0, 100)
                g2d.fill(tagBg)

                // Draw tag text
                g2d.color = jbColor(180, 180, 200)
                g2d.drawString(tagText, tagX.toInt(), tagY.toInt())
            }
        }
    }

    private fun drawNodeWithShadow(g: Graphics2D, rect: RoundRectangle2D.Double, fillColor: Color, borderColor: Color) {
        // Draw shadow
        val shadowOffset = 3.0
        val shadowRect = RoundRectangle2D.Double(
            rect.x + shadowOffset,
            rect.y + shadowOffset,
            rect.width,
            rect.height,
            rect.arcWidth,
            rect.arcHeight
        )
        g.color = jbColor(0, 0, 0, 30)
        g.fill(shadowRect)

        // Draw node without border
        g.color = fillColor
        g.fill(rect)
        // No border - removed stroke drawing
    }

    private fun drawWrappedText(g: Graphics2D, text: String, rect: Rectangle2D.Double, padding: Double, hasIndicator: Boolean = false) {
        val fm = g.fontMetrics

        // Calculate available text area with padding
        // Reserve space for collapse/expand indicator if node has children (indicator is at center-right)
        val indicatorSpace = if (hasIndicator) 20.0 else 0.0 // Space reserved for indicator (14px + 6px margin)
        val textAreaX = rect.x + padding // Text starts from left padding
        val textAreaY = rect.y + padding
        val textAreaWidth = rect.width - padding * 2 - indicatorSpace // Reduce width to account for indicator on right
        val textAreaHeight = rect.height - padding * 2

        // Only wrap text if it actually exceeds text area width
        val actualTextWidth = fm.stringWidth(text).toDouble()
        val lines = if (actualTextWidth > textAreaWidth) {
            wrapText(text, fm, textAreaWidth)
        } else {
            listOf(text)
        }

        // Calculate total text height
        val totalTextHeight = lines.size * fm.height.toDouble()

        // Center text vertically within text area
        val startY = textAreaY + (textAreaHeight - totalTextHeight) / 2 + fm.ascent

        // Draw each line, left-aligned within text area (or center if single line)
        for ((index, line) in lines.withIndex()) {
            val lineWidth = fm.stringWidth(line)
            // Left-align text with padding (or center if single short line)
            val x = if (lines.size == 1 && lineWidth < textAreaWidth * 0.8) {
                // Center single line if it's short
                (textAreaX + (textAreaWidth - lineWidth) / 2).toInt()
            } else {
                // Left-align for multi-line or long single line
                textAreaX.toInt()
            }
            val y = (startY + index * fm.height).toInt()
            g.drawString(line, x, y)
        }
    }

    fun resetView() {
        offsetX = 0.0
        offsetY = 0.0
        scale = 1.0
        repaint()
    }

    fun zoomIn() {
        scale *= 1.2
        scale = scale.coerceIn(0.1, 5.0)
        repaint()
        if (::minimap.isInitialized) {
            minimap.repaint()
        }
    }

    fun zoomOut() {
        scale *= 0.8
        scale = scale.coerceIn(0.1, 5.0)
        repaint()
        if (::minimap.isInitialized) {
            minimap.repaint()
        }
    }

    fun fitToCenter() {
        if (allNodeBounds.isEmpty() && rootNodeBounds == null) {
            resetView()
            return
        }

        val rootBounds = rootNodeBounds ?: return

        // Find the rightmost node (node tận cùng bên phải)
        var rightmostX = rootBounds.x + rootBounds.width
        fun findRightmost(bounds: NodeBounds) {
            val nodeRightX = bounds.x + bounds.width
            if (nodeRightX > rightmostX) {
                rightmostX = nodeRightX
            }
            bounds.childBounds.forEach { findRightmost(it) }
        }
        rootBounds.childBounds.forEach { findRightmost(it) }

        // Calculate vertical bounds: from topmost to bottommost node (including root and all children)
        val topmostY = calculateSubtreeTop(rootBounds)
        val bottommostY = calculateSubtreeBottom(rootBounds)
        val contentHeight = bottommostY - topmostY

        // Calculate horizontal bounds: from root left to rightmost node
        val rootLeftX = rootBounds.x
        val contentWidth = rightmostX - rootLeftX

        // Margin for padding around content (left and right)
        val margin = 50.0

        // Calculate scale to fit content horizontally ONLY (ignore vertical)
        // Horizontal: need to fit contentWidth with margin on left and right
        val scaleX = if (contentWidth > 0) (width - margin * 2) / contentWidth else 1.0
        // Use only horizontal scale, don't zoom in (max 1.0)
        scale = scaleX.coerceIn(0.1, 1.0)

        // Calculate root node center Y (for vertical centering)
        val rootCenterY = rootBounds.y + rootBounds.height / 2

        // Transform order in paintComponent: translate(offsetX, offsetY) then scale(scale, scale)
        // Screen = (World + offset) * scale
        // So: screenX = (worldX + offsetX) * scale, screenY = (worldY + offsetY) * scale
        
        // Position root node vertically centered in viewport
        // screenCenterY = (rootCenterY + offsetY) * scale = height / 2
        // rootCenterY + offsetY = height / 2 / scale
        // offsetY = height / 2 / scale - rootCenterY
        offsetY = height / 2.0 / scale - rootCenterY

        // Position content horizontally: root left at margin, rightmost node at (width - margin)
        // We want: (rootLeftX + offsetX) * scale = margin AND (rightmostX + offsetX) * scale = width - margin
        // From first equation: rootLeftX + offsetX = margin / scale
        // offsetX = margin / scale - rootLeftX
        // Verify with second equation: (rightmostX + margin / scale - rootLeftX) * scale = width - margin
        // rightmostX * scale + margin - rootLeftX * scale = width - margin
        // (rightmostX - rootLeftX) * scale = width - margin * 2
        // contentWidth * scale = width - margin * 2
        // This should be satisfied by our scale calculation above
        
        // Set root left at margin
        val newOffsetX = margin / scale - rootLeftX
        
        // Apply constraints
        val constrainedOffsets = constrainPanBounds(newOffsetX, offsetY)
        offsetX = constrainedOffsets.first
        offsetY = constrainedOffsets.second
        
        // Verify rightmost node fits with margin (should be true if scale is correct)
        val rightmostScreenX = (rightmostX + offsetX) * scale
        if (rightmostScreenX > width - margin + 0.1) { // Add small tolerance for floating point errors
            // If rightmost exceeds margin, recalculate scale to ensure fit
            val correctedScaleX = if (contentWidth > 0) (width - margin * 2) / contentWidth else 1.0
            scale = correctedScaleX.coerceIn(0.1, 1.0)
            
            // Recalculate offsetY with corrected scale
            val newOffsetY = height / 2.0 / scale - rootCenterY
            val newOffsetX = margin / scale - rootLeftX
            
            // Apply constraints
            val constrainedOffsets = constrainPanBounds(newOffsetX, newOffsetY)
            offsetX = constrainedOffsets.first
            offsetY = constrainedOffsets.second
        }

        repaint()
        if (::minimap.isInitialized) {
            minimap.repaint()
        }
    }

    fun centerContent() {
        if (allNodeBounds.isEmpty() && rootNodeBounds == null) {
            return
        }

        // Recursively calculate bounds of all nodes
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = Double.MIN_VALUE
        var maxY = Double.MIN_VALUE

        fun calculateBounds(bounds: NodeBounds) {
            minX = minOf(minX, bounds.x)
            minY = minOf(minY, bounds.y)
            maxX = maxOf(maxX, bounds.x + bounds.width)
            maxY = maxOf(maxY, bounds.y + bounds.height)

            bounds.childBounds.forEach { calculateBounds(it) }
        }

        rootNodeBounds?.let { calculateBounds(it) }

        // Calculate center of content
        val centerX = (minX + maxX) / 2
        val centerY = (minY + maxY) / 2

        // Center the content in current viewport (keep current scale)
        val newOffsetX = width / 2.0 / scale - centerX
        val newOffsetY = height / 2.0 / scale - centerY
        
        // Apply constraints
        val constrainedOffsets = constrainPanBounds(newOffsetX, newOffsetY)
        offsetX = constrainedOffsets.first
        offsetY = constrainedOffsets.second

        repaint()
    }
    
    // Maintain a specific node's screen position after layout update (used after collapse/expand)
    private fun maintainNodeScreenPosition(nodeId: String, targetScreenY: Double) {
        if (rootNodeBounds == null) return
        
        // Find the node by ID in the layout
        fun findNodeById(bounds: NodeBounds): NodeBounds? {
            if (bounds.node.id == nodeId) {
                return bounds
            }
            bounds.childBounds.forEach { child ->
                findNodeById(child)?.let { return it }
            }
            return null
        }
        
        val targetNode = findNodeById(rootNodeBounds!!) ?: return
        
        // Calculate node center Y in world coordinates
        val nodeWorldCenterY = targetNode.y + targetNode.height / 2
        
        // Transform order: translate(offsetX, offsetY) then scale(scale, scale)
        // Screen Y = (World Y + offsetY) * scale
        // We want: (nodeWorldCenterY + offsetY) * scale = targetScreenY
        // nodeWorldCenterY + offsetY = targetScreenY / scale
        // offsetY = targetScreenY / scale - nodeWorldCenterY
        val newOffsetY = targetScreenY / scale - nodeWorldCenterY
        
        // Apply constraints (keep offsetX unchanged)
        val constrainedOffsets = constrainPanBounds(offsetX, newOffsetY)
        offsetX = constrainedOffsets.first
        offsetY = constrainedOffsets.second
        
        repaint()
    }

    fun setFilter(text: String) {
        filterText = text
        calculateLayout()
        updatePreferredSize()
        repaint()
        if (::minimap.isInitialized) {
            minimap.repaint()
        }
    }
    
    fun collapseAllSpecifications() {
        // Collect all specification node IDs (level 1 nodes - children of root)
        rootNode?.children?.forEach { specNode ->
            if (specNode.children.isNotEmpty()) {
                collapsedNodeIds.add(specNode.id)
            }
        }
        calculateLayout()
        repaint()
        
        // Auto-fit to center after collapse
        SwingUtilities.invokeLater {
            fitToCenter()
        }
    }
    
    fun expandAllSpecifications() {
        // Remove all specification node IDs from collapsed set
        rootNode?.children?.forEach { specNode ->
            collapsedNodeIds.remove(specNode.id)
        }
        calculateLayout()
        repaint()
        
        // Auto-fit to center after expand
        SwingUtilities.invokeLater {
            fitToCenter()
        }
    }
    
    fun exportToImage(project: Project) {
        if (allNodeBounds.isEmpty() && rootNodeBounds == null) {
            val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("Gauge Mindmap")
            notificationGroup.createNotification(
                "Export Failed",
                "No mindmap content to export",
                NotificationType.WARNING
            ).notify(project)
            return
        }
        
        // Show file chooser to save image
        SwingUtilities.invokeLater {
            val fileChooser = JFileChooser()
            fileChooser.dialogTitle = "Export Mindmap to Image"
            fileChooser.fileFilter = FileNameExtensionFilter("PNG Images", "png")
            fileChooser.selectedFile = File("gauge-mindmap.png")
            
            val result = fileChooser.showSaveDialog(this)
            if (result == JFileChooser.APPROVE_OPTION) {
                val selectedFile = fileChooser.selectedFile
                val filePath = if (selectedFile.extension == "png") {
                    selectedFile.absolutePath
                } else {
                    "${selectedFile.absolutePath}.png"
                }
                
                // Check if file already exists
                val targetFile = File(filePath)
                if (targetFile.exists()) {
                    val overwriteResult = Messages.showYesNoDialog(
                        project,
                        "File already exists:\n$filePath\n\nDo you want to overwrite it?",
                        "File Exists",
                        Messages.getQuestionIcon()
                    )
                    if (overwriteResult != Messages.YES) {
                        return@invokeLater
                    }
                }
                
                // Run export in background with progress indicator
                ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Exporting Mindmap", true) {
                    override fun run(indicator: ProgressIndicator) {
                        try {
                            indicator.text = "Calculating mindmap bounds..."
                            indicator.fraction = 0.1
                            
                            val rootBounds = rootNodeBounds ?: return
                            
                            // Calculate bounds of entire mindmap
                            var minX = Double.MAX_VALUE
                            var minY = Double.MAX_VALUE
                            var maxX = Double.MIN_VALUE
                            var maxY = Double.MIN_VALUE
                            
                            fun calculateBounds(bounds: NodeBounds) {
                                minX = minOf(minX, bounds.x)
                                minY = minOf(minY, bounds.y)
                                maxX = maxOf(maxX, bounds.x + bounds.width)
                                maxY = maxOf(maxY, bounds.y + bounds.height)
                                
                                bounds.childBounds.forEach { calculateBounds(it) }
                            }
                            
                            calculateBounds(rootBounds)
                            
                            indicator.text = "Rendering mindmap..."
                            indicator.fraction = 0.3
                            
                            // Add padding around content
                            val padding = 50.0
                            val contentWidth = maxX - minX + padding * 2
                            val contentHeight = maxY - minY + padding * 2
                            
                            // Use scale factor for higher resolution (2x for better quality)
                            val scaleFactor = 2.0
                            
                            // Create BufferedImage with higher resolution (scaleFactor x)
                            val imageWidth = (contentWidth * scaleFactor).toInt()
                            val imageHeight = (contentHeight * scaleFactor).toInt()
                            // Use TYPE_INT_ARGB for better quality and alpha support
                            val image = BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB)
                            val g2d = image.createGraphics()
                            
                            // Enable all high-quality rendering hints for maximum sharpness
                            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                            g2d.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
                            g2d.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
                            g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
                            g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                            
                            // Fill background first (before scaling)
                            g2d.color = backgroundColor
                            g2d.fillRect(0, 0, imageWidth, imageHeight)
                            
                            // Scale up the graphics context for high-resolution rendering
                            g2d.scale(scaleFactor, scaleFactor)
                            
                            // Apply transform to center content with padding
                            val transform = AffineTransform()
                            transform.translate(padding - minX, padding - minY)
                            g2d.transform(transform)
                            
                            indicator.text = "Drawing nodes and connections..."
                            indicator.fraction = 0.6
                            
                            // Draw entire mindmap without viewport culling and without hover/selection highlights
                            drawNodeRecursiveForExport(g2d, rootBounds, null)
                            
                            g2d.dispose()
                            
                            indicator.text = "Saving image file..."
                            indicator.fraction = 0.8
                            
                            // Write PNG with high quality
                            val writer = ImageIO.getImageWritersByFormatName("png").next()
                            val writeParam = writer.defaultWriteParam
                            val output = javax.imageio.stream.FileImageOutputStream(targetFile)
                            writer.output = output
                            writer.write(null, javax.imageio.IIOImage(image, null, null), writeParam)
                            writer.dispose()
                            output.close()
                            
                            indicator.fraction = 1.0
                            
                            // Show success notification
                            SwingUtilities.invokeLater {
                                val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("Gauge Mindmap")
                                notificationGroup.createNotification(
                                    "Export Success",
                                    "Mindmap exported successfully to: $filePath (${image.width}x${image.height} pixels)",
                                    NotificationType.INFORMATION
                                ).notify(project)
                            }
                        } catch (e: Exception) {
                            // Show error notification
                            SwingUtilities.invokeLater {
                                val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("Gauge Mindmap")
                                notificationGroup.createNotification(
                                    "Export Failed",
                                    "Failed to export mindmap: ${e.message}",
                                    NotificationType.ERROR
                                ).notify(project)
                            }
                        }
                    }
                })
            }
        }
    }
    
    // Draw mindmap for export (without viewport culling and without hover/selection highlights)
    private fun drawNodeRecursiveForExport(
        g2d: Graphics2D,
        bounds: NodeBounds,
        parentBounds: NodeBounds?
    ) {
        // Step 1: Draw connection lines from parent to this node
        parentBounds?.let { parent ->
            val parentRightX = parent.x + parent.width
            val parentCenterY = parent.y + parent.height / 2
            val childLeftX = bounds.x
            val childCenterY = bounds.y + bounds.height / 2

            // Calculate curve points
            val midX = (parentRightX + childLeftX) / 2
            val dy = childCenterY - parentCenterY

            val curve = CubicCurve2D.Double(
                parentRightX, parentCenterY,
                midX + (childLeftX - parentRightX) * 0.2, parentCenterY + dy * 0.3,
                midX - (childLeftX - parentRightX) * 0.2, childCenterY - dy * 0.3,
                childLeftX, childCenterY
            )

            // Get branch color
            val branchColor = branchLineColors[bounds.colorIndex % branchLineColors.size]
            g2d.color = branchColor
            
            val strokeWidth = if (bounds.node.level == 1) 1.5f else 1.2f
            val dashPattern = if (bounds.node.level == 1) {
                floatArrayOf(8.0f, 4.0f)
            } else {
                floatArrayOf(6.0f, 3.0f)
            }
            g2d.stroke = BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0f, dashPattern, 0.0f)

            // Draw smooth curved line
            g2d.draw(curve)
        }
        
        // Check if node is collapsed - if so, don't draw children
        val isCollapsed = collapsedNodeIds.contains(bounds.node.id)
        
        // Step 2: Draw children connections first (recursive, behind nodes) - only if not collapsed
        if (!isCollapsed) {
            bounds.childBounds.forEach { child ->
                drawNodeRecursiveForExport(g2d, child, bounds)
            }
        }

        // Step 3: Draw this node (on top of connections)
        val nodeRect = RoundRectangle2D.Double(
            bounds.x,
            bounds.y,
            bounds.width,
            bounds.height,
            if (bounds.isRoot) cornerRadius * 1.5 else cornerRadius,
            if (bounds.isRoot) cornerRadius * 1.5 else cornerRadius
        )

        // Get node colors based on level
        val (bgColor, borderColor, textColor) = getNodeColors(bounds)

        // Draw node with shadow (no hover/selection highlights)
        drawNodeWithShadow(g2d, nodeRect, bgColor, borderColor)

        // Draw node content
        drawNodeContent(g2d, bounds, nodeRect, textColor)
        
        // Draw collapse/expand indicator if node has children
        if (bounds.node.children.isNotEmpty()) {
            val isCollapsedIndicator = collapsedNodeIds.contains(bounds.node.id)
            val indicatorSize = 14.0
            val indicatorX = bounds.x + bounds.width - indicatorSize - 6
            val indicatorY = bounds.y + (bounds.height - indicatorSize) / 2
            
            val indicatorBgColor = jbColor(255, 255, 255, 180)
            val indicatorBorderColor = jbColor(200, 200, 200, 220)
            val indicatorIconColor = jbColor(60, 60, 70)
            
            g2d.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            
            val circle = java.awt.geom.Ellipse2D.Double(indicatorX, indicatorY, indicatorSize, indicatorSize)
            g2d.color = indicatorBgColor
            g2d.fill(circle)
            g2d.color = indicatorBorderColor
            g2d.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2d.draw(circle)
            
            val centerX = indicatorX + indicatorSize / 2
            val centerY = indicatorY + indicatorSize / 2
            val lineLength = indicatorSize / 3.5
            
            g2d.color = indicatorIconColor
            g2d.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            
            // Horizontal line (always present)
            g2d.drawLine(
                (centerX - lineLength).toInt(),
                centerY.toInt(),
                (centerX + lineLength).toInt(),
                centerY.toInt()
            )
            
            // Vertical line (only if expanded)
            if (!isCollapsedIndicator) {
                g2d.drawLine(
                    centerX.toInt(),
                    (centerY - lineLength).toInt(),
                    centerX.toInt(),
                    (centerY + lineLength).toInt()
                )
            }
        }
    }
}


