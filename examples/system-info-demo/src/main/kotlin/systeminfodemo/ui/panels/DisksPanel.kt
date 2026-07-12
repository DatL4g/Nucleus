@file:Suppress("MagicNumber")

package systeminfodemo.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import systeminfodemo.ui.InfoRow
import systeminfodemo.ui.ProgressBar
import systeminfodemo.ui.SectionCard
import systeminfodemo.ui.formatBytes
import systeminfodemo.viewmodel.SystemInfoState

@Composable
fun DisksPanel(state: SystemInfoState) {
    state.disks.forEach { disk ->
        val used = disk.totalSpace - disk.availableSpace
        val usedFraction = if (disk.totalSpace > 0) used.toFloat() / disk.totalSpace else 0f

        SectionCard(disk.mountPoint) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow("Device", disk.name)
                InfoRow("File System", disk.fileSystem)
                InfoRow("Type", disk.kind)
                InfoRow("Total", formatBytes(disk.totalSpace))
                InfoRow("Used", "${formatBytes(used)} (%.1f%%)".format(usedFraction * 100))
                InfoRow("Available", formatBytes(disk.availableSpace))
                InfoRow("Removable", if (disk.isRemovable) "Yes" else "No")
                InfoRow("Read-only", if (disk.isReadOnly) "Yes" else "No")
                ProgressBar(progress = usedFraction, color = diskColor(usedFraction))
            }
        }
    }

    if (state.disks.isEmpty()) {
        org.jetbrains.jewel.ui.component
            .Text("No disks detected")
    }
}
