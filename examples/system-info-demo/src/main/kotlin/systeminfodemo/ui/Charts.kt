@file:Suppress("MagicNumber", "MaxLineLength")

package systeminfodemo.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import org.jetbrains.letsPlot.Stat
import org.jetbrains.letsPlot.compose.PlotPanel
import org.jetbrains.letsPlot.geom.geomArea
import org.jetbrains.letsPlot.geom.geomBar
import org.jetbrains.letsPlot.geom.geomLine
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.letsPlot
import org.jetbrains.letsPlot.scale.scaleFillIdentity
import org.jetbrains.letsPlot.scale.scaleXContinuous
import org.jetbrains.letsPlot.scale.scaleYContinuous
import org.jetbrains.letsPlot.themes.elementBlank
import org.jetbrains.letsPlot.themes.elementLine
import org.jetbrains.letsPlot.themes.elementRect
import org.jetbrains.letsPlot.themes.elementText
import org.jetbrains.letsPlot.themes.theme
import org.jetbrains.letsPlot.themes.themeNone
import org.jetbrains.letsPlot.tooltips.layerTooltips
import org.jetbrains.letsPlot.tooltips.tooltipsNone

internal fun Color.toHex(): String {
    val argb = toArgb()
    return "#%06X".format(argb and 0xFFFFFF)
}

internal fun chartTheme(gridColor: String = "#333333") =
    themeNone() +
        theme(
            plotBackground = elementRect(fill = "transparent", color = "transparent"),
            panelBackground = elementRect(fill = "transparent"),
            panelGrid = elementBlank(),
            panelGridMajorY = elementLine(color = gridColor, size = 0.3),
            axisTextY = elementText(color = "#888888", size = 9),
            axisTextX = elementBlank(),
            axisTicksX = elementBlank(),
            axisTicksY = elementBlank(),
            axisLineX = elementBlank(),
            axisLineY = elementBlank(),
            axisTitleX = elementBlank(),
            axisTitleY = elementBlank(),
            legendBackground = elementBlank(),
            legendText = elementBlank(),
            legendTitle = elementBlank(),
            legendKey = elementBlank(),
            plotMargin = listOf(4, 8, 4, 0),
        )

@Composable
fun LineChart(
    data: List<Float>,
    lineColor: Color,
    modifier: Modifier = Modifier,
) {
    if (data.size < 2) return
    val hex = remember(lineColor) { lineColor.toHex() }
    val fadedHex = remember(lineColor) { lineColor.copy(alpha = 0.12f).toHex() }
    val dotHex = remember(lineColor) { lineColor.copy(alpha = 0.9f).toHex() }

    val lastIndex = data.size - 1
    val lastValue = data.last()

    val plotData =
        mapOf(
            "x" to data.indices.toList(),
            "y" to data,
        )

    val figure =
        letsPlot(plotData) {
            x = "x"
            y = "y"
        } +
            // Filled area under the curve
            geomArea(
                fill = fadedHex,
                tooltips = tooltipsNone,
            ) +
            // Main line
            geomLine(
                color = hex,
                size = 1.8,
                tooltips =
                    layerTooltips()
                        .format("y", ".1f")
                        .line("@y%"),
            ) +
            // Latest value dot
            geomPoint(
                x = lastIndex,
                y = lastValue,
                color = dotHex,
                fill = hex,
                size = 4.0,
                shape = 21,
                stroke = 1.5,
                tooltips = tooltipsNone,
            ) +
            scaleYContinuous(limits = Pair(0, 100), breaks = listOf(0, 25, 50, 75, 100)) +
            scaleXContinuous(limits = Pair(0, lastIndex.coerceAtLeast(1))) +
            chartTheme()

    PlotPanel(
        figure = figure,
        modifier = modifier.fillMaxWidth().height(200.dp),
    ) {}
}

@Composable
fun BarChart(
    xData: List<Float>,
    yData: List<Float>,
    barColor: Color,
    maxY: Float = 100f,
    modifier: Modifier = Modifier,
) {
    if (xData.isEmpty()) return
    val hex = remember(barColor) { barColor.toHex() }

    val plotData =
        mapOf(
            "x" to xData.map { it.toInt() },
            "y" to yData,
        )

    val figure =
        letsPlot(plotData) {
            x = "x"
            y = "y"
        } +
            geomBar(
                stat = Stat.identity,
                fill = hex,
                color = hex,
                width = 0.75,
                alpha = 0.85,
                tooltips =
                    layerTooltips()
                        .format("y", ".1f")
                        .line("#@x: @y%"),
            ) +
            scaleFillIdentity() +
            scaleYContinuous(limits = Pair(0, maxY)) +
            chartTheme()

    PlotPanel(
        figure = figure,
        modifier = modifier.fillMaxWidth().height(200.dp),
    ) {}
}
