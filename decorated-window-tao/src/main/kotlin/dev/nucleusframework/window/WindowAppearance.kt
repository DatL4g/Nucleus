package dev.nucleusframework.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.TaoDecoratedWindowScope
import dev.nucleusframework.window.tao.ffi.NativeMetalBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge

/**
 * Forces the appearance of this window's native surfaces — glass regions and
 * system materials, traffic lights, native menus and popups.
 *
 * Apps that let the user pick their own theme need this: native surfaces
 * otherwise follow the OS setting, so an app running dark on a light system
 * gets a light sidebar material under dark Compose content (and vice versa).
 * Pass the same value that drives the Compose theme.
 *
 * macOS only (Tao backend, `NSWindow.appearance`); a no-op elsewhere.
 */
@Suppress("FunctionNaming")
@Composable
public fun DecoratedWindowScope.WindowAppearance(mode: WindowAppearanceMode) {
    // Tao always provides a [TaoDecoratedWindowScope] at runtime — same
    // contract as `BasicTitleBar`.
    val taoWindow = (this as TaoDecoratedWindowScope).window
    LaunchedEffect(taoWindow, mode) {
        if (Platform.Current != Platform.MacOS || !NativeMetalBridge.isLoaded) {
            return@LaunchedEffect
        }
        val nsView = NativeTaoBridge.nativeNsViewHandle(taoWindow.handle)
        if (nsView != 0L) {
            NativeMetalBridge.nativeSetWindowAppearance(nsView, mode.ordinal)
        }
    }
}
