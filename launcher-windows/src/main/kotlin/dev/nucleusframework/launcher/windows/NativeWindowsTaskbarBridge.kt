package dev.nucleusframework.launcher.windows

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import java.awt.Window

private const val LIBRARY_NAME = "nucleus_launcher_windows"

internal object NativeWindowsTaskbarBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeWindowsTaskbarBridge::class.java)

    val isLoaded: Boolean get() = loaded

    // ---- HWND extraction (AWT interop) ----

    @JvmStatic
    external fun nativeGetHwnd(window: Window): Long

    // ---- Overlay Icon ----

    @JvmStatic
    external fun nativeSetOverlayIcon(
        hwnd: Long,
        iconType: Int,
        iconPath: String,
        iconIndex: Int,
        description: String,
    ): String?

    @JvmStatic
    external fun nativeClearOverlayIcon(hwnd: Long): String?

    // ---- Thumbnail Toolbar ----

    @Suppress("LongParameterList")
    @JvmStatic
    external fun nativeThumbBarSetButtons(
        hwnd: Long,
        ids: IntArray,
        tooltips: Array<String>,
        flags: IntArray,
        iconTypes: IntArray,
        iconPaths: Array<String>,
        iconIndices: IntArray,
        callback: Any?,
    ): String?

    @JvmStatic
    external fun nativeThumbBarUpdateButtons(
        hwnd: Long,
        ids: IntArray,
        tooltips: Array<String>,
        flags: IntArray,
        iconTypes: IntArray,
        iconPaths: Array<String>,
        iconIndices: IntArray,
    ): String?

    @JvmStatic
    external fun nativeThumbBarUnregister(hwnd: Long): String?
}
