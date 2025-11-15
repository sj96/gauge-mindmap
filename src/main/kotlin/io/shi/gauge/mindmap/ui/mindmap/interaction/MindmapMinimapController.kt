package io.shi.gauge.mindmap.ui.mindmap.interaction

import io.shi.gauge.mindmap.ui.mindmap.constants.MindmapConstants
import io.shi.gauge.mindmap.ui.mindmap.layout.MindmapViewport
import io.shi.gauge.mindmap.ui.mindmap.model.NodeBounds
import io.shi.gauge.mindmap.ui.mindmap.render.MindmapMinimapRenderer
import io.shi.gauge.mindmap.ui.mindmap.render.MinimapBounds
import io.shi.gauge.mindmap.ui.mindmap.render.MinimapSize
import java.awt.event.MouseEvent
import java.awt.geom.Rectangle2D
import javax.swing.SwingUtilities

/**
 * Controller for minimap interactions (mouse events, dragging, viewport navigation)
 * Separated from MindmapView for better OOP structure
 */
class MindmapMinimapController(
    private val viewport: MindmapViewport,
    private val minimapRenderer: MindmapMinimapRenderer,
    private val onRepaint: () -> Unit,
    private val onConstrainPanBounds: () -> Unit
) {
    private var dragState: MinimapDragState? = null

    private data class MinimapDragState(
        val startX: Int,
        val startY: Int,
        val startOffsetX: Double,
        val startOffsetY: Double
    )

    /**
     * Check if a point is within the minimap bounds
     */
    fun isPointInMinimap(
        screenX: Int,
        screenY: Int,
        rootBounds: NodeBounds?,
        viewportWidth: Int,
        viewportHeight: Int,
        isVisible: Boolean = true
    ): Boolean {
        if (!isVisible || rootBounds == null) return false

        val bounds = minimapRenderer.calculateBounds(rootBounds) ?: return false
        val minimapSize = minimapRenderer.getMinimapSize(bounds, viewportHeight) ?: return false

        // Calculate minimap position from right edge of viewport
        val minimapX = viewportWidth - minimapSize.width.toInt() - minimapSize.margin
        val minimapY = minimapSize.margin

        return screenX >= minimapX && screenX <= minimapX + minimapSize.width.toInt() &&
                screenY >= minimapY && screenY <= minimapY + minimapSize.height.toInt()
    }

    /**
     * Handle mouse press on minimap
     * @return true if the event was handled by minimap
     */
    fun handleMousePressed(
        e: MouseEvent,
        rootBounds: NodeBounds?,
        viewportWidth: Int,
        viewportHeight: Int,
        isVisible: Boolean = true
    ): Boolean {
        if (!isVisible ||
            !isPointInMinimap(e.x, e.y, rootBounds, viewportWidth, viewportHeight, isVisible) ||
            !SwingUtilities.isLeftMouseButton(e) ||
            rootBounds == null
        ) {
            return false
        }

        val bounds = minimapRenderer.calculateBounds(rootBounds) ?: return false
        val minimapSize = minimapRenderer.getMinimapSize(bounds, viewportHeight) ?: return false

        // Calculate minimap position from right edge of viewport
        val minimapX = viewportWidth - minimapSize.width.toInt() - minimapSize.margin
        val minimapY = minimapSize.margin

        val relativeX = e.x - minimapX
        val relativeY = e.y - minimapY

        // Check if clicking on viewport rectangle
        val viewportRect = getViewportRectInMinimap(
            bounds,
            minimapSize,
            viewportWidth,
            viewportHeight
        )

        if (viewportRect.contains(relativeX.toDouble(), relativeY.toDouble())) {
            // Start dragging viewport rectangle
            dragState = MinimapDragState(
                e.x,
                e.y,
                viewport.offsetX,
                viewport.offsetY
            )
            return true
        } else {
            // Click outside viewport - move viewport to clicked position
            moveViewportFromMinimap(
                e.x,
                e.y,
                bounds,
                minimapSize,
                minimapX,
                minimapY,
                viewportWidth,
                viewportHeight
            )
            return true
        }
    }

    /**
     * Handle mouse drag on minimap
     * @return true if the event was handled by minimap
     */
    fun handleMouseDragged(
        e: MouseEvent,
        rootBounds: NodeBounds?,
        viewportHeight: Int
    ): Boolean {
        val currentDragState = dragState ?: return false

        if (rootBounds == null) {
            dragState = null
            return false
        }

        val bounds = minimapRenderer.calculateBounds(rootBounds) ?: return false
        val minimapSize = minimapRenderer.getMinimapSize(bounds, viewportHeight) ?: return false

        val mouseDeltaX = e.x - currentDragState.startX
        val mouseDeltaY = e.y - currentDragState.startY

        val padding = MindmapConstants.MINIMAP_PADDING
        val availableWidth = minimapSize.width - padding * 2
        val availableHeight = minimapSize.height - padding * 2
        val scaleX = availableWidth / bounds.contentWidth
        val scaleY = availableHeight / bounds.contentHeight
        val minimapScale = minOf(scaleX, scaleY)

        // Convert minimap delta to world delta
        val worldDeltaX = mouseDeltaX / minimapScale
        val worldDeltaY = mouseDeltaY / minimapScale

        // Update offset
        val newOffsetX = currentDragState.startOffsetX - worldDeltaX * viewport.scale
        val newOffsetY = currentDragState.startOffsetY - worldDeltaY * viewport.scale

        viewport.offsetX = newOffsetX
        viewport.offsetY = newOffsetY

        // Constrain pan bounds
        onConstrainPanBounds()

        onRepaint()
        return true
    }

    /**
     * Handle mouse release on minimap
     * @return true if the event was handled by minimap
     */
    fun handleMouseReleased(): Boolean {
        if (dragState != null) {
            dragState = null
            return true
        }
        return false
    }

    private fun getViewportRectInMinimap(
        bounds: MinimapBounds,
        minimapSize: MinimapSize,
        viewportWidth: Int,
        viewportHeight: Int
    ): Rectangle2D.Double {
        val padding = MindmapConstants.MINIMAP_PADDING

        val availableWidth = minimapSize.width - padding * 2
        val availableHeight = minimapSize.height - padding * 2
        val scaleX = availableWidth / bounds.contentWidth
        val scaleY = availableHeight / bounds.contentHeight
        val minimapScale = minOf(scaleX, scaleY)

        val viewportWorldX = -viewport.offsetX / viewport.scale
        val viewportWorldY = -viewport.offsetY / viewport.scale
        val viewportWorldWidth = viewportWidth / viewport.scale
        val viewportWorldHeight = viewportHeight / viewport.scale

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
        bounds: MinimapBounds,
        minimapSize: MinimapSize,
        minimapX: Int,
        minimapY: Int,
        viewportWidth: Int,
        viewportHeight: Int
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
        val newOffsetX = (viewportWidth / 2.0 / viewport.scale - worldX) * viewport.scale
        val newOffsetY = (viewportHeight / 2.0 / viewport.scale - worldY) * viewport.scale

        viewport.offsetX = newOffsetX
        viewport.offsetY = newOffsetY

        // Constrain pan bounds
        onConstrainPanBounds()

        onRepaint()
    }
}

