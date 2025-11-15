package io.shi.gauge.mindmap.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import io.shi.gauge.mindmap.model.Specification
import io.shi.gauge.mindmap.service.GaugeSpecScanner
import io.shi.gauge.mindmap.ui.mindmap.constants.MindmapColors
import io.shi.gauge.mindmap.ui.mindmap.constants.MindmapConstants
import io.shi.gauge.mindmap.ui.mindmap.interaction.MindmapInteraction
import io.shi.gauge.mindmap.ui.mindmap.interaction.MindmapMinimapController
import io.shi.gauge.mindmap.ui.mindmap.layout.MindmapLayout
import io.shi.gauge.mindmap.ui.mindmap.layout.MindmapViewport
import io.shi.gauge.mindmap.ui.mindmap.model.MindmapNode
import io.shi.gauge.mindmap.ui.mindmap.model.NodeBounds
import io.shi.gauge.mindmap.ui.mindmap.render.MindmapMinimapRenderer
import io.shi.gauge.mindmap.ui.mindmap.render.MindmapRenderer
import io.shi.gauge.mindmap.ui.mindmap.service.MindmapExporter
import io.shi.gauge.mindmap.ui.mindmap.service.MindmapFileMonitor
import io.shi.gauge.mindmap.ui.mindmap.ui.MindmapMinimap
import io.shi.gauge.mindmap.ui.mindmap.util.MindmapTextMeasurer
import io.shi.gauge.mindmap.ui.mindmap.util.MindmapTreeBuilder
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.io.File
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Refactored MindmapView - simplified and modular
 */
class MindmapView(private val project: Project) : JPanel() {

    // Core components
    private val scanner = GaugeSpecScanner(project)
    private val treeBuilder = MindmapTreeBuilder(project)
    private val textMeasurer = MindmapTextMeasurer()
    private val mindmapLayout = MindmapLayout(textMeasurer)
    private val viewport = MindmapViewport()
    private val renderer = MindmapRenderer(textMeasurer)
    private val fileMonitor = MindmapFileMonitor(project) { reloadCurrentView() }
    private val exporter = MindmapExporter(project, renderer, mindmapLayout)
    private val minimapRenderer = MindmapMinimapRenderer()
    private val minimapController = MindmapMinimapController(
        viewport,
        minimapRenderer,
        onRepaint = { repaint() },
        onConstrainPanBounds = { constrainPanBounds() }
    )

    // Data
    private val specifications = mutableListOf<Specification>()
    private var rootNode: MindmapNode? = null
    private var rootNodeBounds: NodeBounds? = null
    private val allNodeBounds = mutableListOf<NodeBounds>()
    private var currentFiles: MutableList<VirtualFile> = mutableListOf()
    private var filterText: String = ""

    // Interaction
    private val interaction = MindmapInteraction(
        project,
        mindmapLayout,
        viewport,
        onCollapseToggle = { nodeId ->
            // Save screen position of the node before collapse/expand
            val nodeBounds = findNodeById(nodeId, rootNodeBounds)
            val screenPosition = nodeBounds?.let {
                val (screenX, screenY) = viewport.worldToScreen(it.centerX, it.centerY)
                Pair(screenX, screenY)
            }

            // Toggle collapse state
            mindmapLayout.setCollapsed(nodeId, !mindmapLayout.isCollapsed(nodeId))

            // Recalculate layout
            recalculateLayout()

            // Restore screen position of the node
            screenPosition?.let { (targetScreenX, targetScreenY) ->
                val newNodeBounds = findNodeById(nodeId, rootNodeBounds)
                newNodeBounds?.let { newBounds ->
                    // Calculate new world position
                    val newWorldX = newBounds.centerX
                    val newWorldY = newBounds.centerY

                    // Adjust viewport to keep node at same screen position
                    // screenX = (worldX + offsetX) * scale
                    // targetScreenX = (newWorldX + offsetX) * scale
                    // offsetX = targetScreenX / scale - newWorldX
                    viewport.offsetX = targetScreenX / viewport.scale - newWorldX
                    viewport.offsetY = targetScreenY / viewport.scale - newWorldY

                    // Constrain pan bounds after adjustment
                    constrainPanBounds()
                }
            }

            repaint()
        },
        onRepaint = { repaint() }
    )

    private fun findNodeById(nodeId: String, bounds: NodeBounds?): NodeBounds? {
        if (bounds == null) return null

        fun search(currentBounds: NodeBounds): NodeBounds? {
            if (currentBounds.node.id == nodeId) {
                return currentBounds
            }
            currentBounds.childBounds.forEach { child ->
                val found = search(child)
                if (found != null) return found
            }
            return null
        }

        return search(bounds)
    }

