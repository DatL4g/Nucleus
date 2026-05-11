package dev.nucleusframework.systeminfo

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.systeminfo.linux.LinuxSystemInfo
import dev.nucleusframework.systeminfo.macos.MacOsSystemInfo
import dev.nucleusframework.systeminfo.model.BatteryInfo
import dev.nucleusframework.systeminfo.model.ComponentInfo
import dev.nucleusframework.systeminfo.model.ConnectivityInfo
import dev.nucleusframework.systeminfo.model.CpuGlobalInfo
import dev.nucleusframework.systeminfo.model.DiskInfo
import dev.nucleusframework.systeminfo.model.GpuInfo
import dev.nucleusframework.systeminfo.model.MemoryInfo
import dev.nucleusframework.systeminfo.model.MotherboardInfo
import dev.nucleusframework.systeminfo.model.NetworkInterfaceInfo
import dev.nucleusframework.systeminfo.model.OsInfo
import dev.nucleusframework.systeminfo.model.ProcessInfo
import dev.nucleusframework.systeminfo.model.ProductInfo
import dev.nucleusframework.systeminfo.model.UserInfo
import dev.nucleusframework.systeminfo.windows.WindowsSystemInfo

@Suppress("TooManyFunctions")
object SystemInfo {
    private val delegate: PlatformSystemInfo? =
        when (Platform.Current) {
            Platform.Windows -> WindowsSystemInfo
            Platform.MacOS -> MacOsSystemInfo
            Platform.Linux -> LinuxSystemInfo
            else -> null
        }

    fun isAvailable(): Boolean = delegate?.isAvailable() ?: false

    fun osInfo(): OsInfo? = delegate?.osInfo()

    fun memoryInfo(): MemoryInfo? = delegate?.memoryInfo()

    fun cpuInfo(): CpuGlobalInfo? = delegate?.cpuInfo()

    fun disks(): List<DiskInfo> = delegate?.disks() ?: emptyList()

    fun components(): List<ComponentInfo> = delegate?.components() ?: emptyList()

    fun networks(): List<NetworkInterfaceInfo> = delegate?.networks() ?: emptyList()

    fun users(): List<UserInfo> = delegate?.users() ?: emptyList()

    fun motherboard(): MotherboardInfo? = delegate?.motherboard()

    fun product(): ProductInfo? = delegate?.product()

    fun processes(): List<ProcessInfo> = delegate?.processes() ?: emptyList()

    fun process(pid: Long): ProcessInfo? = delegate?.process(pid)

    fun gpus(): List<GpuInfo> = delegate?.gpus() ?: emptyList()

    fun batteryInfo(): BatteryInfo? = delegate?.batteryInfo()

    fun idleTime(): Long = delegate?.idleTime() ?: -1L

    fun connectivityInfo(): ConnectivityInfo? = delegate?.connectivityInfo()
}
