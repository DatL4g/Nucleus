package dev.nucleusframework.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.graphics.toArgb
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.styling.LocalDecoratedWindowStyle
import dev.nucleusframework.window.tao.LocalRequestedGlassBackground
import dev.nucleusframework.window.tao.TaoDecoratedWindowScope
import dev.nucleusframework.window.tao.ffi.NativeMetalBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge

private const val DEFAULT_GLASS_TINT_ALPHA = 0.5f

/**
 * Applies a [WindowBackdropStyle] to this decorated window.
 *
 * With [WindowBackdropStyle.Glass], the window background becomes a native
 * behind-window glass material and the Compose scene is cleared to
 * transparent each frame: anything the app does NOT paint over shows the
 * desktop through the glass. Content that should stay readable keeps its
 * own (possibly translucent) backgrounds — this composes naturally with
 * `WindowScaffold` overlay title bars.
 *
 * This effect is impossible with the AWT-based backends (the window surface
 * is opaque and AWT owns the view hierarchy); it relies on the Tao backend
 * owning the native window. Currently implemented on macOS; a no-op
 * elsewhere. The backdrop reverts to [WindowBackdropStyle.Opaque] when the
 * composable leaves the composition.
 */
@Suppress("FunctionNaming")
@Composable
public fun DecoratedWindowScope.WindowBackdrop(style: WindowBackdropStyle) {
    // Tao always provides a [TaoDecoratedWindowScope] at runtime — same
    // contract as `BasicTitleBar`.
    val taoWindow = (this as TaoDecoratedWindowScope).window
    val glassState = LocalRequestedGlassBackground.current
    val windowBackground = LocalDecoratedWindowStyle.current.colors.background

    val tintArgb =
        when {
            style !is WindowBackdropStyle.Glass -> 0
            style.tint.isUnspecified ->
                windowBackground.copy(alpha = DEFAULT_GLASS_TINT_ALPHA).toArgb()
            style.tint == Color.Transparent -> 0
            else -> style.tint.toArgb()
        }
    val glassRequested = style is WindowBackdropStyle.Glass

    DisposableEffect(taoWindow, glassRequested, tintArgb) {
        val applied =
            glassRequested &&
                Platform.Current == Platform.MacOS &&
                NativeMetalBridge.isLoaded &&
                run {
                    val nsView = NativeTaoBridge.nativeNsViewHandle(taoWindow.handle)
                    nsView != 0L &&
                        NativeMetalBridge.nativeSetGlassBackground(nsView, true, tintArgb)
                }
        // Flip the transparent-clear mode only when the native side actually
        // installed the glass view — an opaque window cleared to alpha 0
        // would just show black.
        if (applied) glassState?.value = true
        onDispose {
            if (applied) {
                glassState?.value = false
                val nsView = NativeTaoBridge.nativeNsViewHandle(taoWindow.handle)
                if (nsView != 0L) {
                    NativeMetalBridge.nativeSetGlassBackground(nsView, false, 0)
                }
            }
        }
    }
}
