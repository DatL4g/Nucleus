@file:Suppress("MagicNumber")

package systeminfodemo.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.letsPlot.Stat
import org.jetbrains.letsPlot.compose.PlotPanel
import org.jetbrains.letsPlot.geom.geomBar
import org.jetbrains.letsPlot.letsPlot
import org.jetbrains.letsPlot.scale.scaleFillIdentity
import org.jetbrains.letsPlot.scale.scaleYContinuous
import org.jetbrains.letsPlot.tooltips.layerTooltips
import systeminfodemo.ui.InfoRow
import systeminfodemo.ui.LineChart
import systeminfodemo.ui.ProgressBar
import systeminfodemo.ui.SectionCard
import systeminfodemo.ui.chartTheme
import systeminfodemo.viewmodel.SystemInfoState

private fun coreColor(usage: Float): String =
    when {
        usage < 30f -> "#5AB869"
        usage < 70f -> "#D4A843"
        else -> "#F75464"
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CpuPanel(state: SystemInfoState) {
    val cpu = state.cpuInfo

    // CPU Temperatures
    val cpuTemps = state.components.filter { it.label.contains("cpu", ignoreCase = true) && it.temperature != null }
    val avgTemp = if (cpuTemps.isNotEmpty()) cpuTemps.mapNotNull { it.temperature }.average().toFloat() else null

    // Top row: usage history + details
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionCard("CPU Usage History", modifier = Modifier.weight(1f)) {
            if (state.cpuHistory.size >= 2) {
                LineChart(data = state.cpuHistory, lineColor = Color(0xFF2196F3))
            } else {
                Text("Collecting data...")
            }
        }

        SectionCard("CPU Details", modifier = Modifier.weight(0.6f)) {
            InfoRow("Model", cpu?.cpus?.firstOrNull()?.brand)
            InfoRow("Vendor", cpu?.cpus?.firstOrNull()?.vendorId)
            InfoRow("Physical Cores", cpu?.physicalCoreCount?.toString())
            InfoRow("Logical Cores", cpu?.cpus?.size?.toString())
            InfoRow("Global Usage", cpu?.let { "%.1f%%".format(it.globalCpuUsage) })
            avgTemp?.let { InfoRow("Global Temperature", "%.1f\u00B0C".format(it)) }
        }
    }

    // Temperature History
    if (state.cpuTempHistory.size >= 2) {
        SectionCard("CPU Temperature History") {
            LineChart(data = state.cpuTempHistory, lineColor = Color(0xFFFF7043))
        }
    }

    // Per-core bar chart with color-coded bars
    SectionCard("Per-Core Usage") {
        if (cpu != null && cpu.cpus.isNotEmpty()) {
            val usages = cpu.cpus.map { it.cpuUsage }
            val colors = remember(usages) { usages.map { coreColor(it) } }

            val plotData =
                mapOf(
                    "core" to cpu.cpus.indices.map { "cpu$it" },
                    "usage" to usages,
                    "color" to colors,
                )

            val figure =
                letsPlot(plotData) {
                    x = "core"
                    y = "usage"
                    fill = "color"
                } +
                    geomBar(
                        stat = Stat.identity,
                        width = 0.75,
                        alpha = 0.9,
                        tooltips =
                            layerTooltips()
                                .line("@core")
                                .format("usage", ".1f")
                                .line("@usage%"),
                    ) +
                    scaleFillIdentity() +
                    scaleYContinuous(limits = Pair(0, 100), breaks = listOf(0, 25, 50, 75, 100)) +
                    chartTheme()

            PlotPanel(
                figure = figure,
                modifier = Modifier.fillMaxWidth().height(200.dp),
            ) {}
        }
    }

    // Per-core grid: each core as a small tile
    SectionCard("Per-Core Details") {
        if (cpu != null) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                cpu.cpus.forEachIndexed { i, c ->
                    if (c.brand.isNotEmpty()) {
                        Column(
                            Modifier.width(160.dp).padding(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("cpu$i", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "%.1f%%".format(c.cpuUsage),
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            ProgressBar(
                                progress = c.cpuUsage / 100f,
                                color = cpuColor(c.cpuUsage),
                            )
                            val coreTemp = cpuTemps.getOrNull(i)?.temperature
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    "${c.frequency} MHz",
                                    fontFamily = FontFamily.Monospace,
                                    color =
                                        org.jetbrains.jewel.foundation.theme.JewelTheme.contentColor
                                            .copy(alpha = 0.5f),
                                )
                                coreTemp?.let {
                                    Text(
                                        "%.1f\u00B0C".format(it),
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = cpuTempColor(it),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun cpuTempColor(temp: Float): Color =
    when {
        temp < 60f -> Color(0xFF5AB869)
        temp < 80f -> Color(0xFFD4A843)
        else -> Color(0xFFF75464)
    }
