package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.window.tao.DecoratedWindow
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.taoApplication
import java.awt.image.BufferedImage
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Manual smoke for #416 / PR #419:
 * `DecoratedWindow(transparent = true)` + a small opaque marker over the desktop.
 *
 * On Windows, [java.awt.Robot] omits layered/per-pixel-alpha windows (no
 * CAPTUREBLT). This smoke shells out to a tiny Win32 helper
 * (`capture_region.exe`) that BitBlts with `SRCCOPY | CAPTUREBLT`.
 *
 * Run: `./gradlew :decorated-window-tao:taoTransparentSmoke`
 */
object TransparentWindowSmokeMain {
    private const val OUTER_X = 120
    private const val OUTER_Y = 120
    private const val OUTER_W = 480
    private const val OUTER_H = 360
    // Hold the window on screen so a manual look is possible; override with
    // -Dnucleus.tao.transparent.smoke.holdMs=…
    private val SETTLE_MS: Long =
        System.getProperty("nucleus.tao.transparent.smoke.holdMs")?.toLongOrNull()
            ?: 1_200L
    // Marker is 48.dp at 24.dp padding → ~centre of square around (48, 48).
    private const val MARKER_SAMPLE_X = 48
    private const val MARKER_SAMPLE_Y = 48
    private const val EMPTY_SAMPLE_X = 300
    private const val EMPTY_SAMPLE_Y = 240
    private const val SAMPLE_HALF = 6

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir =
            File(
                System.getProperty(
                    "nucleus.tao.transparent.smoke.outdir",
                    "build/reports/tao-transparent-smoke",
                ),
            ).absoluteFile
        outDir.mkdirs()
        val captureTool =
            File(
                System.getProperty(
                    "nucleus.tao.transparent.smoke.captureTool",
                    "build/tmp-smoke/capture_region.exe",
                ),
            ).absoluteFile
        check(captureTool.isFile) {
            "capture tool missing: $captureTool — run the gradle task (it builds the helper)"
        }

        val baselineBmp = File(outDir, "01-baseline-desktop.bmp")
        captureRegion(captureTool, OUTER_X, OUTER_Y, OUTER_W, OUTER_H, baselineBmp)
        val baseline = readBmp24(baselineBmp)
        ImageIO.write(baseline, "png", File(outDir, "01-baseline-desktop.png"))
        val baselineEmpty = averageRgb(baseline, EMPTY_SAMPLE_X, EMPTY_SAMPLE_Y, SAMPLE_HALF)
        System.err.println(
            "[smoke/#416] baseline empty RGB=(%d,%d,%d)".format(
                baselineEmpty[0],
                baselineEmpty[1],
                baselineEmpty[2],
            ),
        )

        taoApplication {
            val state =
                rememberWindowState(
                    position = WindowPosition(OUTER_X.dp, OUTER_Y.dp),
                    size = DpSize(OUTER_W.dp, OUTER_H.dp),
                )
            var window by remember { mutableStateOf<TaoWindow?>(null) }

            DecoratedWindow(
                onCloseRequest = { /* smoke owns lifecycle */ },
                state = state,
                title = "tao transparent smoke #416",
                alwaysOnTop = true,
                // Borderless overlay (no CSD outline) + full-window transparency.
                undecorated = true,
                transparent = true,
            ) {
                // No WindowBackground / TitleBar — empty client must composite desktop.
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    Box(Modifier.size(48.dp).background(Color(0xFFFF00AA)))
                }
                val w = this.window
                LaunchedEffect(w) { window = w }
            }

