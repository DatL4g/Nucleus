package dev.nucleusframework.window.tao

import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.makeGLWithInterface
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Verifies the native chain behind the standalone popup panel without any
 * Tao event loop: ownerless panel creation, surface attach and Skia
 * DirectContext creation. Windows-only — see [StandalonePanelMacSmokeMain]
 * for the macOS equivalent (AppKit requires the NSPanel be created on the
 * main thread, so it runs as a `main()` via the `smokeStandalonePanelMac`
 * JavaExec task, not as a JUnit test in the off-main test worker).
 */
class StandalonePanelNativeSmokeTest {
    @Test
    fun standalonePanelPipelineInitializes() {
        if (!System.getProperty("os.name", "").lowercase().contains("win")) return

        assertTrue(NativeTaoGlBridge.isLoaded, "nucleus_tao_gl failed to load")
        assertTrue(PopupNativeBridgeWindows.isLoaded, "nucleus_tao_windows_native_view failed to load")
        assertTrue(
            NativeTaoGlBridge.nativeEnsureHeadlessContext(),
            "headless EGL context bootstrap failed",
        )

        val panel =
            PopupNativeBridgeWindows.nativeCreatePanel(
                parentHwnd = 0L,
                xPx = -32_000,
                yPx = -32_000,
                widthPx = 300,
                heightPx = 200,
            )
        assertNotEquals(0L, panel, "ownerless panel creation failed")

        try {
            assertTrue(
                PopupNativeBridgeWindows.nativeMakeCurrent(panel),
                "nativeMakeCurrent failed on standalone panel",
            )
            val intf =
                GLAssembledInterface.createFromNativePointers(
                    0L,
                    NativeTaoGlBridge.nativeEglGetProcFn(),
                )
            val ctx = DirectContext.makeGLWithInterface(intf)
            ctx.close()
        } finally {
            PopupNativeBridgeWindows.nativeRelease(panel)
        }
    }
}
