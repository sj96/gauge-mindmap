package io.shi.gauge.mindmap.ui.mindmap.render

import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import io.shi.gauge.mindmap.ui.mindmap.layout.MindmapViewport
import io.shi.gauge.mindmap.ui.mindmap.model.HoverState
import io.shi.gauge.mindmap.ui.mindmap.model.NodeBounds
import io.shi.gauge.mindmap.ui.mindmap.model.RenderContext
import io.shi.gauge.mindmap.ui.mindmap.model.SelectionState
import io.shi.gauge.mindmap.ui.mindmap.util.MindmapTextMeasurer
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D

/**
 * Renders mindmap nodes and connections
 * Simplified to use game-style API: node.draw(g2d, context)
 */
class MindmapRenderer(
    private val textMeasurer: MindmapTextMeasurer
) {

    fun render(
        g2d: Graphics2D,
        rootBounds: NodeBounds?,
        viewportBounds: Rectangle2D.Double,
        viewportState: MindmapViewport,
        hoverState: HoverState,
        selectionState: SelectionState,
        collapsedNodeIds: Set<String>
    ) {
        if (rootBounds == null) {
            renderEmptyMessage(g2d, viewportState)
            return
        }

        // Set rendering hints optimized for text rendering at different zoom levels
        if (viewportState.scale >= 0.5) {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            // Enable fractional metrics for better text positioning at all zoom levels
            g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            // Use quality rendering for better text clarity
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        } else {
            // When very zoomed out, disable antialiasing for performance
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            // But still use fractional metrics for accurate text measurement
            g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        }

        // Create render context
        val context = RenderContext(
            textMeasurer = textMeasurer,
            viewportBounds = viewportBounds,
            viewportState = viewportState,
            hoverState = hoverState,
            selectionState = selectionState,
            collapsedNodeIds = collapsedNodeIds
        )

        // Game-style API: node.draw(g2d, context)
        rootBounds.draw(g2d, context)
    }

    private fun renderEmptyMessage(g2d: Graphics2D, viewport: MindmapViewport) {
        g2d.color = JBColor(Gray._200, Gray._200)
        g2d.font = textMeasurer.getJetBrainsFont(16, false)
        val message = "No Gauge specifications found"
        val fontMetrics = g2d.fontMetrics
        val (worldX, worldY) = viewport.screenToWorld(0, 0)
        val textX = (worldX - fontMetrics.stringWidth(message) / 2).toInt()
        val textY = worldY.toInt()
        g2d.drawString(message, textX, textY)
    }
}

