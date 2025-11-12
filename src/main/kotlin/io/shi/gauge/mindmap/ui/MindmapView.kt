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
import io.shi.gauge.mindmap.ui.mindmap.*
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JPanel
import javax.swing.SwingUtilities
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
    private val renderer = MindmapRenderer(textMeasurer, mindmapLayout)
    private val fileMonitor = MindmapFileMonitor(project) { reloadCurrentView() }
    private val exporter = MindmapExporter(project, renderer, mindmapLayout)
    private val minimapRenderer = MindmapMinimapRenderer()

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
        onNodeClick = { node -> /* Handle click */ },
        onNodeDoubleClick = { node -> /* Handle double click */ },
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
                if (handleMinimapMousePressed(e)) return

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
                if (handleMinimapMouseReleased(e)) return
                interaction.handleMouseReleased()
            }

            override fun mouseExited(e: java.awt.event.MouseEvent) {
                interaction.handleMouseExited()
            }

            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (isPointInMinimap(e.x, e.y)) return
                if (SwingUtilities.isLeftMouseButton(e)) {
                    interaction.handleMouseClicked(e.x, e.y, e.clickCount, rootNodeBounds)
                }
            }
        })

        addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseDragged(e: java.awt.event.MouseEvent) {
                if (handleMinimapMouseDragged(e)) return

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
            interaction.handleMouseWheel(e.wheelRotation, rootNodeBounds, width, height)

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
        javax.swing.Timer(50) {
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

    private var minimapDragState: MinimapDragState? = null

    private data class MinimapDragState(
        val startX: Int,
        val startY: Int,
        val startOffsetX: Double,
        val startOffsetY: Double
    )

    private fun handleMinimapMousePressed(e: java.awt.event.MouseEvent): Boolean {
        if (!isPointInMinimap(e.x, e.y) || !SwingUtilities.isLeftMouseButton(e) || rootNodeBounds == null) {
            return false
        }

        val bounds = minimapRenderer.calculateBounds(rootNodeBounds) ?: return false
        val minimapSize = minimapRenderer.getMinimapSize(bounds, height) ?: return false

        val minimapX = width - minimapSize.width.toInt() - minimapSize.margin
        val minimapY = minimapSize.margin
        MindmapConstants.MINIMAP_PADDING

        val relativeX = e.x - minimapX
        val relativeY = e.y - minimapY

        // Check if clicking on viewport rectangle
        val viewportRect = getViewportRectInMinimap(bounds, minimapSize, minimapX, minimapY)
        if (viewportRect != null && viewportRect.contains(relativeX.toDouble(), relativeY.toDouble())) {
            // Start dragging viewport rectangle
            minimapDragState = MinimapDragState(
                e.x,
                e.y,
                viewport.offsetX,
                viewport.offsetY
            )
            return true
        } else {
            // Click outside viewport - move viewport to clicked position
            moveViewportFromMinimap(e.x, e.y, bounds, minimapSize, minimapX, minimapY)
            return true
        }
    }

    private fun handleMinimapMouseDragged(e: java.awt.event.MouseEvent): Boolean {
        val dragState = minimapDragState ?: return false
        if (rootNodeBounds == null) {
            minimapDragState = null
            return false
        }

        val bounds = minimapRenderer.calculateBounds(rootNodeBounds) ?: return false
        val minimapSize = minimapRenderer.getMinimapSize(bounds, height) ?: return false

        val dx = e.x - dragState.startX
        val dy = e.y - dragState.startY

        val padding = MindmapConstants.MINIMAP_PADDING
        val availableWidth = minimapSize.width - padding * 2
        val availableHeight = minimapSize.height - padding * 2
        val scaleX = availableWidth / bounds.contentWidth
        val scaleY = availableHeight / bounds.contentHeight
        val minimapScale = minOf(scaleX, scaleY)

        // Convert minimap delta to world delta
        val worldDx = dx / minimapScale
        val worldDy = dy / minimapScale

        // Update offset
        val newOffsetX = dragState.startOffsetX - worldDx * viewport.scale
        val newOffsetY = dragState.startOffsetY - worldDy * viewport.scale

        viewport.offsetX = newOffsetX
        viewport.offsetY = newOffsetY

        // Constrain pan bounds
        constrainPanBounds()

        repaint()
        return true
    }

    private fun handleMinimapMouseReleased(e: java.awt.event.MouseEvent): Boolean {
        if (minimapDragState != null) {
            minimapDragState = null
            return true
        }
        return false
    }

    private fun getViewportRectInMinimap(
        bounds: MindmapMinimapRenderer.Bounds,
        minimapSize: MindmapMinimapRenderer.MinimapSize,
        minimapX: Int,
        minimapY: Int
    ): Rectangle2D.Double {
        val padding = MindmapConstants.MINIMAP_PADDING

        val availableWidth = minimapSize.width - padding * 2
        val availableHeight = minimapSize.height - padding * 2
        val scaleX = availableWidth / bounds.contentWidth
        val scaleY = availableHeight / bounds.contentHeight
        val minimapScale = minOf(scaleX, scaleY)

        val viewportWorldX = -viewport.offsetX / viewport.scale
        val viewportWorldY = -viewport.offsetY / viewport.scale
        val viewportWorldWidth = width / viewport.scale
        val viewportWorldHeight = height / viewport.scale

        val viewportMinimapX = (viewportWorldX - bounds.minX) * minimapScale + padding
        val viewportMinimapY = (viewportWorldY - bounds.minY) * minimapScale + padding
        val viewportMinimapWidth = viewportWorldWidth * minimapScale
        val viewportMinimapHeight = viewportWorldHeight * minimapScale

        return Rectangle2D.Double(
            viewportMinimapX,
            viewportMinimapY,
            viewportMinimapWidth,
            viewportMinimapHeight
        )
    }

    private fun moveViewportFromMinimap(
        clickX: Int,
        clickY: Int,
        bounds: MindmapMinimapRenderer.Bounds,
        minimapSize: MindmapMinimapRenderer.MinimapSize,
        minimapX: Int,
        minimapY: Int
    ) {
        val padding = MindmapConstants.MINIMAP_PADDING

        val relativeX = clickX - minimapX - padding
        val relativeY = clickY - minimapY - padding

        val availableWidth = minimapSize.width - padding * 2
        val availableHeight = minimapSize.height - padding * 2
        val scaleX = availableWidth / bounds.contentWidth
        val scaleY = availableHeight / bounds.contentHeight
        val minimapScale = minOf(scaleX, scaleY)

        // Convert minimap coordinates to world coordinates
        val worldX = relativeX / minimapScale + bounds.minX
        val worldY = relativeY / minimapScale + bounds.minY

        // Center viewport on clicked position
        val newOffsetX = (width / 2.0 / viewport.scale - worldX) * viewport.scale
        val newOffsetY = (height / 2.0 / viewport.scale - worldY) * viewport.scale

        viewport.offsetX = newOffsetX
        viewport.offsetY = newOffsetY

        // Constrain pan bounds
        constrainPanBounds()

        repaint()
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
    }

    override fun doLayout() {
        super.doLayout()
        if (::minimap.isInitialized) {
            val oldWidth = minimap.parentWidth
            val oldHeight = minimap.parentHeight

            minimap.parentWidth = width
            minimap.parentHeight = height

            // Only invalidate cache if rootBounds changed or size changed significantly
            if (minimap.rootBounds !== rootNodeBounds ||
                kotlin.math.abs(oldWidth - width) > 10 ||
                kotlin.math.abs(oldHeight - height) > 10
            ) {
                minimapRenderer.invalidateCache()
            }

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

        // Calculate viewport bounds
        val viewportBounds = Rectangle2D.Double(
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
        mindmapLayout.clearCollapsed()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Scanning Gauge Specifications", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Scanning specification files..."
                    indicator.fraction = 0.0

                    specifications.clear()

                    if (files.isNotEmpty()) {
                        // Count total spec files for progress
                        fun countSpecFiles(file: VirtualFile): Int {
                            return when {
                                file.isDirectory -> {
                                    var count = 0
                                    for (child in file.children) {
                                        count += countSpecFiles(child)
                                    }
                                    count
                                }

                                file.name.endsWith(".spec") -> 1
                                else -> {
                                    // If not a spec file, scan its parent directory
                                    file.parent?.let { countSpecFiles(it) } ?: 0
                                }
                            }
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
                        repaint()

                        val filesChanged = previousFiles.size != files.size ||
                                previousFiles.zip(files).any { (prev, curr) -> prev != curr }
                        if (autoFit && (isFirstLoad || filesChanged)) {
                            fun tryFitToCenter(attempt: Int = 0) {
                                if (rootNodeBounds != null && width > 0 && height > 0) {
                                    fitToCenter()
                                } else if (attempt < 5) {
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
        loadSpecifications(currentFiles, autoFit = false)
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

    private fun isPointInMinimap(screenX: Int, screenY: Int): Boolean {
        if (rootNodeBounds == null || !minimapVisible) return false
        val bounds = minimapRenderer.calculateBounds(rootNodeBounds) ?: return false
        val minimapSize = minimapRenderer.getMinimapSize(bounds, height) ?: return false

        val minimapX = width - minimapSize.width.toInt() - minimapSize.margin
        val minimapY = minimapSize.margin

        return screenX >= minimapX && screenX <= minimapX + minimapSize.width.toInt() &&
                screenY >= minimapY && screenY <= minimapY + minimapSize.height.toInt()
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
}

