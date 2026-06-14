// Compose marks ScrollConfig / LocalScrollConfig `internal`, but their JVM
// symbols are public. Suppressing the visibility checks lets us reference and
// override them with direct (non-reflective) bytecode calls — fully GraalVM
// native-image compatible. All access to these internals is isolated to this
// file.
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER", "CANNOT_OVERRIDE_INVISIBLE_MEMBER")

package dev.nucleusframework.window.tao.render

import androidx.compose.foundation.gestures.LocalScrollConfig
import androidx.compose.foundation.gestures.ScrollConfig
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize

/**
 * Mouse-wheel scroll mapping that mirrors Chromium's, replacing Compose's
 * default `WindowsWinUIConfig` which scrolls a fraction of the viewport
 * (`height / 20`) — i.e. the scroll distance grows with the window size.
 *
 * Chromium instead uses a FIXED number of pixels per wheel tick, independent of
 * the viewport (verified in `ui/events/blink/web_input_event_builders_win.cc`):
 *
 * ```
 * num_ticks    = wheel_delta / WHEEL_DELTA            // HIWORD / 120
 * scroll_delta = num_ticks * scroll_lines             // SPI_GETWHEELSCROLLLINES, default 3
 * scroll_delta *= 100.0f / 3.0f                        // kScrollbarPixelsPerLine
 * // => 100 px per tick (default), in physical pixels
 * ```
 *
 * The same single formula serves the mouse wheel and precision touchpads:
 * touchpads emit fractional ticks (`wheel_delta < 120`), so they scroll
 * proportionally less without any device-specific branch.
 *
 * The Tao backend already feeds raw wheel ticks as `scrollDelta` (1.0 per
 * notch, fractional for precision touchpads — see `TaoWindow.SCROLL_LINE` and
 * the native `WM_MOUSEWHEEL` patch), so here we only multiply by the fixed
 * [pixelsPerTick].
 *
 * Chromium's per-platform constants, for reference (each FIXED, never
 * viewport-relative): Windows 100, macOS 40 (`kScrollbarPixelsPerCocoaTick`),
 * Linux 53 (`MouseWheelEvent::kWheelDelta`); macOS/Linux trackpads bypass this
 * with pixel-precise deltas.
 *
 * `isSmoothScrollingEnabled` (true) and `isPreciseWheelScroll` (false) keep
 * Compose's stock desktop defaults, so only the wheel-to-pixel mapping changes.
 */
internal class ChromeScrollConfig(
    private val pixelsPerTick: Float = WINDOWS_PIXELS_PER_TICK,
) : ScrollConfig {
    override fun Density.calculateMouseWheelScroll(
        event: PointerEvent,
        bounds: IntSize,
    ): Offset {
        val totalScrollDelta =
            event.changes.fold(Offset.Zero) { acc, change -> acc + change.scrollDelta }
        // Negative to match WindowsWinUIConfig's sign convention (it multiplies
        // by `-scrollAmount`, and the Tao backend feeds scrollAmount = 1).
        return totalScrollDelta * -pixelsPerTick
    }

    companion object {
        /** Chromium Windows: 3 lines per tick × (100/3) px per line = 100 px per tick. */
        const val WINDOWS_PIXELS_PER_TICK = 100f
    }
}

/** Installs [ChromeScrollConfig] for [content] via Compose's `LocalScrollConfig`. */
@Composable
internal fun ProvideChromeScrollConfig(content: @Composable () -> Unit) {
    val config = remember { ChromeScrollConfig() }
    CompositionLocalProvider(LocalScrollConfig provides config, content = content)
}
