@file:Suppress("MagicNumber")

package systeminfodemo.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.component.Text
import systeminfodemo.ui.InfoRow
import systeminfodemo.ui.ProgressBar
import systeminfodemo.ui.SectionCard
import systeminfodemo.viewmodel.SystemInfoState

@Composable
fun BatteryPanel(state: SystemInfoState) {
    val battery = state.batteryInfo
    if (battery == null) {
        Text("No battery detected")
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionCard("Charge") {
            val pct = battery.stateOfCharge * 100f
            val color =
                when {
                    pct < 20f -> Color(0xFFF75464)
                    pct < 50f -> Color(0xFFD4A843)
                    else -> Color(0xFF5AB869)
                }
            InfoRow("State", battery.state.name)
            InfoRow("Charge", "%.1f%%".format(pct))
            ProgressBar(battery.stateOfCharge, color, Modifier.fillMaxWidth())
            InfoRow("Plugged In", if (battery.isPluggedIn) "Yes" else "No")
            battery.timeToFull?.let { InfoRow("Time to Full", "%d min".format(it)) }
            battery.timeToEmpty?.let { InfoRow("Time to Empty", "%d min".format(it)) }
        }

        SectionCard("Capacity") {
            InfoRow("Current", "${battery.currentCapacity} mAh")
            InfoRow("Maximum", "${battery.maxCapacity} mAh")
            InfoRow("Design", "${battery.designCapacity} mAh")
            InfoRow("Health", "%.1f%%".format(battery.health * 100f))
            ProgressBar(battery.health, Color(0xFF3574F0), Modifier.fillMaxWidth())
            InfoRow("Cycle Count", "${battery.cycleCount}")
        }

        SectionCard("Electrical") {
            InfoRow("Voltage", "${battery.voltage} mV")
            InfoRow("Amperage", "${battery.amperage} mA")
            battery.temperature?.let { InfoRow("Temperature", "%.1f\u00B0C".format(it)) }
        }

        SectionCard("Device") {
            InfoRow("Manufacturer", battery.manufacturer)
            InfoRow("Model", battery.modelName)
            InfoRow("Serial", battery.serialNumber)
        }
    }
}
