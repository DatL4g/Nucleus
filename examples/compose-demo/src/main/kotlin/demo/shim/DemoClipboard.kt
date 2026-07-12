package demo.shim

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.asAwtTransferable
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

// Desktop ClipEntry helpers. Compose Desktop's ClipEntry wraps an AWT
// Transferable (nativeClipEntry / asAwtTransferable) rather than the
// text/image accessors the native port exposes — so text goes through
// StringSelection and images through DataFlavor.imageFlavor (PNG <-> BufferedImage).

// Builds a text ClipEntry from a plain String.
@OptIn(ExperimentalComposeUiApi::class)
fun demoClipEntryOf(text: String): ClipEntry = ClipEntry(StringSelection(text))

/* Builds an image ClipEntry from encoded PNG bytes, exposed to the OS as an
   AWT image so any image-aware app can paste it. */
@OptIn(ExperimentalComposeUiApi::class)
fun demoClipEntryOfImage(pngBytes: ByteArray): ClipEntry {
    val image = ImageIO.read(ByteArrayInputStream(pngBytes))
    return ClipEntry(PngImageTransferable(image))
}

// Reads plain text off a ClipEntry, or null when it carries none.
@OptIn(ExperimentalComposeUiApi::class)
fun ClipEntry.demoReadText(): String? {
    val transferable = asAwtTransferable ?: return null
    if (!transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
    return runCatching { transferable.getTransferData(DataFlavor.stringFlavor) as? String }.getOrNull()
}

// Reads an image off a ClipEntry and re-encodes it to PNG bytes, or null.
@OptIn(ExperimentalComposeUiApi::class)
fun ClipEntry.demoReadImage(): ByteArray? {
    val transferable = asAwtTransferable ?: return null
    if (!transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) return null
    val image =
        runCatching { transferable.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image }.getOrNull()
            ?: return null
    val buffered =
        (image as? BufferedImage) ?: BufferedImage(
            image.getWidth(null).coerceAtLeast(1),
            image.getHeight(null).coerceAtLeast(1),
            BufferedImage.TYPE_INT_ARGB,
        ).apply {
            createGraphics().apply {
                drawImage(image, 0, 0, null)
                dispose()
            }
        }
    val out = ByteArrayOutputStream()
    return if (ImageIO.write(buffered, "png", out)) out.toByteArray() else null
}

// AWT Transferable exposing a single BufferedImage via imageFlavor.
private class PngImageTransferable(
    private val image: BufferedImage,
) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)

    override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean = flavor == DataFlavor.imageFlavor

    override fun getTransferData(flavor: DataFlavor?): Any =
        if (flavor == DataFlavor.imageFlavor) image else throw UnsupportedFlavorException(flavor)
}
