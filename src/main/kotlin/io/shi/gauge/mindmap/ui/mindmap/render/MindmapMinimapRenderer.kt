package io.shi.gauge.mindmap.ui.mindmap.render

import io.shi.gauge.mindmap.ui.mindmap.constants.MindmapColors
import io.shi.gauge.mindmap.ui.mindmap.constants.MindmapConstants
import io.shi.gauge.mindmap.ui.mindmap.layout.MindmapViewport
import io.shi.gauge.mindmap.ui.mindmap.model.NodeBounds
import java.awt.BasicStroke
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.CubicCurve2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D

/**
 * Helper class for minimap rendering with performance optimizations
 */
class MindmapMinimapRenderer {

    fun calculateBounds(rootBounds: NodeBounds?): MinimapBounds? {
        if (rootBounds == null) {
            return null
        }

        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = Double.MIN_VALUE
        var maxY = Double.MIN_VALUE

        fun calculate(bounds: NodeBounds) {
            minX = minOf(minX, bounds.x)
            minY = minOf(minY, bounds.y)
            maxX = maxOf(maxX, bounds.x + bounds.width)
            maxY = maxOf(maxY, bounds.y + bounds.height)
            bounds.childBounds.forEach { calculate(it) }
        }

        calculate(rootBounds)

        // Check if we have valid bounds
        if (minX == Double.MAX_VALUE || minY == Double.MAX_VALUE ||
            maxX == Double.MIN_VALUE || maxY == Double.MIN_VALUE
        ) {
            return null
        }

        val contentWidth = maxX - minX
        val contentHeight = maxY - minY
        if (contentWidth <= 0 || contentHeight <= 0) {
            return null
        }

        return MinimapBounds(minX, minY, maxX, maxY, contentWidth, contentHeight)
    }

    fun getMinimapSize(bounds: MinimapBounds?, viewportHeight: Int): MinimapSize? {
        if (bounds == null) {
            return null
        }

        val minimapWidth = MindmapConstants.MINIMAP_WIDTH
        val margin = MindmapConstants.MINIMAP_MARGIN
        val padding = MindmapConstants.MINIMAP_PADDING

        // Minimap always has fixed width
        val availableHeight = (viewportHeight - margin * 2).toDouble()
        val availableWidth = minimapWidth - padding * 2

        // Validate bounds
        if (availableHeight <= 0 || availableWidth <= 0) {
            return null
        }

        // Calculate aspect ratio
        if (bounds.contentHeight <= 0) {
            return null
        }
        val contentAspectRatio = bounds.contentWidth / bounds.contentHeight

        // Calculate height based on fixed width
        val contentHeightFromWidth = availableWidth / contentAspectRatio
        val totalHeightFromWidth = contentHeightFromWidth + padding * 2

        // Use fixed width, height is calculated from aspect ratio
        // But limit height to available space
        val finalHeight = minOf(totalHeightFromWidth, availableHeight)

        return MinimapSize(minimapWidth, finalHeight, margin)
    }

