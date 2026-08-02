package dev.nucleusframework.scaffolddemo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.windowDragArea

/** The page for the selected section, plus its live switches. */
@Composable
internal fun DemoContent(
    demo: DemoState,
    contentPadding: PaddingValues,
) {
    val section = demo.section
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding =
            PaddingValues(
                start = 28.dp,
                end = 28.dp,
                top = contentPadding.calculateTopPadding() + 26.dp,
                bottom = 32.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            PageHeader(section)
            Spacer(Modifier.height(6.dp))
        }
        when (section) {
            DemoSection.Overview -> overviewPage(demo)
            DemoSection.Placement -> placementPage(demo)
            DemoSection.Glass -> glassPage(demo)
            DemoSection.Appearance -> appearancePage(demo)
            DemoSection.Interaction -> interactionPage(demo)
        }
    }
}

@Composable
private fun PageHeader(section: DemoSection) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(section.icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(21.dp))
        }
        Column {
            Text(
                text = section.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Text(
                text = section.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.overviewPage(demo: DemoState) {
    item {
        InfoCard(
            title = "One window, assembled from primitives",
            body =
                "This window has no system title bar. `WindowScaffold` hosts the toolbar above, " +
                    "the sidebar draws Apple's system material through the window, the toolbar is a " +
                    "drag region, and the minimise / maximise / close buttons come from Nucleus on " +
                    "the platforms that need them. Every switch on these pages changes the real " +
                    "window, live.",
        )
    }
    item {
        SwitchRow(
            title = "Content under the title bar",
            description = "TitleBarPlacement.Overlay — the content fills the window height.",
            checked = demo.overlay,
            onCheckedChange = { demo.overlay = it },
        )
    }
    item {
        SwitchRow(
            title = "Glass sidebar",
            description = "Modifier.windowGlassRegion — the desktop wallpaper shows through the pane.",
            checked = demo.glassSidebar,
            onCheckedChange = { demo.glassSidebar = it },
        )
    }
    item {
        PlatformNote()
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.placementPage(demo: DemoState) {
    item {
        InfoCard(
            title = "Docked or overlay",
            body =
                "Docked lays the bar out above the content, the classic decorated window. Overlay " +
                    "lets the content fill the whole window and floats the bar over it, handing the " +
                    "measured bar height back as content padding — that is what this page's list is " +
                    "inset by. The height is also published to the native layer, which centres the " +
                    "macOS traffic-lights against it and sizes the Windows caption zone.",
        )
    }
    item {
        SwitchRow(
            title = "Overlay placement",
            description = if (demo.overlay) "Content runs under the bar." else "Content starts below the bar.",
            checked = demo.overlay,
            onCheckedChange = { demo.overlay = it },
        )
    }
    item {
        HintCard("Scroll this list: in overlay mode the rows pass beneath the toolbar.")
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.glassPage(demo: DemoState) {
    item {
        InfoCard(
            title = "The System Settings sidebar, for real",
            body =
                "`Modifier.windowGlassRegion` hosts a genuine NSSplitViewController pane below the " +
                    "Compose surface, so AppKit applies the same desktop-tinted material as its own " +
                    "apps: the wallpaper shows through, windows behind never do, and light/dark, " +
                    "inactive-window desaturation and Reduce Transparency all behave natively. " +
                    "macOS only — elsewhere the pane simply paints a surface colour.",
        )
    }
    item {
        SwitchRow(
            title = "Glass sidebar",
            description = "Move the window over a colourful wallpaper to see it clearly.",
            checked = demo.glassSidebar,
            onCheckedChange = { demo.glassSidebar = it },
        )
    }
    item {
        HintCard(
            "Only the pane is a material. Apple keeps glass for that functional layer, never for a " +
                "whole window, so Nucleus exposes the region and nothing else.",
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.appearancePage(demo: DemoState) {
    item {
        InfoCard(
            title = "Native surfaces follow the app",
            body =
                "An app that picks its own theme has to tell the window, or the OS keeps deciding: " +
                    "a dark app on a light system would get light traffic-lights and a light sidebar " +
                    "material under dark content. `WindowAppearance` forces the window's NSAppearance " +
                    "and reverts to the system setting when it leaves the composition.",
        )
    }
    item {
        SwitchRow(
            title = "Dark theme",
            description = "Watch the traffic-lights and the sidebar material follow.",
            checked = demo.darkTheme,
            onCheckedChange = { demo.darkTheme = it },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.interactionPage(demo: DemoState) {
    item {
        InfoCard(
            title = "Dragging, and opting out",
            body =
                "`Modifier.windowDragArea` turns any component into a window move region, with " +
                    "double-click to maximise; interactive children opt out by consuming the press. " +
                    "Detectors that only claim the pointer once it moves — scrollbars, sliders — need " +
                    "`Modifier.noWindowDrag` instead, which every sidebar row here uses.",
        )
    }
    item {
        SwitchRow(
            title = "Make this panel draggable",
            description = "Adds windowDragArea to the strip below — drag it to move the window.",
            checked = demo.dragFromContent,
            onCheckedChange = { demo.dragFromContent = it },
        )
    }
    item {
        DragSandbox(demo)
    }
    item {
        InfoCard(
            title = "Window controls",
            body =
                "`WindowControls` renders the platform's own buttons — Fluent on Windows, Adwaita or " +
                    "Breeze on Linux, following the desktop's button-layout for order and side — while " +
                    "Nucleus keeps the semantics. On macOS it draws nothing and reserves the " +
                    "traffic-light footprint instead, which is the inset this toolbar pads itself by.",
        )
    }
}

@Composable
private fun DragSandbox(demo: DemoState) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(76.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (demo.dragFromContent) {
                        colors.primary.copy(alpha = 0.14f)
                    } else {
                        colors.surfaceContainerHigh
                    },
                ).then(if (demo.dragFromContent) Modifier.windowDragArea() else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text =
                if (demo.dragFromContent) {
                    "Drag me — the window follows. Double-click to maximise."
                } else {
                    "Inert panel. Turn the switch on to make it a drag region."
                },
            style = MaterialTheme.typography.bodyMedium,
            color = if (demo.dragFromContent) colors.primary else colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlatformNote() {
    val platform =
        when (Platform.Current) {
            Platform.MacOS -> "macOS — native traffic-lights, glass regions and backdrop are live here."
            Platform.Windows -> "Windows — Fluent caption buttons are drawn by Nucleus; glass is a no-op."
            else -> "Linux — Adwaita/Breeze buttons follow the desktop's button-layout; glass is a no-op."
        }
    HintCard(platform)
}

@Composable
private fun InfoCard(
    title: String,
    body: String,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(16.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun HintCard(text: String) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.primary.copy(alpha = 0.09f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}
