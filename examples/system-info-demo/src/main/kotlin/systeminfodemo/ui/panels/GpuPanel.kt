@file:Suppress("MagicNumber")

package systeminfodemo.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import systeminfodemo.ui.InfoRow
import systeminfodemo.ui.LineChart
import systeminfodemo.ui.ProgressBar
import systeminfodemo.ui.SectionCard
import systeminfodemo.ui.formatBytes
import systeminfodemo.viewmodel.SystemInfoState

private fun vendorName(vendorId: Long): String =
    when (vendorId.toInt()) {
        0x10DE -> "NVIDIA"
        0x1002 -> "AMD"
        0x8086 -> "Intel"
        0x1414 -> "Microsoft"
        else -> "0x%04X".format(vendorId)
    }

@Composable
fun GpuPanel(state: SystemInfoState) {
    if (state.gpus.isEmpty()) {
        SectionCard("GPU") {
            InfoRow("Status", "No GPU detected")
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        state.gpus.forEachIndexed { index, gpu ->
            val title = if (state.gpus.size > 1) "GPU $index — ${gpu.name}" else gpu.name

            // Static info card
            SectionCard(title) {
                InfoRow("Vendor", vendorName(gpu.vendorId))
                InfoRow("Device ID", "0x%04X".format(gpu.deviceId))
                gpu.driverVersion?.let { InfoRow("Driver Version", it) }

                if (gpu.dedicatedVideoMemory > 0) {
                    InfoRow("Dedicated VRAM", formatBytes(gpu.dedicatedVideoMemory))
                }
                if (gpu.dedicatedSystemMemory > 0) {
                    InfoRow("Dedicated System Memory", formatBytes(gpu.dedicatedSystemMemory))
                }
                if (gpu.sharedSystemMemory > 0) {
                    InfoRow("Shared System Memory", formatBytes(gpu.sharedSystemMemory))
                }
            }

            // Live metrics card
            val hasLiveData = gpu.temperature != null || gpu.gpuUsage != null || gpu.memoryUsed != null
            if (hasLiveData) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Usage + Temperature
                    SectionCard("Usage & Temperature", modifier = Modifier.weight(1f)) {
                        gpu.gpuUsage?.let { usage ->
                            InfoRow("GPU Usage", "%.1f%%".format(usage))
                            ProgressBar(
                                progress = usage / 100f,
                                color = gpuUsageColor(usage),
                            )
                        }

                        gpu.temperature?.let { temp ->
                            InfoRow("Temperature", "%.0f\u00B0C".format(temp))
                            ProgressBar(
                                progress = (temp / 100f).coerceIn(0f, 1f),
                                color = gpuTempColor(temp),
                            )
                        }

                        gpu.fanSpeedPercent?.let { fan ->
                            InfoRow("Fan Speed", "%.0f%%".format(fan))
                        }

                        gpu.powerDrawWatts?.let { power ->
                            InfoRow("Power Draw", "%.1f W".format(power))
                        }

                        val gpuHistory = state.gpuUsageHistory.getOrElse(index) { emptyList() }
                        if (gpuHistory.size >= 2) {
                            LineChart(data = gpuHistory, lineColor = Color(0xFF9C6ADE))
                        }
                    }

                    // VRAM + Clocks
                    SectionCard("Memory & Clocks", modifier = Modifier.weight(1f)) {
                        gpu.memoryUsed?.let { used ->
                            val total = gpu.dedicatedVideoMemory
                            if (total > 0) {
                                val pct = used.toFloat() / total * 100f
                                InfoRow("VRAM Used", "${formatBytes(used)} / ${formatBytes(total)}")
                                ProgressBar(
                                    progress = pct / 100f,
                                    color = gpuVramColor(pct),
                                )
                            } else {
                                InfoRow("VRAM Used", formatBytes(used))
                            }
                        }

                        gpu.coreClockMhz?.let { clock ->
                            InfoRow("Core Clock", "$clock MHz")
                        }
                        gpu.memoryClockMhz?.let { clock ->
                            InfoRow("Memory Clock", "$clock MHz")
                        }

                        val tempHistory = state.gpuTempHistory.getOrElse(index) { emptyList() }
                        if (tempHistory.size >= 2) {
                            LineChart(data = tempHistory, lineColor = Color(0xFFF75464))
                        }
                    }
                }
            } else {
                // No live data available — show memory breakdown only
                val totalMemory = gpu.dedicatedVideoMemory + gpu.dedicatedSystemMemory + gpu.sharedSystemMemory
                if (totalMemory > 0) {
                    SectionCard("Memory") {
                        InfoRow("Total Memory", formatBytes(totalMemory))
                        val dedicatedFraction = gpu.dedicatedVideoMemory.toFloat() / totalMemory
                        ProgressBar(progress = dedicatedFraction, color = Color(0xFF9C6ADE))
                    }
                }
            }
        }
    }
}

private fun gpuUsageColor(usage: Float): Color =
    when {
        usage < 30f -> Color(0xFF5AB869)
        usage < 70f -> Color(0xFFD4A843)
        else -> Color(0xFFF75464)
    }

private fun gpuTempColor(temp: Float): Color =
    when {
        temp < 60f -> Color(0xFF5AB869)
        temp < 80f -> Color(0xFFD4A843)
        else -> Color(0xFFF75464)
    }

private fun gpuVramColor(percent: Float): Color =
    when {
        percent < 50f -> Color(0xFF9C6ADE)
        percent < 80f -> Color(0xFFD4A843)
        else -> Color(0xFFF75464)
    }
