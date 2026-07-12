package com.example.composedemo

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.PaletteStyle
import demo.shell.App
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.systemcolor.systemAccentColor
import dev.nucleusframework.window.TitleBarScope
import dev.nucleusframework.window.material.MaterialDecoratedWindow
import dev.nucleusframework.window.material.MaterialTitleBar

private enum class ThemeMode {
    System,
    Dark,
    Light,
    ;

    fun next(): ThemeMode =
        when (this) {
            System -> Dark
            Dark -> Light
            Light -> System
        }
}

fun main(args: Array<String>) =
    nucleusApplication(args) {
        var themeMode by remember { mutableStateOf(ThemeMode.System) }

        val isDark =
            when (themeMode) {
                ThemeMode.System -> isSystemInDarkMode()
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
            }
        val accentColor = systemAccentColor()
        val seedColor = accentColor ?: Color(0xFF6750A4) // Material 3 default seed

        DynamicMaterialTheme(
            seedColor = seedColor,
            isDark = isDark,
            animate = true,
            style = PaletteStyle.TonalSpot,
        ) {
            val state =
                rememberWindowState(
                    size = DpSize(1000.dp, 700.dp),
                    position = WindowPosition.Aligned(Alignment.Center),
                    placement = WindowPlacement.Floating,
                )
            MaterialDecoratedWindow(
                state = state,
                onCloseRequest = ::exitApplication,
                title = "Compose Desktop Demo — Tao backend",
                minimumSize = DpSize(960.dp, 600.dp),
                nativePopupLayers = true,
            ) {
                MaterialTitleBar {
                    ThemeToggleButton(
                        icon =
                            when (themeMode) {
                                ThemeMode.System -> Icons.Filled.AutoAwesome
                                ThemeMode.Dark -> Icons.Filled.DarkMode
                                ThemeMode.Light -> Icons.Filled.LightMode
                            },
                        contentDescription = "Toggle theme (${themeMode.name.lowercase()})",
                        onClick = { themeMode = themeMode.next() },
                    )
                }
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        App(isJvm = true)
                    }
                }
            }
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TitleBarScope.ThemeToggleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val isHovered by hoverInteraction.collectIsHoveredAsState()

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(contentDescription) } },
        state = rememberTooltipState(),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier =
                Modifier
                    .padding(horizontal = 4.dp)
                    .clip(CircleShape)
                    .hoverable(hoverInteraction)
                    .background(
                        if (isHovered) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                        } else {
                            Color.Transparent
                        },
                    ).titleBarClickable { onClick() }
                    .padding(4.dp)
                    .size(16.dp),
        )
    }
}
