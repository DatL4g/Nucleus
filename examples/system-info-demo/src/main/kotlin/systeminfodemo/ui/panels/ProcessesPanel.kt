@file:Suppress("MagicNumber", "MaxLineLength", "CyclomaticComplexMethod", "LongMethod")

package systeminfodemo.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import systeminfodemo.ui.SectionCard
import systeminfodemo.ui.formatBytes
import systeminfodemo.viewmodel.SystemInfoState

private enum class SortColumn { Pid, Name, Memory, Cpu, Status }

private enum class SortDirection { Asc, Desc }

private const val MAX_VISIBLE = 100

@Composable
fun ProcessesPanel(state: SystemInfoState) {
    var search by remember { mutableStateOf("") }
    var sortCol by remember { mutableStateOf(SortColumn.Memory) }
    var sortDir by remember { mutableStateOf(SortDirection.Desc) }

    val filtered =
        state.processes.filter { proc ->
            if (search.isBlank()) {
                true
            } else {
                proc.name.contains(search, ignoreCase = true) ||
                    proc.pid.toString().contains(search) ||
                    (proc.exe ?: "").contains(search, ignoreCase = true)
            }
        }

    val sorted =
        when (sortCol) {
            SortColumn.Pid ->
                if (sortDir ==
                    SortDirection.Asc
                ) {
                    filtered.sortedBy { it.pid }
                } else {
                    filtered.sortedByDescending { it.pid }
                }
            SortColumn.Name ->
                if (sortDir ==
                    SortDirection.Asc
                ) {
                    filtered.sortedBy { it.name.lowercase() }
                } else {
                    filtered.sortedByDescending { it.name.lowercase() }
                }
            SortColumn.Memory ->
                if (sortDir ==
                    SortDirection.Asc
                ) {
                    filtered.sortedBy { it.memory }
                } else {
                    filtered.sortedByDescending { it.memory }
                }
            SortColumn.Cpu ->
                if (sortDir ==
                    SortDirection.Asc
                ) {
                    filtered.sortedBy { it.cpuUsage }
                } else {
                    filtered.sortedByDescending { it.cpuUsage }
                }
            SortColumn.Status ->
                if (sortDir ==
                    SortDirection.Asc
                ) {
                    filtered.sortedBy { it.status }
                } else {
                    filtered.sortedByDescending { it.status }
                }
        }.take(MAX_VISIBLE)

    fun toggleSort(col: SortColumn) {
        if (sortCol == col) {
            sortDir = if (sortDir == SortDirection.Asc) SortDirection.Desc else SortDirection.Asc
        } else {
            sortCol = col
            sortDir = SortDirection.Desc
        }
    }

    SectionCard("Processes (${filtered.size} of ${state.processes.size})") {
        // Search bar
        val searchState =
            androidx.compose.foundation.text.input
                .rememberTextFieldState(search)
        androidx.compose.runtime.LaunchedEffect(searchState.text) {
            search = searchState.text.toString()
        }
        org.jetbrains.jewel.ui.component.TextField(
            state = searchState,
            placeholder = { Text("Search by name, PID, or executable...") },
            modifier = Modifier.fillMaxWidth(),
        )

        // Header
        val headerBg =
            JewelTheme.globalColors.outlines.focused
                .copy(alpha = 0.08f)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(headerBg)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SortableHeader(
                "PID",
                SortColumn.Pid,
                sortCol,
                sortDir,
                Modifier.weight(0.08f),
            ) { toggleSort(SortColumn.Pid) }
            SortableHeader(
                "Name",
                SortColumn.Name,
                sortCol,
                sortDir,
                Modifier.weight(0.22f),
            ) { toggleSort(SortColumn.Name) }
            SortableHeader(
                "Memory",
                SortColumn.Memory,
                sortCol,
                sortDir,
                Modifier.weight(0.12f),
            ) { toggleSort(SortColumn.Memory) }
            SortableHeader(
                "CPU%",
                SortColumn.Cpu,
                sortCol,
                sortDir,
                Modifier.weight(0.08f),
            ) { toggleSort(SortColumn.Cpu) }
            SortableHeader(
                "Status",
                SortColumn.Status,
                sortCol,
                sortDir,
                Modifier.weight(0.1f),
            ) { toggleSort(SortColumn.Status) }
            Text("Executable", fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.4f))
        }

        // Rows
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            sorted.forEach { proc ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(proc.pid.toString(), fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.08f))
                    Text(proc.name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.22f))
                    Text(formatBytes(proc.memory), fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.12f))
                    Text(
                        "%.1f".format(proc.cpuUsage),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(0.08f),
                    )
                    Text(proc.status, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.1f))
                    Text(
                        proc.exe?.substringAfterLast('/') ?: "",
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(0.4f),
                        color = JewelTheme.contentColor.copy(alpha = 0.6f),
                    )
                }
            }
        }

        if (filtered.size > MAX_VISIBLE) {
            Text(
                "Showing $MAX_VISIBLE of ${filtered.size} processes",
                color = JewelTheme.contentColor.copy(alpha = 0.5f),
            )
        }
    }

    if (state.processes.isEmpty()) {
        Text("No processes detected")
    }
}

@Composable
private fun SortableHeader(
    label: String,
    column: SortColumn,
    currentSort: SortColumn,
    currentDir: SortDirection,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val arrow =
        if (currentSort == column) {
            if (currentDir == SortDirection.Asc) " \u25B2" else " \u25BC"
        } else {
            ""
        }
    Text(
        "$label$arrow",
        fontWeight = FontWeight.Bold,
        modifier = modifier.clickable { onClick() },
    )
}
