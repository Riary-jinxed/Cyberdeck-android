package dev.netrunner.aska

import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * What she sees when the runner asks her to look: the screen under the mouse, or an image copied
 * to the clipboard, shrunk and sent to the local model only (Gemma reads images).
 */
object Screen {
    /** The monitor under the mouse, as JPEG in base64; null when the system refuses. */
    fun capture(): String? = runCatching {
        val mouse = MouseInfo.getPointerInfo()?.location
        val screens = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        val bounds = screens.map { it.defaultConfiguration.bounds }.firstOrNull { mouse != null && it.contains(mouse) }
            ?: screens.firstOrNull()?.defaultConfiguration?.bounds
            ?: Rectangle(Toolkit.getDefaultToolkit().screenSize)
        encode(Robot().createScreenCapture(bounds))
    }.getOrNull()

    /** An image copied to the clipboard (a screenshot, a picture from the web). */
    fun clipboardImage(): String? = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return null
        (clipboard.getData(DataFlavor.imageFlavor) as? java.awt.Image)?.let { encode(toBuffered(it)) }
    }.getOrNull()

    /** Shrunk to [MAX_SIDE] on its longest side: enough to read text, light for the model. */
    fun encode(image: BufferedImage): String {
        val scale = minOf(1.0, MAX_SIDE.toDouble() / maxOf(image.width, image.height))
        val w = (image.width * scale).toInt().coerceAtLeast(1)
        val h = (image.height * scale).toInt().coerceAtLeast(1)
        val rgb = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(image, 0, 0, w, h, null)
            dispose()
        }
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val params = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = 0.85f
            }
            writer.write(null, IIOImage(rgb, null, null), params)
        }
        writer.dispose()
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun toBuffered(image: java.awt.Image): BufferedImage {
        if (image is BufferedImage) return image
        val b = BufferedImage(image.getWidth(null).coerceAtLeast(1), image.getHeight(null).coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
        b.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
        return b
    }

    private const val MAX_SIDE = 1536
}
