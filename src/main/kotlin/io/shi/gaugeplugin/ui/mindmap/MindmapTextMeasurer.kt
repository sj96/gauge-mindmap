package io.shi.gaugeplugin.ui.mindmap

import java.awt.Font
import java.awt.FontMetrics
import java.awt.image.BufferedImage

/**
 * Measures text size and wraps text for nodes
 */
class MindmapTextMeasurer {
    
    fun getJetBrainsFont(size: Int, isBold: Boolean): Font {
        val fontNames = listOf("JetBrains Mono", "JetBrainsMono", "JetBrains")
        val style = if (isBold) Font.BOLD else Font.PLAIN
        
        for (fontName in fontNames) {
            val font = Font(fontName, style, size)
            if (font.family == fontName || font.family.contains("JetBrains", ignoreCase = true)) {
                return font
            }
        }
        
        return Font(Font.MONOSPACED, style, size)
    }
    
    fun calculateTextSize(
        text: String,
        level: Int,
        hasChildren: Boolean
    ): TextSize {
        val (padding, minHeight, fontSize, isBold) = when {
            level == 0 -> Quadruple(
                MindmapConstants.ROOT_NODE_PADDING,
                MindmapConstants.ROOT_NODE_MIN_HEIGHT,
                MindmapConstants.ROOT_FONT_SIZE,
                true
            )
            level <= 2 -> Quadruple(
                MindmapConstants.SPEC_NODE_PADDING,
                MindmapConstants.SPEC_NODE_MIN_HEIGHT,
                MindmapConstants.SPEC_FONT_SIZE,
                true
            )
            else -> Quadruple(
                MindmapConstants.SCENARIO_NODE_PADDING,
                MindmapConstants.SCENARIO_NODE_MIN_HEIGHT,
                MindmapConstants.SCENARIO_FONT_SIZE,
                false
            )
        }
        
        val font = getJetBrainsFont(fontSize, isBold)
        val tempImage = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val tempGraphics = tempImage.createGraphics()
        tempGraphics.font = font
        val fm = tempGraphics.fontMetrics
        tempGraphics.dispose()
        
        // Reserve space for collapse/expand indicator
        val indicatorSpace = if (hasChildren) MindmapConstants.INDICATOR_SPACE else 0.0
        
        // Determine max width for text wrapping
        val maxTextWidth = when {
            level == 0 -> MindmapConstants.ROOT_NODE_MAX_WIDTH - padding * 2
            level <= 2 -> MindmapConstants.SPEC_NODE_MAX_WIDTH - padding * 2 - indicatorSpace
            else -> MindmapConstants.SCENARIO_NODE_MAX_WIDTH - padding * 2
        }
        
        // Wrap text if needed
        val actualTextWidth = fm.stringWidth(text).toDouble()
        val lines = if (actualTextWidth > maxTextWidth) {
            wrapText(text, fm, maxTextWidth)
        } else {
            listOf(text)
        }
        
        // Calculate dimensions
        val textWidth = lines.maxOfOrNull { fm.stringWidth(it) }?.toDouble() ?: 0.0
        val textHeight = lines.size * fm.height.toDouble()
        
        // Calculate node width
        val calculatedWidth = textWidth + padding * 2 + indicatorSpace
        
        val width = when {
            level == 0 -> calculatedWidth.coerceIn(
                MindmapConstants.ROOT_NODE_MIN_WIDTH,
                MindmapConstants.ROOT_NODE_MAX_WIDTH
            )
            level <= 2 -> calculatedWidth.coerceIn(
                MindmapConstants.SPEC_NODE_MIN_WIDTH,
                MindmapConstants.SPEC_NODE_MAX_WIDTH
            )
            else -> calculatedWidth.coerceIn(
                MindmapConstants.SCENARIO_NODE_MIN_WIDTH,
                MindmapConstants.SCENARIO_NODE_MAX_WIDTH
            )
        }
        
        // Calculate node height
        val calculatedHeight = textHeight + padding * 2
        val height = maxOf(minHeight, calculatedHeight)
        
        return TextSize(width, height, lines)
    }
    
    private fun wrapText(text: String, fm: FontMetrics, maxWidth: Double): List<String> {
        if (fm.stringWidth(text) <= maxWidth) {
            return listOf(text)
        }
        
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = ""
        
        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (fm.stringWidth(testLine) <= maxWidth) {
                currentLine = testLine
            } else {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine)
                }
                currentLine = word
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine)
        }
        
        return lines
    }
    
    data class TextSize(val width: Double, val height: Double, val lines: List<String>)
    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}

