package dev.nucleusframework.window

import androidx.compose.foundation.layout.PaddingValues

/**
 * Where [WindowScaffold] places its title bar relative to the window content.
 */
public sealed interface TitleBarPlacement {
    /**
     * The title bar is laid out above the content — the classic decorated
     * window layout, matching what composing `TitleBar` directly does today.
     */
    public data object Docked : TitleBarPlacement

    /**
     * The title bar is drawn as an overlay on top of the content, which fills
     * the full window height (macOS `fullSizeContentView`-style layouts). The
     * content receives the measured bar height as a top [PaddingValues] and
     * may scroll behind the bar.
     *
     * @param autoHideInFullscreen when `true`, the bar is not composed while
     * the window is fullscreen so the content is fully immersive.
     */
    public data class Overlay(
        val autoHideInFullscreen: Boolean = true,
    ) : TitleBarPlacement
}