    // UI state
    private var minimapVisible = true
    private lateinit var minimap: MindmapMinimap
    private var lastRepaintTime = 0L
    private var pendingRepaint = false

    // Reusable viewport bounds object to avoid allocation
    private val viewportBounds = Rectangle2D.Double()

    init {
        background = MindmapColors.backgroundColor
        isOpaque = true
        isDoubleBuffered = true

        setupMouseListeners()
        setupComponentListeners()

        // Create minimap
        minimap = MindmapMinimap()
        layout = null // Use absolute layout for JPanel
        add(minimap)
        setComponentZOrder(minimap, 0)

        // Start file monitoring
        fileMonitor.startMonitoring()
    }

    private fun setupMouseListeners() {
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                if (minimapController.handleMousePressed(e, rootNodeBounds, width, height, minimapVisible)) return

                val button = when {
                    SwingUtilities.isLeftMouseButton(e) -> 1
                    SwingUtilities.isMiddleMouseButton(e) -> 2
                    else -> 0
                }
                if (button > 0) {
                    interaction.handleMousePressed(e.x, e.y, button, rootNodeBounds)
                }
            }

            override fun mouseReleased(e: java.awt.event.MouseEvent) {
                if (minimapController.handleMouseReleased()) return
                interaction.handleMouseReleased()
            }

