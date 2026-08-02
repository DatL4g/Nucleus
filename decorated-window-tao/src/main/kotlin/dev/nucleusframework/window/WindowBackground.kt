package dev.nucleusframework.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.nucleusframework.window.tao.LocalRequestedClearColor
import dev.nucleusframework.window.tao.TaoDecoratedWindowScope

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
@Suppress("FunctionNaming")
@Composable
public fun DecoratedWindowScope.WindowBackground(color: Color) {
    // Tao always provides a [TaoDecoratedWindowScope] at runtime — same
    // contract as `BasicTitleBar`.
    val taoWindow = (this as TaoDecoratedWindowScope).window
    val clearColorState = LocalRequestedClearColor.current
    val argb = color.toArgb()
    SideEffect {
        // Drives the Skia clear on every platform, and from there the native
        // window colour: macOS mirrors it onto the NSWindow, Windows onto the
        // HWND brush.
        clearColorState?.value = argb
        taoWindow.setBackgroundColor(argb)
    }
}
