@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.nucleusframework.window.tao.render

import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Carries the per-window callback that publishes a non-editable selection to
 * native accessibility (wired by `DecoratedWindow` to the a11y controller).
 *
 * Provided at the scene root by the host. Exposed so higher-level wrappers
 * (e.g. `nucleus-application`'s themed adapter) can re-install the selection
 * observer *inside* a theme's own `LocalTextContextMenu` (Jewel's, …) — keeping
 * that theme's menu as the observer's delegate so cut/copy/paste icons and
 * shortcuts survive. `null` outside a Tao window content lambda.
 */
public val LocalTaoTextSelectionA11yPublisher:
    ProvidableCompositionLocal<((text: String, editable: Boolean, sourceId: Int) -> Unit)?> =
    staticCompositionLocalOf { null }

/**
 * Wraps [content] with the selection-accessibility observer, delegating to
 * whatever [TextContextMenu] is currently ambient (so a theme's styled context
 * menu is preserved). No-op when [LocalTaoTextSelectionA11yPublisher] is unset.
 */
@Composable
public fun TaoTextSelectionAccessibility(content: @Composable () -> Unit) {
    val publisher = LocalTaoTextSelectionA11yPublisher.current
    if (publisher != null) {
        TaoSelectionAccessibilityObserver(onSelection = publisher, content = content)
    } else {
        content()
    }
}

/**
 * Bridges Compose's **non-editable** text selection (`SelectionContainer` /
 * selectable `BasicText`) to the native accessibility layer so cross-process
 * readers like PopClip can read it.
 *
 * Compose exposes *editable* selection through semantics
 * (`SemanticsProperties.TextSelectionRange`, handled by the normal a11y
 * pipeline) but deliberately keeps the `SelectionContainer` selection internal
 * (`SelectionManager`/`SelectionRegistrarImpl` are `internal` in Google's
 * `androidx.compose.foundation`). The ONLY public, reflection-free — therefore
 * GraalVM native-image-safe — surface that exposes the selected string is
 * [TextContextMenu.TextManager.selectedText].
 *
 * We layer a delegating [TextContextMenu] over whatever is already provided
 * (so the real selection toolbar / context menu still works) and observe the
 * selected text via [snapshotFlow], forwarding it to [onSelection] together
 * with an `editable` flag derived from the availability of cut/paste actions.
 * The host wires [onSelection] to the per-window accessibility controller,
 * which publishes it to native as the focused element's `AXSelectedText` when
 * no editable field owns the selection.
 */
@Composable
internal fun TaoSelectionAccessibilityObserver(
    onSelection: (text: String, editable: Boolean, sourceId: Int) -> Unit,
    content: @Composable () -> Unit,
) {
    val base = LocalTextContextMenu.current
    val menu = remember(base, onSelection) { ObservingTextContextMenu(base, onSelection) }
    CompositionLocalProvider(LocalTextContextMenu provides menu, content = content)
}

private class ObservingTextContextMenu(
    private val delegate: TextContextMenu,
    private val onSelection: (text: String, editable: Boolean, sourceId: Int) -> Unit,
) : TextContextMenu {
    @Composable
    override fun Area(
        textManager: TextContextMenu.TextManager,
        state: ContextMenuState,
        content: @Composable () -> Unit,
    ) {
        // Stable per-region identity. There is one Area (one TextManager) per
        // selectable region; when the user selects in region A, every OTHER
        // region's `selectedText` is "" and would otherwise clobber A's
        // selection. The source id lets the controller ignore empty clears that
        // don't come from the region currently owning the selection.
        val sourceId = remember(textManager) { System.identityHashCode(textManager) }
        LaunchedEffect(textManager) {
            snapshotFlow {
                // `cut`/`paste` are non-null only on editable targets — that's
                // how we tell a SelectionContainer (read-only) apart from a
                // BasicTextField, whose selection is already exposed via
                // semantics.
                val editable = textManager.cut != null || textManager.paste != null
                textManager.selectedText.text to editable
            }.distinctUntilChanged()
                .collect { (text, editable) -> onSelection(text, editable, sourceId) }
        }
        delegate.Area(textManager, state, content)
    }
}
