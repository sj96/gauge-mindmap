package io.shi.gauge.mindmap.ui

import com.intellij.ui.JBColor
import io.shi.gauge.mindmap.ui.MindmapView.Statistics
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Panel to display statistics about specifications and scenarios
 */
class StatisticsPanel : JPanel(BorderLayout()) {

    private val statisticsLabel: JLabel

    init {
        // Setup border - use a subtle gray color that works in both light and dark themes
        val borderColor = JBColor(
            Color(200, 200, 200), // Light theme
            Color(60, 60, 60)      // Dark theme
        )
        
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, borderColor),
            BorderFactory.createEmptyBorder(4, 8, 4, 8)
        )

        // Create and configure label
        statisticsLabel = JLabel("Specifications: 0 | Scenarios: 0")
        statisticsLabel.font = statisticsLabel.font.deriveFont(statisticsLabel.font.size - 1f)
        statisticsLabel.toolTipText = "Total number of specifications and scenarios"
        add(statisticsLabel, BorderLayout.WEST)
    }

    /**
     * Update the statistics display
     */
    fun updateStatistics(stats: Statistics) {
        SwingUtilities.invokeLater {
            statisticsLabel.text = "Specifications: ${stats.specificationCount} | Scenarios: ${stats.scenarioCount}"
        }
    }

    /**
     * Initialize with default statistics
     */
    fun initialize() {
        statisticsLabel.text = "Specifications: 0 | Scenarios: 0"
    }
}

