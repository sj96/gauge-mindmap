package io.shi.gauge.mindmap.ui.mindmap.util

import com.intellij.util.ui.ImageUtil
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
    private val measurementImage = ImageUtil.createImage(1, 1, BufferedImage.TYPE_INT_ARGB)
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
        // For visual symmetry, add extra padding on right when indicator is present
        // This compensates for indicator taking up visual space
        val rightPaddingAdjustment = if (hasChildren) {
            nodeStyle.padding * 0.3 // Add 30% of padding as extra space for visual balance
        } else {
            0.0
        }
        val leftPadding = nodeStyle.padding
        val rightPadding = nodeStyle.padding + indicatorSpace + rightPaddingAdjustment
        val maxTextAreaWidth = nodeStyle.maxWidth - leftPadding - rightPadding

        // Check if text needs wrapping at max width
        val actualTextWidth = fontMetrics.stringWidth(text).toDouble()
        val needsWrapping = actualTextWidth > maxTextAreaWidth

        // Calculate dimensions based on whether wrapping is needed
        val (textWidth, _, initialLines) = if (needsWrapping) {
            // Text needs wrapping - wrap it first
            val wrappedLines = wrapText(text, fontMetrics, maxTextAreaWidth)
            val w = wrappedLines.maxOfOrNull { fontMetrics.stringWidth(it) }?.toDouble() ?: 0.0
            val h = wrappedLines.size * fontMetrics.height.toDouble()
            Triple(w, h, wrappedLines)
        } else {
            // Text fits in one line
            val w = actualTextWidth
            val h = fontMetrics.height.toDouble()
            Triple(w, h, listOf(text))
        }

        // Calculate node width
        // Width includes: left padding + text width + right padding (with adjustment)
        // Reuse leftPadding, rightPadding, and rightPaddingAdjustment from above
        val calculatedWidth = textWidth + leftPadding + rightPadding
        val width = calculatedWidth.coerceIn(nodeStyle.minWidth, nodeStyle.maxWidth)

        // Calculate actual available text area width
        // Use same padding calculation as above for consistency
        val actualTextAreaWidth = width - leftPadding - rightPadding

        // Re-wrap if width was constrained or if text doesn't fit
        // This ensures text always fits within the available space
        val finalLines = if (width < calculatedWidth || textWidth > actualTextAreaWidth) {
            // Width was constrained or text doesn't fit - re-wrap with actual available width
            wrapText(text, fontMetrics, actualTextAreaWidth)
        } else {
            initialLines
        }

        // Recalculate text width and height with final lines
        finalLines.maxOfOrNull { fontMetrics.stringWidth(it) }?.toDouble() ?: 0.0
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