            LaunchedEffect(Unit) {
                val deadline = System.currentTimeMillis() + 15_000L
                while (window == null) {
                    check(System.currentTimeMillis() < deadline) { "window never published" }
                    kotlinx.coroutines.delay(25)
                }
                val w = window!!
                w.setOuterPosition(OUTER_X.toDouble(), OUTER_Y.toDouble())
                w.setInnerSize(OUTER_W.toDouble(), OUTER_H.toDouble())
                w.focus()
                kotlinx.coroutines.delay(SETTLE_MS)

                val bounds = w.outerBoundsPx()
                System.err.println(
                    "[smoke/#416] outerBoundsPx=" +
                        (bounds?.joinToString(prefix = "[", postfix = "]") ?: "null") +
                        " scale=${w.scaleFactor}",
                )

                val withBmp = File(outDir, "02-transparent-window.bmp")
                captureRegion(captureTool, OUTER_X, OUTER_Y, OUTER_W, OUTER_H, withBmp)
                val withWindow = readBmp24(withBmp)
                ImageIO.write(withWindow, "png", File(outDir, "02-transparent-window.png"))

                val ox =
                    if (bounds != null && bounds.size >= 4) {
                        (bounds[0] - OUTER_X).toInt()
                    } else {
                        0
                    }
                val oy =
                    if (bounds != null && bounds.size >= 4) {
                        (bounds[1] - OUTER_Y).toInt()
                    } else {
                        0
                    }

                val marker =
                    averageRgb(
                        withWindow,
                        (ox + MARKER_SAMPLE_X).coerceIn(0, withWindow.width - 1),
                        (oy + MARKER_SAMPLE_Y).coerceIn(0, withWindow.height - 1),
                        SAMPLE_HALF,
                    )
                val empty =
                    averageRgb(
                        withWindow,
                        (ox + EMPTY_SAMPLE_X).coerceIn(0, withWindow.width - 1),
                        (oy + EMPTY_SAMPLE_Y).coerceIn(0, withWindow.height - 1),
                        SAMPLE_HALF,
                    )
                System.err.println(
                    "[smoke/#416] marker RGB=(%d,%d,%d) empty RGB=(%d,%d,%d) origin=(%d,%d)".format(
                        marker[0],
                        marker[1],
                        marker[2],
                        empty[0],
                        empty[1],
                        empty[2],
                        ox,
                        oy,
                    ),
                )

                val failures = mutableListOf<String>()

                if (!isMagenta(marker)) {
                    // Scan a band for the marker in case chrome inset shifted it.
                    val found = findMagenta(withWindow)
                    if (found == null) {
                        failures +=
                            "opaque marker not magenta anywhere: sample " +
                                "RGB=(${marker[0]},${marker[1]},${marker[2]})"
                    } else {
                        System.err.println(
                            "[smoke/#416] marker found by scan at ${found.first},${found.second}",
                        )
                    }
                }

                if (isNearSolid(empty, 0xFF, 0xFF, 0xFF, tol = 18)) {
                    failures += "empty client looks opaque white — transparency not applied"
                }
                if (isMagenta(empty)) {
                    failures += "empty client is magenta — marker bled across whole surface"
                }

                val dist = rgbDistance(empty, baselineEmpty)
                System.err.println("[smoke/#416] empty vs baseline Δ=$dist (max allowed 90)")
                if (dist > 90) {
                    failures +=
                        "empty client RGB=(${empty[0]},${empty[1]},${empty[2]}) " +
                            "diverges from baseline desktop " +
                            "RGB=(${baselineEmpty[0]},${baselineEmpty[1]},${baselineEmpty[2]}) " +
                            "Δ=$dist — desktop not compositing through"
                }

                // Marker region must differ from the pre-window desktop.
                val baselineMarker =
                    averageRgb(baseline, MARKER_SAMPLE_X, MARKER_SAMPLE_Y, SAMPLE_HALF)
                val markerDelta = rgbDistance(marker, baselineMarker)
                System.err.println("[smoke/#416] marker vs baseline Δ=$markerDelta (min expected 80)")
                if (isMagenta(marker) && markerDelta < 80) {
                    failures += "marker sample matches desktop — opaque marker not composited"
                }

                // Undecorated + transparent must not leave a pure-black DWM/
                // erase contour around the window (the regression the user
                // spotted after Compose border was already skipped).
                val blackEdge = countNearBlackPerimeter(withWindow, thickness = 2, maxChannel = 6)
                System.err.println("[smoke/#416] near-black perimeter pixels=$blackEdge (max allowed 40)")
                if (blackEdge > 40) {
                    failures +=
                        "near-black perimeter contour still present ($blackEdge px) — " +
                            "DWM borderless chrome not applied"
                }

                if (failures.isEmpty()) {
                    System.err.println("[smoke/#416] VERDICT: OK — transparent window over desktop")
                    System.err.println("[smoke/#416] screenshots: $outDir")
                    exitProcess(0)
                } else {
                    System.err.println("[smoke/#416] VERDICT: FAIL")
                    failures.forEach { System.err.println("  - $it") }
                    System.err.println("[smoke/#416] screenshots: $outDir")
                    exitProcess(1)
                }
            }
        }
    }

    private fun captureRegion(
        tool: File,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        out: File,
    ) {
        val pb =
            ProcessBuilder(
                tool.absolutePath,
                x.toString(),
                y.toString(),
                w.toString(),
                h.toString(),
                out.absolutePath,
            )
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val log = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        check(code == 0 && out.isFile) {
            "capture failed (exit=$code): $log"
        }
        System.err.println("[smoke/#416] capture: ${out.name} (${out.length()} bytes) $log".trim())
    }

    /** Reads a 24-bit top-down or bottom-up BMP produced by capture_region.exe. */
    private fun readBmp24(file: File): BufferedImage {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(14)
            raf.readFully(header)
            check(header[0] == 'B'.code.toByte() && header[1] == 'M'.code.toByte()) {
                "not a BMP: $file"
            }
            val dibSizeBuf = ByteArray(4)
            raf.readFully(dibSizeBuf)
            val dibSize = ByteBuffer.wrap(dibSizeBuf).order(ByteOrder.LITTLE_ENDIAN).int
            val dib = ByteArray(dibSize - 4)
            raf.readFully(dib)
            val bb = ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN)
            val width = bb.int
            val heightRaw = bb.int
            val topDown = heightRaw < 0
            val height = abs(heightRaw)
            bb.short // planes
            val bpp = bb.short.toInt() and 0xFFFF
            check(bpp == 24) { "expected 24-bpp BMP, got $bpp" }
            raf.seek(ByteBuffer.wrap(header, 10, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong())
            val row = (width * 3 + 3) and inv3
            val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val rowBytes = ByteArray(row)
            for (rowIndex in 0 until height) {
                raf.readFully(rowBytes)
                val y = if (topDown) rowIndex else height - 1 - rowIndex
                for (x in 0 until width) {
                    val i = x * 3
                    val b = rowBytes[i].toInt() and 0xFF
                    val g = rowBytes[i + 1].toInt() and 0xFF
                    val r = rowBytes[i + 2].toInt() and 0xFF
                    img.setRGB(x, y, (r shl 16) or (g shl 8) or b)
                }
            }
            return img
        }
    }

    private const val inv3 = 3.inv()

    private fun averageRgb(
        image: BufferedImage,
        cx: Int,
        cy: Int,
        half: Int,
    ): IntArray {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        val x0 = (cx - half).coerceAtLeast(0)
        val y0 = (cy - half).coerceAtLeast(0)
        val x1 = (cx + half).coerceAtMost(image.width - 1)
        val y1 = (cy + half).coerceAtMost(image.height - 1)
        for (y in y0..y1) {
            for (x in x0..x1) {
                val rgb = image.getRGB(x, y)
                r += (rgb ushr 16) and 0xFF
                g += (rgb ushr 8) and 0xFF
                b += rgb and 0xFF
                n++
            }
        }
        check(n > 0) { "empty sample region" }
        return intArrayOf((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun findMagenta(image: BufferedImage): Pair<Int, Int>? {
        // Coarse grid scan — marker is 48px.
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                val rgb = image.getRGB(x, y)
                val r = (rgb ushr 16) and 0xFF
                val g = (rgb ushr 8) and 0xFF
                val b = rgb and 0xFF
                if (r >= 0xC0 && g <= 0x40 && b >= 0x70) return x to y
                x += 8
            }
            y += 8
        }
        return null
    }

    private fun isMagenta(rgb: IntArray): Boolean {
        val (r, g, b) = rgb
        return r >= 0xC0 && g <= 0x40 && b >= 0x70
    }

    private fun isNearSolid(
        rgb: IntArray,
        tr: Int,
        tg: Int,
        tb: Int,
        tol: Int,
    ): Boolean =
        abs(rgb[0] - tr) <= tol &&
            abs(rgb[1] - tg) <= tol &&
            abs(rgb[2] - tb) <= tol

    private fun rgbDistance(
        a: IntArray,
        b: IntArray,
    ): Int = abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])

    /** Counts near-black pixels on a [thickness]-px ring around the image. */
    private fun countNearBlackPerimeter(
        image: BufferedImage,
        thickness: Int,
        maxChannel: Int,
    ): Int {
        var n = 0
        val w = image.width
        val h = image.height
        for (y in 0 until h) {
            for (x in 0 until w) {
                val onEdge =
                    x < thickness ||
                        y < thickness ||
                        x >= w - thickness ||
                        y >= h - thickness
                if (!onEdge) continue
                val rgb = image.getRGB(x, y)
                val r = (rgb ushr 16) and 0xFF
                val g = (rgb ushr 8) and 0xFF
                val b = rgb and 0xFF
                // Skip the opaque magenta marker if it touches the top edge.
                if (r >= 0xC0 && g <= 0x40 && b >= 0x70) continue
                if (r <= maxChannel && g <= maxChannel && b <= maxChannel) n++
            }
        }
        return n
    }
}
