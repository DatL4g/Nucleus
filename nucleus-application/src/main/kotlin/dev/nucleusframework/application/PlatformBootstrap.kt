package dev.nucleusframework.application

import dev.nucleusframework.core.runtime.Platform

/**
 * Runs platform-specific priming that must happen before any window is created:
 *
 * - `AutoLaunch.wasStartedAtLogin(args)` — caches the "was-started-at-login"
 *   verdict from the original CLI args. After this point any consumer can call
 *   `AutoLaunch.wasStartedAtLogin(emptyArray())` and still get the right answer.
 * - `WindowsJumpListManager.setProcessAppId()` — sets the AUMID on Windows.
 *   Required before the first window is shown in non-APPX builds, otherwise
 *   jump lists silently break.
 *
 * Both calls go through reflection so [nucleusApplication] stays decoupled from
 * the `autolaunch` and `launcher-windows` modules — they kick in only when the
 * user adds those modules to their classpath.
 */
internal fun primePlatformIntegrations(args: Array<String>) {
    primeAutoLaunchCache(args)
    if (Platform.Current == Platform.Windows) {
        primeWindowsAumid()
    }
}

private fun primeAutoLaunchCache(args: Array<String>) {
    runCatching {
        val cls = Class.forName("dev.nucleusframework.autolaunch.AutoLaunch")
        val instance = cls.getField("INSTANCE").get(null)
        cls.getMethod("wasStartedAtLogin", Array<String>::class.java).invoke(instance, args)
    }
}

private fun primeWindowsAumid() {
    runCatching {
        val cls = Class.forName("dev.nucleusframework.launcher.windows.WindowsJumpListManager")
        val instance = cls.getField("INSTANCE").get(null)
        cls.getMethod("setProcessAppId", String::class.java).invoke(instance, null)
    }
}
