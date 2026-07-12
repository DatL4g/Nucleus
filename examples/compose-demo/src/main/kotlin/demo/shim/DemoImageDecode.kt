package demo.shim

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

// Encoded-bytes → ImageBitmap decoder. On the JVM this goes through Skia's
// Image.makeFromEncoded. Used by the ClipboardScreen "paste image" preview.
// Returns null when the bytes aren't a decodable image.
fun demoDecodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
