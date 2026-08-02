package dev.nucleusframework.window.tao.headful

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.TaoEventCode
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Headful probes for issue #418 — "Wrong scale factor when moving window
 * between screens on macOS with tao backend".
 *
 * The defect was in the JNI loop. On a display hop AppKit calls
 * `windowDidChangeBackingProperties:`, which tao turns into
 * `ScaleFactorChanged { scale_factor, new_inner_size }` — and **no `Resized`
 * follows**, because the NSWindow frame in points is unchanged so
 * `windowDidResize:` never fires. `event_loop.rs` used to match
 * `{ scale_factor, .. }` and forward only the scale, leaving the Compose scene
 * and the CAMetalLayer at the previous display's pixel size while the density
 * moved on: half-size in the top-left going 2x→1x, oversized and clipped going
 * 1x→2x, until any manual resize delivered the missing size. It now forwards
 * `new_inner_size` behind the scale change (macOS only — see the cfg comment
 * there for why Windows must not).
 *
 * Both cases assert the same user-visible invariant: a display hop does not
 * change the window's size in points, so the scene's **logical** size must not
 * change either.
 *
 * Coverage split, because it matters for what a green run here proves:
 *  - [realDisplayHop] is the regression gate for the loop fix. It needs two
 *    displays with different scale factors, so it skips on every CI runner and
 *    on single-display dev machines.
 *  - [scaleChangeWithSuggestedSize] drives the event pair at the Kotlin seam.
 *    It runs anywhere, but it cannot reach `event_loop.rs`: reverting the
 *    native hunk leaves it green.
 */
internal object DisplayScaleHeadfulCases {
    private val isMac: Boolean get() = Platform.Current == Platform.MacOS

    fun all(): List<TaoWindowTestCase> =
        listOf(
            scaleChangeWithSuggestedSize(),
            realDisplayHop(),
        )

    /** Live scene metrics published from inside the window composition. */
    private class SceneMetrics {
        val widthPx = AtomicInteger(0)
        val heightPx = AtomicInteger(0)
        private val densityMilli = AtomicInteger(0)

        val ready: Boolean get() = widthPx.get() > 0 && densityMilli.get() > 0

        // Guarded rather than dividing by a 0f density: an unpublished probe
        // must read as an obvious 0, not as a NaN that silently compares false
        // and gets reported as a #418 regression.
        val density: Float get() = if (densityMilli.get() == 0) 0f else densityMilli.get() / MILLI
        val logicalWidth: Float get() = if (ready) widthPx.get() / density else 0f
        val logicalHeight: Float get() = if (ready) heightPx.get() / density else 0f

        fun publish(
            wPx: Int,
            hPx: Int,
            scale: Float,
        ) {
            widthPx.set(wPx)
            heightPx.set(hPx)
            densityMilli.set((scale * MILLI).roundToInt())
        }

        fun describe(): String =
            "scenePx=${widthPx.get()}x${heightPx.get()} density=$density " +
                "logical=${logicalWidth.roundToInt()}x${logicalHeight.roundToInt()}dp"
    }

    /**
     * Publishes [LocalWindowInfo]'s container size and [LocalDensity] — the two
     * values the host mutates on a scale change — into [metrics].
     */
    @Composable
    private fun SceneMetricsProbe(metrics: SceneMetrics) {
        val size = LocalWindowInfo.current.containerSize
        val density = LocalDensity.current.density
        SideEffect { metrics.publish(size.width, size.height, density) }
    }

