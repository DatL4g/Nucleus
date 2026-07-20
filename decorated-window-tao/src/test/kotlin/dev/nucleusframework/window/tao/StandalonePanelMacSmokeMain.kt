package dev.nucleusframework.window.tao

import dev.nucleusframework.window.tao.ffi.NativeMetalBridge
import dev.nucleusframework.window.tao.ffi.PopupNativeBridge
import org.jetbrains.skia.DirectContext

/**
 * macOS standalone-popup native-chain smoke check, run as `main()` via the
 * `smokeStandalonePanelMac` JavaExec task.
 *
 * Why a `main()` and not a JUnit `@Test`: AppKit requires an `NSWindow`/
 * `NSPanel` to be instantiated on the macOS main thread. Gradle's test worker
 * runs tests off the main thread, and the process main thread isn't in a run
 * loop, so dispatching back to it would deadlock — the panel cannot be
 * created there. A `main()` runs on the process main thread (which *is* the
 * macOS main thread), so the panel can be instantiated directly. No event
 * loop is entered; creation + attach + Metal `DirectContext` + close is
 * enough to validate the JNI symbols resolve and the Metal pipeline boots.
 *
 * Run with: `./gradlew :decorated-window-tao:smokeStandalonePanelMac`
 */
object StandalonePanelMacSmokeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        check(NativeMetalBridge.isLoaded) { "nucleus_tao_metal failed to load" }
        check(PopupNativeBridge.isLoaded) { "nucleus_tao_macos_popup failed to load" }

        // Ownerless panel (parentNsView = 0) → native takes the screen-coord
        // ownerless branch. Offscreen so nothing is shown during the check.
        val panel =
            PopupNativeBridge.nativeCreatePanel(
                parentNsView = 0L,
                xPx = -32_000,
                yPx = -32_000,
                widthPx = 300,
                heightPx = 200,
            )
        check(panel != 0L) { "ownerless panel creation failed" }

        var attachment = 0L
        try {
            val contentNsView = PopupNativeBridge.nativeContentNsView(panel)
            check(contentNsView != 0L) { "panel content NSView is null" }

            attachment = NativeMetalBridge.nativeAttachOverlay(contentNsView)
            check(attachment != 0L) { "CAMetalLayer attach failed" }

            NativeMetalBridge.nativeResize(attachment, 300, 200, 1f)

            // Skia Metal DirectContext from the attachment's device/queue.
            // Created and closed here on the main thread (the sole owner — in
            // the real host it lives on a dedicated render thread for affinity).
            val ctx =
                DirectContext.makeMetal(
                    NativeMetalBridge.nativeDevicePtr(attachment),
                    NativeMetalBridge.nativeQueuePtr(attachment),
                )
            ctx.close()

            // Position + dismissal cycle on the real NSPanel: reposition,
            // order front, reposition again, order out. Exercises the same
            // native entry points a production popup uses to show, follow
            // its anchor, and dismiss.
            PopupNativeBridge.nativeSetFrameOnScreen(panel, 50, 60, 300, 200)
            PopupNativeBridge.nativeOrderFront(panel)
            PopupNativeBridge.nativeSetFrameOnScreen(panel, 80, 90, 300, 200)
            PopupNativeBridge.nativeOrderOut(panel)
            println("standalonePanelMacSmoke: OK (panel=$panel, attachment=$attachment)")
        } finally {
            if (attachment != 0L) NativeMetalBridge.nativeDetach(attachment)
            PopupNativeBridge.nativeRelease(panel)
        }
    }
}
