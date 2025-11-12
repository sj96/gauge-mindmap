package io.shi.gaugeplugin.ui.mindmap

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Manages color scheme for mindmap
 */
object MindmapColors {
    // Root node colors
    val rootBgColor = jbColor(70, 130, 180) // Steel blue
    val rootBorderColor = jbColor(100, 149, 237) // Cornflower blue
    val rootTextColor = JBColor.WHITE
    
    // Branch line colors (optimized for dark theme)
    val branchLineColors = listOf(
        jbColor(64, 224, 208),   // Turquoise/Cyan
        jbColor(100, 181, 246),  // Light Blue
        jbColor(129, 199, 132),  // Light Green
        jbColor(255, 183, 77),   // Amber/Orange
        jbColor(186, 104, 200),  // Purple
        jbColor(239, 154, 154),  // Light Pink/Red
        jbColor(255, 213, 79),   // Light Yellow
        jbColor(144, 202, 249)   // Sky Blue
    )
    
    // Text colors
    val specTextColor: JBColor = JBColor.WHITE
    val scenarioTextColor = jbColor(240, 240, 240) // Light gray
    val backgroundColor = jbColor(30, 30, 35) // Dark background
    
    // Minimap colors
    val minimapBgColor = jbColor(50, 50, 60, 200) // Semi-transparent background with better opacity
    val minimapBorderColor = jbColor(100, 100, 110) // Lighter border for better contrast
    val minimapShadowColor = jbColor(0, 0, 0, 80) // Shadow for depth with reduced opacity
    val viewportFillColor = jbColor(255, 255, 255, 30)
    val viewportBorderColor = jbColor(200, 220, 255)
    val viewportInnerColor = jbColor(255, 255, 255, 60)
    
    // Collapse indicator colors
    val indicatorBgColor = jbColor(255, 255, 255, 180)
    val indicatorBorderColor = jbColor(200, 200, 200, 220)
    val indicatorIconColor = jbColor(60, 60, 70)
    
    // Shadow
    val shadowColor = jbColor(0, 0, 0, 30)
    const val SHADOW_OFFSET = 3.0
    
    private fun jbColor(r: Int, g: Int, b: Int): JBColor = JBColor(Color(r, g, b), Color(r, g, b))
    private fun jbColor(r: Int, g: Int, b: Int, a: Int): JBColor = JBColor(Color(r, g, b, a), Color(r, g, b, a))
    
    fun getBranchColor(colorIndex: Int): JBColor {
        return branchLineColors[colorIndex % branchLineColors.size]
    }
    
    fun brightenColor(color: JBColor, factor: Float): JBColor {
        val r = (color.red + (255 - color.red) * factor).toInt().coerceIn(0, 255)
        val g = (color.green + (255 - color.green) * factor).toInt().coerceIn(0, 255)
        val b = (color.blue + (255 - color.blue) * factor).toInt().coerceIn(0, 255)
        return jbColor(r, g, b)
    }
    
    fun getNodeColors(level: Int, colorIndex: Int): Triple<JBColor, JBColor, JBColor> {
        return when {
            level == 0 -> Triple(rootBgColor, rootBorderColor, rootTextColor)
            level <= 2 -> {
                // Level 1 (Folder) and Level 2 (Spec)
                val branchColor = getBranchColor(colorIndex)
                val bgColor = jbColor(
                    (branchColor.red * 0.7 + 50).toInt().coerceIn(0, 255),
                    (branchColor.green * 0.7 + 50).toInt().coerceIn(0, 255),
                    (branchColor.blue * 0.7 + 50).toInt().coerceIn(0, 255)
                )
                val borderColor = jbColor(
                    branchColor.red.coerceIn(0, 255),
                    branchColor.green.coerceIn(0, 255),
                    branchColor.blue.coerceIn(0, 255)
                )
                Triple(bgColor, borderColor, specTextColor)
            }
            else -> {
                // Level 3+ (Scenario)
                val branchColor = getBranchColor(colorIndex)
                val bgColor = jbColor(
                    (branchColor.red * 0.5 + 30).toInt().coerceIn(0, 255),
                    (branchColor.green * 0.5 + 30).toInt().coerceIn(0, 255),
                    (branchColor.blue * 0.5 + 30).toInt().coerceIn(0, 255)
                )
                val borderColor = jbColor(
                    (branchColor.red * 0.8).toInt().coerceIn(0, 255),
                    (branchColor.green * 0.8).toInt().coerceIn(0, 255),
                    (branchColor.blue * 0.8).toInt().coerceIn(0, 255)
                )
                Triple(bgColor, borderColor, scenarioTextColor)
            }
        }
    }
}