    /**
     * Replays the event pair the loop emits for a display hop —
     * `SCALE_FACTOR_CHANGED` immediately followed by the `RESIZED` carrying
     * tao's suggested size — and checks the scene ends up coherent. Guards the
     * Kotlin half of the contract: the host must pair its density change with
     * the size that trails it, whatever the order they arrive in.
     */
    private fun scaleChangeWithSuggestedSize(): TaoWindowTestCase {
        val metrics = SceneMetrics()
        return TaoWindowTestCase(
            name = "#418 scale change plus the loop's suggested size keeps the scene coherent",
            content = { SceneMetricsProbe(metrics) },
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                awaitUntil("scene metrics published") { metrics.ready }
                settle()

                // Baseline off the scene's own numbers, never off
                // `window.scaleFactor`: the host seeds its scale from
                // `initialMacOsScaleFactor`, i.e. max(window, primary monitor),
                // so on a mixed-DPI Mac the two legitimately disagree.
                val basePxW = metrics.widthPx.get()
                val basePxH = metrics.heightPx.get()
                val baseScale = metrics.density
                val baseLogicalW = metrics.logicalWidth
                val baseLogicalH = metrics.logicalHeight
                System.err.println("[probe] baseline ${metrics.describe()} nativeScale=${window.scaleFactor}")

                // Hop direction: 2x → 1x when the scene is already Retina,
                // 1x → 2x otherwise. Same shape either way.
                val hopScale = if (baseScale >= HIDPI) baseScale / 2f else baseScale * 2f
                // The window keeps its size in points, so this is what tao
                // puts in `new_inner_size` and what the loop forwards.
                val suggestedPxW = (baseLogicalW * hopScale).roundToInt()
                val suggestedPxH = (baseLogicalH * hopScale).roundToInt()

                window.dispatch(TaoEventCode.SCALE_FACTOR_CHANGED, (hopScale * MILLI).roundToInt(), 0)
                window.dispatch(TaoEventCode.RESIZED, suggestedPxW, suggestedPxH)
                settle(SETTLE_LONG)
                System.err.println(
                    "[probe] dispatched SCALE_FACTOR_CHANGED $baseScale -> $hopScale " +
                        "+ RESIZED(${suggestedPxW}x$suggestedPxH) -> ${metrics.describe()}",
                )

                check(abs(metrics.density - hopScale) < TOLERANCE_SCALE) {
                    "scene density ${metrics.density} never followed the scale $hopScale — " +
                        "the event did not reach the host, probe is invalid"
                }
                val coherent =
                    abs(metrics.logicalWidth - baseLogicalW) <= TOLERANCE_DP &&
                        abs(metrics.logicalHeight - baseLogicalH) <= TOLERANCE_DP

                // Leave the host on the real display's numbers for teardown.
                window.dispatch(TaoEventCode.SCALE_FACTOR_CHANGED, (baseScale * MILLI).roundToInt(), 0)
                window.dispatch(TaoEventCode.RESIZED, basePxW, basePxH)
                settle()

                check(coherent) {
                    "REPRO #418: scale $baseScale -> $hopScale with the suggested size left the scene at " +
                        "${metrics.logicalWidth.roundToInt()}x${metrics.logicalHeight.roundToInt()}dp " +
                        "instead of ${baseLogicalW.roundToInt()}x${baseLogicalH.roundToInt()}dp"
                }
                System.err.println("[VERDICT] OK — density and suggested size applied coherently")
            },
        )
    }

    /**
     * The real thing, and the only case that exercises the loop fix: move the
     * window onto a display with a different scale factor and let AppKit fire
     * `windowDidChangeBackingProperties:`. Skipped without two such displays.
     */
    private fun realDisplayHop(): TaoWindowTestCase {
        val metrics = SceneMetrics()
        return TaoWindowTestCase(
            name = "#418 real display hop keeps the scene coherent",
            // Order matters: `screens()` spins up the AWT graphics environment,
            // so it must stay behind the platform and headless guards — this
            // lambda runs from the suite's composable body on the Tao main
            // thread, in a module whose whole premise is no-AWT.
            skip = {
                when {
                    !isMac -> "macOS-only display-hop probe"
                    GraphicsEnvironment.isHeadless() -> "no display"
                    else -> {
                        val scales = screens().map { it.scale }
                        if (scales.distinct().size < 2) {
                            "needs two displays with different scale factors " +
                                "(found ${scales.joinToString { "${it}x" }})"
                        } else {
                            null
                        }
                    }
                }
            },
            content = { SceneMetricsProbe(metrics) },
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                awaitUntil("scene metrics published") { metrics.ready }
                // Small enough to fit on any of the attached displays. Awaited,
                // not settled: the resize round-trips through the loop and a
                // recomposition before `metrics` catches up, and a baseline
                // read too early fails the case for the wrong reason.
                window.setInnerSize(HOP_W_DP, HOP_H_DP)
                awaitUntil("scene resized to ${HOP_W_DP.toInt()}x${HOP_H_DP.toInt()}dp") {
                    abs(metrics.logicalWidth - HOP_W_DP.toFloat()) <= HOP_SIZE_TOLERANCE_DP &&
                        abs(metrics.logicalHeight - HOP_H_DP.toFloat()) <= HOP_SIZE_TOLERANCE_DP
                }
                settle(SETTLE_LONG)

                val fromScale = window.scaleFactor
                val target =
                    requireNotNull(screens().firstOrNull { abs(it.scale - fromScale) > TOLERANCE_SCALE }) {
                        "no display with a scale factor other than $fromScale"
                    }
                val baseLogicalW = metrics.logicalWidth
                val baseLogicalH = metrics.logicalHeight
                System.err.println(
                    "[probe] baseline ${metrics.describe()} nativeScale=$fromScale " +
                        "target=${target.scale}x @ ${target.bounds}",
                )

                // AWT screen bounds and Tao's setOuterPosition share macOS'
                // top-left point space, so the target origin plus a margin puts
                // the window well inside the other display.
                window.setOuterPosition(
                    target.bounds.x + HOP_MARGIN_DP,
                    target.bounds.y + HOP_MARGIN_DP,
                )
                awaitUntil("window reports the target display's scale (${target.scale})") {
                    abs(window.scaleFactor - target.scale) < TOLERANCE_SCALE
                }
                settle(SETTLE_LONG)
                System.err.println("[probe] after hop ${metrics.describe()} nativeScale=${window.scaleFactor}")

                val coherent =
                    abs(metrics.logicalWidth - baseLogicalW) <= TOLERANCE_DP &&
                        abs(metrics.logicalHeight - baseLogicalH) <= TOLERANCE_DP
                check(coherent) {
                    "REPRO #418: hopping ${fromScale}x -> ${target.scale}x changed the scene's logical size " +
                        "from ${baseLogicalW.roundToInt()}x${baseLogicalH.roundToInt()}dp to " +
                        "${metrics.logicalWidth.roundToInt()}x${metrics.logicalHeight.roundToInt()}dp " +
                        "(${metrics.describe()}); the window is still the same size in points"
                }
                System.err.println("[VERDICT] OK — scene stayed coherent across a real display hop")
            },
        )
    }

    private class ScreenInfo(
        val bounds: Rectangle,
        val scale: Float,
    )

    private fun screens(): List<ScreenInfo> =
        runCatching {
            GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .screenDevices
                .map { device ->
                    val config = device.defaultConfiguration
                    ScreenInfo(config.bounds, config.defaultTransform.scaleX.toFloat())
                }
        }.getOrDefault(emptyList())

    private const val MILLI = 1000f
    private const val HIDPI = 2f
    private const val TOLERANCE_DP = 1f
    private const val TOLERANCE_SCALE = 0.01f
    private const val SETTLE_LONG = 600L
    private const val HOP_W_DP = 600.0
    private const val HOP_H_DP = 400.0
    private const val HOP_SIZE_TOLERANCE_DP = 2f
    private const val HOP_MARGIN_DP = 80.0
}
