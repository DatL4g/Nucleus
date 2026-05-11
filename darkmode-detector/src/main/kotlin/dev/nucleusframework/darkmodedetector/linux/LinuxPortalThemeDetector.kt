package dev.nucleusframework.darkmodedetector.linux

import dev.nucleusframework.darkmodedetector.IDarkModeDetector
import dev.nucleusframework.darkmodedetector.debugln
import java.util.function.Consumer

private const val TAG = "LinuxPortalThemeDetector"

/**
 * LinuxPortalThemeDetector uses a JNI native library to read the XDG Desktop Portal
 * color-scheme preference via the org.freedesktop.portal.Settings D-Bus interface.
 *
 * color-scheme value 1 means prefer-dark; all other values mean light/no preference.
 *
 * The detector also monitors for SettingChanged signals in real-time via a background
 * D-Bus dispatch thread.
 */
internal object LinuxPortalThemeDetector : IDarkModeDetector {
    init {
        debugln(TAG) { "Initializing Linux portal theme observer via JNI" }
        NativeLinuxBridge.nativeStartObserving()
    }

    override fun isDark(): Boolean = NativeLinuxBridge.nativeIsDark()

    override fun registerListener(listener: Consumer<Boolean>) {
        NativeLinuxBridge.registerListener(listener)
    }

    override fun removeListener(listener: Consumer<Boolean>) {
        NativeLinuxBridge.removeListener(listener)
    }
}
