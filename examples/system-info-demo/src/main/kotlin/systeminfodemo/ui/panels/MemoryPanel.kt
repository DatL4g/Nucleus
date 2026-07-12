@file:Suppress("MagicNumber")

package systeminfodemo.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.typography
import systeminfodemo.ui.InfoRow
import systeminfodemo.ui.LineChart
import systeminfodemo.ui.SectionCard
import systeminfodemo.ui.formatBytes
import systeminfodemo.viewmodel.SystemInfoState

@Composable
fun MemoryPanel(state: SystemInfoState) {
    val mem = state.memoryInfo

    SectionCard("Memory Usage History") {
        if (state.memoryHistory.size >= 2) {
            LineChart(data = state.memoryHistory, lineColor = Color(0xFF9C27B0))
        } else {
            Text("Collecting data...")
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionCard("RAM Distribution") {
                if (mem != null && mem.totalMemory > 0) {
                    val pct = mem.usedMemory.toFloat() / mem.totalMemory
                    val usedLabel = formatBytes(mem.usedMemory)
                    val totalLabel = formatBytes(mem.totalMemory)

                    Box(
                        modifier = Modifier.fillMaxWidth().height(230.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val usedColor = Color(0xFF2196F3)
                        val trackColor = Color(0xFF404040)

                        Canvas(modifier = Modifier.size(180.dp)) {
                            val strokeWidth = 18.dp.toPx()
                            val padding = strokeWidth / 2
                            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                            val topLeft = Offset(padding, padding)

                            // Background track
                            drawArc(
                                color = trackColor,
                                startAngle = -90f,
                                sweepAngle = 360f,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                            )

                            // Used arc
                            drawArc(
                                color = usedColor,
                                startAngle = -90f,
                                sweepAngle = 360f * pct,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "%.0f%%".format(pct * 100f),
                                style = JewelTheme.typography.h1TextStyle,
                            )
                            Text(
                                "$usedLabel / $totalLabel",
                                color = JewelTheme.contentColor.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionCard("RAM Details") {
                InfoRow("Total", mem?.let { formatBytes(it.totalMemory) })
                InfoRow("Used", mem?.let { formatBytes(it.usedMemory) })
                InfoRow("Free", mem?.let { formatBytes(it.freeMemory) })
                InfoRow("Available", mem?.let { formatBytes(it.availableMemory) })
            }

            SectionCard("Swap") {
                InfoRow("Total", mem?.let { formatBytes(it.totalSwap) })
                InfoRow("Used", mem?.let { formatBytes(it.usedSwap) })
                InfoRow("Free", mem?.let { formatBytes(it.freeSwap) })
            }
        }
    }
}
