package io.shi.gaugeplugin.ui.mindmap

/**
 * Constants for mindmap layout and rendering
 */
object MindmapConstants {
    // Root node dimensions
    const val ROOT_NODE_MIN_HEIGHT = 60.0
    const val ROOT_NODE_PADDING = 18.0
    const val ROOT_NODE_MIN_WIDTH = 120.0
    const val ROOT_NODE_MAX_WIDTH = 450.0
    
    // Spec node dimensions
    const val SPEC_NODE_MIN_HEIGHT = 50.0
    const val SPEC_NODE_PADDING = 12.0
    const val SPEC_NODE_MIN_WIDTH = 100.0
    const val SPEC_NODE_MAX_WIDTH = 400.0
    
    // Scenario node dimensions
    const val SCENARIO_NODE_MIN_HEIGHT = 40.0
    const val SCENARIO_NODE_PADDING = 10.0
    const val SCENARIO_NODE_MIN_WIDTH = 80.0
    const val SCENARIO_NODE_MAX_WIDTH = 350.0
    
    // Spacing
    const val HORIZONTAL_SPACING = 180.0
    const val VERTICAL_SPACING = 40.0
    const val CORNER_RADIUS = 12.0
    
    // Layout
    const val ROOT_X = 150.0
    const val BASE_START_Y = 200.0
    
    // Font sizes
    const val ROOT_FONT_SIZE = 18
    const val SPEC_FONT_SIZE = 15
    const val SCENARIO_FONT_SIZE = 12
    const val TAG_FONT_SIZE = 9
    
    // Performance
    const val REPAINT_THROTTLE_MS = 16L // ~60 FPS max
    
    // Interaction
    const val SINGLE_CLICK_DELAY_MS = 300
    const val FILE_RELOAD_DELAY_MS = 500L
    const val TYPING_RELOAD_DELAY_MS = 800L
    
    // Minimap
    const val MINIMAP_WIDTH = 83.0
    const val MINIMAP_MARGIN = 8
    const val MINIMAP_PADDING = 3.0
    
    // Export
    const val EXPORT_SCALE_FACTOR = 2.0
    const val EXPORT_PADDING = 50.0
    
    // Viewport
    const val MIN_SCALE = 0.1
    const val MAX_SCALE = 5.0
    const val DEFAULT_SCALE = 1.0
    const val ZOOM_FACTOR = 1.2
    const val FIT_MARGIN = 50.0
    const val PAN_MARGIN = 100.0 // Margin for panning outside content bounds
    
    // Collapse indicator
    const val INDICATOR_SIZE = 14.0
    const val INDICATOR_MARGIN = 6.0
    const val INDICATOR_SPACE = 20.0
}

