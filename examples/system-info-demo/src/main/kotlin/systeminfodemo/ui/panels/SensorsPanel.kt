@file:Suppress("MagicNumber")

package systeminfodemo.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.letsPlot.Stat
import org.jetbrains.letsPlot.compose.PlotPanel
import org.jetbrains.letsPlot.geom.geomBar
import org.jetbrains.letsPlot.letsPlot
import org.jetbrains.letsPlot.scale.scaleFillIdentity
import org.jetbrains.letsPlot.scale.scaleXDiscrete
import org.jetbrains.letsPlot.scale.scaleYContinuous
import org.jetbrains.letsPlot.tooltips.layerTooltips
import systeminfodemo.ui.SectionCard
import systeminfodemo.ui.chartTheme
import systeminfodemo.viewmodel.SystemInfoState

private fun tempColor(
    temp: Float,
    maxTemp: Float,
): String {
    val ratio = (temp / maxTemp).coerceIn(0f, 1f)
    return when {
        ratio < 0.4f -> "#5AB869"
        ratio < 0.7f -> "#FF9800"
        else -> "#F75464"
    }
}

@Composable
fun SensorsPanel(state: SystemInfoState) {
    val sensors = state.components.filter { it.temperature != null }

    if (sensors.isNotEmpty()) {
        val maxTemp = sensors.mapNotNull { it.critical ?: it.max ?: it.temperature }.maxOrNull() ?: 100f
        val cappedMax = maxTemp.coerceAtLeast(50f)

        val labels = sensors.map { it.label }
        val temps = sensors.map { it.temperature ?: 0f }
        val colors = remember(temps, cappedMax) { temps.map { tempColor(it, cappedMax) } }

        val plotData =
            mapOf(
                "sensor" to labels,
                "temp" to temps,
                "color" to colors,
            )

        SectionCard("Temperature Overview") {
            val figure =
                letsPlot(plotData) {
                    x = "sensor"
                    y = "temp"
                    fill = "color"
                } +
                    geomBar(
                        stat = Stat.identity,
                        width = 0.7,
                        alpha = 0.9,
                        tooltips =
                            layerTooltips()
                                .line("@sensor")
                                .format("temp", ".1f")
                                .line("@temp\u00B0C"),
                    ) +
                    scaleFillIdentity() +
                    scaleYContinuous(limits = Pair(0, cappedMax)) +
                    scaleXDiscrete() +
                    chartTheme()

            PlotPanel(
                figure = figure,
                modifier = Modifier.fillMaxWidth().height(220.dp),
            ) {}
        }
    }

    SectionCard("All Sensors") {
        // Header
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sensor", fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.4f))
            Text("Temp", fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.2f))
            Text("Max", fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.2f))
            Text("Critical", fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.2f))
        }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.components.forEach { comp ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(comp.label, modifier = Modifier.weight(0.4f))
                    Text(
                        comp.temperature?.let { "%.1f\u00B0C".format(it) } ?: "N/A",
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(0.2f),
                    )
                    Text(
                        comp.max?.let { "%.1f\u00B0C".format(it) } ?: "N/A",
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(0.2f),
                    )
                    Text(
                        comp.critical?.let { "%.1f\u00B0C".format(it) } ?: "N/A",
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(0.2f),
                    )
                }
            }
        }
    }

    if (state.components.isEmpty()) {
        Text("No temperature sensors detected")
    }
}
