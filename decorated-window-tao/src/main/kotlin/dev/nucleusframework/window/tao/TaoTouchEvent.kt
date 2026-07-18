package dev.nucleusframework.window.tao

import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoLinuxTouchBridge

/**
 * Touch phase reported by [NativeTaoLinuxTouchBridge.Callback.onTouchEvent]
 * (Linux multi-finger snapshot path) and [NativeTaoBridge.EventCallback.onTouchInput]
 * (Windows per-finger Tao path). Values are shared so JVM-side hosts can map
 * them with a single `when`.
 */
@Suppress("MagicNumber")
object TaoTouchEvent {
    const val PRESS: Int = 0
    const val MOVE: Int = 1
    const val RELEASE: Int = 2
    const val CANCEL: Int = 3

    /**
     * Sentinel `forceFixed` value meaning the digitizer doesn't expose
     * pressure (most consumer touchscreens). Distinguishable from a true
     * 0 reading.
     */
    const val FORCE_UNKNOWN: Int = -1
}
