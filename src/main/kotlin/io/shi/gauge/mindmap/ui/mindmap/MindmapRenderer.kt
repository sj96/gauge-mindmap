package io.shi.gauge.mindmap.ui.mindmap

import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.CubicCurve2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D

/**
 * Renders mindmap nodes and connections
 */
class MindmapRenderer(
    private val textMeasurer: MindmapTextMeasurer,
    private val layout: MindmapLayout
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

        // Set rendering hints
        if (viewportState.scale >= 0.5) {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        } else {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        }

        // Draw nodes recursively
        drawNodeRecursive(
            g2d,
            rootBounds,
            null,
            viewportBounds,
            hoverState,
            selectionState,
            collapsedNodeIds
        )
    }

    private fun renderEmptyMessage(g2d: Graphics2D, viewport: MindmapViewport) {
        g2d.color = JBColor(Gray._200, Gray._200)
        g2d.font = textMeasurer.getJetBrainsFont(16, false)
        val message = "No Gauge specifications found"
        val fm = g2d.fontMetrics
        val (worldX, worldY) = viewport.screenToWorld(0, 0)
        val x = (worldX - fm.stringWidth(message) / 2).toInt()
        val y = worldY.toInt()
        g2d.drawString(message, x, y)
    }

    private fun drawNodeRecursive(
        g2d: Graphics2D,
        bounds: NodeBounds,
        parentBounds: NodeBounds?,
        viewport: Rectangle2D.Double,
        hoverState: HoverState,
        selectionState: SelectionState,
        collapsedNodeIds: Set<String>
    ) {
        // Draw connection line
        parentBounds?.let { parent ->
            drawConnection(
                g2d,
                parent,
                bounds,
                viewport,
                hoverState
            )
        }

        val isCollapsed = collapsedNodeIds.contains(bounds.node.id)

        // Viewport culling
        val nodeBoundsRect = Rectangle2D.Double(bounds.x, bounds.y, bounds.width, bounds.height)
        if (!viewport.intersects(nodeBoundsRect)) {
            // Still draw children that might be visible
            if (!isCollapsed) {
                bounds.childBounds.forEach { child ->
                    drawNodeRecursive(
                        g2d,
                        child,
                        bounds,
                        viewport,
                        hoverState,
                        selectionState,
                        collapsedNodeIds
                    )
                }
            }
            return
        }

        // Draw children first (behind nodes)
        if (!isCollapsed) {
            bounds.childBounds.forEach { child ->
                drawNodeRecursive(
                    g2d,
                    child,
                    bounds,
                    viewport,
                    hoverState,
                    selectionState,
                    collapsedNodeIds
                )
            }
        }

        // Draw this node (on top)
        drawNode(
            g2d,
            bounds,
            hoverState,
            selectionState,
            collapsedNodeIds
        )
    }

    private fun drawConnection(
        g2d: Graphics2D,
        parent: NodeBounds,
        child: NodeBounds,
        viewport: Rectangle2D.Double,
        hoverState: HoverState
    ) {
        val parentRightX = parent.rightX
        val parentCenterY = parent.centerY
        val childLeftX = child.x
        val childCenterY = child.centerY

        // Calculate curve
        val midX = (parentRightX + childLeftX) / 2
        val dy = childCenterY - parentCenterY

        val curve = CubicCurve2D.Double(
            parentRightX, parentCenterY,
            midX + (childLeftX - parentRightX) * 0.2, parentCenterY + dy * 0.3,
            midX - (childLeftX - parentRightX) * 0.2, childCenterY - dy * 0.3,
            childLeftX, childCenterY
        )

        // Check if line should be drawn
        val parentRect = Rectangle2D.Double(parent.x, parent.y, parent.width, parent.height)
        val childRect = Rectangle2D.Double(child.x, child.y, child.width, child.height)
        val parentInViewport = viewport.intersects(parentRect)
        val childInViewport = viewport.intersects(childRect)
        val curveInViewport = viewport.intersects(curve.bounds2D)

        if (!parentInViewport && !childInViewport && !curveInViewport) {
            return
        }

        // Get branch color
        val branchColor = MindmapColors.getBranchColor(child.colorIndex)

        // Check if line should be highlighted
        val isParentHovered = hoverState.hoveredNodeId == parent.node.id
        val isChildHovered = hoverState.hoveredNodeId == child.node.id
        val isParentInSubtree = hoverState.hoveredChildrenIds.contains(parent.node.id)
        val isChildInSubtree = hoverState.hoveredChildrenIds.contains(child.node.id)
        val isLineHighlighted = hoverState.hoveredNodeId != null && (
                isParentHovered || isChildHovered || (isParentInSubtree && isChildInSubtree)
                )

        // Draw line
        if (isLineHighlighted) {
            val highlightColor = JBColor(
                Color(
                    (branchColor.red + 100).coerceIn(0, 255),
                    (branchColor.green + 100).coerceIn(0, 255),
                    (branchColor.blue + 100).coerceIn(0, 255)
                ),
                Color(
                    (branchColor.red + 100).coerceIn(0, 255),
                    (branchColor.green + 100).coerceIn(0, 255),
                    (branchColor.blue + 100).coerceIn(0, 255)
                )
            )
            g2d.color = highlightColor
            val strokeWidth = if (child.node.level <= 2) 2.5f else 2.0f
            val dashPattern = if (child.node.level <= 2) {
                floatArrayOf(8.0f, 4.0f)
            } else {
                floatArrayOf(6.0f, 3.0f)
            }
            g2d.stroke = BasicStroke(
                strokeWidth,
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
                10.0f,
                dashPattern,
                0.0f
            )
        } else {
            g2d.color = branchColor
            val strokeWidth = if (child.node.level <= 2) 1.5f else 1.2f
            val dashPattern = if (child.node.level <= 2) {
                floatArrayOf(8.0f, 4.0f)
            } else {
                floatArrayOf(6.0f, 3.0f)
            }
            g2d.stroke = BasicStroke(
                strokeWidth,
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
                10.0f,
                dashPattern,
                0.0f
            )
        }

        g2d.draw(curve)
    }

    private fun drawNode(
        g2d: Graphics2D,
        bounds: NodeBounds,
        hoverState: HoverState,
        selectionState: SelectionState,
        collapsedNodeIds: Set<String>
    ) {
        val nodeRect = RoundRectangle2D.Double(
            bounds.x,
            bounds.y,
            bounds.width,
            bounds.height,
            if (bounds.isRoot) MindmapConstants.CORNER_RADIUS * 1.5 else MindmapConstants.CORNER_RADIUS,
            if (bounds.isRoot) MindmapConstants.CORNER_RADIUS * 1.5 else MindmapConstants.CORNER_RADIUS
        )

        // Get node colors
        val (bgColor, _, textColor) = MindmapColors.getNodeColors(bounds.node.level, bounds.colorIndex)

        // Apply hover/selection highlight
        val isHovered = hoverState.hoveredNodeId == bounds.node.id
        val isSelected = selectionState.selectedNodeId == bounds.node.id
        val isChildOfHovered = hoverState.hoveredChildrenIds.contains(bounds.node.id)

        val finalBgColor = when {
            isSelected -> MindmapColors.brightenColor(bgColor, 0.3f)
            isHovered -> MindmapColors.brightenColor(bgColor, 0.2f)
            isChildOfHovered -> MindmapColors.brightenColor(bgColor, 0.15f)
            else -> bgColor
        }

        // Draw node with shadow
        drawNodeWithShadow(g2d, nodeRect, finalBgColor)

        // Draw selection/hover border
        if (isSelected || isHovered || isChildOfHovered) {
            val highlightColor = when {
                isSelected -> JBColor.WHITE
                isHovered -> {
                    val branchColor = MindmapColors.getBranchColor(bounds.colorIndex)
                    JBColor(
                        Color(
                            (branchColor.red + 100).coerceIn(0, 255),
                            (branchColor.green + 100).coerceIn(0, 255),
                            (branchColor.blue + 100).coerceIn(0, 255)
                        ),
                        Color(
                            (branchColor.red + 100).coerceIn(0, 255),
                            (branchColor.green + 100).coerceIn(0, 255),
                            (branchColor.blue + 100).coerceIn(0, 255)
                        )
                    )
                }

                else -> {
                    val branchColor = MindmapColors.getBranchColor(bounds.colorIndex)
                    JBColor(
                        Color(
                            (branchColor.red + 80).coerceIn(0, 255),
                            (branchColor.green + 80).coerceIn(0, 255),
                            (branchColor.blue + 80).coerceIn(0, 255)
                        ),
                        Color(
                            (branchColor.red + 80).coerceIn(0, 255),
                            (branchColor.green + 80).coerceIn(0, 255),
                            (branchColor.blue + 80).coerceIn(0, 255)
                        )
                    )
                }
            }
            g2d.color = highlightColor
            val strokeWidth = when {
                isSelected -> 3.0f
                isHovered -> 2.5f
                else -> 2.0f
            }
            g2d.stroke = BasicStroke(strokeWidth)
            g2d.draw(nodeRect)

            // Add glow effect
            g2d.color = JBColor(
                Color(highlightColor.red, highlightColor.green, highlightColor.blue, 50),
                Color(highlightColor.red, highlightColor.green, highlightColor.blue, 50)
            )
            g2d.stroke = BasicStroke(
                when {
                    isSelected -> 5.0f
                    isHovered -> 4.0f
                    else -> 3.0f
                }
            )
            g2d.draw(nodeRect)
        }

        // Draw node content
        drawNodeContent(g2d, bounds, nodeRect, textColor)

        // Draw collapse/expand indicator
        if (bounds.node.children.isNotEmpty()) {
            drawCollapseIndicator(g2d, bounds, collapsedNodeIds.contains(bounds.node.id))
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
        bounds: NodeBounds,
        rect: RoundRectangle2D.Double,
        textColor: Color
    ) {
        val node = bounds.node
        val padding = when {
            bounds.isRoot -> MindmapConstants.ROOT_NODE_PADDING
            node.level <= 2 -> MindmapConstants.SPEC_NODE_PADDING
            else -> MindmapConstants.SCENARIO_NODE_PADDING
        }

        g2d.color = textColor
        val fontSize = when {
            bounds.isRoot -> MindmapConstants.ROOT_FONT_SIZE
            node.level <= 2 -> MindmapConstants.SPEC_FONT_SIZE
            else -> MindmapConstants.SCENARIO_FONT_SIZE
        }
        val isBold = bounds.isRoot || node.level <= 2
        g2d.font = textMeasurer.getJetBrainsFont(fontSize, isBold)

        // Get text lines from text measurer
        val textSize = textMeasurer.calculateTextSize(node.text, node.level, node.children.isNotEmpty())
        val textRect = Rectangle2D.Double(rect.x, rect.y, rect.width, rect.height)
        val hasChildren = node.children.isNotEmpty()

        drawWrappedText(g2d, textSize.lines, textRect, padding, hasChildren)

        // Draw tags
        if (node.tags.isNotEmpty() && node.tags.size > 1) {
            drawTags(g2d, node.tags, rect, padding)
        }
    }

    private fun drawWrappedText(
        g2d: Graphics2D,
        lines: List<String>,
        rect: Rectangle2D.Double,
        padding: Double,
        hasIndicator: Boolean
    ) {
        val fm = g2d.fontMetrics
        val indicatorSpace = if (hasIndicator) MindmapConstants.INDICATOR_SPACE else 0.0
        val textAreaX = rect.x + padding
        val textAreaY = rect.y + padding
        val textAreaWidth = rect.width - padding * 2 - indicatorSpace
        val textAreaHeight = rect.height - padding * 2

        val totalTextHeight = lines.size * fm.height.toDouble()
        val startY = textAreaY + (textAreaHeight - totalTextHeight) / 2 + fm.ascent

        for ((index, line) in lines.withIndex()) {
            val lineWidth = fm.stringWidth(line)
            val x = if (lines.size == 1 && lineWidth < textAreaWidth * 0.8) {
                (textAreaX + (textAreaWidth - lineWidth) / 2).toInt()
            } else {
                textAreaX.toInt()
            }
            val y = (startY + index * fm.height).toInt()
            g2d.drawString(line, x, y)
        }
    }

    private fun drawTags(
        g2d: Graphics2D,
        tags: List<String>,
        rect: RoundRectangle2D.Double,
        padding: Double
    ) {
        val tagFont = textMeasurer.getJetBrainsFont(MindmapConstants.TAG_FONT_SIZE, false)
        g2d.font = tagFont
        val fm = g2d.fontMetrics
        var tagX = rect.x + rect.width - padding
        val tagY = rect.y + rect.height - padding / 2

        tags.take(3).reversed().forEach { tag ->
            val tagText = "#$tag"
            val tagWidth = fm.stringWidth(tagText)
            tagX -= tagWidth + 4

            val tagBg = Rectangle2D.Double(
                tagX - 2,
                tagY - fm.height,
                tagWidth.toDouble() + 4,
                fm.height.toDouble()
            )
            g2d.color = JBColor(Color(0, 0, 0, 100), Color(0, 0, 0, 100))
            g2d.fill(tagBg)

            g2d.color = JBColor(Color(180, 180, 200), Color(180, 180, 200))
            g2d.drawString(tagText, tagX.toInt(), tagY.toInt())
        }
    }

    private fun drawCollapseIndicator(
        g2d: Graphics2D,
        bounds: NodeBounds,
        isCollapsed: Boolean
    ) {
        val indicatorSize = MindmapConstants.INDICATOR_SIZE
        val indicatorX = bounds.x + bounds.width - indicatorSize - MindmapConstants.INDICATOR_MARGIN
        val indicatorY = bounds.y + (bounds.height - indicatorSize) / 2

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

    data class HoverState(
        var hoveredNodeId: String? = null,
        val hoveredChildrenIds: MutableSet<String> = mutableSetOf()
    )

    data class SelectionState(
        var selectedNodeId: String? = null
    )
}

