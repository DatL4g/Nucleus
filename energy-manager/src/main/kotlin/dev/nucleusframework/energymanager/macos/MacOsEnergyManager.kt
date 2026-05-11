package dev.nucleusframework.energymanager.macos

import dev.nucleusframework.energymanager.EnergyManager
import dev.nucleusframework.energymanager.PlatformEnergyManager

internal object MacOsEnergyManager : PlatformEnergyManager {
    private fun callNative(block: () -> Int): EnergyManager.Result {
        if (!NativeMacOsEnergyBridge.isLoaded) {
            return EnergyManager.Result(false, -1, "Native library not loaded")
        }
        return try {
            val rc = block()
            if (rc == 0) {
                EnergyManager.Result(true)
            } else {
                EnergyManager.Result(false, rc, "Native call failed with error code $rc")
            }
        } catch (e: UnsatisfiedLinkError) {
            EnergyManager.Result(false, -1, "Exception: ${e.message}")
        }
    }

    override fun isAvailable(): Boolean =
        NativeMacOsEnergyBridge.isLoaded &&
            runCatching { NativeMacOsEnergyBridge.nativeIsSupported() }.getOrDefault(false)

    override fun enableEfficiencyMode() = callNative { NativeMacOsEnergyBridge.nativeEnableEfficiencyMode() }

    override fun disableEfficiencyMode() = callNative { NativeMacOsEnergyBridge.nativeDisableEfficiencyMode() }

    override fun enableLightEfficiencyMode(): EnergyManager.Result =
        callNative { NativeMacOsEnergyBridge.nativeEnableLightEfficiencyMode() }

    override fun disableLightEfficiencyMode(): EnergyManager.Result =
        callNative { NativeMacOsEnergyBridge.nativeDisableLightEfficiencyMode() }

    override fun enableThreadEfficiencyMode(): EnergyManager.Result =
        callNative { NativeMacOsEnergyBridge.nativeEnableThreadEfficiencyMode() }

    override fun disableThreadEfficiencyMode(): EnergyManager.Result =
        callNative { NativeMacOsEnergyBridge.nativeDisableThreadEfficiencyMode() }

    override fun keepScreenAwake() = callNative { NativeMacOsEnergyBridge.nativeKeepScreenAwake() }

    override fun releaseScreenAwake() = callNative { NativeMacOsEnergyBridge.nativeReleaseScreenAwake() }

    override fun isScreenAwakeActive(): Boolean =
        NativeMacOsEnergyBridge.isLoaded &&
            runCatching { NativeMacOsEnergyBridge.nativeIsScreenAwakeActive() }.getOrDefault(false)
}
