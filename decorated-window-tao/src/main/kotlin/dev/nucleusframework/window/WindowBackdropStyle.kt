package dev.nucleusframework.window

import androidx.compose.ui.graphics.Color

/**
 * Backdrop rendered behind the window content by [WindowBackdrop].
 */
public sealed interface WindowBackdropStyle {
    /** Opaque themed background — the default decorated-window behavior. */
    public data object Opaque : WindowBackdropStyle

    /**
     * Native behind-window glass: the desktop and windows behind show
     * through wherever the Compose scene renders transparent pixels.
     *
     * macOS 26+ uses `NSGlassEffectView` (real Liquid Glass); older macOS
     * falls back to an `NSVisualEffectView` behind-window material (frosted
     * blur, no lensing). A no-op on other platforms — Windows Mica/Acrylic
     * may map onto this style later.
     *
     * @param tint color giving the glass its material body — an untinted
     * glass over a large surface reads as plain transparency.
     * [Color.Unspecified] (default) derives the tint from the decorated
     * window style's background color at 50% opacity; [Color.Transparent]
     * requests pure untinted glass. Ignored by the pre-26 fallback.
     */
    public data class Glass(
        val tint: Color = Color.Unspecified,
    ) : WindowBackdropStyle
}
