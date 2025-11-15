package io.shi.gauge.mindmap.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.event.DocumentListener

class MindmapToolWindowFactory : ToolWindowFactory {

    companion object {
        // UI Constants
        private const val BUTTON_SIZE = 24
        private const val SEPARATOR_HEIGHT = 18
        private const val BUTTON_SPACING = 2
        private const val FILTER_LABEL_SPACING = 3
        private const val TOOLBAR_BORDER_TOP = 3
        private const val TOOLBAR_BORDER_SIDES = 5
        private const val BUTTON_BORDER = 2
        private const val BUTTON_CORNER_RADIUS = 4.0
        private const val FONT_SIZE_REDUCTION = 1f
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val mindmapView = MindmapView(project)
        viewMap[project] = mindmapView

        val toolbar = createToolbar(mindmapView, project)
        val contentPanel = createContentPanel(mindmapView)
        val mainPanel = createMainPanel(toolbar, contentPanel)

        val content = ContentFactory.getInstance().createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)

        mindmapView.loadSpecifications()
    }

    private fun createToolbar(mindmapView: MindmapView, project: Project): JPanel {
        val toolbar = JPanel(BorderLayout())
        toolbar.border = BorderFactory.createEmptyBorder(
            TOOLBAR_BORDER_TOP, TOOLBAR_BORDER_SIDES,
            TOOLBAR_BORDER_TOP, TOOLBAR_BORDER_SIDES
        )

        val leftPanel = createToolbarLeftPanel(mindmapView, project)
        val rightPanel = createToolbarRightPanel(mindmapView)

        toolbar.add(leftPanel, BorderLayout.WEST)
        toolbar.add(rightPanel, BorderLayout.EAST)

        return toolbar
    }

    private fun createToolbarLeftPanel(mindmapView: MindmapView, project: Project): JPanel {
        val panel = createHorizontalPanel()

        addButton(panel, AllIcons.Actions.Refresh, "Refresh specifications") {
            mindmapView.loadSpecifications()
        }
        addSeparator(panel)

        addButton(panel, AllIcons.General.ZoomOut, "Zoom Out") {
            mindmapView.zoomOut()
        }
        addButton(panel, AllIcons.General.ZoomIn, "Zoom In") {
            mindmapView.zoomIn()
        }
        addButton(panel, AllIcons.General.FitContent, "Fit all content to view") {
            mindmapView.fitToCenter()
        }
        addSeparator(panel)

        addButton(panel, AllIcons.Actions.Collapseall, "Collapse all specifications") {
            mindmapView.collapseAllSpecifications()
        }
        addButton(panel, AllIcons.Actions.Expandall, "Expand all specifications") {
            mindmapView.expandAllSpecifications()
        }
        addSeparator(panel)

        addButton(panel, AllIcons.Actions.MenuSaveall, "Export mindmap to image") {
            mindmapView.exportToImage(project)
        }
        addSeparator(panel)

        val minimapCheckbox = createMinimapCheckbox(mindmapView)
        panel.add(minimapCheckbox)

        return panel
    }

    private fun createToolbarRightPanel(mindmapView: MindmapView): JPanel {
        val panel = createHorizontalPanel()

        val filterLabel = JLabel("Filter:")
        filterLabel.alignmentY = JComponent.CENTER_ALIGNMENT
        panel.add(filterLabel)
        panel.add(Box.createHorizontalStrut(FILTER_LABEL_SPACING))

        val filterTextField = createFilterTextField(mindmapView)
        panel.add(filterTextField)

        return panel
    }

    private fun createHorizontalPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.X_AXIS)
        panel.alignmentY = JComponent.CENTER_ALIGNMENT
        return panel
    }

    private fun addButton(panel: JPanel, icon: Icon, tooltip: String, action: () -> Unit) {
        val button = createToolbarButton(icon, tooltip, action)
        button.alignmentY = JComponent.CENTER_ALIGNMENT
        panel.add(button)
        panel.add(Box.createHorizontalStrut(BUTTON_SPACING))
    }

    private fun addSeparator(panel: JPanel) {
        val separator = JSeparator(SwingConstants.VERTICAL)
        separator.preferredSize = Dimension(1, SEPARATOR_HEIGHT)
        separator.alignmentY = JComponent.CENTER_ALIGNMENT
        panel.add(separator)
        panel.add(Box.createHorizontalStrut(BUTTON_SPACING))
    }

    private fun createToolbarButton(icon: Icon, tooltip: String, action: () -> Unit): JButton {
        val button = RoundedToolbarButton(icon)
        button.toolTipText = tooltip
        button.addActionListener { action() }
        return button
    }

    private fun createMinimapCheckbox(mindmapView: MindmapView): JCheckBox {
        val checkbox = JCheckBox("Minimap", true)
        checkbox.toolTipText = "Show/hide minimap"
        checkbox.alignmentY = JComponent.CENTER_ALIGNMENT
        checkbox.font = checkbox.font.deriveFont(checkbox.font.size - FONT_SIZE_REDUCTION)
        checkbox.addActionListener {
            mindmapView.setMinimapVisible(checkbox.isSelected)
        }
        return checkbox
    }

    private fun createFilterTextField(mindmapView: MindmapView): JTextField {
        val textField = JTextField(25)
        textField.toolTipText = "Filter specifications and scenarios by name"
        textField.alignmentY = JComponent.CENTER_ALIGNMENT
        textField.preferredSize = Dimension(textField.preferredSize.width, BUTTON_SIZE)
        textField.maximumSize = Dimension(textField.maximumSize.width, BUTTON_SIZE)
        textField.font = textField.font.deriveFont(textField.font.size - FONT_SIZE_REDUCTION)

        textField.addActionListener {
            mindmapView.setFilter(textField.text)
        }

        textField.document.addDocumentListener(createFilterDocumentListener(textField, mindmapView))

        return textField
    }

    private fun createFilterDocumentListener(
        textField: JTextField,
        mindmapView: MindmapView
    ): DocumentListener {
        return object : DocumentListener {
            private fun updateFilter() {
                mindmapView.setFilter(textField.text)
            }

            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = updateFilter()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = updateFilter()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = updateFilter()
        }
    }

    private fun createContentPanel(mindmapView: MindmapView): JPanel {
        mindmapView.layout = null // Use absolute layout

        val statisticsPanel = StatisticsPanel()
        statisticsPanel.initialize()

        mindmapView.setStatisticsUpdateCallback { stats ->
            statisticsPanel.updateStatistics(stats)
        }

        SwingUtilities.invokeLater {
            val stats = mindmapView.getStatistics()
            statisticsPanel.updateStatistics(stats)
        }

        val contentPanel = JPanel(BorderLayout())
        contentPanel.add(mindmapView, BorderLayout.CENTER)
        contentPanel.add(statisticsPanel, BorderLayout.SOUTH)

        return contentPanel
    }

    private fun createMainPanel(toolbar: JPanel, contentPanel: JPanel): JPanel {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.add(toolbar, BorderLayout.NORTH)
        mainPanel.add(contentPanel, BorderLayout.CENTER)
        return mainPanel
    }

    private class RoundedToolbarButton(icon: Icon) : JButton(icon) {
        private var isHovered = false

        init {
            isContentAreaFilled = false
            isFocusPainted = false
            isOpaque = false
            border = BorderFactory.createEmptyBorder(
                BUTTON_BORDER, BUTTON_BORDER,
                BUTTON_BORDER, BUTTON_BORDER
            )
            val buttonSize = Dimension(BUTTON_SIZE, BUTTON_SIZE)
            preferredSize = buttonSize
            minimumSize = buttonSize
            maximumSize = buttonSize

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

            if (isHovered) {
                val hoverColor = UIUtil.getListSelectionBackground(true)
                g2d.color = hoverColor
                val rect = RoundRectangle2D.Double(
                    0.0, 0.0,
                    width.toDouble(), height.toDouble(),
                    BUTTON_CORNER_RADIUS, BUTTON_CORNER_RADIUS
                )
                g2d.fill(rect)
            }

            super.paintComponent(g)
        }
    }
}

private val viewMap = mutableMapOf<Project, MindmapView>()
fun getView(project: Project): MindmapView? {
    return viewMap[project]
}