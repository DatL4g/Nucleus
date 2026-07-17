package dev.nucleusframework.window.tao

import androidx.compose.runtime.Composable

/**
 * Bridges the Tao windowing backend to JetBrains Compose Hot Reload.
 *
 * Compose Hot Reload's runtime assumes an AWT windowing model: a window manager
 * (`org.jetbrains.compose.reload.jvm.startWindowManager`) installs
 * `ComponentListener`s on each `java.awt.Window` to publish its geometry into
 * the orchestration `WindowsState` — which the separate dev-tools process uses
 * to position its sidecar window next to the app's. The Tao backend owns its
 * own windows (Tao/GTK/Win32/AppKit via JNI) and has no `java.awt.Window`, so
 * nobody publishes geometry and the sidecar never appears.
 *
 * [trackWindow] fixes that by feeding Tao window callbacks into the same
 * `WindowsState` orchestration state, so the dev-tools sidecar follows the Tao
 * window exactly like it follows an AWT `ComposeWindow`.
 *
 * This object stays on the hot path (called from [openDecoratedWindow]) so it
 * must never reference any hot-reload class directly: those live in artifacts
 * (`hot-reload-agent`, `-core`, `-orchestration`, `-devtools-api`) that are
 * only on the runtime classpath under the hot-reload agent. When hot reload is
 * inactive (`compose.reload.isActive != "true"`, the property the agent sets),
 * [bridge] is `null` and every method is a no-op / pass-through. When active,
 * the agent has already placed those artifacts on the classpath, so we load
 * [TaoHotReloadBridgeImpl] reflectively. Mirrors [LifecycleMainDispatcherPriming].
 */
internal object TaoHotReloadIntegration {
    private val active: Boolean = System.getProperty("compose.reload.isActive") == "true"

    private val bridge: TaoHotReloadBridge? =
        if (active) {
            runCatching {
                Class.forName("dev.nucleusframework.window.tao.TaoHotReloadBridgeImpl")
                    .getDeclaredConstructor().newInstance() as TaoHotReloadBridge
            }.getOrNull()
        } else {
            null
        }

    /** True when running under the Compose Hot Reload agent and the bridge loaded. */
    val isActive: Boolean get() = bridge != null

    /**
     * Hook for wrapping scene content in a hot-reload entry point. Currently a
     * pass-through: see the class KDoc for why we don't wrap in
     * `DevelopmentEntryPoint` here. Kept as an indirection at the
     * [openDecoratedWindow] call site so a wrapper can be introduced later
     * without touching every platform path.
     */
    @Composable
    fun wrapContent(content: @Composable () -> Unit): Unit = content()

    /**
     * Publishes [window]'s geometry into the hot-reload orchestration
     * `WindowsState` so the dev-tools sidecar can follow it. No-op when hot
     * reload is inactive. [title] / [alwaysOnTop] are captured from the
     * `openDecoratedWindow` call site (TaoWindow exposes no getters for them).
     */
    fun trackWindow(window: TaoWindow, title: String?, alwaysOnTop: Boolean) {
        bridge?.trackWindow(window, title, alwaysOnTop)
    }
}

/**
 * Hot-reload bridge surface. Implemented by [TaoHotReloadBridgeImpl], loaded
 * reflectively only when hot reload is active (and thus the hot-reload
 * artifacts are on the classpath). Kept free of hot-reload type references so
 * it can be loaded unconditionally.
 */
internal interface TaoHotReloadBridge {
    fun trackWindow(window: TaoWindow, title: String?, alwaysOnTop: Boolean)
}