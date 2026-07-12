package dev.nucleusframework.window.tao

import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.makeGLWithInterface
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Verifies the native chain behind the Linux standalone popup panel without
 * any Tao event loop: X connection, override-redirect ARGB panel creation,
 * EGL attach and Skia DirectContext creation. The Linux twin of
 * [StandalonePanelNativeSmokeTest] (Windows). Unlike AppKit, X11 has no
 * main-thread requirement — the command connection is owned by whichever
 * single thread uses it, here the test worker.
 *
 * Skipped when no X server is reachable (headless CI without Xvfb, rare
 * Wayland-only setups) — the same signal `isTaoStandalonePopupAvailable()`
 * reports to production callers.
 */
class StandalonePanelLinuxNativeSmokeTest {
    @Test
    fun standalonePanelPipelineInitializes() {
        if (!System.getProperty("os.name", "").lowercase().contains("linux")) return

        assertTrue(PopupNativeBridgeLinux.isLoaded, "nucleus_tao_linux_popup failed to load")
        assertTrue(NativeTaoEglBridge.isLoaded, "nucleus_tao_egl failed to load")
        if (!PopupNativeBridgeLinux.nativeIsAvailable()) {
            println("SKIP: no X server reachable (DISPLAY unset?)")
            return
        }

        val panel =
            PopupNativeBridgeLinux.nativeCreatePanel(
                xPx = -32_000,
                yPx = -32_000,
                widthPx = 300,
                heightPx = 200,
            )
        assertNotEquals(0L, panel, "standalone panel creation failed (no ARGB visual?)")

        var attachment = 0L
        try {
            attachment =
                NativeTaoEglBridge.nativeAttachX11(
                    displayPtr = PopupNativeBridgeLinux.nativeDisplayPtr(),
                    xid = PopupNativeBridgeLinux.nativeWindowXid(panel),
                    widthPx = 300,
                    heightPx = 200,
                )
            assertNotEquals(0L, attachment, "EGL attach failed on standalone panel")
            NativeTaoEglBridge.nativeSetSwapInterval(attachment, 0)
            val intf =
                GLAssembledInterface.createFromNativePointers(
                    0L,
                    NativeTaoEglBridge.nativeGetProcAddrFunctionPointer(),
                )
            val ctx = DirectContext.makeGLWithInterface(intf)
            ctx.close()
            assertTrue(PopupNativeBridgeLinux.nativeScale() > 0f, "panel scale must be positive")
        } finally {
            if (attachment != 0L) NativeTaoEglBridge.nativeDetach(attachment)
            PopupNativeBridgeLinux.nativeRelease(panel)
        }
    }
}