    fun renderMinimap(
        g2d: Graphics2D,
        rootBounds: NodeBounds?,
        viewport: MindmapViewport,
        viewportWidth: Int,
        viewportHeight: Int
    ) {
        if (rootBounds == null) return

        val bounds = calculateBounds(rootBounds) ?: return
        val minimapSize = getMinimapSize(bounds, viewportHeight) ?: return

        // Validate minimap size
        if (minimapSize.width <= 0 || minimapSize.height <= 0) return
        if (bounds.contentWidth <= 0 || bounds.contentHeight <= 0) return

        val savedTransform = g2d.transform
        try {
            // Reset to identity transform for minimap rendering
            // This ensures consistent rendering across platforms (including macOS Retina)
            // We need to work in device coordinates, not logical coordinates
            g2d.transform = AffineTransform()

            // Minimap is always right-aligned with fixed margin from right edge
            val rightMargin = MindmapConstants.MINIMAP_MARGIN
            val minimapX = viewportWidth - minimapSize.width.toInt() - rightMargin
            val minimapY = MindmapConstants.MINIMAP_MARGIN
            val padding = MindmapConstants.MINIMAP_PADDING
            val cornerRadius = 6.0

            // Calculate minimap scale once
            val availableWidth = minimapSize.width - padding * 2
            val availableHeight = minimapSize.height - padding * 2
            val scaleX = availableWidth / bounds.contentWidth
            val scaleY = availableHeight / bounds.contentHeight
            val minimapScale = minOf(scaleX, scaleY)

            // Calculate actual content area size after scaling
            val scaledContentWidth = bounds.contentWidth * minimapScale
            val scaledContentHeight = bounds.contentHeight * minimapScale

            // Content drawing position (where content starts)
            val contentStartX = minimapX + padding
            val contentStartY = minimapY + padding
            val contentAreaWidth = scaledContentWidth
            val contentAreaHeight = scaledContentHeight

            // Background should have padding around content
            val bgPadding = padding // Use same padding as content padding
            val bgStartX = contentStartX - bgPadding
            val bgStartY = contentStartY - bgPadding
            val bgWidth = contentAreaWidth + bgPadding * 2
            val bgHeight = contentAreaHeight + bgPadding * 2

            // Draw background with padding around content (no shadow, no border)
            val bgRect = RoundRectangle2D.Double(
                bgStartX,
                bgStartY,
                bgWidth,
                bgHeight,
                cornerRadius,
                cornerRadius
            )
            g2d.color = MindmapColors.minimapBgColor
            g2d.fill(bgRect)

            // Apply transform for content
            val transform = AffineTransform()
            transform.translate(minimapX + padding, minimapY + padding)
            transform.scale(minimapScale, minimapScale)
            transform.translate(-bounds.minX, -bounds.minY)
            g2d.transform(transform)

            // Set rendering hints for consistent rendering across platforms
            // On macOS Retina, we want crisp rendering for the minimap
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

            // Draw simplified mindmap
            drawMinimapRecursive(g2d, rootBounds, null)

            // Reset transform for viewport rectangle (device coordinates)
            // This ensures viewport rectangle is drawn in screen space, not transformed space
            g2d.transform = AffineTransform()

            // Calculate and draw viewport rectangle
            // Use the same reference point as content drawing (minimapX + padding)
            drawViewportRectangle(
                g2d,
                viewport,
                bounds,
                minimapScale,
                minimapX + padding,
                minimapY + padding,
                viewportWidth,
                viewportHeight
            )
        } finally {
            // Always restore transform, even if an exception occurs
            g2d.transform = savedTransform
        }
    }

