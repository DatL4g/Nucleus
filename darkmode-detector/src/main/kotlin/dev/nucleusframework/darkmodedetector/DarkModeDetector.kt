package dev.nucleusframework.darkmodedetector

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.darkmodedetector.linux.LinuxPortalThemeDetector
import dev.nucleusframework.darkmodedetector.mac.MacOSThemeDetector
import dev.nucleusframework.darkmodedetector.windows.WindowsThemeDetector
import java.util.function.Consumer

interface IDarkModeDetector {
    fun isDark(): Boolean

    fun registerListener(listener: Consumer<Boolean>)

    fun removeListener(listener: Consumer<Boolean>)
}

object NoopDarkModeDetector : IDarkModeDetector {
    override fun isDark(): Boolean = false

    override fun registerListener(listener: Consumer<Boolean>) = Unit

    override fun removeListener(listener: Consumer<Boolean>) = Unit
}

public fun getPlatformDarkModeDetector(): IDarkModeDetector =
    when (Platform.Current) {
        Platform.MacOS -> MacOSThemeDetector
        Platform.Windows -> WindowsThemeDetector
        Platform.Linux -> LinuxPortalThemeDetector
        else -> NoopDarkModeDetector
    }
