@file:Suppress("MagicNumber")

package systeminfodemo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.typography

private val cardShape = RoundedCornerShape(12.dp)

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(cardShape)
            .border(1.dp, JewelTheme.globalColors.borders.normal, cardShape)
            .background(JewelTheme.globalColors.panelBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = JewelTheme.typography.h3TextStyle, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String?,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = JewelTheme.globalColors.text.info)
        Text(value ?: "N/A", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ProgressBar(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val trackColor = JewelTheme.globalColors.borders.normal
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(trackColor),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}

fun formatDuration(seconds: Long): String {
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    return buildString {
        if (days > 0) append("${days}d ")
        if (hours > 0) append("${hours}h ")
        append("${minutes}m")
    }
}
