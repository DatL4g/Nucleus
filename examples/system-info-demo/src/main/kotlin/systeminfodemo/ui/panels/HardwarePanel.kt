package systeminfodemo.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import systeminfodemo.ui.InfoRow
import systeminfodemo.ui.SectionCard
import systeminfodemo.viewmodel.SystemInfoState

@Composable
fun HardwarePanel(state: SystemInfoState) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionCard("Motherboard") {
            val mb = state.motherboard
            InfoRow("Name", mb?.name)
            InfoRow("Vendor", mb?.vendorName)
            InfoRow("Version", mb?.version)
            InfoRow("Serial", mb?.serialNumber)
            InfoRow("Asset Tag", mb?.assetTag)
        }

        SectionCard("Product") {
            val prod = state.product
            InfoRow("Name", prod?.name)
            InfoRow("Family", prod?.family)
            InfoRow("Version", prod?.version)
            InfoRow("Vendor", prod?.vendorName)
            InfoRow("Serial", prod?.serialNumber)
            InfoRow("SKU", prod?.sku)
            InfoRow("UUID", prod?.uuid)
        }

        SectionCard("Users") {
            state.users.forEach { user ->
                InfoRow(user.name, "uid=${user.id} gid=${user.groupId} groups=[${user.groups.joinToString(", ")}]")
            }
        }
    }
}
