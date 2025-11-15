package io.shi.gauge.mindmap.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.UIUtil
import io.shi.gauge.mindmap.ui.mindmap.constants.MindmapColors
import io.shi.gauge.mindmap.ui.mindmap.constants.MindmapConstants
import io.shi.gauge.mindmap.ui.mindmap.layout.MindmapLayout
import io.shi.gauge.mindmap.ui.mindmap.layout.MindmapViewport
import io.shi.gauge.mindmap.ui.mindmap.model.MindmapNode
import io.shi.gauge.mindmap.ui.mindmap.model.NodeBounds
import io.shi.gauge.mindmap.ui.mindmap.render.MindmapRenderer
import io.shi.gauge.mindmap.ui.mindmap.util.MindmapTextMeasurer
import java.awt.*
import java.awt.event.*
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.*

/**
 * Dialog to view a node and its children independently
 */
class NodeViewDialog(
    project: Project,
    private val rootNode: MindmapNode,
    private val textMeasurer: MindmapTextMeasurer,
    private val renderer: MindmapRenderer
) : DialogWrapper(project) {

    private var rootNodeBounds: NodeBounds? = null
    private val viewportBounds = Rectangle2D.Double()
    private val dialogViewport = MindmapViewport()
    private var isDragging = false
    private var lastMouseX = 0
    private var lastMouseY = 0
    private var panel: JPanel? = null

    // Create a separate layout instance for this dialog to avoid sharing collapsed state
    private val dialogLayout = MindmapLayout(textMeasurer)

    // For collapse/expand click handling
    private var singleClickTimer: Timer? = null
    private var pendingSingleClickNode: NodeBounds? = null
    private var isInitialLayout = true

    init {
        title = "View Node: ${rootNode.text}"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2d = g as Graphics2D

                // Ensure layout is calculated
                if (rootNodeBounds == null && width > 0 && height > 0) {
                    calculateLayout()
                    // If still null after calculation, return early
                    if (rootNodeBounds == null) return
                }

                // Apply transform for pan and zoom
                val transform = AffineTransform()
                transform.translate(dialogViewport.offsetX, dialogViewport.offsetY)
                transform.scale(dialogViewport.scale, dialogViewport.scale)
                g2d.transform(transform)

                // Calculate viewport bounds
                viewportBounds.setRect(
                    -dialogViewport.offsetX / dialogViewport.scale,
                    -dialogViewport.offsetY / dialogViewport.scale,
                    width / dialogViewport.scale,
                    height / dialogViewport.scale
                )

                // Render mindmap
                rootNodeBounds?.let { bounds ->
                    renderer.render(
                        g2d,
                        bounds,
                        viewportBounds,
                        dialogViewport,
                        io.shi.gauge.mindmap.ui.mindmap.model.HoverState(null, mutableSetOf()),
                        io.shi.gauge.mindmap.ui.mindmap.model.SelectionState(null),
                        emptySet<String>()
                    )
                }
            }
        }

        panel.background = MindmapColors.backgroundColor
        panel.isOpaque = true
        panel.isDoubleBuffered = true
        panel.preferredSize = Dimension(1000, 700)

        // Add mouse listeners for pan, zoom, and collapse/expand
        panel.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) || SwingUtilities.isMiddleMouseButton(e)) {
                    isDragging = true
                    lastMouseX = e.x
                    lastMouseY = e.y
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                isDragging = false
            }

            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && !isDragging) {
                    handleMouseClick(e.x, e.y, e.clickCount)
                }
            }
        })

        panel.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                if (isDragging) {
                    val deltaX = e.x - lastMouseX
                    val deltaY = e.y - lastMouseY
                    dialogViewport.pan(deltaX.toDouble(), deltaY.toDouble())
                    constrainPanBounds(panel.width, panel.height)
                    lastMouseX = e.x
                    lastMouseY = e.y
                    panel.repaint()
                }
            }
        })

        panel.addMouseWheelListener { e: MouseWheelEvent ->
            if (e.wheelRotation < 0) {
                dialogViewport.zoomIn(panel.width, panel.height, e.x.toDouble(), e.y.toDouble())
            } else {
                dialogViewport.zoomOut(panel.width, panel.height, e.x.toDouble(), e.y.toDouble())
            }
            constrainPanBounds(panel.width, panel.height)
            panel.repaint()
        }

        panel.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                if (rootNodeBounds != null) {
                    fitToCenter(panel.width, panel.height)
                    panel.repaint()
                }
            }

            override fun componentShown(e: ComponentEvent?) {
                // Calculate layout when panel is shown
                if (rootNodeBounds == null) {
                    calculateLayout()
                }
            }

            override fun componentMoved(e: ComponentEvent?) {
                // Ensure layout is calculated
                if (rootNodeBounds == null && panel.width > 0 && panel.height > 0) {
                    calculateLayout()
                }
            }
        })

        this.panel = panel

        // Create toolbar
        val toolbar = createToolbar()

        // Create main panel with toolbar and mindmap panel
        val mainPanel = JPanel(BorderLayout())
        mainPanel.add(toolbar, BorderLayout.NORTH)
        mainPanel.add(panel, BorderLayout.CENTER)

        // Calculate layout after panel is added to dialog
        SwingUtilities.invokeLater {
            // Try to calculate layout multiple times to ensure it works
            fun tryCalculateLayout(attempt: Int = 0) {
                if (panel.width > 0 && panel.height > 0) {
                    calculateLayout()
                } else if (attempt < 10) {
                    // Retry if panel doesn't have size yet
                    SwingUtilities.invokeLater {
                        tryCalculateLayout(attempt + 1)
                    }
                }
            }
            tryCalculateLayout()
        }

        return mainPanel
    }

    private fun createToolbar(): JPanel {
        val toolbar = JPanel(BorderLayout())
        toolbar.border = BorderFactory.createEmptyBorder(3, 5, 3, 5)

        val leftPanel = JPanel()
        leftPanel.layout = BoxLayout(leftPanel, BoxLayout.X_AXIS)
        leftPanel.alignmentY = JComponent.CENTER_ALIGNMENT

        // Custom button with rounded corners and hover effect
        class RoundedToolbarButton(icon: Icon) : JButton(icon) {
            private var isHovered = false
            private val cornerRadius = 4.0

            init {
                isContentAreaFilled = false
                isFocusPainted = false
                isOpaque = false
                border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
                preferredSize = Dimension(24, 24)
                minimumSize = Dimension(24, 24)
                maximumSize = Dimension(24, 24)

                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) {
                        isHovered = true
                        repaint()
                    }

                    override fun mouseExited(e: MouseEvent) {
                        isHovered = false
                        repaint()
                    }

                    override fun mousePressed(e: MouseEvent) {
                        if (SwingUtilities.isLeftMouseButton(e)) {
                            isHovered = true
                            repaint()
                        }
                    }

                    override fun mouseReleased(e: MouseEvent) {
                        if (SwingUtilities.isLeftMouseButton(e)) {
                            isHovered = contains(e.x, e.y)
                            repaint()
                        }
                    }
                })
            }

            override fun paintComponent(g: Graphics) {
                val g2d = g as Graphics2D
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                // Draw background with rounded corners if hovered
                if (isHovered) {
                    val hoverColor = UIUtil.getListSelectionBackground(true)
                    g2d.color = hoverColor
                    val rect = RoundRectangle2D.Double(
                        0.0, 0.0,
                        width.toDouble(), height.toDouble(),
                        cornerRadius, cornerRadius
                    )
                    g2d.fill(rect)
                }

                // Draw icon
                super.paintComponent(g)
            }
        }

        // Helper function to create toolbar button with hover effect
        fun createToolbarButton(icon: Icon, tooltip: String, action: () -> Unit): JButton {
            val button = RoundedToolbarButton(icon)
            button.toolTipText = tooltip
            button.addActionListener { action() }
            return button
        }

        // Zoom out button
        val zoomOutButton = createToolbarButton(AllIcons.General.ZoomOut, "Zoom Out") {
            panel?.let {
                dialogViewport.zoomOut(it.width, it.height)
                constrainPanBounds(it.width, it.height)
                it.repaint()
            }
        }
        zoomOutButton.alignmentY = JComponent.CENTER_ALIGNMENT
        leftPanel.add(zoomOutButton)
        leftPanel.add(Box.createHorizontalStrut(2))

        // Zoom in button
        val zoomInButton = createToolbarButton(AllIcons.General.ZoomIn, "Zoom In") {
            panel?.let {
                dialogViewport.zoomIn(it.width, it.height)
                constrainPanBounds(it.width, it.height)
                it.repaint()
            }
        }
        zoomInButton.alignmentY = JComponent.CENTER_ALIGNMENT
        leftPanel.add(zoomInButton)
        leftPanel.add(Box.createHorizontalStrut(2))

        // Fit to view button
        val fitToViewButton = createToolbarButton(AllIcons.General.FitContent, "Fit all content to view") {
            panel?.let {
                fitToCenter(it.width, it.height)
                it.repaint()
            }
        }
        fitToViewButton.alignmentY = JComponent.CENTER_ALIGNMENT
        leftPanel.add(fitToViewButton)

        toolbar.add(leftPanel, BorderLayout.WEST)
        return toolbar
    }

    private fun handleMouseClick(x: Int, y: Int, clickCount: Int) {
        if (clickCount == 0) return

        val (worldX, worldY) = dialogViewport.screenToWorld(x, y)
        val node = dialogLayout.findNodeAt(rootNodeBounds, worldX, worldY) ?: return

        if (clickCount == 1) {
            // Single click - collapse/expand
            if (node.node.children.isNotEmpty()) {
                singleClickTimer?.stop()
                pendingSingleClickNode = node

                singleClickTimer = Timer(MindmapConstants.SINGLE_CLICK_DELAY_MS) { _ ->
                    val clickedNode = pendingSingleClickNode
                    if (clickedNode != null && clickedNode.node.children.isNotEmpty()) {
                        toggleCollapse(clickedNode.node.id)
                    }
                    singleClickTimer = null
                    pendingSingleClickNode = null
                }
                singleClickTimer?.isRepeats = false
                singleClickTimer?.start()
            }
        }
    }

    private fun toggleCollapse(nodeId: String) {
        // Save screen position of the node before collapse/expand
        val nodeBounds = findNodeById(nodeId, rootNodeBounds)
        val screenPosition = nodeBounds?.let {
            val (screenX, screenY) = dialogViewport.worldToScreen(it.centerX, it.centerY)
            Pair(screenX, screenY)
        }

        // Toggle collapse state
        dialogLayout.setCollapsed(nodeId, !dialogLayout.isCollapsed(nodeId))

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
                dialogViewport.offsetX = targetScreenX / dialogViewport.scale - newWorldX
                dialogViewport.offsetY = targetScreenY / dialogViewport.scale - newWorldY

                // Constrain pan bounds after adjustment
                panel?.let { constrainPanBounds(it.width, it.height) }
            }
        }

        panel?.repaint()
    }

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

    private fun recalculateLayout() {
        rootNodeBounds = if (rootNode.children.isEmpty()) {
            val nodeTextSize = textMeasurer.calculateTextSize(rootNode.text, 0, false)
            NodeBounds(
                node = rootNode,
                x = MindmapConstants.ROOT_X,
                y = MindmapConstants.BASE_START_Y,
                width = nodeTextSize.width,
                height = nodeTextSize.height,
                childBounds = emptyList(),
                colorIndex = 0,
                isRoot = true
            )
        } else {
            dialogLayout.calculateLayout(rootNode) ?: run {
                val nodeTextSize = textMeasurer.calculateTextSize(rootNode.text, 0, rootNode.children.isNotEmpty())
                NodeBounds(
                    node = rootNode,
                    x = MindmapConstants.ROOT_X,
                    y = MindmapConstants.BASE_START_Y,
                    width = nodeTextSize.width,
                    height = nodeTextSize.height,
                    childBounds = emptyList(),
                    colorIndex = 0,
                    isRoot = true
                )
            }
        }

        panel?.let {
            if (it.width > 0 && it.height > 0) {
                constrainPanBounds(it.width, it.height)
            }
        }
    }

    private fun constrainPanBounds(width: Int, height: Int) {
        if (rootNodeBounds == null) return

        val contentBounds = calculateContentBounds(rootNodeBounds!!)
        dialogViewport.constrainPanBounds(
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

    private fun calculateLayout() {
        // Clear collapsed state only on initial layout - all nodes start expanded
        // After that, preserve user's collapse/expand choices
        if (isInitialLayout) {
            dialogLayout.clearCollapsed()
            isInitialLayout = false
        }

        // Calculate layout
        // MindmapLayout.calculateLayout requires root node to have children
        // If node has no children, create a simple layout for just the node
        rootNodeBounds = if (rootNode.children.isEmpty()) {
            // Create a simple bounds for a node without children
            val nodeTextSize = textMeasurer.calculateTextSize(rootNode.text, 0, false)
            NodeBounds(
                node = rootNode,
                x = MindmapConstants.ROOT_X,
                y = MindmapConstants.BASE_START_Y,
                width = nodeTextSize.width,
                height = nodeTextSize.height,
                childBounds = emptyList(),
                colorIndex = 0,
                isRoot = true
            )
        } else {
            // Calculate layout - this should return a valid NodeBounds
            // Note: calculateLayout returns null if rootNode.children.isEmpty(),
            // but we already checked that above
            dialogLayout.calculateLayout(rootNode) ?: run {
                // Fallback: create a simple layout even if calculateLayout returns null
                val nodeTextSize = textMeasurer.calculateTextSize(rootNode.text, 0, rootNode.children.isNotEmpty())
                NodeBounds(
                    node = rootNode,
                    x = MindmapConstants.ROOT_X,
                    y = MindmapConstants.BASE_START_Y,
                    width = nodeTextSize.width,
                    height = nodeTextSize.height,
                    childBounds = emptyList(),
                    colorIndex = 0,
                    isRoot = true
                )
            }
        }

        SwingUtilities.invokeLater {
            panel?.let {
                if (it.width > 0 && it.height > 0) {
                    fitToCenter(it.width, it.height)
                }
                it.repaint()
            }
        }
    }

    private fun fitToCenter(width: Int, height: Int) {
        if (rootNodeBounds == null || width == 0 || height == 0) return

        val rootLeftX = rootNodeBounds!!.x
        var rightmostX = rootNodeBounds!!.rightX

        fun findRightmost(bounds: NodeBounds) {
            if (bounds.rightX > rightmostX) {
                rightmostX = bounds.rightX
            }
            bounds.childBounds.forEach { findRightmost(it) }
        }
        rootNodeBounds!!.childBounds.forEach { findRightmost(it) }

        val rootCenterY = rootNodeBounds!!.centerY
        dialogViewport.fitToContent(rootLeftX, rightmostX, rootCenterY, width, height)
        constrainPanBounds(width, height)
    }
}

