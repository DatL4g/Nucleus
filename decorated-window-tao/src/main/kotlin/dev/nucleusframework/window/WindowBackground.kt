package dev.nucleusframework.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.nucleusframework.window.tao.LocalWindowClearColorLayers

/**
 * Sets the window's own background — the colour that shows wherever Compose
 * paints nothing.
 *
 * That is more places than it sounds: the margins around a floating panel, the
 * band a chrome animation briefly leaves open, and above all the frames the
 * compositor draws during a live resize, which is what makes an unset
 * background flash white on a dark app.
 *
 * A themed app should call this with the same colour its theme paints,
 * anywhere inside the window content:
 *
 * ```
 * DecoratedWindow(...) {
 *     MaterialTheme(colorScheme = scheme) {
 *         WindowBackground(scheme.background)
 *         WindowScaffold(...) { ... }
 *     }
 * }
 * ```
 *
 * The alternative is providing `LocalDecoratedWindowStyle` *around* the
 * window, which forces the theme state to be hoisted above it — awkward
 * precisely when the theme lives inside, as it must on the Tao backend where
 * every window owns its `ComposeScene`.
 */
@Suppress("FunctionNaming", "UnusedReceiverParameter")
@Composable
public fun DecoratedWindowScope.WindowBackground(color: Color) {
    val layers = LocalWindowClearColorLayers.current
    val argb = color.toArgb()
    SideEffect {
        // Single write to the window's content layer; the layer resolver
        // pushes the result into the host state synchronously, so the first
        // composition themes the window before its first frame. Every
        // consumer — the Skia clear, the NSWindow colour on macOS, the HWND
        // brush + DWM caption/dark-mode on Windows — derives from that one
        // resolved state, which is what keeps the chrome atomically themed.
        layers?.setContent(argb)
    }
    DisposableEffect(Unit) {
        onDispose {
            // Hand the window back to the hoisted style layer: a removed
            // WindowBackground must not keep shadowing it forever.
            layers?.setContent(null)
        }
    }
}
