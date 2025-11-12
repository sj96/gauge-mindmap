package io.shi.gauge.mindmap.ui.mindmap

import java.awt.*
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel

/**
 * Minimap component for navigation
 */
class MindmapMinimap(
) : JPanel() {

    var rootBounds: NodeBounds? = null
        set(value) {
            field = value
            repaint()
        }

    var parentWidth: Int = 0
    var parentHeight: Int = 0

    init {
        isOpaque = false
        isEnabled = true
        preferredSize = Dimension(MindmapConstants.MINIMAP_WIDTH.toInt(), 50)
        minimumSize = Dimension(MindmapConstants.MINIMAP_WIDTH.toInt(), 50)
        maximumSize = Dimension(MindmapConstants.MINIMAP_WIDTH.toInt(), 2000)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        // Mouse events are handled by parent view
    }


    override fun contains(x: Int, y: Int): Boolean {
        return x >= 0 && x < width && y >= 0 && y < height
    }

    override fun paintComponent(g: Graphics) {
        // Minimap is drawn in parent's paintComponent
    }
}

/**
 * Helper class for minimap rendering with performance optimizations
 */
class MindmapMinimapRenderer {

    // Cache for bounds calculation
    private var cachedRootBounds: NodeBounds? = null
    private var cachedBounds: Bounds? = null

    // Cache for minimap size calculation
    private var cachedViewportHeight: Int = -1
    private var cachedMinimapSize: MinimapSize? = null

    fun calculateBounds(rootBounds: NodeBounds?): Bounds? {
        if (rootBounds == null) {
            cachedRootBounds = null
            cachedBounds = null
            return null
        }

        // Return cached bounds if rootBounds hasn't changed
        if (cachedRootBounds === rootBounds && cachedBounds != null) {
            return cachedBounds
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

        val contentWidth = maxX - minX
        val contentHeight = maxY - minY
        if (contentWidth <= 0 || contentHeight <= 0) {
            cachedRootBounds = rootBounds
            cachedBounds = null
            return null
        }

        val bounds = Bounds(minX, minY, maxX, maxY, contentWidth, contentHeight)
        cachedRootBounds = rootBounds
        cachedBounds = bounds
        return bounds
    }

    fun getMinimapSize(bounds: Bounds?, viewportHeight: Int): MinimapSize? {
        if (bounds == null) {
            cachedMinimapSize = null
            return null
        }

        // Return cached size if viewport height hasn't changed
        if (cachedViewportHeight == viewportHeight && cachedMinimapSize != null) {
            return cachedMinimapSize
        }

        val minimapWidth = MindmapConstants.MINIMAP_WIDTH
        val margin = MindmapConstants.MINIMAP_MARGIN
        val padding = MindmapConstants.MINIMAP_PADDING

        // Minimap always has fixed width
        val availableHeight = (viewportHeight - margin * 2).toDouble()
        val availableWidth = minimapWidth - padding * 2

        // Calculate aspect ratio
        val contentAspectRatio = bounds.contentWidth / bounds.contentHeight

        // Calculate height based on fixed width
        val contentHeightFromWidth = availableWidth / contentAspectRatio
        val totalHeightFromWidth = contentHeightFromWidth + padding * 2

        // Use fixed width, height is calculated from aspect ratio
        // But limit height to available space
        val finalHeight = minOf(totalHeightFromWidth, availableHeight)

        val size = MinimapSize(minimapWidth, finalHeight, margin)
        cachedViewportHeight = viewportHeight
        cachedMinimapSize = size
        return size
    }

    fun invalidateCache() {
        cachedRootBounds = null
        cachedBounds = null
        cachedViewportHeight = -1
        cachedMinimapSize = null
    }

    fun renderMinimap(
        g2d: Graphics2D,
        rootBounds: NodeBounds?,
        viewport: MindmapViewport,
        viewportWidth: Int,
        viewportHeight: Int
    ) {
        val bounds = calculateBounds(rootBounds) ?: return
        val minimapSize = getMinimapSize(bounds, viewportHeight) ?: return

        val savedTransform = g2d.transform
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

        // Disable antialiasing for better performance on small minimap
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)

        // Draw simplified mindmap
        drawMinimapRecursive(g2d, rootBounds!!, null)

        // Reset transform for viewport rectangle
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

        g2d.transform = savedTransform
    }

    private fun drawViewportRectangle(
        g2d: Graphics2D,
        viewport: MindmapViewport,
        bounds: Bounds,
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
        g2d.color = MindmapColors.viewportBorderColor
        g2d.stroke = BasicStroke(2.0f)
        g2d.draw(viewportRect)

        // Inner highlight for depth (can extend outside minimap)
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
        // Draw connection line if has parent
        parentBounds?.let { parent ->
            val branchColor = MindmapColors.getBranchColor(bounds.colorIndex)
            g2d.color = branchColor
            g2d.stroke = BasicStroke(0.5f)
            g2d.drawLine(
                parent.rightX.toInt(),
                parent.centerY.toInt(),
                bounds.x.toInt(),
                bounds.centerY.toInt()
            )
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

    data class Bounds(
        val minX: Double,
        val minY: Double,
        val maxX: Double,
        val maxY: Double,
        val contentWidth: Double,
        val contentHeight: Double
    )

    data class MinimapSize(
        val width: Double,
        val height: Double,
        val margin: Int
    )
}

