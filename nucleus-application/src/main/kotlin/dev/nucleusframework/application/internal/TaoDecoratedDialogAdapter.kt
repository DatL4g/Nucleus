package dev.nucleusframework.application.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.window.DialogState
import dev.nucleusframework.application.LocalNucleusBackend
import dev.nucleusframework.application.LocalNucleusWindow
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.NucleusDecoratedDialogScope
import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.application.TaoNucleusApplicationScope
import dev.nucleusframework.application.TaoNucleusWindow
import dev.nucleusframework.window.DecoratedDialogState
import dev.nucleusframework.window.DecoratedWindowState
import dev.nucleusframework.window.LocalModalDialogCount
import dev.nucleusframework.window.tao.TaoDecoratedDialogScope
import dev.nucleusframework.window.tao.DecoratedDialog as TaoDecoratedDialog

internal object TaoDecoratedDialogAdapter {
    @Suppress("LongParameterList")
    @Composable
    fun Dialog(
        scope: TaoNucleusApplicationScope,
        onCloseRequest: () -> Unit,
        state: DialogState,
        visible: Boolean,
        title: String,
        icon: Painter?,
        resizable: Boolean,
        enabled: Boolean,
        focusable: Boolean,
        onPreviewKeyEvent: (KeyEvent) -> Boolean,
        onKeyEvent: (KeyEvent) -> Boolean,
        content: @Composable NucleusDecoratedDialogScope.() -> Unit,
    ) {
        // Bridge every CompositionLocal across the fresh Tao ComposeScene —
        // see TaoDecoratedWindowAdapter for rationale.
        val outerLocals = currentCompositionLocalContext

        // Tell the parent window to block its pointer input while this dialog
        // is alive. The parent provides LocalModalDialogCount with a shared
        // MutableState; we increment it here and decrement on dispose.
        // outerLocals includes that state by reference, so the parent reacts.
        val parentModalCount = LocalModalDialogCount.current
        DisposableEffect(Unit) {
            parentModalCount.value++
            onDispose { parentModalCount.value-- }
        }

        with(scope.taoScope) {
            TaoDecoratedDialog(
                onCloseRequest = onCloseRequest,
                state = state,
                visible = visible,
                title = title,
                icon = icon,
                resizable = resizable,
                enabled = enabled,
                focusable = focusable,
                onPreviewKeyEvent = onPreviewKeyEvent,
                onKeyEvent = onKeyEvent,
            ) {
                val taoScope: TaoDecoratedDialogScope = this
                // Tao dialogs share TaoWindow with regular windows; rebuild the
                // active-state mirror as a single-bit DecoratedWindowState so
                // [TaoNucleusWindow] can read uniform flow values.
                val windowStateMirror =
                    remember(taoScope) {
                        derivedStateOf {
                            DecoratedWindowState.of(active = taoScope.state.isActive)
                        }
                    }
                val nucleusWindow: NucleusWindow =
                    remember(taoScope.window) {
                        TaoNucleusWindow(taoScope.window, windowStateMirror)
                    }
                val nucleusScope =
                    remember(taoScope, nucleusWindow) {
                        TaoNucleusDecoratedDialogScope(taoScope, nucleusWindow)
                    }
                CompositionLocalProvider(outerLocals) {
                    CompositionLocalProvider(
                        LocalNucleusBackend provides NucleusBackend.Tao,
                        LocalNucleusWindow provides nucleusWindow,
                    ) {
                        nucleusScope.content()
                    }
                }
            }
        }
    }
}

private class TaoNucleusDecoratedDialogScope(
    private val taoScope: TaoDecoratedDialogScope,
    override val nucleusWindow: NucleusWindow,
) : NucleusDecoratedDialogScope,
    TaoDecoratedDialogScope by taoScope {
    override val state: DecoratedDialogState get() = taoScope.state
}
