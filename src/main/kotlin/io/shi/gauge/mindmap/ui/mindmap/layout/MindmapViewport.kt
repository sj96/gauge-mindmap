package io.shi.gauge.mindmap.ui.mindmap.layout

import io.shi.gauge.mindmap.ui.mindmap.constants.MindmapConstants

/**
 * Manages viewport state (pan, zoom, offset)
 */
class MindmapViewport(
    var offsetX: Double = 0.0,
    var offsetY: Double = 0.0,
    var scale: Double = MindmapConstants.DEFAULT_SCALE
) {

    fun zoomIn(viewportWidth: Int, viewportHeight: Int) {
        // Save world position of viewport center before zoom
        // Formula: worldX = (screenX - offsetX) / scale
        val centerScreenX = viewportWidth / 2.0
        val centerScreenY = viewportHeight / 2.0
        val centerWorldX = (centerScreenX - offsetX) / scale
        val centerWorldY = (centerScreenY - offsetY) / scale

        // Apply zoom
        scale *= MindmapConstants.ZOOM_FACTOR
        scale = scale.coerceIn(MindmapConstants.MIN_SCALE, MindmapConstants.MAX_SCALE)

        // Adjust offset to keep center point at same world position
        // After zoom: centerWorldX = (centerScreenX - newOffsetX) / newScale
        // So: centerScreenX - newOffsetX = centerWorldX * newScale
        // newOffsetX = centerScreenX - centerWorldX * newScale
        offsetX = centerScreenX - centerWorldX * scale
        offsetY = centerScreenY - centerWorldY * scale
    }

    fun zoomOut(viewportWidth: Int, viewportHeight: Int) {
        // Save world position of viewport center before zoom
        // Formula: worldX = (screenX - offsetX) / scale
        val centerScreenX = viewportWidth / 2.0
        val centerScreenY = viewportHeight / 2.0
        val centerWorldX = (centerScreenX - offsetX) / scale
        val centerWorldY = (centerScreenY - offsetY) / scale

        // Apply zoom
        scale /= MindmapConstants.ZOOM_FACTOR
        scale = scale.coerceIn(MindmapConstants.MIN_SCALE, MindmapConstants.MAX_SCALE)

        // Adjust offset to keep center point at same world position
        // After zoom: centerWorldX = (centerScreenX - newOffsetX) / newScale
        // So: centerScreenX - newOffsetX = centerWorldX * newScale
        // newOffsetX = centerScreenX - centerWorldX * newScale
        offsetX = centerScreenX - centerWorldX * scale
        offsetY = centerScreenY - centerWorldY * scale
    }

    fun reset() {
        offsetX = 0.0
        offsetY = 0.0
        scale = MindmapConstants.DEFAULT_SCALE
    }

    fun pan(deltaX: Double, deltaY: Double) {
        offsetX += deltaX / scale
        offsetY += deltaY / scale
    }

    /**
     * Constrains pan bounds to keep viewport within content area
     * @param contentMinX Minimum X of content
     * @param contentMaxX Maximum X of content
     * @param contentMinY Minimum Y of content
     * @param contentMaxY Maximum Y of content
     * @param viewportWidth Width of viewport
     * @param viewportHeight Height of viewport
     * @param margin Optional margin to allow panning slightly outside content
     */
    fun constrainPanBounds(
        contentMinX: Double,
        contentMaxX: Double,
        contentMinY: Double,
        contentMaxY: Double,
        viewportWidth: Int,
        viewportHeight: Int,
        margin: Double = MindmapConstants.PAN_MARGIN
    ) {
        // Calculate viewport size in world coordinates
        val viewportWorldWidth = viewportWidth / scale
        val viewportWorldHeight = viewportHeight / scale

        val contentWidth = contentMaxX - contentMinX
        val contentHeight = contentMaxY - contentMinY

        // Constrain X axis (left/right)
        // Only constrain if content width is larger than viewport width
        if (contentWidth + margin * 2 > viewportWorldWidth) {
            // Formula: worldX = (screenX - offsetX) / scale
            // When screenX = 0: worldX = -offsetX / scale
            // When screenX = viewportWidth: worldX = (viewportWidth - offsetX) / scale

            // Constraint: left edge (screenX = 0) should not go beyond contentMinX - margin
            // -offsetX / scale >= contentMinX - margin
            // -offsetX >= (contentMinX - margin) * scale
            // offsetX <= (margin - contentMinX) * scale
            val maxOffsetX = (margin - contentMinX) * scale

            // Constraint: right edge (screenX = viewportWidth) should not go beyond contentMaxX + margin
            // (viewportWidth - offsetX) / scale <= contentMaxX + margin
            // viewportWidth - offsetX <= (contentMaxX + margin) * scale
            // -offsetX <= (contentMaxX + margin) * scale - viewportWidth
            // offsetX >= viewportWidth - (contentMaxX + margin) * scale
            val minOffsetX = viewportWidth - (contentMaxX + margin) * scale

            // Apply constraints (swap if needed)
            val actualMin = minOf(minOffsetX, maxOffsetX)
            val actualMax = maxOf(minOffsetX, maxOffsetX)

            if (actualMin <= actualMax) {
                offsetX = offsetX.coerceIn(actualMin, actualMax)
            }
        }

        // Constrain Y axis (top/bottom)
        // Only constrain if content height is larger than viewport height
        if (contentHeight + margin * 2 > viewportWorldHeight) {
            // Similar logic for Y
            // Constraint: top edge (screenY = 0) should not go beyond contentMinY - margin
            // -offsetY / scale >= contentMinY - margin
            // offsetY <= (margin - contentMinY) * scale
            val maxOffsetY = (margin - contentMinY) * scale

            // Constraint: bottom edge (screenY = viewportHeight) should not go beyond contentMaxY + margin
            // (viewportHeight - offsetY) / scale <= contentMaxY + margin
            // offsetY >= viewportHeight - (contentMaxY + margin) * scale
            val minOffsetY = viewportHeight - (contentMaxY + margin) * scale

            // Apply constraints (swap if needed)
            val actualMin = minOf(minOffsetY, maxOffsetY)
            val actualMax = maxOf(minOffsetY, maxOffsetY)

            if (actualMin <= actualMax) {
                offsetY = offsetY.coerceIn(actualMin, actualMax)
            }
        }
    }

    fun screenToWorld(screenX: Int, screenY: Int): Pair<Double, Double> {
        val worldX = (screenX - offsetX) / scale
        val worldY = (screenY - offsetY) / scale
        return Pair(worldX, worldY)
    }

    fun worldToScreen(worldX: Double, worldY: Double): Pair<Double, Double> {
        val screenX = (worldX + offsetX) * scale
        val screenY = (worldY + offsetY) * scale
        return Pair(screenX, screenY)
    }

    fun fitToContent(
        contentMinX: Double,
        contentMaxX: Double,
        contentCenterY: Double,
        viewportWidth: Int,
        viewportHeight: Int
    ) {
        val contentWidth = contentMaxX - contentMinX
        val margin = MindmapConstants.FIT_MARGIN

        // Calculate scale to fit horizontally
        val scaleX = if (contentWidth > 0) (viewportWidth - margin * 2) / contentWidth else 1.0
        scale = scaleX.coerceIn(MindmapConstants.MIN_SCALE, MindmapConstants.DEFAULT_SCALE)

        // Center vertically
        offsetY = viewportHeight / 2.0 / scale - contentCenterY

        // Position left edge at margin
        offsetX = margin / scale - contentMinX
    }

    fun centerContent(
        contentCenterX: Double,
        contentCenterY: Double,
        viewportWidth: Int,
        viewportHeight: Int
    ) {
        offsetX = viewportWidth / 2.0 / scale - contentCenterX
        offsetY = viewportHeight / 2.0 / scale - contentCenterY
    }
}

