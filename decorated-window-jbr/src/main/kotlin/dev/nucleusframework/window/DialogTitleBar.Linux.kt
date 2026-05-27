package dev.nucleusframework.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import com.jetbrains.JBR
import dev.nucleusframework.window.styling.TitleBarStyle
import dev.nucleusframework.window.utils.linux.rememberLinuxButtonLayout
import java.awt.event.MouseEvent

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("FunctionNaming")
@Composable
internal fun AwtDecoratedDialogScope.LinuxDialogTitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    layoutPolicy: TitleBarLayoutPolicy = TitleBarLayoutPolicy.Default,
    content: @Composable TitleBarScope.(DecoratedDialogState) -> Unit = {},
) {
    val linuxStyle = createLinuxTitleBarStyle(style)
    val dialogState = state
    val controlDir = controlButtonsDirection.resolve()
    val controlsOnRight = rememberLinuxButtonLayout().controlsOnRight
    val controlsSide = if (controlsOnRight) WindowControlsSide.End else WindowControlsSide.Start

    CompositionLocalProvider(LocalWindowControlsSide provides controlsSide) {
        DialogTitleBarImpl(
            modifier =
                modifier.onPointerEvent(PointerEventType.Press, PointerEventPass.Main) {
                    if (
                        this.currentEvent.button == PointerButton.Primary &&
                        this.currentEvent.changes.any { changed -> !changed.isConsumed }
                    ) {
                        JBR.getWindowMove()?.startMovingTogetherWithMouse(window, MouseEvent.BUTTON1)
                    }
                },
            gradientStartColor = gradientStartColor,
            style = linuxStyle,
            controlButtonsDirection = controlDir,
            layoutPolicy = layoutPolicy,
            applyTitleBar = { _, _ -> kdePaddingForButtonLayout() },
        ) { _ ->
            DialogCloseButton(window, dialogState, linuxStyle)
            content(dialogState)
        }
    }
}