    private fun drawViewportRectangle(
        g2d: Graphics2D,
        viewport: MindmapViewport,
        bounds: MinimapBounds,
        minimapScale: Double,
        contentStartX: Double,
        contentStartY: Double,
        viewportWidth: Int,
        viewportHeight: Int
    ) {
        // Calculate viewport world coordinates
        val viewportWorldX = -viewport.offsetX / viewport.scale
        val viewportWorldY = -viewport.offsetY / viewport.scale
        val viewportWorldWidth = viewportWidth / viewport.scale
        val viewportWorldHeight = viewportHeight / viewport.scale

        // Calculate viewport rectangle position in minimap coordinates
        // Use the same reference point as content drawing (contentStartX, contentStartY)
        // Content is drawn from (contentStartX, contentStartY) with transform:
        // translate(contentStartX, contentStartY) -> scale(minimapScale) -> translate(-bounds.minX, -bounds.minY)
        // So world point (worldX, worldY) maps to:
        // minimapX = contentStartX + (worldX - bounds.minX) * minimapScale
        // minimapY = contentStartY + (worldY - bounds.minY) * minimapScale

        val viewportMinimapX = contentStartX + (viewportWorldX - bounds.minX) * minimapScale
        val viewportMinimapY = contentStartY + (viewportWorldY - bounds.minY) * minimapScale

        // Calculate viewport rectangle size maintaining aspect ratio of actual viewport
        val viewportMinimapWidth = viewportWorldWidth * minimapScale
        val viewportMinimapHeight = viewportWorldHeight * minimapScale

        // Viewport rectangle can extend outside minimap bounds
        val viewportRect = Rectangle2D.Double(
            viewportMinimapX,
            viewportMinimapY,
            viewportMinimapWidth,
            viewportMinimapHeight
        )

        // Fill viewport with semi-transparent overlay (can extend outside minimap)
        g2d.color = MindmapColors.viewportFillColor
        g2d.fill(viewportRect)

        // Outer border for better visibility (can extend outside minimap)
        // Use device-independent stroke width for consistent rendering on macOS Retina
        g2d.color = MindmapColors.viewportBorderColor
        val strokeWidth = 2.0f
        g2d.stroke = BasicStroke(strokeWidth, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER)
        g2d.draw(viewportRect)

        // Inner highlight for depth (can extend outside minimap)
        // Use device-independent stroke width for consistent rendering on macOS Retina
        g2d.color = MindmapColors.viewportInnerColor
        g2d.stroke = BasicStroke(1.0f)
        val innerRect = Rectangle2D.Double(
            viewportMinimapX + 1,
            viewportMinimapY + 1,
            viewportMinimapWidth - 2,
            viewportMinimapHeight - 2
        )
        g2d.draw(innerRect)
    }

    private fun drawMinimapRecursive(g2d: Graphics2D, bounds: NodeBounds, parentBounds: NodeBounds?) {
        // Draw connection line if it has parent
        parentBounds?.let { parent ->
            val branchColor = MindmapColors.getBranchColor(bounds.colorIndex)
            g2d.color = branchColor

            // Use same curve style as main mindmap
            val curve = createConnectionCurve(
                parent.rightX,
                parent.centerY,
                bounds.x,
                bounds.centerY
            )

            // Apply stroke style similar to main mindmap but thinner for minimap
            val isMajorLevel = bounds.node.level <= 2
            val baseStrokeWidth = if (isMajorLevel) 0.8f else 0.6f
            val strokeWidth = baseStrokeWidth

            val dashPattern = if (isMajorLevel) {
                floatArrayOf(4.0f, 2.0f) // Smaller dash pattern for minimap
            } else {
                floatArrayOf(3.0f, 1.5f)
            }

            g2d.stroke = BasicStroke(
                strokeWidth,
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
                10.0f,
                dashPattern,
                0.0f
            )

            g2d.draw(curve)
        }

        // Draw node rectangle
        val (bgColor, _, _) = MindmapColors.getNodeColors(bounds.node.level, bounds.colorIndex)
        g2d.color = bgColor
        g2d.fillRect(
            bounds.x.toInt(),
            bounds.y.toInt(),
            bounds.width.toInt(),
            bounds.height.toInt()
        )

        // Draw children recursively
        bounds.childBounds.forEach { child ->
            drawMinimapRecursive(g2d, child, bounds)
        }
    }

    /**
     * Create connection curve between parent and child nodes
     * Uses same algorithm as main mindmap for consistency
     */
    private fun createConnectionCurve(
        parentRightX: Double,
        parentCenterY: Double,
        childLeftX: Double,
        childCenterY: Double
    ): CubicCurve2D.Double {
        val midX = (parentRightX + childLeftX) / 2
        val horizontalDistance = childLeftX - parentRightX
        val verticalDistance = childCenterY - parentCenterY

        // Same curve control points as main mindmap
        val curveControlOffset = 0.2
        val curveControlYOffset = 0.3

        return CubicCurve2D.Double(
            parentRightX, parentCenterY,
            midX + horizontalDistance * curveControlOffset, parentCenterY + verticalDistance * curveControlYOffset,
            midX - horizontalDistance * curveControlOffset, childCenterY - verticalDistance * curveControlYOffset,
            childLeftX, childCenterY
        )
    }

}

