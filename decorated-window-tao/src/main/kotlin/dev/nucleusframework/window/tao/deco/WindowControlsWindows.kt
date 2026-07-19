package dev.nucleusframework.window.tao.deco

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.DecoratedWindowState
import dev.nucleusframework.window.LocalControlButtonsDirection
import dev.nucleusframework.window.LocalIsDarkTheme
import dev.nucleusframework.window.icons.windows.Close
import dev.nucleusframework.window.icons.windows.CloseDark
import dev.nucleusframework.window.icons.windows.CloseFullscreen
import dev.nucleusframework.window.icons.windows.CloseFullscreenDark
import dev.nucleusframework.window.icons.windows.CloseFullscreenInactive
import dev.nucleusframework.window.icons.windows.CloseFullscreenInactiveDark
import dev.nucleusframework.window.icons.windows.CloseHover
import dev.nucleusframework.window.icons.windows.CloseInactive
import dev.nucleusframework.window.icons.windows.CloseInactiveDark
import dev.nucleusframework.window.icons.windows.Maximize
import dev.nucleusframework.window.icons.windows.MaximizeDark
import dev.nucleusframework.window.icons.windows.MaximizeInactive
import dev.nucleusframework.window.icons.windows.MaximizeInactiveDark
import dev.nucleusframework.window.icons.windows.Minimize
import dev.nucleusframework.window.icons.windows.MinimizeDark
import dev.nucleusframework.window.icons.windows.MinimizeInactive
import dev.nucleusframework.window.icons.windows.MinimizeInactiveDark
import dev.nucleusframework.window.icons.windows.Restore
import dev.nucleusframework.window.icons.windows.RestoreDark
import dev.nucleusframework.window.icons.windows.RestoreInactive
import dev.nucleusframework.window.icons.windows.RestoreInactiveDark
import dev.nucleusframework.window.icons.windows.WindowsControlButtonIcons
import dev.nucleusframework.window.styling.TitleBarStyle
import dev.nucleusframework.window.tao.TaoWindow

// Mirrors `decorated-window-core/WindowsWindowControlArea.kt` so the visual
// output is identical between the AWT-based backend and the Tao backend.

private val WINDOWS_BUTTON_WIDTH = 46.dp

@Suppress("MagicNumber")
private val WindowsButtonHoveredLight = Color(0x1A000000)

@Suppress("MagicNumber")
private val WindowsButtonHoveredDark = Color(0x1AFFFFFF)

@Suppress("MagicNumber")
private val WindowsButtonPressedLight = Color(0x33000000)

@Suppress("MagicNumber")
private val WindowsButtonPressedDark = Color(0x33FFFFFF)

@Suppress("MagicNumber")
private val WindowsCloseButtonHovered = Color(0xFFE81123)

@Suppress("MagicNumber")
private val WindowsCloseButtonPressed = Color(0xFFF1707A)

/**
 * Windows-style window controls (minimize / maximize-restore / close).
 *
 * Auto-injected by [TitleBar] when running on Windows; library users do not
 * need to call it directly. The visual output mirrors
 * `decorated-window-core`'s `WindowsWindowControlArea` (same icon set, same
 * hover/pressed colors, same active/inactive variants) so the two backends
 * stay visually consistent.
 *
 * Hit-testing rule: drawn entirely in Compose because the WndProc subclass
 * returns HTCLIENT for the title bar zone — DWM never repaints native buttons
 * on top, which would otherwise happen on non-JBR JDKs.
 */
