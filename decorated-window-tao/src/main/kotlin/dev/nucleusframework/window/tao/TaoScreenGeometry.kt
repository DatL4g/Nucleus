package dev.nucleusframework.window.tao

import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDecoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDecoBridge
import dev.nucleusframework.window.tao.ffi.PopupNativeBridgeLinux
import dev.nucleusframework.core.runtime.Platform

/**
 * Public screen-geometry queries for the Tao backend. Gives external libraries
 * (e.g. the system-tray popup) access to the primary monitor's work area and
 * scale factor without going through AWT's `Toolkit`/`GraphicsEnvironment` —
 * neither exists on a no-AWT Tao runtime.
 *
 * All values follow the same conventions as the native deco bridges: physical
 * pixels, top-left origin, `[x, y, width, height]`.
 */
object TaoScreenGeometry {
    /**
     * The primary monitor's work area (full screen minus taskbar / menu bar /
     * dock) as `[x, y, width, height]` in physical pixels, or `null` when the
     * platform bridge is unavailable.
     *
     * On Linux the query is GDK-backed and needs a realized window: pass any
     * open [TaoWindow] (e.g. the popup being positioned). Without one
     * (panel-only tray apps) it falls back to the standalone-popup helper's
     * X11-side query (XRandR primary ∩ `_NET_WORKAREA`) — the same coordinate
     * space the standalone panel is positioned in. Ignored on Windows/macOS.
     */
    fun primaryMonitorWorkAreaPx(window: TaoWindow? = null): LongArray? =
        when (Platform.Current) {
            Platform.Windows ->
                if (NativeTaoWindowsDecoBridge.isLoaded) {
                    NativeTaoWindowsDecoBridge.nativeGetPrimaryMonitorWorkArea()
                } else {
                    null
                }
            Platform.MacOS ->
                if (NativeTaoMacOsDecoBridge.isLoaded) {
                    NativeTaoMacOsDecoBridge.nativeGetPrimaryMonitorWorkArea()
                } else {
                    null
                }
            Platform.Linux ->
                if (NativeTaoBridge.isLoaded && window != null) {
                    NativeTaoBridge.nativeLinuxPrimaryMonitorWorkArea(window.handle)
                } else if (PopupNativeBridgeLinux.isLoaded) {
                    PopupNativeBridgeLinux.nativePrimaryWorkArea()
                } else {
                    null
                }
            else -> null
        }

    /**
     * The primary monitor's scale factor (`1.0` on non-HiDPI displays), or
     * `1.0` when the platform bridge is unavailable. Same [window] contract as
     * [primaryMonitorWorkAreaPx] on Linux.
     */
    @Suppress("MagicNumber")
    fun primaryMonitorScaleFactor(window: TaoWindow? = null): Float {
        val scaleMilli =
            when (Platform.Current) {
                Platform.Windows ->
                    if (NativeTaoWindowsDecoBridge.isLoaded) {
                        NativeTaoWindowsDecoBridge.nativeGetPrimaryMonitorScaleMilli()
                    } else {
                        1000
                    }
                Platform.MacOS ->
                    if (NativeTaoMacOsDecoBridge.isLoaded) {
                        NativeTaoMacOsDecoBridge.nativeGetPrimaryMonitorScaleMilli()
                    } else {
                        1000
                    }
                Platform.Linux ->
                    if (NativeTaoBridge.isLoaded && window != null) {
                        NativeTaoBridge.nativeLinuxPrimaryMonitorScaleMilli(window.handle)
                    } else if (PopupNativeBridgeLinux.isLoaded) {
                        // Xft.dpi-based: the X11 coordinate space the panel
                        // (and this work-area fallback) lives in.
                        (PopupNativeBridgeLinux.nativeScale() * 1000).toInt()
                    } else {
                        1000
                    }
                else -> 1000
            }
        return scaleMilli.coerceAtLeast(1000) / 1000f
    }
}
