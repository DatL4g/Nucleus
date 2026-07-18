package dev.nucleusframework.window.tao

import dev.nucleusframework.window.tao.ffi.NativeTaoBridge

/** Cursor icon codes mirrored 1:1 with the Rust `cursor_from_code` table. */
@Suppress("MagicNumber")
object TaoCursorIcon {
    const val DEFAULT: Int = 0
    const val TEXT: Int = 1
    const val HAND: Int = 2
    const val CROSSHAIR: Int = 3
    const val WAIT: Int = 4
    const val MOVE: Int = 5
    const val NOT_ALLOWED: Int = 6
    const val HELP: Int = 7
    const val PROGRESS: Int = 8
    const val EW_RESIZE: Int = 9
    const val NS_RESIZE: Int = 10
    const val NESW_RESIZE: Int = 11
    const val NWSE_RESIZE: Int = 12
}

/** Mirrors the event constants in `nucleus_tao` (`lib.rs`). */
@Suppress("MagicNumber")
object TaoEventCode {
    const val LAUNCHED: Int = 1
    const val RESIZED: Int = 2
    const val CLOSE_REQUESTED: Int = 3
    const val DESTROYED: Int = 4
    const val REDRAW_REQUESTED: Int = 5
    const val FOCUSED: Int = 6
    const val UNFOCUSED: Int = 7
    const val SCALE_FACTOR_CHANGED: Int = 8

    /** `a` = 1 when the window became minimized (iconified), 0 when restored. */
    const val MINIMIZED: Int = 9

    const val CURSOR_MOVED: Int = 10
    const val CURSOR_LEFT: Int = 11
    const val MOUSE_DOWN: Int = 12
    const val MOUSE_UP: Int = 13
    const val KEY_DOWN: Int = 14
    const val KEY_UP: Int = 15
    const val WINDOW_READY: Int = 16
    const val SCROLL_LINE: Int = 17
    const val SCROLL_PIXEL: Int = 18
    const val KEY_TYPED: Int = 19

    /**
     * Fired once per Tao event-loop iteration once every in-flight event has
     * been processed. We use it to drain `TaoMainDispatcher`'s task queue so
     * the Compose Recomposer can run on the same thread as the Tao loop.
     */
    const val MAIN_EVENTS_CLEARED: Int = 20

    /** `a`/`b` carry `x`/`y` in physical pixels. */
    const val MOVED: Int = 21

    /** `a` carries the current [TaoModifierMask] bitset. */
    const val MODIFIERS_CHANGED: Int = 22

    /**
     * Linux only. Dispatched synchronously right BEFORE the GTK window is
     * hidden, so the host can suspend EGL rendering first — on Wayland the
     * hide destroys the parent `wl_surface` and a racing swap on the owned
     * subsurface is a fatal protocol error (GDK "Error 71").
     */
    const val WILL_HIDE: Int = 23

    /**
     * Linux only. Dispatched synchronously right AFTER the GTK window is
     * shown again (GDK surface re-created) so the host can re-attach EGL.
     */
    const val SHOWN: Int = 24

    /**
     * Windows only. `a` = 1 when the OS modal resize/move loop starts
     * (WM_ENTERSIZEMOVE), 0 when it ends (WM_EXITSIZEMOVE). The host drops
     * VSync while active so border-drag frames don't block on VBlank.
     */
    const val SIZE_MOVE: Int = 25
}

/** Trackpad gesture kind reported by [NativeTaoBridge.EventCallback.onTrackpadGesture]. */
@Suppress("MagicNumber")
object TaoTrackpadGesture {
    const val MAGNIFY: Int = 0
    const val ROTATE: Int = 1
    const val SMART_MAGNIFY: Int = 2
}

/** Trackpad gesture phase reported by [NativeTaoBridge.EventCallback.onTrackpadGesture]. */
@Suppress("MagicNumber")
object TaoTrackpadPhase {
    const val BEGAN: Int = 0
    const val CHANGED: Int = 1
    const val ENDED: Int = 2
    const val CANCELLED: Int = 3
}

/** Modifier-state bitmask that mirrors the Rust side. */
@Suppress("MagicNumber")
object TaoModifierMask {
    const val SHIFT: Int = 1 shl 0
    const val CONTROL: Int = 1 shl 1
    const val ALT: Int = 1 shl 2
    const val META: Int = 1 shl 3
}

/** AWT-equivalent `KeyEvent.KEY_LOCATION_*` constants we accept from Rust. */
@Suppress("MagicNumber")
object TaoKeyLocation {
    const val STANDARD: Int = 1
    const val LEFT: Int = 2
    const val RIGHT: Int = 3
    const val NUMPAD: Int = 4
}

@Suppress("MagicNumber")
object TaoMouseButton {
    const val LEFT: Int = 0
    const val RIGHT: Int = 1
    const val MIDDLE: Int = 2
    const val OTHER: Int = 3
}
