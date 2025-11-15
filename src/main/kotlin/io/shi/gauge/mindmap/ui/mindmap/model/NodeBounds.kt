package io.shi.gauge.mindmap.ui.mindmap.model

import com.intellij.ui.JBColor
import io.shi.gauge.mindmap.ui.mindmap.constants.MindmapColors
import io.shi.gauge.mindmap.ui.mindmap.constants.MindmapConstants
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.CubicCurve2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D

/**
 * Represents the bounds and layout information of a node
 * Game-style API: node.draw(), node.hover(), node.link()
 */
class NodeBounds(
    val node: MindmapNode,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val childBounds: List<NodeBounds>,
    val colorIndex: Int,
    val isRoot: Boolean = false,
    var parent: NodeBounds? = null
) {
    val centerX: Double get() = x + width / 2
    val centerY: Double get() = y + height / 2
    val rightX: Double get() = x + width
    val bottomY: Double get() = y + height

    // Hover and selection state
    private var isHovered: Boolean = false
    private var isSelected: Boolean = false

    // Connection drawing constants
    private companion object {
        const val CURVE_CONTROL_OFFSET = 0.2
        const val CURVE_CONTROL_Y_OFFSET = 0.3
        const val HIGHLIGHT_BRIGHTNESS = 100
        const val CHILD_HIGHLIGHT_BRIGHTNESS = 80
        const val GLOW_ALPHA = 50

        // Stroke widths
        const val HIGHLIGHT_STROKE_WIDTH_MAJOR = 2.5f
        const val HIGHLIGHT_STROKE_WIDTH_MINOR = 2.0f
        const val NORMAL_STROKE_WIDTH_MAJOR = 1.5f
        const val NORMAL_STROKE_WIDTH_MINOR = 1.2f
        const val BORDER_STROKE_WIDTH_SELECTED = 3.0f
        const val BORDER_STROKE_WIDTH_HOVERED = 2.5f
        const val BORDER_STROKE_WIDTH_CHILD = 2.0f
        const val GLOW_STROKE_WIDTH_SELECTED = 5.0f
        const val GLOW_STROKE_WIDTH_HOVERED = 4.0f
        const val GLOW_STROKE_WIDTH_CHILD = 3.0f

        // Dash patterns
        val dashPatternMajor = floatArrayOf(8.0f, 4.0f)
        val dashPatternMinor = floatArrayOf(6.0f, 3.0f)
    }

    fun contains(worldX: Double, worldY: Double): Boolean {
        return worldX >= x && worldX <= rightX && worldY >= y && worldY <= bottomY
    }

    /**
     * Link this node to a parent node
     */
    fun link(parentNode: NodeBounds) {
        parent = parentNode
    }

    /**
     * Set hover state
     */
    fun hover() {
        isHovered = true
    }

    /**
     * Clear hover state
     */
    fun unhover() {
        isHovered = false
    }

    /**
     * Set selection state
     */
    fun select() {
        isSelected = true
    }

    /**
     * Clear selection state
     */
    fun deselect() {
        isSelected = false
    }

    /**
     * Draw this node and its children recursively
     * Game-style rendering: node.draw(g2d, context)
     */
    fun draw(g2d: Graphics2D, context: RenderContext) {
        val isCollapsed = context.collapsedNodeIds.contains(node.id)

        // Draw connection to parent first (behind node)
        // This must be done before viewport culling, as the connection line
        // may be visible even if the node itself is outside the viewport
        parent?.let {
            drawConnection(g2d, it, context)
        }

        // Viewport culling - fast check before creating Rectangle2D
        val viewport = context.viewportBounds
        if (x + width < viewport.x || x > viewport.x + viewport.width ||
            y + height < viewport.y || y > viewport.y + viewport.height
        ) {
            // Still draw children that might be visible
            if (!isCollapsed) {
                childBounds.forEach { child ->
                    child.draw(g2d, context)
                }
            }
            return
        }

        // Draw children first (behind nodes)
        if (!isCollapsed) {
            childBounds.forEach { child ->
                child.draw(g2d, context)
            }
        }

        // Draw this node (on top)
        drawNode(g2d, context, isCollapsed)
    }

    /**
     * Draw connection line to parent node
     */
    private fun drawConnection(g2d: Graphics2D, parent: NodeBounds, context: RenderContext) {
        val parentRightX = parent.rightX
        val parentCenterY = parent.centerY
        val childLeftX = x
        val childCenterY = centerY

        // Calculate curve
        val curve = createConnectionCurve(parentRightX, parentCenterY, childLeftX, childCenterY)

        // Check if line should be drawn - fast viewport check
        val viewport = context.viewportBounds
        val parentInViewport = !(parent.x + parent.width < viewport.x || parent.x > viewport.x + viewport.width ||
                parent.y + parent.height < viewport.y || parent.y > viewport.y + viewport.height)
        val childInViewport = !(x + width < viewport.x || x > viewport.x + viewport.width ||
                y + height < viewport.y || y > viewport.y + viewport.height)

        // Check if curve intersects viewport (approximate check using bounding box)
        val curveBounds = curve.bounds2D
        val curveInViewport = !(curveBounds.maxX < viewport.x || curveBounds.minX > viewport.x + viewport.width ||
                curveBounds.maxY < viewport.y || curveBounds.minY > viewport.y + viewport.height)

        if (!parentInViewport && !childInViewport && !curveInViewport) {
            return
        }

        // Get branch color
        val branchColor = MindmapColors.getBranchColor(colorIndex)

        // Check if line should be highlighted
        val isLineHighlighted = isConnectionHighlighted(context, parent)

        // Draw line with appropriate style
        if (isLineHighlighted) {
            val highlightColor = brightenColor(branchColor, HIGHLIGHT_BRIGHTNESS)
            applyConnectionStroke(g2d, node.level, highlightColor, isHighlighted = true)
        } else {
            g2d.color = branchColor
            applyConnectionStroke(g2d, node.level, branchColor, isHighlighted = false)
        }

        g2d.draw(curve)
    }

    /**
     * Draw the node itself
     */
    private fun drawNode(g2d: Graphics2D, context: RenderContext, isCollapsed: Boolean) {
        val nodeRect = RoundRectangle2D.Double(
            x,
            y,
            width,
            height,
            if (isRoot) MindmapConstants.CORNER_RADIUS * 1.5 else MindmapConstants.CORNER_RADIUS,
            if (isRoot) MindmapConstants.CORNER_RADIUS * 1.5 else MindmapConstants.CORNER_RADIUS
        )

        // Get node colors
        val (bgColor, _, textColor) = MindmapColors.getNodeColors(node.level, colorIndex)

        // Apply hover/selection highlight from context
        val isHovered = context.hoverState.hoveredNodeId == node.id
        val isSelected = context.selectionState.selectedNodeId == node.id
        val isChildOfHovered = context.hoverState.hoveredChildrenIds.contains(node.id)

        val adjustedBgColor = when {
            isSelected -> MindmapColors.brightenColor(bgColor, 0.3f)
            isHovered -> MindmapColors.brightenColor(bgColor, 0.2f)
            isChildOfHovered -> MindmapColors.brightenColor(bgColor, 0.15f)
            else -> bgColor
        }

        // Draw node with shadow
        drawNodeWithShadow(g2d, nodeRect, adjustedBgColor)

        // Draw selection/hover border
        if (isSelected || isHovered || isChildOfHovered) {
            drawHighlightBorder(g2d, nodeRect, isSelected, isHovered)
        }

        // Draw node content
        drawNodeContent(g2d, nodeRect, textColor, context)

        // Draw collapse/expand indicator
        if (node.children.isNotEmpty()) {
            drawCollapseIndicator(g2d, isCollapsed)
        }
    }

    private fun drawNodeWithShadow(g2d: Graphics2D, rect: RoundRectangle2D.Double, fillColor: Color) {
        // Draw shadow
        val shadowRect = RoundRectangle2D.Double(
            rect.x + MindmapColors.SHADOW_OFFSET,
            rect.y + MindmapColors.SHADOW_OFFSET,
            rect.width,
            rect.height,
            rect.arcWidth,
            rect.arcHeight
        )
        g2d.color = MindmapColors.shadowColor
        g2d.fill(shadowRect)

        // Draw node
        g2d.color = fillColor
        g2d.fill(rect)
    }

    private fun drawNodeContent(
        g2d: Graphics2D,
        rect: RoundRectangle2D.Double,
        textColor: Color,
        context: RenderContext
    ) {
        val padding = when {
            isRoot -> MindmapConstants.ROOT_NODE_PADDING
            node.level <= 2 -> MindmapConstants.SPEC_NODE_PADDING
            else -> MindmapConstants.SCENARIO_NODE_PADDING
        }

        g2d.color = textColor
        val fontSize = when {
            isRoot -> MindmapConstants.ROOT_FONT_SIZE
            node.level <= 2 -> MindmapConstants.SPEC_FONT_SIZE
            else -> MindmapConstants.SCENARIO_FONT_SIZE
        }
        val isBold = isRoot || node.level <= 2
        g2d.font = context.textMeasurer.getJetBrainsFont(fontSize, isBold)

        // Get text lines from text measurer
        val textSize = context.textMeasurer.calculateTextSize(node.text, node.level, node.children.isNotEmpty())
        val textRect = Rectangle2D.Double(rect.x, rect.y, rect.width, rect.height)
        val hasChildren = node.children.isNotEmpty()

        drawWrappedText(g2d, textSize.lines, textRect, padding, hasChildren, context)

        // Draw tags
        if (node.tags.isNotEmpty() && node.tags.size > 1) {
            drawTags(g2d, node.tags, rect, padding, context)
        }
    }

    private fun drawWrappedText(
        g2d: Graphics2D,
        lines: List<String>,
        rect: Rectangle2D.Double,
        padding: Double,
        hasIndicator: Boolean,
        context: RenderContext? = null
    ) {
        val fontMetrics = g2d.fontMetrics
        val indicatorSpace = if (hasIndicator) MindmapConstants.INDICATOR_SPACE else 0.0

        // Calculate text area with symmetric padding
        // To achieve visual balance, we need to account for indicator taking up space
        // Add extra padding on the right to compensate for indicator visual weight
        val rightPaddingAdjustment = if (hasIndicator) {
            // Add extra padding to right side to match left padding visually
            // This compensates for the indicator taking up visual space
            padding * 0.3 // Add 30% of padding as extra space for visual balance
        } else {
            0.0
        }

        val leftPadding = padding
        val rightPadding = padding + indicatorSpace + rightPaddingAdjustment

        val textAreaX = rect.x + leftPadding
        val textAreaY = rect.y + padding
        // Reduce textAreaWidth slightly to create more right padding for visual balance
        val textAreaWidth = rect.width - leftPadding - rightPadding
        val textAreaHeight = rect.height - padding * 2

        // Set clipping rectangle to prevent text overflow
        // Use generous margin to ensure text is never cut off
        // Since text is already wrapped correctly, we just need enough margin for rendering artifacts
        val baseClipMargin = 8.0 // Generous margin to prevent any text cutoff
        val scale = context?.viewportState?.scale ?: 1.0
        val clipMargin = if (scale < 1.0) {
            // When zoomed out, increase margin significantly to account for scaling artifacts
            (baseClipMargin / scale).coerceAtLeast(10.0)
        } else {
            if (scale > 1.0) {
                // When zoomed in, use base margin
                baseClipMargin
            } else {
                // Normal scale, use base margin
                baseClipMargin
            }.coerceAtMost(textAreaWidth * 0.25) // Cap at 25% of width to ensure text is never cut
        }

        val clipShape = Rectangle2D.Double(
            textAreaX,
            textAreaY,
            textAreaWidth + clipMargin,
            textAreaHeight
        )
        val oldClip = g2d.clip
        g2d.clip = clipShape

        val totalTextHeight = lines.size * fontMetrics.height.toDouble()
        val startY = textAreaY + (textAreaHeight - totalTextHeight) / 2 + fontMetrics.ascent

        for ((index, line) in lines.withIndex()) {
            // Text should already be wrapped correctly, so just draw it
            // No need for truncation since wrapping ensures it fits
            val lineWidth = fontMetrics.stringWidth(line)
            val textX = if (lines.size == 1 && lineWidth < textAreaWidth * 0.8) {
                (textAreaX + (textAreaWidth - lineWidth) / 2).toInt()
            } else {
                textAreaX.toInt()
            }
            val textY = (startY + index * fontMetrics.height).toInt()
            g2d.drawString(line, textX, textY)
        }

        // Restore original clip
        g2d.clip = oldClip
    }

    private fun drawTags(
        g2d: Graphics2D,
        tags: List<String>,
        rect: RoundRectangle2D.Double,
        padding: Double,
        context: RenderContext
    ) {
        val tagFont = context.textMeasurer.getJetBrainsFont(MindmapConstants.TAG_FONT_SIZE, false)
        g2d.font = tagFont
        val fontMetrics = g2d.fontMetrics
        var tagX = rect.x + rect.width - padding
        val tagY = rect.y + rect.height - padding / 2

        tags.take(3).reversed().forEach { tag ->
            val tagText = "#$tag"
            val tagWidth = fontMetrics.stringWidth(tagText)
            tagX -= tagWidth + 4

            val tagBg = Rectangle2D.Double(
                tagX - 2,
                tagY - fontMetrics.height,
                tagWidth.toDouble() + 4,
                fontMetrics.height.toDouble()
            )
            g2d.color = JBColor(Color(0, 0, 0, 100), Color(0, 0, 0, 100))
            g2d.fill(tagBg)

            g2d.color = JBColor(Color(180, 180, 200), Color(180, 180, 200))
            g2d.drawString(tagText, tagX.toInt(), tagY.toInt())
        }
    }

    private fun drawCollapseIndicator(g2d: Graphics2D, isCollapsed: Boolean) {
        val indicatorSize = MindmapConstants.INDICATOR_SIZE
        val indicatorX = x + width - indicatorSize - MindmapConstants.INDICATOR_MARGIN
        val indicatorY = y + (height - indicatorSize) / 2

        g2d.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

        // Draw circle background
        val circle = java.awt.geom.Ellipse2D.Double(indicatorX, indicatorY, indicatorSize, indicatorSize)
        g2d.color = MindmapColors.indicatorBgColor
        g2d.fill(circle)
        g2d.color = MindmapColors.indicatorBorderColor
        g2d.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2d.draw(circle)

        // Draw + or - sign
        val centerX = indicatorX + indicatorSize / 2
        val centerY = indicatorY + indicatorSize / 2
        val lineLength = indicatorSize / 3.5

        g2d.color = MindmapColors.indicatorIconColor
        g2d.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

        // Horizontal line (always present)
        g2d.drawLine(
            (centerX - lineLength).toInt(),
            centerY.toInt(),
            (centerX + lineLength).toInt(),
            centerY.toInt()
        )

        // Vertical line (only if collapsed - reversed logic)
        if (isCollapsed) {
            g2d.drawLine(
                centerX.toInt(),
                (centerY - lineLength).toInt(),
                centerX.toInt(),
                (centerY + lineLength).toInt()
            )
        }
    }

    // Helper methods for connection drawing
    private fun createConnectionCurve(
        parentRightX: Double,
        parentCenterY: Double,
        childLeftX: Double,
        childCenterY: Double
    ): CubicCurve2D.Double {
        val midX = (parentRightX + childLeftX) / 2
        val horizontalDistance = childLeftX - parentRightX
        val verticalDistance = childCenterY - parentCenterY

        return CubicCurve2D.Double(
            parentRightX, parentCenterY,
            midX + horizontalDistance * CURVE_CONTROL_OFFSET, parentCenterY + verticalDistance * CURVE_CONTROL_Y_OFFSET,
            midX - horizontalDistance * CURVE_CONTROL_OFFSET, childCenterY - verticalDistance * CURVE_CONTROL_Y_OFFSET,
            childLeftX, childCenterY
        )
    }

    private fun isConnectionHighlighted(context: RenderContext, parent: NodeBounds): Boolean {
        val hoverState = context.hoverState
        if (hoverState.hoveredNodeId == null) return false

        val isParentHovered = hoverState.hoveredNodeId == parent.node.id
        val isChildHovered = hoverState.hoveredNodeId == node.id
        val isParentInSubtree = hoverState.hoveredChildrenIds.contains(parent.node.id)
        val isChildInSubtree = hoverState.hoveredChildrenIds.contains(node.id)

        return isParentHovered || isChildHovered || (isParentInSubtree && isChildInSubtree)
    }

    private fun brightenColor(color: JBColor, brightness: Int): JBColor {
        val red = (color.red + brightness).coerceIn(0, 255)
        val green = (color.green + brightness).coerceIn(0, 255)
        val blue = (color.blue + brightness).coerceIn(0, 255)
        return JBColor(Color(red, green, blue), Color(red, green, blue))
    }

    private fun applyConnectionStroke(
        g2d: Graphics2D,
        level: Int,
        color: JBColor,
        isHighlighted: Boolean
    ) {
        g2d.color = color
        val isMajorLevel = level <= 2
        val baseStrokeWidth = when {
            isHighlighted && isMajorLevel -> HIGHLIGHT_STROKE_WIDTH_MAJOR
            isHighlighted -> HIGHLIGHT_STROKE_WIDTH_MINOR
            isMajorLevel -> NORMAL_STROKE_WIDTH_MAJOR
            else -> NORMAL_STROKE_WIDTH_MINOR
        }

        val dashPattern = if (isMajorLevel) dashPatternMajor else dashPatternMinor

        g2d.stroke = BasicStroke(
            baseStrokeWidth,
            BasicStroke.CAP_ROUND,
            BasicStroke.JOIN_ROUND,
            10.0f,
            dashPattern,
            0.0f
        )
    }

    // Helper methods for node drawing
    private fun drawHighlightBorder(
        g2d: Graphics2D,
        nodeRect: RoundRectangle2D.Double,
        isSelected: Boolean,
        isHovered: Boolean
    ) {
        val highlightColor = when {
            isSelected -> JBColor.WHITE
            isHovered -> brightenColor(MindmapColors.getBranchColor(colorIndex), HIGHLIGHT_BRIGHTNESS)
            else -> brightenColor(MindmapColors.getBranchColor(colorIndex), CHILD_HIGHLIGHT_BRIGHTNESS)
        }

        // Draw border
        g2d.color = highlightColor
        val borderWidth = when {
            isSelected -> BORDER_STROKE_WIDTH_SELECTED
            isHovered -> BORDER_STROKE_WIDTH_HOVERED
            else -> BORDER_STROKE_WIDTH_CHILD
        }
        g2d.stroke = BasicStroke(borderWidth)
        g2d.draw(nodeRect)

        // Draw glow effect
        val glowColor = JBColor(
            Color(highlightColor.red, highlightColor.green, highlightColor.blue, GLOW_ALPHA),
            Color(highlightColor.red, highlightColor.green, highlightColor.blue, GLOW_ALPHA)
        )
        g2d.color = glowColor
        val glowWidth = when {
            isSelected -> GLOW_STROKE_WIDTH_SELECTED
            isHovered -> GLOW_STROKE_WIDTH_HOVERED
            else -> GLOW_STROKE_WIDTH_CHILD
        }
        g2d.stroke = BasicStroke(glowWidth)
        g2d.draw(nodeRect)
    }

}


