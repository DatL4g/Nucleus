package dev.nucleusframework.autolaunch.windows

import dev.nucleusframework.autolaunch.AutoLaunchBackend
import dev.nucleusframework.autolaunch.AutoLaunchResult
import dev.nucleusframework.autolaunch.AutoLaunchState

/**
 * Windows dispatcher. Chooses between MSIX StartupTask API and Win32 registry
 * at first access based on runtime packaged-context detection.
 */
internal object WindowsAutoLaunch : AutoLaunchBackend {
    private val delegate: AutoLaunchBackend by lazy { resolveDelegate() }

    override fun state(): AutoLaunchState = delegate.state()

    override fun enable(): AutoLaunchResult = delegate.enable()

    override fun disable(): AutoLaunchResult = delegate.disable()

    override fun wasStartedAtLogin(args: Array<String>): Boolean = delegate.wasStartedAtLogin(args)

    override fun openSystemSettings(): Boolean =
        try {
            Runtime.getRuntime().exec(arrayOf("cmd", "/c", "start", "ms-settings:startupapps"))
            true
        } catch (_: Exception) {
            false
        }

    private fun resolveDelegate(): AutoLaunchBackend {
        if (!NativeAutoLaunchBridge.isLoaded) return Win32RegistryBackend
        return if (NativeAutoLaunchBridge.isPackaged()) {
            MsixStartupTaskBackend
        } else {
            Win32RegistryBackend
        }
    }
}
