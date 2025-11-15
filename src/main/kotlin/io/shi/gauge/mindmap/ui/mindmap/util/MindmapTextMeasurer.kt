package io.shi.gauge.mindmap.ui.mindmap.util

import io.shi.gauge.mindmap.ui.mindmap.constants.MindmapConstants
import java.awt.Font
import java.awt.FontMetrics
import java.awt.image.BufferedImage

/**
 * Measures text size and wraps text for nodes
 */
class MindmapTextMeasurer {
    
    // Cache for fonts and font metrics to avoid recreation
    private val fontCache = mutableMapOf<Pair<Int, Boolean>, Font>()
    private val fontMetricsCache = mutableMapOf<Font, FontMetrics>()
    private val measurementImage = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    private val measurementGraphics = measurementImage.createGraphics()

    fun getJetBrainsFont(size: Int, isBold: Boolean): Font {
        val cacheKey = Pair(size, isBold)
        return fontCache.getOrPut(cacheKey) {
            val fontNames = listOf("JetBrains Mono", "JetBrainsMono", "JetBrains")
            val style = if (isBold) Font.BOLD else Font.PLAIN

            for (fontName in fontNames) {
                val font = Font(fontName, style, size)
                if (font.family == fontName || font.family.contains("JetBrains", ignoreCase = true)) {
                    return font
                }
            }

            Font(Font.MONOSPACED, style, size)
        }
    }
    
    private fun getFontMetrics(font: Font): FontMetrics {
        return fontMetricsCache.getOrPut(font) {
            measurementGraphics.font = font
            measurementGraphics.fontMetrics
        }
    }

    fun calculateTextSize(
        text: String,
        level: Int,
        hasChildren: Boolean
    ): TextSize {
        val nodeStyle = getNodeStyle(level)

        val font = getJetBrainsFont(nodeStyle.fontSize, nodeStyle.isBold)
        val fontMetrics = getFontMetrics(font)

        // Reserve space for collapse/expand indicator
        val indicatorSpace = if (hasChildren) MindmapConstants.INDICATOR_SPACE else 0.0

        // Determine max width for text wrapping
        val maxTextWidth = nodeStyle.maxWidth - nodeStyle.padding * 2 - indicatorSpace

        // Wrap text if needed
        val actualTextWidth = fontMetrics.stringWidth(text).toDouble()
        val lines = if (actualTextWidth > maxTextWidth) {
            wrapText(text, fontMetrics, maxTextWidth)
        } else {
            listOf(text)
        }

        // Calculate dimensions
        val textWidth = lines.maxOfOrNull { fontMetrics.stringWidth(it) }?.toDouble() ?: 0.0
        val textHeight = lines.size * fontMetrics.height.toDouble()

        // Calculate node width
        val calculatedWidth = textWidth + nodeStyle.padding * 2 + indicatorSpace
        val width = calculatedWidth.coerceIn(nodeStyle.minWidth, nodeStyle.maxWidth)
        
        // Calculate actual available text area width
        val actualTextAreaWidth = width - nodeStyle.padding * 2 - indicatorSpace
        
        // Re-wrap if text doesn't fit in actual available width
        // Use very small tolerance (0.5px) only for rounding error comparison
        // This ensures we only re-wrap when truly necessary
        val comparisonTolerance = 0.5
        val finalLines = if (textWidth > actualTextAreaWidth + comparisonTolerance) {
            // Re-wrap with the actual available width (use exact width, no tolerance)
            wrapText(text, fontMetrics, actualTextAreaWidth)
        } else {
            lines
        }

        // Recalculate text width and height with final lines
        val finalTextWidth = finalLines.maxOfOrNull { fontMetrics.stringWidth(it) }?.toDouble() ?: 0.0
        val finalTextHeight = finalLines.size * fontMetrics.height.toDouble()
        
        // Calculate node height
        val calculatedHeight = finalTextHeight + nodeStyle.padding * 2
        val height = maxOf(nodeStyle.minHeight, calculatedHeight)

        return TextSize(width, height, finalLines)
    }

    private fun wrapText(text: String, fm: FontMetrics, maxWidth: Double): List<String> {
        // Use exact maxWidth without tolerance to maximize space usage
        // Only wrap when text truly exceeds the width
        if (fm.stringWidth(text) <= maxWidth) {
            return listOf(text)
        }

        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = ""

        for (word in words) {
            val wordWidth = fm.stringWidth(word).toDouble()
            
            // If a single word is longer than maxWidth, break it by characters
            if (wordWidth > maxWidth) {
                // First, add current line if it has content
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine)
                    currentLine = ""
                }
                // Break the long word into characters
                var wordPart = ""
                for (char in word) {
                    val testPart = wordPart + char
                    if (fm.stringWidth(testPart) <= maxWidth) {
                        wordPart = testPart
                    } else {
                        if (wordPart.isNotEmpty()) {
                            lines.add(wordPart)
                        }
                        wordPart = char.toString()
                    }
                }
                if (wordPart.isNotEmpty()) {
                    currentLine = wordPart
                }
            } else {
                // Normal word wrapping - use exact maxWidth
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
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine)
        }

        return lines
    }

    private data class NodeStyle(
        val padding: Double,
        val minHeight: Double,
        val minWidth: Double,
        val maxWidth: Double,
        val fontSize: Int,
        val isBold: Boolean
    )

    private fun getNodeStyle(level: Int): NodeStyle {
        return when {
            level == 0 -> NodeStyle(
                padding = MindmapConstants.ROOT_NODE_PADDING,
                minHeight = MindmapConstants.ROOT_NODE_MIN_HEIGHT,
                minWidth = MindmapConstants.ROOT_NODE_MIN_WIDTH,
                maxWidth = MindmapConstants.ROOT_NODE_MAX_WIDTH,
                fontSize = MindmapConstants.ROOT_FONT_SIZE,
                isBold = true
            )
            level <= 2 -> NodeStyle(
                padding = MindmapConstants.SPEC_NODE_PADDING,
                minHeight = MindmapConstants.SPEC_NODE_MIN_HEIGHT,
                minWidth = MindmapConstants.SPEC_NODE_MIN_WIDTH,
                maxWidth = MindmapConstants.SPEC_NODE_MAX_WIDTH,
                fontSize = MindmapConstants.SPEC_FONT_SIZE,
                isBold = true
            )
            else -> NodeStyle(
                padding = MindmapConstants.SCENARIO_NODE_PADDING,
                minHeight = MindmapConstants.SCENARIO_NODE_MIN_HEIGHT,
                minWidth = MindmapConstants.SCENARIO_NODE_MIN_WIDTH,
                maxWidth = MindmapConstants.SCENARIO_NODE_MAX_WIDTH,
                fontSize = MindmapConstants.SCENARIO_FONT_SIZE,
                isBold = false
            )
        }
    }
}

