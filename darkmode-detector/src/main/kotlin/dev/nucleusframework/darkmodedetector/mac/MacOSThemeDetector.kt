package dev.nucleusframework.darkmodedetector.mac

import dev.nucleusframework.darkmodedetector.IDarkModeDetector
import dev.nucleusframework.darkmodedetector.debugln
import java.util.function.Consumer

private const val TAG = "MacOSThemeDetector"

/**
 * MacOSThemeDetector registers a native observer via JNI with NSDistributedNotificationCenter
 * to detect theme changes in macOS. It reads the system preference "AppleInterfaceStyle"
 * (which is "Dark" when in dark mode) from NSUserDefaults.
 */
internal object MacOSThemeDetector : IDarkModeDetector {
    init {
        debugln(TAG) { "Initializing macOS theme observer via JNI" }
        NativeDarkModeBridge.nativeStartObserving()
    }

    override fun isDark(): Boolean = NativeDarkModeBridge.nativeIsDark()

    override fun registerListener(listener: Consumer<Boolean>) {
        NativeDarkModeBridge.registerListener(listener)
    }

    override fun removeListener(listener: Consumer<Boolean>) {
        NativeDarkModeBridge.removeListener(listener)
    }
}
