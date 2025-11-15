package io.shi.gauge.mindmap.ui.mindmap.model

import io.shi.gauge.mindmap.ui.mindmap.layout.MindmapViewport
import io.shi.gauge.mindmap.ui.mindmap.util.MindmapTextMeasurer
import java.awt.geom.Rectangle2D

/**
 * Context for rendering nodes - contains all necessary information for drawing
 */
data class RenderContext(
    val textMeasurer: MindmapTextMeasurer,
    val viewportBounds: Rectangle2D.Double,
    val viewportState: MindmapViewport,
    val hoverState: HoverState,
    val selectionState: SelectionState,
    val collapsedNodeIds: Set<String>
)

