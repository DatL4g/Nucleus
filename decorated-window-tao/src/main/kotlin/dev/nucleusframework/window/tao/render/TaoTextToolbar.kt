@file:Suppress("MagicNumber")

package dev.nucleusframework.window.tao.render

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocalization
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/**
 * [TextToolbar] for the Tao backends. Compose's `PlatformContext.Empty` ships a
 * no-op `EmptyTextToolbar`, so without this nothing appears when text is
 * selected by touch. The common selection code decides *when* (a touch
 * selection calls [showMenu], gated on `isInTouchMode`); this decides *what*.
 *
 * Reproduces the floating selection bar of the native-touch platforms. Like
 * Chromium's `TouchSelectionMenuViews` (and iOS / Android), it is a dedicated,
 * self-rendered **horizontal** bar of actions anchored above the selection —
 * not an OS menu (none exists for custom-drawn text) and not the app's
 * right-click dropdown. Labels come from [LocalLocalization] so they follow the
 * system language; colors follow the system light/dark setting. This is just a
 * state holder; [TaoTextToolbarHost] renders it.
 */
internal class TaoTextToolbar : TextToolbar {
    var state: TaoTextToolbarState? by mutableStateOf(null)
        private set

    override val status: TextToolbarStatus
        get() = if (state == null) TextToolbarStatus.Hidden else TextToolbarStatus.Shown

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        val next =
            TaoTextToolbarState(
                rect = rect,
                onCut = onCutRequested,
                onCopy = onCopyRequested,
                onPaste = onPasteRequested,
                onSelectAll = onSelectAllRequested,
            )
        state = if (next.isEmpty) null else next
    }

    override fun hide() {
        state = null
    }
}

internal data class TaoTextToolbarState(
    val rect: Rect,
    val onCut: (() -> Unit)?,
    val onCopy: (() -> Unit)?,
    val onPaste: (() -> Unit)?,
    val onSelectAll: (() -> Unit)?,
) {
    val isEmpty: Boolean
        get() = onCut == null && onCopy == null && onPaste == null && onSelectAll == null
}

private data class TaoTextToolbarItem(
    val label: String,
    val onClick: () -> Unit,
)

/**
 * Wraps the scene content and overlays the floating selection bar when one is
 * requested. Drop into each host's `setContent`:
 * `scene.setContent { TaoTextToolbarHost(toolbar) { content() } }`.
 */
@Composable
internal fun TaoTextToolbarHost(
    textToolbar: TaoTextToolbar,
    content: @Composable () -> Unit,
) {
    content()
    val state = textToolbar.state
    if (state != null) {
        TaoTextSelectionBar(state = state, onDismissRequest = textToolbar::hide)
    }
}

@Composable
private fun TaoTextSelectionBar(
    state: TaoTextToolbarState,
    onDismissRequest: () -> Unit,
) {
    val l = LocalLocalization.current
    val items =
        listOfNotNull(
            state.onCut?.let { TaoTextToolbarItem(l.cut, it) },
            state.onCopy?.let { TaoTextToolbarItem(l.copy, it) },
            state.onPaste?.let { TaoTextToolbarItem(l.paste, it) },
            state.onSelectAll?.let { TaoTextToolbarItem(l.selectAll, it) },
        )
    if (items.isEmpty()) return

    val dark = isSystemInDarkTheme()
    val background = if (dark) Color(0xFF2B2B2B) else Color.White
    val foreground = if (dark) Color(0xFFEAEAEA) else Color(0xFF1F1F1F)
    val divider = if (dark) Color(0x33FFFFFF) else Color(0x1F000000)

    val gapPx = with(LocalDensity.current) { 8.dp.roundToPx() }

    Popup(
        popupPositionProvider = TaoTextSelectionBarPositionProvider(state.rect, gapPx),
        onDismissRequest = onDismissRequest,
        // Non-focusable so the text field keeps focus (and its selection) while
        // the bar is shown; pointer clicks still reach the items.
        properties = PopupProperties(focusable = false),
    ) {
        Row(
            modifier =
                Modifier
                    .shadow(6.dp, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(background)
                    .height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    Box(
                        modifier =
                            Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .padding(vertical = 8.dp)
                                .background(divider),
                    )
                }
                TaoTextSelectionBarItem(
                    item = item,
                    foreground = foreground,
                    onDismissRequest = onDismissRequest,
                )
            }
        }
    }
}

@Composable
private fun TaoTextSelectionBarItem(
    item: TaoTextToolbarItem,
    foreground: Color,
    onDismissRequest: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxHeight()
                .clickable {
                    item.onClick()
                    onDismissRequest()
                }.padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = item.label,
            style = TextStyle(color = foreground, fontSize = 14.sp),
        )
    }
}

/**
 * Anchors the bar centered horizontally over the selection and just above it,
 * flipping below when there is not enough room. [rect] is the selection bounds
 * in window coordinates; the bar lives at the scene root so the anchor bounds
 * are the whole window (origin 0,0).
 */
private class TaoTextSelectionBarPositionProvider(
    private val rect: Rect,
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val selectionCenterX = anchorBounds.left + rect.center.x.roundToInt()
        val x = selectionCenterX - popupContentSize.width / 2
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)

        val selectionTop = anchorBounds.top + rect.top.roundToInt()
        val selectionBottom = anchorBounds.top + rect.bottom.roundToInt()
        val above = selectionTop - gapPx - popupContentSize.height
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val y = if (above >= 0) above else selectionBottom + gapPx

        return IntOffset(
            x = x.coerceIn(0, maxX),
            y = y.coerceIn(0, maxY),
        )
    }
}
