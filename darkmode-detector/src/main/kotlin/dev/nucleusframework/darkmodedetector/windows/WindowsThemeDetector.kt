package dev.nucleusframework.darkmodedetector.windows

import dev.nucleusframework.darkmodedetector.IDarkModeDetector
import dev.nucleusframework.darkmodedetector.debugln
import java.util.function.Consumer

private const val TAG = "WindowsThemeDetector"

/**
 * WindowsThemeDetector uses a JNI native library to read the Windows registry value:
 * HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize\AppsUseLightTheme
 *
 * If this value = 0 => Dark mode. If this value = 1 => Light mode.
 *
 * The detector monitors the registry for changes in real-time via a native
 * background thread using RegNotifyChangeKeyValue in async mode.
 */
internal object WindowsThemeDetector : IDarkModeDetector {
    init {
        debugln(TAG) { "Initializing Windows theme observer via JNI" }
        NativeWindowsBridge.nativeStartObserving()
    }

    override fun isDark(): Boolean = NativeWindowsBridge.nativeIsDark()

    override fun registerListener(listener: Consumer<Boolean>) {
        NativeWindowsBridge.registerListener(listener)
    }

    override fun removeListener(listener: Consumer<Boolean>) {
        NativeWindowsBridge.removeListener(listener)
    }
}
