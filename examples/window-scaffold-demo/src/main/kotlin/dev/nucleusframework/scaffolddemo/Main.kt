package dev.nucleusframework.scaffolddemo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nucleusframework.window.LocalWindowChromeInsets
import dev.nucleusframework.window.TitleBarPlacement
import dev.nucleusframework.window.WindowGlassRegionKind
import dev.nucleusframework.window.WindowScaffold
import dev.nucleusframework.window.tao.DecoratedWindow
import dev.nucleusframework.window.tao.taoApplication
import dev.nucleusframework.window.windowDragArea
import dev.nucleusframework.window.windowGlassRegion

private val ContentBackground = Color(0xFF1E212B)
private val SidebarFallback = Color(0xFF262A36)

fun main() =
    taoApplication {
        DecoratedWindow(
            onCloseRequest = ::exitApplication,
            title = "WindowScaffold Demo",
        ) {
            var overlay by remember { mutableStateOf(true) }
            var glass by remember { mutableStateOf(true) }

            WindowScaffold(
                titleBar = {
                    DemoToolbar(
                        overlay = overlay,
                        onToggle = { overlay = !overlay },
                        glass = glass,
                        onToggleGlass = { glass = !glass },
                    )
                },
                titleBarPlacement =
                    if (overlay) TitleBarPlacement.Overlay() else TitleBarPlacement.Docked,
            ) { padding ->
                Row(Modifier.fillMaxSize()) {
                    // System Settings-style sidebar: ONLY this region shows the
                    // desktop through the native sidebar material — it must not
                    // paint an opaque background of its own.
                    Sidebar(
                        modifier =
                            Modifier
                                .width(220.dp)
                                .fillMaxHeight()
                                .then(
                                    if (glass) {
                                        Modifier.windowGlassRegion(WindowGlassRegionKind.Sidebar)
                                    } else {
                                        Modifier.background(SidebarFallback)
                                    },
                                ),
                        contentPadding = padding,
                    )
                    // Main content stays fully opaque — the glass stops at the
                    // sidebar edge, like a real AppKit split view.
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxHeight().background(ContentBackground),
                        contentPadding = padding,
                    ) {
                        items(count = 60) { index ->
                            BasicText(
                                text = "Item $index — opaque content next to the glass sidebar",
                                style =
                                    TextStyle(
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 14.sp,
                                    ),
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }

@Composable
private fun Sidebar(
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    Column(
        modifier = modifier.padding(contentPadding).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val entries = listOf("General", "Appearance", "Notifications", "Sound", "Focus", "Screen Time")
        entries.forEachIndexed { index, entry ->
            val selected = index == 1
            BasicText(
                text = entry,
                style =
                    TextStyle(
                        color = Color.White.copy(alpha = if (selected) 1f else 0.8f),
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .then(
                            if (selected) {
                                Modifier.background(Color.White.copy(alpha = 0.18f))
                            } else {
                                Modifier
                            },
                        ).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun DemoToolbar(
    overlay: Boolean,
    onToggle: () -> Unit,
    glass: Boolean,
    onToggleGlass: () -> Unit,
) {
    val insets = LocalWindowChromeInsets.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                // Always paints: the window surface is cleared to transparent
                // while glass is active, so an unpainted bar would show the
                // desktop through it — translucent over the glass, opaque
                // otherwise.
                .background(Color.White.copy(alpha = if (glass) 0.15f else 0.08f))
                .windowDragArea()
                .padding(insets.controlsInsets)
                .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(
            text = "WindowScaffold — ${if (overlay) "Overlay" else "Docked"}",
            style =
                TextStyle(
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
        )
        Spacer(Modifier.weight(1f))
        ToolbarButton(
            text = if (glass) "Glass sidebar: on" else "Glass sidebar: off",
            onClick = onToggleGlass,
        )
        ToolbarButton(
            text = if (overlay) "Switch to Docked" else "Switch to Overlay",
            onClick = onToggle,
        )
    }
}

@Composable
private fun ToolbarButton(
    text: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.25f))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        BasicText(
            text = text,
            style = TextStyle(color = Color.White, fontSize = 12.sp),
        )
    }
}
