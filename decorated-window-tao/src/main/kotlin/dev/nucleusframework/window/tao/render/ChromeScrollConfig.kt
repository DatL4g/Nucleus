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
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import java.awt.event.MouseWheelEvent
import kotlin.math.abs
import kotlin.math.round

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
 * `isSmoothScrollingEnabled` stays true (stock default): notched mouse-wheel
 * ticks animate, as in Chromium.
 *
 * [isPreciseWheelScroll], however, deliberately diverges from Compose's
 * Windows default (hardcoded `false` in `DesktopScrollable.desktop.kt`).
 * Windows delivers precision-touchpad pans as synthesized `WM_MOUSEWHEEL`
 * bursts (fractional ticks, ~60–125 Hz). Funnelling those into the smooth-
 * scroll tween reads as a visible saccade on fast flicks: the tween consumes
 * at `AnimationSpeed = 1dp/ms` with `MaxAnimationDuration = 100ms`
 * (`MouseWheelScrollingLogic`), while a flick feeds ~8–10 px/ms — the backlog
 * balloons, then drains in one ~100ms linear lurch and stops dead. Native
 * Windows apps (and Chromium, via DirectManipulation) apply touchpad pans
 * directly, finger-tracking. So: fractional ticks → precise (delta applied
 * on arrival, batched per frame); integer ticks → animated wheel notches.
 *
 * A short session latch keeps a touchpad burst on the direct path even when
 * an individual event happens to quantize to a whole tick —
 * `MouseWheelScrollingLogic` samples the flag from the first event of a burst
 * and sticks with it, so one integer-looking first event would otherwise
 * re-animate the whole gesture.
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

    /** Event-time timestamp until which the source is treated as a touchpad. */
    private var touchpadActiveUntilMs = 0L

    override fun isPreciseWheelScroll(event: PointerEvent): Boolean {
        val wheel = event.awtEventOrNull as? MouseWheelEvent ?: return false
        return isPreciseTick(wheel.preciseWheelRotation, wheel.`when`)
    }

    /**
     * Precision touchpads synthesize fractional wheel ticks; physical wheel
     * notches are exact integers (also when Windows coalesces a fast spin).
     * A fractional tick opens a [TOUCHPAD_SESSION_MS] window during which
     * integer-quantized ticks stay on the direct path too.
     */
    internal fun isPreciseTick(
        rotation: Double,
        nowMs: Long,
    ): Boolean {
        val fractional = abs(rotation - round(rotation)) > FRACTIONAL_TICK_EPSILON
        if (fractional) touchpadActiveUntilMs = nowMs + TOUCHPAD_SESSION_MS
        return fractional || nowMs < touchpadActiveUntilMs
    }

    companion object {
        /** Chromium Windows: 3 lines per tick × (100/3) px per line = 100 px per tick. */
        const val WINDOWS_PIXELS_PER_TICK = 100f

        /** Same tolerance as Compose's own precise-wheel heuristic on macOS/Linux. */
        const val FRACTIONAL_TICK_EPSILON = 0.001

        /** Touchpad inertia tails pause briefly; 1s bridges the gaps without
         *  noticeably delaying the switch back to animated wheel notches. */
        const val TOUCHPAD_SESSION_MS = 1_000L
    }
}

/** Installs [ChromeScrollConfig] for [content] via Compose's `LocalScrollConfig`. */
@Composable
internal fun ProvideChromeScrollConfig(content: @Composable () -> Unit) {
    val config = remember { ChromeScrollConfig() }
    CompositionLocalProvider(LocalScrollConfig provides config, content = content)
}
