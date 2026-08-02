package dev.nucleusframework.scaffolddemo

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector

/** A page of the demo, one per chrome primitive. */
enum class DemoSection(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
) {
    Overview(
        title = "Overview",
        subtitle = "What this window is made of",
        icon = Icons.Rounded.Dashboard,
    ),
    Placement(
        title = "Placement",
        subtitle = "Docked bar or content under it",
        icon = Icons.Rounded.VerticalSplit,
    ),
    Glass(
        title = "Glass region",
        subtitle = "System material behind a panel",
        icon = Icons.Rounded.BlurOn,
    ),
    Appearance(
        title = "Appearance",
        subtitle = "Theme and native surfaces",
        icon = Icons.Rounded.DarkMode,
    ),
    Interaction(
        title = "Drag & controls",
        subtitle = "Move regions and window buttons",
        icon = Icons.Rounded.OpenWith,
    ),
}

/**
 * Everything the chrome reacts to, hoisted so the sidebar, the toolbar and the
 * pages all drive the same window.
 */
@Immutable
class DemoState {
    var section by mutableStateOf(DemoSection.Overview)
    var overlay by mutableStateOf(true)
    var glassSidebar by mutableStateOf(true)
    var darkTheme by mutableStateOf(true)
    var dragFromContent by mutableStateOf(false)
}
