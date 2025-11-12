package io.shi.gauge.mindmap.ui.mindmap

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * Exports mindmap to image file
 */
class MindmapExporter(
    private val project: Project,
    private val renderer: MindmapRenderer,
    private val layout: MindmapLayout
) {

    fun exportToImage(
        rootBounds: NodeBounds?,
        targetFile: File,
        collapsedNodeIds: Set<String>
    ) {
        if (rootBounds == null) {
            showNotification("Export Failed", "No mindmap content to export", NotificationType.WARNING)
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Exporting Mindmap", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Calculating mindmap bounds..."
                    indicator.fraction = 0.1

                    val bounds = calculateContentBounds(rootBounds)

                    indicator.text = "Rendering mindmap..."
                    indicator.fraction = 0.3

                    val padding = MindmapConstants.EXPORT_PADDING
                    val scaleFactor = MindmapConstants.EXPORT_SCALE_FACTOR
                    val contentWidth = bounds.maxX - bounds.minX
                    val contentHeight = bounds.maxY - bounds.minY

                    val imageWidth = ((contentWidth + padding * 2) * scaleFactor).toInt()
                    val imageHeight = ((contentHeight + padding * 2) * scaleFactor).toInt()
                    val image = BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB)
                    val g2d = image.createGraphics()

                    setupHighQualityRendering(g2d)
                    g2d.color = MindmapColors.backgroundColor
                    g2d.fillRect(0, 0, imageWidth, imageHeight)

                    indicator.text = "Drawing nodes and connections..."
                    indicator.fraction = 0.6

                    applyTransform(g2d, bounds.minX, bounds.minY, padding, scaleFactor)

                    val viewportBounds = Rectangle2D.Double(
                        bounds.minX - padding * 2,
                        bounds.minY - padding * 2,
                        contentWidth + padding * 4,
                        contentHeight + padding * 4
                    )

                    val exportViewport = MindmapViewport(0.0, 0.0, scaleFactor)
                    val emptyHoverState = MindmapRenderer.HoverState()
                    val emptySelectionState = MindmapRenderer.SelectionState()

                    renderer.render(
                        g2d,
                        rootBounds,
                        viewportBounds,
                        exportViewport,
                        emptyHoverState,
                        emptySelectionState,
                        collapsedNodeIds
                    )

                    g2d.dispose()

                    indicator.text = "Saving image file..."
                    indicator.fraction = 0.8

                    saveImage(image, targetFile)

                    indicator.fraction = 1.0

                    SwingUtilities.invokeLater {
                        showNotification(
                            "Export Success",
                            "Mindmap exported successfully to: ${targetFile.absolutePath} (${image.width}x${image.height} pixels)",
                            NotificationType.INFORMATION
                        )
                    }
                } catch (e: Exception) {
                    SwingUtilities.invokeLater {
                        showNotification(
                            "Export Failed",
                            "Failed to export mindmap: ${e.message}",
                            NotificationType.ERROR
                        )
                    }
                }
            }
        })
    }

    private fun calculateContentBounds(rootBounds: NodeBounds): ContentBounds {
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = Double.MIN_VALUE
        var maxY = Double.MIN_VALUE

        fun traverse(bounds: NodeBounds) {
            minX = minOf(minX, bounds.x)
            minY = minOf(minY, bounds.y)
            maxX = maxOf(maxX, bounds.x + bounds.width)
            maxY = maxOf(maxY, bounds.y + bounds.height)
            bounds.childBounds.forEach { traverse(it) }
        }

        traverse(rootBounds)
        return ContentBounds(minX, maxX, minY, maxY)
    }

    private fun setupHighQualityRendering(g2d: java.awt.Graphics2D) {
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g2d.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
        g2d.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
        g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }

    private fun applyTransform(
        g2d: java.awt.Graphics2D,
        minX: Double,
        minY: Double,
        padding: Double,
        scaleFactor: Double
    ) {
        val transform = AffineTransform()
        transform.scale(scaleFactor, scaleFactor)
        transform.translate(padding - minX, padding - minY)
        g2d.transform(transform)
    }

    private fun saveImage(image: BufferedImage, targetFile: File) {
        val writer = ImageIO.getImageWritersByFormatName("png").next()
        val writeParam = writer.defaultWriteParam
        val output = javax.imageio.stream.FileImageOutputStream(targetFile)
        writer.output = output
        writer.write(null, javax.imageio.IIOImage(image, null, null), writeParam)
        writer.dispose()
        output.close()
    }

    private fun showNotification(title: String, content: String, type: NotificationType) {
        val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("Gauge Mindmap")
        notificationGroup.createNotification(title, content, type).notify(project)
    }

    private data class ContentBounds(
        val minX: Double,
        val maxX: Double,
        val minY: Double,
        val maxY: Double
    )
}