            override fun mouseExited(e: java.awt.event.MouseEvent) {
                interaction.handleMouseExited()
            }

            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (minimapController.isPointInMinimap(e.x, e.y, rootNodeBounds, width, height, minimapVisible)) return
                if (SwingUtilities.isLeftMouseButton(e)) {
                    interaction.handleMouseClicked(e.x, e.y, e.clickCount, rootNodeBounds)
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    val node = interaction.handleRightClick(e.x, e.y, rootNodeBounds)
                    if (node != null) {
                        showContextMenu(e.x, e.y, node)
                    }
                }
            }
        })

        addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseDragged(e: java.awt.event.MouseEvent) {
                if (minimapController.handleMouseDragged(e, rootNodeBounds, height)) return

                // Always update viewport immediately for smooth dragging
                interaction.handleMouseDragged(e.x, e.y, rootNodeBounds, width, height)

                // Throttle repaints
                val now = System.currentTimeMillis()
                if (now - lastRepaintTime >= MindmapConstants.REPAINT_THROTTLE_MS) {
                    repaint()
                    lastRepaintTime = now
                    pendingRepaint = false
                } else {
                    pendingRepaint = true
                }
            }

            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                val now = System.currentTimeMillis()
                if (now - lastRepaintTime < MindmapConstants.REPAINT_THROTTLE_MS) return

                interaction.handleMouseMoved(e.x, e.y, rootNodeBounds)
                lastRepaintTime = now
            }
        })

        addMouseWheelListener { e ->
            // Always update viewport immediately for responsive zooming
            // Pass mouse position as zoom center point
            interaction.handleMouseWheel(e.wheelRotation, rootNodeBounds, width, height, e.x, e.y)

            // Throttle repaints
            val now = System.currentTimeMillis()
            if (now - lastRepaintTime >= MindmapConstants.REPAINT_THROTTLE_MS) {
                repaint()
                lastRepaintTime = now
                pendingRepaint = false
            } else {
                pendingRepaint = true
            }
        }

        // Timer to flush pending repaints (max 20 FPS for pending updates)
        Timer(50) {
            if (pendingRepaint) {
                repaint()
                pendingRepaint = false
                lastRepaintTime = System.currentTimeMillis()
            }
        }.apply {
            isRepeats = true
            start()
        }
    }


    private fun constrainPanBounds() {
        if (rootNodeBounds == null) return

        val contentBounds = calculateContentBounds(rootNodeBounds!!)
        viewport.constrainPanBounds(
            contentBounds.minX,
            contentBounds.maxX,
            contentBounds.minY,
            contentBounds.maxY,
            width,
            height
        )
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

    private fun setupComponentListeners() {
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                if (::minimap.isInitialized) {
                    minimap.parentWidth = width
                    minimap.parentHeight = height
                }
                if (rootNode != null) {
                    recalculateLayout()
                    constrainPanBounds()
                    repaint()
                }
            }

            override fun componentShown(e: ComponentEvent?) {
                SwingUtilities.invokeLater {
                    if (rootNodeBounds != null && width > 0 && height > 0) {
                        fitToCenter()
                    } else {
                        val retryTimer = Timer(100) { _ ->
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
    }

    override fun doLayout() {
        super.doLayout()
        if (::minimap.isInitialized) {
            minimap.parentWidth
            minimap.parentHeight

            minimap.parentWidth = width
            minimap.parentHeight = height

            minimap.rootBounds = rootNodeBounds

            if (rootNodeBounds != null) {
                val bounds = minimapRenderer.calculateBounds(rootNodeBounds)
                val minimapSize = minimapRenderer.getMinimapSize(bounds, height)

                if (minimapSize != null) {
                    val minimapX = width - minimapSize.width.toInt() - minimapSize.margin
                    minimap.setBounds(
                        minimapX,
                        minimapSize.margin,
                        minimapSize.width.toInt(),
                        minimapSize.height.toInt()
                    )
                }
            } else {
                minimap.setBounds(
                    width - MindmapConstants.MINIMAP_WIDTH.toInt() - MindmapConstants.MINIMAP_MARGIN,
                    MindmapConstants.MINIMAP_MARGIN,
                    MindmapConstants.MINIMAP_WIDTH.toInt(),
                    50
                )
            }
            minimap.isVisible = minimapVisible
        }
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2d = g as Graphics2D

        // Apply transform for pan and zoom
        val transform = AffineTransform()
        transform.translate(viewport.offsetX, viewport.offsetY)
        transform.scale(viewport.scale, viewport.scale)
        g2d.transform(transform)

        // Calculate viewport bounds (reuse object to avoid allocation)
        viewportBounds.setRect(
            -viewport.offsetX / viewport.scale,
            -viewport.offsetY / viewport.scale,
            width / viewport.scale,
            height / viewport.scale
        )

        // Render mindmap
        renderer.render(
            g2d,
            rootNodeBounds,
            viewportBounds,
            viewport,
            interaction.getHoverState(),
            interaction.getSelectionState(),
            getCollapsedNodeIds()
        )

        // Draw minimap overlay
        if (minimapVisible && rootNodeBounds != null) {
            val savedTransform = g2d.transform
            minimapRenderer.renderMinimap(g2d, rootNodeBounds, viewport, width, height)
            g2d.transform = savedTransform
        }
    }

    private fun getCollapsedNodeIds(): Set<String> {
        return mindmapLayout.getCollapsedNodeIds()
    }

    fun loadSpecifications(directory: VirtualFile? = null, autoFit: Boolean = true) {
        // Support single file/folder (backward compatibility)
        val files = if (directory != null) listOf(directory) else emptyList()
        loadSpecifications(files, autoFit)
    }

    fun loadSpecifications(files: List<VirtualFile>, autoFit: Boolean = true) {
        val previousFiles = currentFiles.toList()
        val isFirstLoad = previousFiles.isEmpty() && rootNodeBounds == null
        currentFiles = files.toMutableList()

        fileMonitor.setCurrentFiles(files)

        // Save collapsed state and viewport position for realtime updates
        val savedCollapsedState = if (!isFirstLoad) mindmapLayout.getCollapsedNodeIds() else emptySet()
        val savedViewportOffsetX = viewport.offsetX
        val savedViewportOffsetY = viewport.offsetY
        val savedViewportScale = viewport.scale

        // Only clear collapsed state on first load or when files change
        val filesChanged = previousFiles.size != files.size ||
                previousFiles.zip(files).any { (prev, curr) -> prev != curr }
        if (isFirstLoad || filesChanged) {
            mindmapLayout.clearCollapsed()
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Scanning Gauge Specifications", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Scanning specification files..."
                    indicator.fraction = 0.0

                    specifications.clear()

                    if (files.isNotEmpty()) {
                        // Count total spec files for progress (iterative to avoid stack overflow)
                        fun countSpecFiles(file: VirtualFile): Int {
                            if (file.name.endsWith(".spec")) {
                                return 1
                            }

                            if (!file.isDirectory) {
                                // If not a spec file, scan its parent directory
                                return file.parent?.let { countSpecFiles(it) } ?: 0
                            }

                            // Use iterative approach with stack to avoid stack overflow
                            val stack = ArrayDeque<VirtualFile>()
                            val visited = mutableSetOf<VirtualFile>()
                            var count = 0

                            stack.addLast(file)
                            visited.add(file)

                            while (stack.isNotEmpty()) {
                                val currentDir = stack.removeLast()

                                try {
                                    for (child in currentDir.children) {
                                        if (child.isDirectory) {
                                            if (!visited.contains(child)) {
                                                visited.add(child)
                                                stack.addLast(child)
                                            }
                                        } else if (child.name.endsWith(".spec")) {
                                            count++
                                        }
                                    }
                                } catch (_: Exception) {
                                    // Skip directories that can't be accessed
                                    continue
                                }
                            }

                            return count
                        }

                        // Collect directories and files to scan (avoid duplicates)
                        val directoriesToScan = mutableSetOf<VirtualFile>()
                        val specFilesToScan = mutableListOf<VirtualFile>()

                        files.forEach { file ->
                            when {
                                file.isDirectory -> {
                                    directoriesToScan.add(file)
                                }

                                file.name.endsWith(".spec") -> {
                                    specFilesToScan.add(file)
                                }

                                else -> {
                                    // If file is not a .spec file, scan its parent directory
                                    file.parent?.let { directoriesToScan.add(it) }
                                }
                            }
                        }

                        val totalFiles = directoriesToScan.sumOf { countSpecFiles(it) } + specFilesToScan.size
                        var scannedFiles = 0

                        // Scan directories
                        directoriesToScan.forEach { directory ->
                            indicator.text = "Scanning directory: ${directory.name}..."
                            val specs = scanner.scanDirectory(directory)
                            specifications.addAll(specs)
                            scannedFiles += specs.size
                            if (totalFiles > 0) {
                                indicator.fraction = scannedFiles.toDouble() / totalFiles * 0.7
                            }
                        }

                        // Scan individual spec files
                        specFilesToScan.forEach { specFile ->
                            indicator.text = "Scanning file: ${specFile.name}..."
                            val specs = scanner.scanFile(specFile)
                            specifications.addAll(specs)
                            scannedFiles++
                            if (totalFiles > 0) {
                                indicator.fraction = scannedFiles.toDouble() / totalFiles * 0.7
                            }
                        }
                    } else {
                        indicator.text = "Scanning entire project..."
                        specifications.addAll(scanner.scanProject())
                        indicator.fraction = 0.7
                    }

                    indicator.text = "Building tree structure..."
                    indicator.fraction = 0.8
                    rootNode = treeBuilder.buildTree(specifications)

                    indicator.text = "Calculating layout..."
                    indicator.fraction = 0.9
                    recalculateLayout()

                    indicator.fraction = 1.0

                    SwingUtilities.invokeLater {
                        // Always ensure tree is rebuilt with latest data
                        // The rootNode has already been rebuilt above with new specifications
                        // Now restore collapsed state if needed (only for realtime updates, not first load)
                        if (!isFirstLoad && !filesChanged && savedCollapsedState.isNotEmpty() && rootNode != null) {
                            // Build a set of all existing node IDs in the new tree
                            val existingNodeIds = mutableSetOf<String>()
                            fun collectNodeIds(node: MindmapNode) {
                                existingNodeIds.add(node.id)
                                node.children.forEach { collectNodeIds(it) }
                            }
                            collectNodeIds(rootNode!!)

                            // Only restore collapsed state for nodes that still exist
                            savedCollapsedState.forEach { nodeId ->
                                if (existingNodeIds.contains(nodeId)) {
                                    mindmapLayout.setCollapsed(nodeId, true)
                                }
                            }
                            // Recalculate layout with restored collapsed state
                            recalculateLayout()
                        }

                        // Restore viewport position for realtime updates
                        if (!isFirstLoad && !filesChanged && rootNodeBounds != null) {
                            viewport.offsetX = savedViewportOffsetX
                            viewport.offsetY = savedViewportOffsetY
                            viewport.scale = savedViewportScale
                            constrainPanBounds()
                        }

                        // Force repaint to show updated text
                        repaint()

                        // Update statistics
                        statisticsUpdateCallback?.invoke(getStatistics())

                        if (autoFit && (isFirstLoad || filesChanged)) {
                            fun tryFitToCenter(attempt: Int = 0) {
                                if (rootNodeBounds != null && width > 0 && height > 0) {
                                    fitToCenter()
                                } else if (attempt < 5) {
                                    val delay = 50 * (attempt + 1)
                                    val retryTimer = Timer(delay) { _ ->
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
                    SwingUtilities.invokeLater {
                        val notificationGroup =
                            NotificationGroupManager.getInstance().getNotificationGroup("Gauge Mindmap")
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

    private fun reloadCurrentView() {
        // Force reload with current files to update tree with latest content
        // Clear any cached data to ensure fresh rebuild
        if (currentFiles.isNotEmpty()) {
            // Force reload to get latest content from files
            loadSpecifications(currentFiles, autoFit = false)
        } else {
            // If no files, reload entire project
            loadSpecifications(emptyList(), autoFit = false)
        }
    }

    private fun recalculateLayout() {
        allNodeBounds.clear()
        rootNodeBounds = null

        val filteredRoot = rootNode?.let { treeBuilder.filterTree(it, filterText) } ?: return
        rootNodeBounds = mindmapLayout.calculateLayout(filteredRoot)

        rootNodeBounds?.let { mindmapLayout.collectAllNodes(it, allNodeBounds) }
    }

    override fun removeNotify() {
        super.removeNotify()
        fileMonitor.stopMonitoring()
        interaction.cleanup()
    }


    override fun getPreferredSize(): Dimension {
        val parent = parent
        if (parent != null && parent.width > 0 && parent.height > 0) {
            return Dimension(parent.width, parent.height)
        }
        return Dimension(800, 600)
    }

    // Public API methods
    fun setFilter(text: String) {
        filterText = text
        recalculateLayout()
        constrainPanBounds()
        repaint()
    }

    fun resetView() {
        viewport.reset()
        constrainPanBounds()
        repaint()
    }

    fun zoomIn() {
        viewport.zoomIn(width, height)
        constrainPanBounds()
        repaint()
    }

    fun zoomOut() {
        viewport.zoomOut(width, height)
        constrainPanBounds()
        repaint()
    }

    fun fitToCenter() {
        if (rootNodeBounds == null) {
            resetView()
            return
        }

        // Find rightmost node
        var rightmostX = rootNodeBounds!!.rightX
        fun findRightmost(bounds: NodeBounds) {
            if (bounds.rightX > rightmostX) {
                rightmostX = bounds.rightX
            }
            bounds.childBounds.forEach { findRightmost(it) }
        }
        rootNodeBounds!!.childBounds.forEach { findRightmost(it) }

        val rootLeftX = rootNodeBounds!!.x
        rightmostX - rootLeftX
        val rootCenterY = rootNodeBounds!!.centerY

        viewport.fitToContent(rootLeftX, rightmostX, rootCenterY, width, height)
        constrainPanBounds()
        repaint()
    }

    fun collapseAllSpecifications() {
        rootNode?.let { mindmapLayout.collapseAllSpecs(it) }
        recalculateLayout()

        // Fit to center after collapse
        fitToCenter()
    }

    fun expandAllSpecifications() {
        rootNode?.let { mindmapLayout.expandAllSpecs(it) }
        recalculateLayout()

        // Fit to center after expand
        fitToCenter()
    }

    private fun maintainViewportCenter(targetWorldX: Double, targetWorldY: Double) {
        // Center viewport on target world position
        viewport.centerContent(targetWorldX, targetWorldY, width, height)

        // Constrain pan bounds
        constrainPanBounds()
    }

    fun setMinimapVisible(visible: Boolean) {
        minimapVisible = visible
        if (::minimap.isInitialized) {
            minimap.isVisible = visible
            repaint()
        }
    }

    fun exportToImage(project: Project) {
        if (rootNodeBounds == null) {
            val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("Gauge Mindmap")
            notificationGroup.createNotification(
                "Export Failed",
                "No mindmap content to export",
                NotificationType.WARNING
            ).notify(project)
            return
        }

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

                exporter.exportToImage(rootNodeBounds, targetFile, getCollapsedNodeIds())
            }
        }
    }

    /**
     * Returns statistics about loaded specifications and scenarios
     */
    data class Statistics(
        val specificationCount: Int,
        val scenarioCount: Int
    )

    fun getStatistics(): Statistics {
        val specCount = specifications.size
        val scenarioCount = specifications.sumOf { it.scenarios.size }
        return Statistics(specCount, scenarioCount)
    }

    /**
     * Callback to notify when statistics change
     */
    private var statisticsUpdateCallback: ((Statistics) -> Unit)? = null

    fun setStatisticsUpdateCallback(callback: (Statistics) -> Unit) {
        statisticsUpdateCallback = callback
    }

    private fun showContextMenu(x: Int, y: Int, node: NodeBounds) {
        val popupMenu = JPopupMenu()

        // Open file menu item
        val openFileItem = JMenuItem("Open file")
        openFileItem.addActionListener {
            interaction.openFile(node)
        }
        popupMenu.add(openFileItem)

        // View node menu item - only show if node has children
        if (node.node.children.isNotEmpty()) {
            val viewNodeItem = JMenuItem("View node")
            viewNodeItem.addActionListener {
                showNodeViewDialog(node)
            }
            popupMenu.add(viewNodeItem)
        }

        popupMenu.show(this, x, y)
    }

    private fun showNodeViewDialog(node: NodeBounds) {
        // Create a new root node from the selected node and its children
        // This creates a subtree starting from the selected node
        val rootNodeForView = node.node.copy(level = 0)
        val dialog = NodeViewDialog(project, rootNodeForView, textMeasurer, renderer)
        dialog.show()
    }
}