@Suppress("FunctionNaming", "CyclomaticComplexMethod")
@Composable
internal fun WindowControlsWindows(
    win: TaoWindow,
    state: DecoratedWindowState,
    style: TitleBarStyle,
    modifier: Modifier = Modifier,
    isFullscreen: Boolean = false,
    onExitFullscreen: (() -> Unit)? = null,
) {
    val isDark = LocalIsDarkTheme.current
    // Match decorated-window-jni's WindowsWindowControlArea: LTR renders
    // Minimize/Maximize/Close, RTL mirrors it to Close/Maximize/Minimize.
    CompositionLocalProvider(LocalLayoutDirection provides LocalControlButtonsDirection.current) {
        Row(modifier = modifier.fillMaxHeight()) {
            // Minimize
            WindowsCaptionButton(
                onClick = { win.minimize() },
                isDark = isDark,
                style = style,
                icon =
                    if (state.isActive) {
                        if (isDark) WindowsControlButtonIcons.MinimizeDark else WindowsControlButtonIcons.Minimize
                    } else {
                        if (isDark) {
                            WindowsControlButtonIcons.MinimizeInactiveDark
                        } else {
                            WindowsControlButtonIcons.MinimizeInactive
                        }
                    },
                contentDescription = "Minimize",
            )

            // Fullscreen → exit-fullscreen button replaces maximize/restore.
            // Mirrors decorated-window-jni's WindowsWindowControlArea behaviour.
            if (isFullscreen && onExitFullscreen != null) {
                WindowsCaptionButton(
                    onClick = onExitFullscreen,
                    isDark = isDark,
                    style = style,
                    icon =
                        if (state.isActive) {
                            if (isDark) {
                                WindowsControlButtonIcons.CloseFullscreenDark
                            } else {
                                WindowsControlButtonIcons.CloseFullscreen
                            }
                        } else {
                            if (isDark) {
                                WindowsControlButtonIcons.CloseFullscreenInactiveDark
                            } else {
                                WindowsControlButtonIcons.CloseFullscreenInactive
                            }
                        },
                    contentDescription = "Exit fullscreen",
                )
            } else if (win.isResizable && state.isMaximized) {
                // Maximize / Restore — switches icon based on actual window state.
                // Hidden when non-resizable (win.isResizable is snapshot-backed,
                // so runtime setResizable() recomposes — mirrors the AWT
                // backends' WindowsWindowControlArea gating, #260).
                WindowsCaptionButton(
                    onClick = { win.setMaximized(false) },
                    isDark = isDark,
                    style = style,
                    icon =
                        if (state.isActive) {
                            if (isDark) WindowsControlButtonIcons.RestoreDark else WindowsControlButtonIcons.Restore
                        } else {
                            if (isDark) {
                                WindowsControlButtonIcons.RestoreInactiveDark
                            } else {
                                WindowsControlButtonIcons.RestoreInactive
                            }
                        },
                    contentDescription = "Restore",
                )
            } else if (win.isResizable) {
                WindowsCaptionButton(
                    onClick = { win.setMaximized(true) },
                    isDark = isDark,
                    style = style,
                    icon =
                        if (state.isActive) {
                            if (isDark) WindowsControlButtonIcons.MaximizeDark else WindowsControlButtonIcons.Maximize
                        } else {
                            if (isDark) {
                                WindowsControlButtonIcons.MaximizeInactiveDark
                            } else {
                                WindowsControlButtonIcons.MaximizeInactive
                            }
                        },
                    contentDescription = "Maximize",
                )
            }

            // Close — fire user's onCloseRequest (mirrors AWT's WINDOW_CLOSING
            // dispatch). Calling `requestClose()` directly would destroy the
            // window without giving the app a chance to exit the Tao event loop.
            WindowsCaptionButton(
                onClick = { win.requestUserClose() },
                isDark = isDark,
                style = style,
                icon =
                    if (state.isActive) {
                        if (isDark) WindowsControlButtonIcons.CloseDark else WindowsControlButtonIcons.Close
                    } else {
                        if (isDark) {
                            WindowsControlButtonIcons.CloseInactiveDark
                        } else {
                            WindowsControlButtonIcons.CloseInactive
                        }
                    },
                iconHover = WindowsControlButtonIcons.CloseHover,
                isCloseButton = true,
                contentDescription = "Close",
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("FunctionNaming")
@Composable
private fun WindowsCaptionButton(
    onClick: () -> Unit,
    isDark: Boolean,
    style: TitleBarStyle,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    iconHover: androidx.compose.ui.graphics.vector.ImageVector? = null,
    isCloseButton: Boolean = false,
) {
    var hovered by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }

    val backgroundColor =
        captionButtonBackground(
            hovered = hovered,
            pressed = pressed,
            isCloseButton = isCloseButton,
            isDark = isDark,
            style = style,
        )

    val isCloseHovered = (hovered || pressed) && isCloseButton
    val currentIcon: Painter =
        rememberVectorPainter(
            if (isCloseHovered && iconHover != null) iconHover else icon,
        )

    val colorFilter =
        captionButtonColorFilter(
            hovered = hovered,
            pressed = pressed,
            isCloseHovered = isCloseHovered,
            style = style,
        )

    Box(
        modifier =
            Modifier
                .focusable(false)
                .fillMaxHeight()
                .width(WINDOWS_BUTTON_WIDTH)
                .background(backgroundColor)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) {
                    hovered = false
                    pressed = false
                }.onPointerEvent(PointerEventType.Press) { pressed = true }
                .onPointerEvent(PointerEventType.Release) { pressed = false }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Image(painter = currentIcon, contentDescription = contentDescription, colorFilter = colorFilter)
    }
}

// Mirrors `decorated-window-core/WindowsWindowControlArea.kt` so custom
// [TitleBarStyle] colors apply identically on the Tao backend. Close-button
// hover/pressed always use the fixed Windows red — matching AWT.
private fun captionButtonBackground(
    hovered: Boolean,
    pressed: Boolean,
    isCloseButton: Boolean,
    isDark: Boolean,
    style: TitleBarStyle,
): Color {
    val customHover = style.colors.iconButtonHoveredBackground
    val customPressed = style.colors.iconButtonPressedBackground
    val pressedColor =
        customPressed.takeUnless { it == Color.Transparent }
            ?: if (isDark) WindowsButtonPressedDark else WindowsButtonPressedLight
    val hoveredColor =
        customHover.takeUnless { it == Color.Transparent }
            ?: if (isDark) WindowsButtonHoveredDark else WindowsButtonHoveredLight
    return when {
        pressed && isCloseButton -> WindowsCloseButtonPressed
        pressed -> pressedColor
        hovered && isCloseButton -> WindowsCloseButtonHovered
        hovered -> hoveredColor
        else -> Color.Transparent
    }
}

private fun captionButtonColorFilter(
    hovered: Boolean,
    pressed: Boolean,
    isCloseHovered: Boolean,
    style: TitleBarStyle,
): ColorFilter? {
    val iconTint = style.colors.controlButtonIconColor
    val iconHoverTint = style.colors.controlButtonIconHoverColor
    return when {
        // Close hover swaps to the baked-red close artwork; don't tint it.
        isCloseHovered -> null
        (hovered || pressed) && iconHoverTint != Color.Unspecified ->
            ColorFilter.tint(iconHoverTint)
        iconTint != Color.Unspecified -> ColorFilter.tint(iconTint)
        else -> null
    }
}
