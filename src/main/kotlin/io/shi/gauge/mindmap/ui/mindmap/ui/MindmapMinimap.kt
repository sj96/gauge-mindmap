package io.shi.gauge.mindmap.ui.mindmap.ui

import io.shi.gauge.mindmap.ui.mindmap.constants.MindmapConstants
import io.shi.gauge.mindmap.ui.mindmap.model.NodeBounds
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
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
        return x in 0..<width && y >= 0 && y < height
    }

    override fun paintComponent(g: Graphics) {
        // Minimap is drawn in parent's paintComponent
    }
}

