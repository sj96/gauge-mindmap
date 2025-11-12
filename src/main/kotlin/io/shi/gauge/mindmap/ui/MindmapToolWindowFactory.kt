package io.shi.gauge.mindmap.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.*

class MindmapToolWindowFactory : ToolWindowFactory {

    companion object {
        private val viewMap = mutableMapOf<Project, MindmapView>()

        fun getView(project: Project): MindmapView? {
            return viewMap[project]
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val mindmapView = MindmapView(project)
        viewMap[project] = mindmapView

        // Create toolbar with better UI/UX
        val toolbar = JPanel(BorderLayout())
        toolbar.border = BorderFactory.createEmptyBorder(3, 5, 3, 5)

        // Left side: Action buttons - use BoxLayout for vertical centering
        val leftPanel = JPanel()
        leftPanel.layout = BoxLayout(leftPanel, BoxLayout.X_AXIS)
        leftPanel.alignmentY = JComponent.CENTER_ALIGNMENT

        // Custom button with rounded corners and IntelliJ standard colors
        class RoundedToolbarButton(icon: Icon) : JButton(icon) {
            private var isHovered = false
            private val cornerRadius = 4.0

            init {
                isContentAreaFilled = false
                isFocusPainted = false
                isOpaque = false
                border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
                preferredSize = java.awt.Dimension(24, 24)
                minimumSize = java.awt.Dimension(24, 24)
                maximumSize = java.awt.Dimension(24, 24)

                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseEntered(e: java.awt.event.MouseEvent) {
                        isHovered = true
                        repaint()
                    }

                    override fun mouseExited(e: java.awt.event.MouseEvent) {
                        isHovered = false
                        repaint()
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

        // Helper function to create button with hover effect following IntelliJ design principles
        fun createToolbarButton(icon: Icon, tooltip: String, action: () -> Unit): JButton {
            val button = RoundedToolbarButton(icon)
            button.toolTipText = tooltip
            button.addActionListener { action() }
            return button
        }

        // Refresh button
        val refreshButton = createToolbarButton(AllIcons.Actions.Refresh, "Refresh specifications") {
            mindmapView.loadSpecifications()
        }
        refreshButton.alignmentY = JComponent.CENTER_ALIGNMENT
        leftPanel.add(refreshButton)
        leftPanel.add(Box.createHorizontalStrut(2))

        // Separator
        val separator = JSeparator(SwingConstants.VERTICAL)
        separator.preferredSize = java.awt.Dimension(1, 18)
        separator.alignmentY = JComponent.CENTER_ALIGNMENT
        leftPanel.add(separator)
        leftPanel.add(Box.createHorizontalStrut(2))

        // Zoom controls - use zoom icons instead of add/remove
        val zoomOutButton = createToolbarButton(AllIcons.General.ZoomOut, "Zoom Out") {
            mindmapView.zoomOut()
        }
        zoomOutButton.alignmentY = JComponent.CENTER_ALIGNMENT
        leftPanel.add(zoomOutButton)
        leftPanel.add(Box.createHorizontalStrut(2))

        val zoomInButton = createToolbarButton(AllIcons.General.ZoomIn, "Zoom In") {
            mindmapView.zoomIn()
        }
        zoomInButton.alignmentY = JComponent.CENTER_ALIGNMENT
        leftPanel.add(zoomInButton)
        leftPanel.add(Box.createHorizontalStrut(2))

        // Fit to view button - use fit content icon
        val fitToViewButton = createToolbarButton(AllIcons.General.FitContent, "Fit all content to view") {
            mindmapView.fitToCenter()
        }
        fitToViewButton.alignmentY = JComponent.CENTER_ALIGNMENT
        leftPanel.add(fitToViewButton)
        leftPanel.add(Box.createHorizontalStrut(2))

        // Separator
        val separator2 = JSeparator(SwingConstants.VERTICAL)
        separator2.preferredSize = java.awt.Dimension(1, 18)
        separator2.alignmentY = JComponent.CENTER_ALIGNMENT
        leftPanel.add(separator2)
        leftPanel.add(Box.createHorizontalStrut(2))

        // Collapse all specifications button
        val collapseAllButton = createToolbarButton(AllIcons.Actions.Collapseall, "Collapse all specifications") {
            mindmapView.collapseAllSpecifications()
        }
        collapseAllButton.alignmentY = JComponent.CENTER_ALIGNMENT
        leftPanel.add(collapseAllButton)
        leftPanel.add(Box.createHorizontalStrut(2))

        // Expand all specifications button
        val expandAllButton = createToolbarButton(AllIcons.Actions.Expandall, "Expand all specifications") {
            mindmapView.expandAllSpecifications()
        }
        expandAllButton.alignmentY = JComponent.CENTER_ALIGNMENT
        leftPanel.add(expandAllButton)
        leftPanel.add(Box.createHorizontalStrut(2))

        // Separator
        val separator3 = JSeparator(SwingConstants.VERTICAL)
        separator3.preferredSize = java.awt.Dimension(1, 18)
        separator3.alignmentY = JComponent.CENTER_ALIGNMENT
        leftPanel.add(separator3)
        leftPanel.add(Box.createHorizontalStrut(2))

        // Export to image button - use SaveAll icon (save/export icon)
        val exportButton = createToolbarButton(AllIcons.Actions.Menu_saveall, "Export mindmap to image") {
            mindmapView.exportToImage(project)
        }
        exportButton.alignmentY = JComponent.CENTER_ALIGNMENT
        leftPanel.add(exportButton)
        leftPanel.add(Box.createHorizontalStrut(2))

        // Separator
        val separator4 = JSeparator(SwingConstants.VERTICAL)
        separator4.preferredSize = java.awt.Dimension(1, 18)
        separator4.alignmentY = JComponent.CENTER_ALIGNMENT
        leftPanel.add(separator4)
        leftPanel.add(Box.createHorizontalStrut(2))

        // Minimap checkbox
        val minimapCheckbox = JCheckBox("Minimap", true)
        minimapCheckbox.toolTipText = "Show/hide minimap"
        minimapCheckbox.alignmentY = JComponent.CENTER_ALIGNMENT
        minimapCheckbox.font = minimapCheckbox.font.deriveFont(minimapCheckbox.font.size - 1f)
        minimapCheckbox.addActionListener {
            mindmapView.setMinimapVisible(minimapCheckbox.isSelected)
        }
        leftPanel.add(minimapCheckbox)

        toolbar.add(leftPanel, BorderLayout.WEST)

        // Right side: Filter - use BoxLayout for vertical centering
        val rightPanel = JPanel()
        rightPanel.layout = BoxLayout(rightPanel, BoxLayout.X_AXIS)
        rightPanel.alignmentY = JComponent.CENTER_ALIGNMENT

        val filterLabel = JLabel("Filter:")
        filterLabel.alignmentY = JComponent.CENTER_ALIGNMENT
        rightPanel.add(filterLabel)
        rightPanel.add(Box.createHorizontalStrut(3))
        val filterTextField = JTextField(25)
        filterTextField.toolTipText = "Filter specifications and scenarios by name"
        filterTextField.alignmentY = JComponent.CENTER_ALIGNMENT
        // Set height to match button height (24px)
        val textFieldHeight = 24
        filterTextField.preferredSize = java.awt.Dimension(
            filterTextField.preferredSize.width,
            textFieldHeight
        )
        filterTextField.maximumSize = java.awt.Dimension(
            filterTextField.maximumSize.width,
            textFieldHeight
        )
        // Reduce font size slightly to fit better in smaller height
        filterTextField.font = filterTextField.font.deriveFont(filterTextField.font.size - 1f)
        filterTextField.addActionListener {
            mindmapView.setFilter(filterTextField.text)
        }
        filterTextField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) {
                mindmapView.setFilter(filterTextField.text)
            }

            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) {
                mindmapView.setFilter(filterTextField.text)
            }

            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) {
                mindmapView.setFilter(filterTextField.text)
            }
        })
        rightPanel.add(filterTextField)
        toolbar.add(rightPanel, BorderLayout.EAST)

        // Don't use scroll pane - let MindmapView handle pan/zoom internally
        // This ensures the view fits exactly in the viewport without scrolling
        mindmapView.layout = null // Use absolute layout

        // Create main panel
        val mainPanel = JPanel(BorderLayout())
        mainPanel.add(toolbar, BorderLayout.NORTH)
        mainPanel.add(mindmapView, BorderLayout.CENTER)

        val content = ContentFactory.getInstance().createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)

        // Load initial data
        mindmapView.loadSpecifications()
    }
}

