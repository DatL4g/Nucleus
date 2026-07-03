@file:Suppress("MagicNumber")

package dev.nucleusframework.window.tao.render

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.GlobalLayoutDirection
import dev.nucleusframework.window.tao.NativeTaoBridge
import dev.nucleusframework.window.tao.NativeTaoGlBridge
import dev.nucleusframework.window.tao.NativeTaoWindowsDecoBridge
import dev.nucleusframework.window.tao.TaoEventCode
import dev.nucleusframework.window.tao.TaoMainDispatcher
import dev.nucleusframework.window.tao.TaoModifierMask
import dev.nucleusframework.window.tao.TaoPointerScrollEvent
import dev.nucleusframework.window.tao.TaoTouchEvent
import dev.nucleusframework.window.tao.TaoWindow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.makeGLWithInterface
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.coroutines.CoroutineContext as KCoroutineContext

/**
 * Windows variant of [TaoComposeSceneHost]. Drives a Compose scene onto the
 * Tao-owned HWND via the WGL helper, with custom title-bar decoration applied
 * by [NativeTaoWindowsDecoBridge].
 *
 * Threading: every public method runs on the thread that owns the Tao event
 * loop (Windows imposes no main-thread constraint, but the GL context is bound
 * to whatever thread called `nativeAttach`, so all rendering must stay on it).
 */
@OptIn(InternalComposeUiApi::class)
@Suppress("LargeClass", "TooManyFunctions")
internal class TaoComposeSceneHostWindows(
    private val window: TaoWindow,
    private val coroutineContext: CoroutineContext = EmptyCoroutineContext,
) {
    val titleBarHeightDpState: androidx.compose.runtime.MutableState<Float> =
        androidx.compose.runtime.mutableStateOf(0f)

    /** App-level pre-dispatch hook. See [TaoComposeSceneHost.previewKeyHandler]. */
    var previewKeyHandler: ((KeyEvent) -> Boolean)? = null

    /** App-level post-dispatch hook. See [TaoComposeSceneHost.keyHandler]. */
    var keyHandler: ((KeyEvent) -> Boolean)? = null

    /**
     * SemanticsOwnerListener installed when the host carries an a11y
     * controller. Wired through [WindowsTaoPlatformContext] so Compose's
     * BaseComposeScene picks it up. Set once before [attach].
     */
    var semanticsOwnerListener: androidx.compose.ui.platform.PlatformContext.SemanticsOwnerListener? = null

    private val windowInfo = WindowsTaoWindowInfo()
    private var currentKeyboardModifiers: PointerKeyboardModifiers = PointerKeyboardModifiers()
    private var attachmentHandle: Long = 0
    private var hwnd: Long = 0
    private var directContext: DirectContext? = null

    /**
     * True when the active backend is EGL/ANGLE (vs WGL). Cached at [attach].
     * Gates the off-thread present: ANGLE contexts on a shared EGLDisplay must
     * not be driven from the swap thread while a sibling host renders.
     */
    private var backendIsEgl: Boolean = false
    private var scene: ComposeScene? = null

    /**
     * Presents finished frames off the event-loop thread. [onRedrawRequested]
     * renders + flushes on this (event-loop) thread, releases the WGL context,
     * and signals the swap thread, which re-binds the context and calls the
     * vsync-blocking `SwapBuffers`. Keeping the present off this thread is what
     * lets input keep flowing during the refresh wait — mirrors the Linux EGL
     * swap thread. Created in [attach], stopped in [detach].
     */
    private var swapThread: SwapThread? = null

    /** Parent locals bridged via [setSceneCompositionLocalContext]; applied to the scene once created. */
    private var pendingCompositionLocalContext: androidx.compose.runtime.CompositionLocalContext? = null
    private val frameClock = BroadcastFrameClock()
    private val flushingDispatcher = FlushingMainDispatcher()

    /**
     * Scope for host-owned timers (currently only the trackpad-pinch idle-end
     * debounce). Runs on [flushingDispatcher] so resumed continuations land on
     * the event-loop thread; `delay` itself ticks on the shared coroutines
     * scheduler. Cancelled in [detach].
     */
    private val gestureScope = CoroutineScope(coroutineContext + flushingDispatcher + SupervisorJob())

    /** Floating text-selection bar shown on touch selection. */
    private val textToolbar = TaoTextToolbar()

    private var widthPx: Int = 0
    private var heightPx: Int = 0
    private var scale: Float = 1f

    private var lastPointerX: Float = 0f
    private var lastPointerY: Float = 0f

    // ── Scroll vsync heartbeat ──────────────────────────────────────────────
    // `System.nanoTime()` of the last scroll event. While within
    // [SCROLL_PUMP_WINDOW_NS] of it, the swap thread re-pumps a frame after every
    // present (see [maybeScheduleVsyncFrame]) so the smooth-scroll tween — driven
    // by the frame clock in [onRedrawRequested] — ticks at the display refresh
    // instead of the ~20 Hz wheel-event rate (WM_PAINT is starved during a
    // WM_MOUSEWHEEL flood). Volatile: written on the event-loop thread, read on
    // the swap thread.
    @Volatile
    private var lastScrollNanos: Long = 0L

    // Guards against piling up more than one queued heartbeat frame at a time
    // (the inline pump and the previous heartbeat can both try to re-arm).
    // Set on the swap thread, cleared on the event-loop thread.
    @Volatile
    private var vsyncFrameQueued: Boolean = false

    /**
     * Renderers registered by overlay/popup scenes. Drained AFTER the
     * main scene's render in [onRedrawRequested] so each tick paints
     * into every live overlay/popup HWND in the same Tao event-loop wake.
     *
     * Cross-context sync (per NATIVE_VIEW_WINDOWS_PLAN.md "Cross-context
     * synchronization"): before draining, we call
     * `directContext.flushAndSubmit()` so the GPU sees host commands
     * before any share-group consumer reads from them; after draining,
     * we re-make the host context current and call
     * `directContext.resetGLAll()` so Skia re-syncs its per-context GL
     * state cache (the overlay's own renderer will have switched contexts
     * behind Skia's back).
     */
    private val popupRenderers: MutableMap<Any, () -> Unit> = LinkedHashMap()

    /**
     * Key handlers consulted before the main scene's key dispatch
     * (Phase 8). Overlay scenes register here when they hold a focusable
     * Compose node.
     */
    private val popupKeyHandlers: MutableMap<Any, (KeyEvent) -> Boolean> = LinkedHashMap()

    /** Callbacks invoked when the owner window's screen position changes. */
    private val ownerMoveListeners: MutableMap<Any, () -> Unit> = LinkedHashMap()

    /** Callbacks invoked when the host window loses keyboard focus. */
    private val ownerFocusLostListeners: MutableMap<Any, () -> Unit> = LinkedHashMap()

    /** Callbacks invoked when the host window regains keyboard focus. */
    private val ownerFocusGainedListeners: MutableMap<Any, () -> Unit> = LinkedHashMap()

    /**
     * Callbacks invoked just before a popup scene layer
     * ([TaoPopupSceneLayerWindows]) destroys its HWND. Used by parent
     * scenes (overlay) to flush stuck focus state.
     */
    private val popupClosingListeners: MutableMap<Any, () -> Unit> = LinkedHashMap()

    /**
     * Set whenever something on the same thread might have changed the
     * current WGL context behind Skia's back: a popup/overlay renderer
     * registered (its DirectContext.makeGL() does an internal
     * wglMakeCurrent), a popupRenderers tick ran. Consumed at the start
     * of [onRedrawRequested] — calls `directContext.resetGLAll()` on
     * the host's DirectContext so Skia re-fetches GL state before
     * `flushAndSubmit` issues commands.
     *
     * Without this, the host's DirectContext keeps a stale GL state
     * cache after an overlay's first paint and `flushAndSubmit` reaches
     * a NULL bind point inside the driver (reproduced on NVIDIA).
     */
    private var hostContextDirtied: Boolean = false

    // Frame pacing is delegated to VSync — `wglSwapIntervalEXT(1)` makes
    // SwapBuffers block until the next display refresh, which keeps Compose
    // animations (smooth scroll, etc.) aligned on the display cadence at the
    // monitor's native refresh rate (60/120/144/240 Hz — one frame per VBlank).
    // The blocking SwapBuffers runs on [swapThread], not the event-loop thread:
    // the event-loop thread renders, releases the context, and returns to pump
    // input while the present waits for the refresh. Blocking it inline instead
    // (the old path) starved input under a precision-touchpad WM_MOUSEWHEEL
    // flood, dropping animation frames and making smooth-scroll judder.

    fun attach() {
        check(NativeTaoBridge.isLoaded && NativeTaoGlBridge.isLoaded && NativeTaoWindowsDecoBridge.isLoaded) {
            "Tao Windows native libraries not loaded"
        }
        hwnd = NativeTaoBridge.nativeHwndHandle(window.handle)
        require(hwnd != 0L) { "HWND unavailable; window not yet realised" }

        // Install custom decoration (WndProc subclass + DwmExtendFrameIntoClientArea).
        // Title-bar height is set later — the value the TitleBar composable publishes
        // via SideEffect arrives after first composition.
        scale = NativeTaoBridge.nativeScaleFactor(window.handle) / 1000f
        val initialTitleBarPx = (titleBarHeightDpState.value * scale).toInt().coerceAtLeast(28)
        NativeTaoWindowsDecoBridge.nativeInstallDecoration(hwnd, initialTitleBarPx)

        // Preferred backend is ANGLE (Direct3D 11, WARP-capable on RDP/VMs);
        // it falls back to native WGL. The native side picks per
        // NUCLEUS_TAO_WIN_RENDER, but a successful EGL *context* doesn't
        // guarantee Skia can build a DirectContext on it — that's only known
        // here, after makeGLWithInterface. So if the EGL attachment can't back
        // a DirectContext we re-attach with WGL and retry.
        var handle = NativeTaoGlBridge.nativeAttach(hwnd)
        require(handle != 0L) { "Failed to create render context for HWND" }
        // ANGLE needs an EGL-assembled GL interface (eglGetProcAddress); WGL uses
        // the default. Returns null if Skia can't build a context on this backend.
        var ctx =
            try {
                if (NativeTaoGlBridge.nativeBackend(handle) == BACKEND_EGL) {
                    val intf = GLAssembledInterface.createFromNativePointers(0L, NativeTaoGlBridge.nativeEglGetProcFn())
                    DirectContext.makeGLWithInterface(intf)
                } else {
                    DirectContext.makeGL()
                }
            } catch (_: RuntimeException) {
                null
            }
        if (ctx == null && NativeTaoGlBridge.nativeBackend(handle) == BACKEND_EGL) {
            NativeTaoGlBridge.nativeDetach(handle)
            handle = NativeTaoGlBridge.nativeAttachWgl(hwnd)
            require(handle != 0L) { "Failed to create WGL context for HWND (ANGLE fallback)" }
            ctx = DirectContext.makeGL()
        }
        attachmentHandle = handle
        directContext = ctx ?: error("Failed to create DirectContext for HWND")
        backendIsEgl = NativeTaoGlBridge.nativeBackend(handle) == BACKEND_EGL
        attachedHostCount.incrementAndGet()

        // Start the presenter — WGL only. It parks until the first frame's
        // `requestSwap`, so it never touches the context before the event-loop
        // thread has released it.
        //
        // ANGLE/EGL deliberately gets NO swap thread: a cross-thread present on
        // ANGLE's shared per-display D3D11 device deadlocks the global display
        // lock — the swap thread blocks inside `eglSwapBuffers` holding the lock
        // while the event-loop thread's next `eglMakeCurrent` waits on it,
        // freezing the whole app (seen when a sibling host such as a
        // DecoratedDialog detaches). With no swap thread, every present runs
        // inline on the event-loop thread, fully serialised. The off-thread
        // present only ever mattered for WGL's vsync-blocking `SwapBuffers`
        // (touchpad-scroll smoothness); ANGLE's `eglSwapBuffers` paces fine
        // inline.
        swapThread = if (backendIsEgl) null else SwapThread(attachmentHandle).also { it.start() }

        @OptIn(ExperimentalComposeUiApi::class)
        val dndManager =
            dev.nucleusframework.window.tao.TaoDragAndDropManager(
                getRootNode = { scene!!.rootDragAndDropNode },
                outboundLauncher = ::launchWindowsOutboundDrag,
            )
        // Match the Linux backend for the main scene: keep Compose Popup /
        // DropdownMenu / Tooltip layers inside the same GL render target
        // instead of materialising them as native WS_POPUP windows. This
        // avoids Windows-only WGL/native-window compositor artifacts in the
        // custom title bar path. NativeView overlay scenes can still opt into
        // TaoComposeSceneContextWindows when they need popups outside their
        // overlay bounds.
        val platformContext =
            WindowsTaoPlatformContext(
                windowHandle = window.handle,
                // The custom title bar is drawn inside the same Compose scene as
                // the rest of the content, so it shares the (0, 0) origin with
                // everything else. We must NOT report it as a `PlatformInsets.top`:
                // Compose's `RootMeasurePolicy` (cf. RootMeasurePolicy.skiko.kt::
                // positionWithInsets) applies platform insets as an *additive
                // offset* on the popup position (designed for iOS notches /
                // Android status bars, where the safe area is outside the Compose
                // surface). Reporting `top = titleBarHeight` here shifts every
                // Popup, DropdownMenu, ContextMenu, and Tooltip down by that
                // amount — visible as a consistent "title-bar-height downward
                // drift" of every popup the user opens. Popups are free to
                // overlap the title bar zone; popup scene layers naturally float
                // above content via z-order. Same fix as Linux (commit 2d8ca500).
                topInsetPx = { 0 },
                windowInfo = windowInfo,
                semanticsOwnerListener = semanticsOwnerListener,
                dragAndDropManager = dndManager,
                textToolbar = textToolbar,
            )
        scene =
            CanvasLayersComposeScene(
                density = Density(scale),
                layoutDirection = GlobalLayoutDirection,
                coroutineContext = coroutineContext + frameClock + flushingDispatcher,
                platformContext = platformContext,
                invalidate = { window.requestRedraw() },
            ).apply { compositionLocalContext = pendingCompositionLocalContext }

        registerInboundDnD()
        registerTouchInput()

        // Notify overlay/popup layers when the host window moves on screen
        // — top-level WS_POPUP children of the owner don't auto-track.
        window.onMoved { _, _ -> onOwnerMoved() }

        // Notify overlay/popup layers when the host window loses keyboard
        // focus — for instance, the user clicked the embedded WebView,
        // which grabs Win32 focus and holds it. The overlay's
        // Compose-side TextField focus should release so its visual
        // indicator (highlight border, blinking caret) goes away.
        window.onFocusChanged { focused ->
            if (focused) onOwnerFocusGained() else onOwnerFocusLost()
        }
    }

    private fun onOwnerFocusLost() {
        if (ownerFocusLostListeners.isEmpty()) return
        for (cb in ownerFocusLostListeners.values.toList()) cb()
    }

    private fun onOwnerFocusGained() {
        if (ownerFocusGainedListeners.isEmpty()) return
        for (cb in ownerFocusGainedListeners.values.toList()) cb()
    }

    private fun markOwnerFocusedFromPointerInput() {
        if (windowInfo.isWindowFocused) return
        windowInfo.isWindowFocused = true
        onOwnerFocusGained()
    }

    // ── Touch (Windows) ───────────────────────────────────────────────────
    //
    // Tao routes Windows touchscreen input through WM_POINTER. Without routing
    // `WindowEvent::Touch` to Compose, `LazyColumn` scroll, drag gestures, and
    // `detectTransformGestures` (pinch / rotate) would not react on tablets /
    // 2-in-1s - same gap Compose Desktop officiel hits on this platform
    // (JBR-2702).
    //
    // The Rust side dispatches one event per finger update; we accumulate
    // the active set here and issue a single `sendPointerEvent` with the
    // full pointer list every time, since Compose treats absence as a
    // release.

    private data class ActiveTouch(
        val id: Long,
        var xPx: Float,
        var yPx: Float,
        var pressed: Boolean,
        var pressure: Float,
    )

    /** Insertion order matters for stable pointer ordering across events. */
    private val activeTouches = LinkedHashMap<Long, ActiveTouch>()

    private fun registerTouchInput() {
        window.onTouchInput { phase, id, xFixed, yFixed, forceFixed ->
            onTouchInput(phase, id, xFixed, yFixed, forceFixed)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun onTouchInput(
        phase: Int,
        id: Long,
        xFixed: Int,
        yFixed: Int,
        forceFixed: Int,
    ) {
        val sc = scene ?: return
        val xPx = xFixed / TOUCH_POSITION_SCALE
        val yPx = yFixed / TOUCH_POSITION_SCALE
        window.updateWindowsTitleBarTouchDrag(phase, id, xPx, yPx)
        val pressure =
            if (forceFixed == TaoTouchEvent.FORCE_UNKNOWN) {
                // No digitizer pressure data — Compose expects a non-zero value
                // for an active contact, so report the standard "average touch".
                1f
            } else {
                forceFixed / TOUCH_FORCE_SCALE
            }

        val composeType =
            when (phase) {
                TaoTouchEvent.PRESS -> {
                    markOwnerFocusedFromPointerInput()
                    activeTouches[id] = ActiveTouch(id, xPx, yPx, pressed = true, pressure = pressure)
                    PointerEventType.Press
                }
                TaoTouchEvent.MOVE -> {
                    val existing = activeTouches[id]
                    if (existing != null) {
                        existing.xPx = xPx
                        existing.yPx = yPx
                        existing.pressure = pressure
                        PointerEventType.Move
                    } else {
                        // Synthetic Press for an unknown id - defensive in case Tao
                        // ever forwards a Move without a prior Started (palm-reject
                        // race observed on some Surface drivers).
                        markOwnerFocusedFromPointerInput()
                        activeTouches[id] = ActiveTouch(id, xPx, yPx, pressed = true, pressure = pressure)
                        PointerEventType.Press
                    }
                }
                TaoTouchEvent.RELEASE, TaoTouchEvent.CANCEL -> {
                    val existing = activeTouches[id]
                    if (existing != null) {
                        existing.xPx = xPx
                        existing.yPx = yPx
                        existing.pressed = false
                    } else {
                        return
                    }
                    PointerEventType.Release
                }
                else -> return
            }

        val pointers =
            activeTouches.values.map { t ->
                ComposeScenePointer(
                    id = PointerId(t.id),
                    position = Offset(t.xPx, t.yPx),
                    pressed = t.pressed,
                    type = PointerType.Touch,
                    pressure = t.pressure,
                )
            }
        // Match Compose iOS (`ComposeSceneMediator.uikit.kt`): direct
        // touchscreen contacts are PointerType.Touch events with no
        // event-level button and an empty button mask. Skiko's primary
        // matcher treats Touch itself as primary; synthesising BUTTON1 here
        // prevents touch long-press/onClick matchers from recognizing it.
        sc.sendPointerEvent(
            eventType = composeType,
            pointers = pointers,
            keyboardModifiers = currentKeyboardModifiers,
        )

        // Purge after the dispatch so the JVM saw the released finger one
        // last time with `pressed=false` — same convention as Linux.
        if (phase == TaoTouchEvent.RELEASE || phase == TaoTouchEvent.CANCEL) {
            activeTouches.remove(id)
            if (phase == TaoTouchEvent.CANCEL) {
                sc.cancelPointerInput()
            }
        }
    }

    // ── Trackpad pinch-to-zoom (Ctrl-flagged WM_MOUSEWHEEL) ───────────────
    //
    // Windows delivers a precision-touchpad pinch (and a real Ctrl+wheel) as a
    // WM_MOUSEWHEEL carrying the Ctrl flag; the vendored Tao patch routes those
    // to the magnify hook (instead of a scroll, which would drive the
    // scrollable — the bug we're fixing). Each notch/tick is a discrete delta,
    // but pinch detection (`detectTransformGestures`) only crosses its touch
    // slop once distance has changed enough, so per-tick Press→Release bursts
    // would swallow fine touchpad zooms. We instead keep ONE continuous
    // two-finger Touch gesture: the first tick presses, every tick moves
    // (accumulating scale), and an idle debounce releases it — the same
    // continuous model the macOS path uses, so zoom is smooth and the gesture
    // never reaches the scrollable.

    private var pinchActive = false
    private var pinchScale = 1f
    private var pinchCenterX = 0f
    private var pinchCenterY = 0f
    private var pinchEndJob: Job? = null

    /**
     * Synthesises a two-finger pinch from one Ctrl+wheel tick. [valueFixed] is
     * the normalized wheel delta × [TRACKPAD_VALUE_SCALE] (positive = zoom in).
     * Only magnify gestures are produced on Windows, so kind/phase/x/y from the
     * shared `onTrackpadGesture` wire are ignored.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    fun onTrackpadGesture(
        @Suppress("UNUSED_PARAMETER") kind: Int,
        @Suppress("UNUSED_PARAMETER") phase: Int,
        @Suppress("UNUSED_PARAMETER") xFixed: Int,
        @Suppress("UNUSED_PARAMETER") yFixed: Int,
        valueFixed: Int,
    ) {
        if (scene == null) return
        currentKeyboardModifiers = taoKeyboardModifiers(window.modifierState)
        windowInfo.keyboardModifiers = currentKeyboardModifiers

        val value = valueFixed / TRACKPAD_VALUE_SCALE
        // Precision touchpads can deliver many fractional deltas; map the
        // WHEEL_DELTA-normalized value through a multiplicative curve so small
        // ticks accumulate smoothly without each message behaving like a large
        // zoom step.
        val step = TaoWindowsPinchZoom.stepFromWheelDelta(value)

        if (!pinchActive) {
            pinchActive = true
            pinchScale = 1f
            // Centre on the cursor = zoom focal point (the pinch doesn't move it).
            pinchCenterX = lastPointerX
            pinchCenterY = lastPointerY
            sendPinchPointers(PointerEventType.Press)
        }
        pinchScale *= step
        sendPinchPointers(PointerEventType.Move)
        schedulePinchEnd()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun sendPinchPointers(eventType: PointerEventType) {
        val sc = scene ?: return
        val radius = PINCH_BASE_RADIUS_PX * pinchScale
        val pressed = eventType != PointerEventType.Release
        val pointers =
            listOf(
                ComposeScenePointer(
                    id = PointerId(PINCH_POINTER_ID_A),
                    position = Offset(pinchCenterX - radius, pinchCenterY),
                    pressed = pressed,
                    type = PointerType.Touch,
                ),
                ComposeScenePointer(
                    id = PointerId(PINCH_POINTER_ID_B),
                    position = Offset(pinchCenterX + radius, pinchCenterY),
                    pressed = pressed,
                    type = PointerType.Touch,
                ),
            )
        sc.sendPointerEvent(
            eventType = eventType,
            pointers = pointers,
            keyboardModifiers = currentKeyboardModifiers,
        )
    }

    /** Re-arms the idle timer that releases the synthetic pinch once ticks stop. */
    private fun schedulePinchEnd() {
        pinchEndJob?.cancel()
        pinchEndJob =
            gestureScope.launch {
                delay(PINCH_IDLE_END_MS.milliseconds)
                endPinchGesture()
            }
    }

    private fun endPinchGesture() {
        pinchEndJob = null
        if (!pinchActive) return
        sendPinchPointers(PointerEventType.Release)
        pinchActive = false
        pinchScale = 1f
    }

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private fun launchWindowsOutboundDrag(
        request: dev.nucleusframework.window.tao.TaoDragAndDropManager.OutboundRequest,
    ): androidx.compose.ui.draganddrop.DragAndDropTransferAction? {
        if (!dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.isLoaded) return null
        if (hwnd == 0L) return null

        val allowed =
            request.supportedActions
                .fold(0) { acc, action ->
                    acc or
                        when (action) {
                            androidx.compose.ui.draganddrop.DragAndDropTransferAction.Copy ->
                                dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_COPY
                            androidx.compose.ui.draganddrop.DragAndDropTransferAction.Move ->
                                dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_MOVE
                            androidx.compose.ui.draganddrop.DragAndDropTransferAction.Link ->
                                dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_LINK
                            else -> 0
                        }
                }.let {
                    if (it == 0) {
                        dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_COPY
                    } else {
                        it
                    }
                }

        val files =
            request.files
                .takeIf { it.isNotEmpty() }
                ?.map { it.absolutePath }
                ?.toTypedArray()
        val effect =
            dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.nativeStartDrag(
                hwnd = hwnd,
                files = files,
                text = request.text,
                allowedEffects = allowed,
            )
        return when (effect) {
            dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_COPY ->
                androidx.compose.ui.draganddrop.DragAndDropTransferAction.Copy
            dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_MOVE ->
                androidx.compose.ui.draganddrop.DragAndDropTransferAction.Move
            dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_LINK ->
                androidx.compose.ui.draganddrop.DragAndDropTransferAction.Link
            else -> null
        }
    }

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private fun registerInboundDnD() {
        if (!dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.isLoaded) {
            dev.nucleusframework.window.tao.TaoDnDDiagnostics.log(
                "windows DnD lib not loaded — inbound disabled",
            )
            return
        }
        val callback = InboundDnDCallback()
        val rc =
            dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge
                .nativeRegister(hwnd, callback)
        dev.nucleusframework.window.tao.TaoDnDDiagnostics
            .log("RegisterDragDrop rc=$rc")
    }

    /**
     * Named (non-anonymous) callback class so GraalVM JNI reachability metadata
     * can register it explicitly. Anonymous classes inheriting JNI-accessible
     * interface methods aren't picked up by `GetMethodID` under native-image.
     */
    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private inner class InboundDnDCallback :
        dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.Callback {
        private fun rootNode() = scene?.rootDragAndDropNode

        private fun makeDragEvent(
            xPx: Int,
            yPx: Int,
            files: Array<String>?,
        ): androidx.compose.ui.draganddrop.DragAndDropEvent {
            val payload =
                dev.nucleusframework.window.tao.TaoDragAndDropPayload(
                    files = files?.toList() ?: emptyList(),
                )
            val transferable =
                dev.nucleusframework.window.tao.TaoFilesTransferable(
                    files = payload.files.map { java.io.File(it) },
                )
            val native =
                dev.nucleusframework.window.tao.TaoSyntheticDragEvent(
                    cursorLocn = java.awt.Point(xPx, yPx),
                    dropAction = java.awt.dnd.DnDConstants.ACTION_COPY,
                    backingTransferable = transferable,
                    payload = payload,
                )
            return androidx.compose.ui.draganddrop.DragAndDropEvent(
                action = androidx.compose.ui.draganddrop.DragAndDropTransferAction.Copy,
                nativeEvent = native,
                positionInRootImpl =
                    androidx.compose.ui.geometry
                        .Offset(xPx.toFloat(), yPx.toFloat()),
            )
        }

        private fun makeDropEvent(
            xPx: Int,
            yPx: Int,
            files: Array<String>?,
        ): androidx.compose.ui.draganddrop.DragAndDropEvent {
            val payload =
                dev.nucleusframework.window.tao.TaoDragAndDropPayload(
                    files = files?.toList() ?: emptyList(),
                )
            val transferable =
                dev.nucleusframework.window.tao.TaoFilesTransferable(
                    files = payload.files.map { java.io.File(it) },
                )
            val native =
                dev.nucleusframework.window.tao.TaoSyntheticDropEvent(
                    cursorLocn = java.awt.Point(xPx, yPx),
                    dropAction = java.awt.dnd.DnDConstants.ACTION_COPY,
                    backingTransferable = transferable,
                    payload = payload,
                )
            return androidx.compose.ui.draganddrop.DragAndDropEvent(
                action = androidx.compose.ui.draganddrop.DragAndDropTransferAction.Copy,
                nativeEvent = native,
                positionInRootImpl =
                    androidx.compose.ui.geometry
                        .Offset(xPx.toFloat(), yPx.toFloat()),
            )
        }

        override fun onDragEnter(
            hwnd: Long,
            x: Int,
            y: Int,
            keyState: Int,
            hasFiles: Boolean,
        ): Int {
            dev.nucleusframework.window.tao.TaoDnDDiagnostics.log(
                "onDragEnter x=$x y=$y hasFiles=$hasFiles",
            )
            if (!hasFiles) {
                return dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            }
            val node =
                rootNode()
                    ?: return dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            val ev = makeDragEvent(x, y, null)
            val accepted = node.acceptDragAndDropTransfer(ev)
            if (accepted) {
                node.onStarted(ev)
                node.onEntered(ev)
            }
            return if (accepted) {
                dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_COPY
            } else {
                dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            }
        }

        override fun onDragOver(
            hwnd: Long,
            x: Int,
            y: Int,
            keyState: Int,
            hasFiles: Boolean,
        ): Int {
            val node =
                rootNode()
                    ?: return dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            val ev = makeDragEvent(x, y, null)
            node.onMoved(ev)
            return if (node.hasEligibleDropTarget) {
                dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_COPY
            } else {
                dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            }
        }

        override fun onDragLeave(hwnd: Long) {
            dev.nucleusframework.window.tao.TaoDnDDiagnostics
                .log("onDragLeave")
            val node = rootNode() ?: return
            val ev = makeDragEvent(-1, -1, null)
            node.onExited(ev)
            node.onEnded(ev)
        }

        override fun onDrop(
            hwnd: Long,
            x: Int,
            y: Int,
            keyState: Int,
            files: Array<String>?,
        ): Int {
            dev.nucleusframework.window.tao.TaoDnDDiagnostics.log(
                "onDrop x=$x y=$y files=${files?.size ?: 0}",
            )
            val node =
                rootNode()
                    ?: return dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            val ev = makeDropEvent(x, y, files)
            val accepted = node.onDrop(ev)
            node.onEnded(ev)
            return if (accepted) {
                dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_COPY
            } else {
                dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            }
        }
    }

    fun setContent(content: @Composable () -> Unit) {
        scene?.setContent {
            // Chromium-style fixed pixels-per-tick scrolling instead of Compose's
            // viewport-relative WindowsWinUIConfig (see ChromeScrollConfig).
            ProvideChromeScrollConfig {
                TaoTextToolbarHost(textToolbar, content)
            }
        }
    }

    /**
     * Forwards a parent composition's locals into this scene via
     * `ComposeScene.compositionLocalContext` — applied above the scene's own
     * `LocalComposeSceneContext`, so popups keep routing into THIS scene. See
     * [dev.nucleusframework.window.tao.LocalTaoCompositionLocalContextBridge].
     */
    fun setSceneCompositionLocalContext(context: androidx.compose.runtime.CompositionLocalContext?) {
        pendingCompositionLocalContext = context
        scene?.compositionLocalContext = context
    }

    fun onResized(
        widthPxNew: Int,
        heightPxNew: Int,
    ) {
        // Win32 emits WM_SIZE/SIZE_MINIMIZED as 0x0. Keep the last real
        // ComposeScene size so taskbar previews and restore do not collapse.
        if (widthPxNew <= 0 || heightPxNew <= 0) return
        if (widthPxNew == widthPx && heightPxNew == heightPx) return
        widthPx = widthPxNew
        heightPx = heightPxNew
        NativeTaoGlBridge.nativeResize(attachmentHandle, widthPx, heightPx, scale)
        scene?.size = IntSize(widthPx, heightPx)
        updateWindowInfoSize()
        window.requestRedraw()
    }

    fun onScaleFactorChanged(newScale: Float) {
        if (newScale == scale) return
        scale = newScale
        scene?.density = Density(scale)
        NativeTaoGlBridge.nativeResize(attachmentHandle, widthPx, heightPx, scale)
        // Re-publish title-bar height in physical pixels so the deco WndProc
        // keeps its hit-test caption zone in sync after a DPI change.
        NativeTaoWindowsDecoBridge.nativeSetTitleBarHeight(
            hwnd,
            (titleBarHeightDpState.value * scale).toInt(),
        )
        updateWindowInfoSize()
        window.requestRedraw()
    }

    fun onFocusChanged(focused: Boolean) {
        windowInfo.isWindowFocused = focused
    }

    private fun updateWindowInfoSize() {
        windowInfo.containerSize = IntSize(widthPx, heightPx)
        if (scale > 0f) {
            val dpW = (widthPx / scale)
            val dpH = (heightPx / scale)
            windowInfo.containerDpSize = DpSize(dpW.dp, dpH.dp)
        }
    }

    fun onRedrawRequested() {
        // ── Off-thread vsync pacing ───────────────────────────────────────
        // The previous frame's `SwapBuffers` runs on the dedicated swap thread
        // and blocks until the display refresh. Wait for it to finish (and
        // release the WGL context) before rendering the next frame. While the
        // swap thread is parked in `SwapBuffers`, *this* thread keeps draining
        // the Tao event loop — so a touchpad's WM_MOUSEWHEEL flood no longer
        // queues behind a 16 ms main-thread present, which is what made the
        // smooth-scroll animation drop frames and judder (mouse/JBR were fine
        // because they never blocked input on the present). If the swap is
        // still in flight past the timeout (minimised/occluded window), skip
        // this frame; Compose re-arms a redraw on the next invalidation.
        val st = swapThread
        if (st != null && !st.waitForIdle()) return

        val ctx = directContext ?: return
        val sc = scene ?: return

        if (widthPx <= 0 || heightPx <= 0) return
        val now = System.nanoTime()

        // ── Frame clock ordering ──────────────────────────────────────────
        // Tick the frame clock BEFORE rendering and drain twice. Without this
        // the smooth-scroll animation (and any other `withFrameNanos`-driven
        // animation) lags one frame behind: `sendFrame` resumes the awaiting
        // continuations which then mutate state, but if we render first the
        // composition reads the *previous* frame's state. JNI / Skiko's
        // default loop ticks before render, so to match that feel we mirror
        // the order here.
        flushingDispatcher.drain()
        frameClock.sendFrame(now)
        flushingDispatcher.drain()

        // Make sure the WGL context is current on this thread (defensive — it
        // already was since `attach`, but other tools/tests can clear it).
        NativeTaoGlBridge.nativeMakeCurrent(attachmentHandle)
        // Consume the dirtied flag: an overlay/popup created since our last
        // tick did its own DirectContext.makeGL() (internal wglMakeCurrent
        // on a sibling HGLRC), or a popupRenderers loop swapped contexts.
        // Tell Skia "external code touched GL state" so it re-fetches via
        // glGet* before issuing flush/submit commands. resetGLAll is cheap
        // (state-cache invalidation only); calling it on every frame
        // unconditionally is too heavy for some drivers (nvoglv64 chokes),
        // so we gate on the flag.
        // Sibling-host mode: another TaoComposeSceneHostWindows is alive
        // (e.g., DecoratedDialog over a DecoratedWindow). Each host owns
        // its own HGLRC + DirectContext, and the dialog's onRedrawRequested
        // can run between our frames — swapping the current WGL context
        // behind our back. Our DirectContext's per-context GL state cache
        // is then stale relative to GL, and the next flushAndSubmit
        // reaches a NULL pointer inside the driver. Force resetGLAll on
        // every frame entry while >1 host coexists; revert to the
        // popup-only flag-gated path once it's just us.
        if (hostContextDirtied || attachedHostCount.get() > 1) {
            ctx.resetGLAll()
            hostContextDirtied = false
        }

        // Wrap the default framebuffer (id 0). Skia's GL backend uses
        // BOTTOM_LEFT origin with the GL convention; SurfaceOrigin handles the
        // flip so Compose draws right-side up.
        val rt =
            BackendRenderTarget.makeGL(
                width = widthPx,
                height = heightPx,
                sampleCnt = 0,
                stencilBits = 8,
                fbId = 0,
                fbFormat = FramebufferFormat.GR_GL_RGBA8,
            )
        val surface =
            Surface.makeFromBackendRenderTarget(
                context = ctx,
                rt = rt,
                origin = SurfaceOrigin.BOTTOM_LEFT,
                colorFormat = SurfaceColorFormat.RGBA_8888,
                colorSpace = ColorSpace.sRGB,
            ) ?: run {
                rt.close()
                return
            }

        try {
            surface.canvas.clear(0xFFFFFFFF.toInt())
            sc.render(surface.canvas.asComposeCanvas(), now)
            // `flushAndSubmit` issues the glFlush that commits the frame to the
            // back buffer; the actual `SwapBuffers` (present) is deferred to the
            // swap thread below. The popup cross-context sync only needs the
            // flush — not the present — so running popups before the present is
            // safe.
            surface.flushAndSubmit(syncCpu = false)
        } finally {
            surface.close()
            rt.close()
        }

        // Drain overlay/popup renderers. Cross-context sync (per
        // NATIVE_VIEW_WINDOWS_PLAN.md "Cross-context synchronization"):
        //   1. Host already flushed above (flushAndSubmit issues glFlush
        //      internally when committing the surface).
        //   2. Each renderer below switches to its own HGLRC, calls
        //      resetGLAll on its own DirectContext, paints, swaps.
        //   3. We flag the host DirectContext dirty so the next frame's entry
        //      runs resetGLAll — Skia's GL state cache no longer reflects truth
        //      after the external context switches.
        // Popups run on this thread and finish before we hand the host context
        // to the swap thread, so the host present never races a popup's
        // context switch.
        if (popupRenderers.isNotEmpty()) {
            val snapshot = popupRenderers.values.toList()
            for (render in snapshot) render()
            hostContextDirtied = true
        }

        // Hand the host context to the swap thread for the vsync-blocking
        // `SwapBuffers`. Release it here first: a GL context is current on one
        // thread at a time, and the swap thread re-binds it via
        // `nativeMakeCurrent`. This thread then returns to the event loop and
        // keeps processing input while the present blocks for the refresh.
        //
        // ANGLE/EGL has no swap thread (see `attach`), so `st` is null and the
        // present runs inline below. WGL hands off to the swap thread for the
        // vsync-blocking `SwapBuffers`.
        if (st != null) {
            NativeTaoGlBridge.nativeReleaseCurrent(attachmentHandle)
            st.requestSwap()
        } else {
            NativeTaoGlBridge.nativePresent(attachmentHandle)
        }
    }

    fun onPointerMove(
        aFixed: Int,
        bFixed: Int,
    ) {
        val xPx = aFixed / 1024f
        val yPx = bFixed / 1024f
        lastPointerX = xPx
        lastPointerY = yPx
        currentKeyboardModifiers = taoKeyboardModifiers(window.modifierState)
        windowInfo.keyboardModifiers = currentKeyboardModifiers
        scene?.sendPointerEvent(
            eventType = PointerEventType.Move,
            position = Offset(xPx, yPx),
            type = PointerType.Mouse,
            keyboardModifiers = currentKeyboardModifiers,
        )
    }

    fun onPointerExited() {
        if (
            hwnd != 0L &&
            NativeTaoWindowsDecoBridge.isLoaded &&
            NativeTaoWindowsDecoBridge.nativeIsCursorOverWindowOrOwnedPopup(hwnd)
        ) {
            return
        }
        currentKeyboardModifiers = taoKeyboardModifiers(window.modifierState)
        windowInfo.keyboardModifiers = currentKeyboardModifiers
        scene?.sendPointerEvent(
            eventType = PointerEventType.Exit,
            position = Offset(lastPointerX, lastPointerY),
            type = PointerType.Mouse,
            keyboardModifiers = currentKeyboardModifiers,
        )
    }

    fun onPointerButton(
        buttonCode: Int,
        pressed: Boolean,
    ) {
        currentKeyboardModifiers = taoKeyboardModifiers(window.modifierState)
        windowInfo.keyboardModifiers = currentKeyboardModifiers
        scene?.sendPointerEvent(
            eventType = if (pressed) PointerEventType.Press else PointerEventType.Release,
            position = Offset(lastPointerX, lastPointerY),
            type = PointerType.Mouse,
            keyboardModifiers = currentKeyboardModifiers,
            button = mapButton(buttonCode),
        )
    }

    fun onPointerScroll(event: TaoPointerScrollEvent) {
        currentKeyboardModifiers = taoKeyboardModifiers(window.modifierState)
        windowInfo.keyboardModifiers = currentKeyboardModifiers
        scene?.sendPointerEvent(
            eventType = PointerEventType.Scroll,
            position = Offset(lastPointerX, lastPointerY),
            scrollDelta = Offset(event.dxAwt, event.dyAwt),
            type = PointerType.Mouse,
            keyboardModifiers = currentKeyboardModifiers,
            nativeEvent =
                TaoSyntheticMouseWheelEvent.create(
                    event = event,
                    x = lastPointerX,
                    y = lastPointerY,
                    keyboardModifiers = currentKeyboardModifiers,
                ),
        )

        // WM_PAINT-starvation mitigation for brisk scrolling. Our frame clock
        // only ticks in [onRedrawRequested], which fires from `WM_PAINT` — the
        // lowest-priority Win32 message, synthesized only when the queue is
        // otherwise empty. A precision-touchpad / fast-wheel flood keeps
        // `WM_MOUSEWHEEL` queued continuously, starving `WM_PAINT`: the
        // smooth-scroll animation freezes mid-flood then lurches (judder).
        // AWT/JBR don't hit this — their frame clock isn't `WM_PAINT`-driven.
        //
        // Compose's smooth-scroll animation is frame-clock driven (one tick per
        // `frameClock.sendFrame`, i.e. one per [onRedrawRequested]). The inline
        // pump below renders one frame per wheel event — but wheel events arrive
        // at only ~20 Hz, so on their own they tick the tween at ~20 fps = the
        // residual judder. So we also arm a vsync heartbeat: [lastScrollNanos]
        // opens a window during which the swap thread re-pumps a frame after
        // every present (see [maybeScheduleVsyncFrame]), driving the tween at the
        // full display refresh independent of the wheel-event rate. The window
        // resets on each event, so sustained scrolling stays at 60 fps and a
        // flick's tail keeps animating until the tween settles.
        lastScrollNanos = System.nanoTime()

        // Inline bootstrap: render the first frame here (with the GL context
        // already current on this thread) when the previous present has completed
        // (`waitForIdle(0)` — non-blocking, never stalls input). That present then
        // kicks off the swap-thread heartbeat. EGL/ANGLE presents inline (no swap
        // thread, so no heartbeat) — pumping would block input, so skip it there
        // and keep the `WM_PAINT` path.
        val pumpSwap = swapThread
        if (pumpSwap != null && pumpSwap.waitForIdle(0L)) onRedrawRequested()
    }

    /**
     * Re-pumps a frame at the display refresh while a scroll is active, so
     * Compose's frame-clock-driven smooth-scroll animation ticks at vsync rather
     * than the ~20 Hz wheel-event rate. Called on the swap thread right after
     * each present (the present's vsync wait is what paces the loop). Posts the
     * render to [TaoMainDispatcher] — drained on `MAIN_EVENTS_CLEARED`, which (unlike
     * `WM_PAINT`) is not starved by the `WM_MOUSEWHEEL` flood. The posted block
     * re-renders → re-presents → re-arms, sustaining a vsync-paced loop until the
     * [SCROLL_PUMP_WINDOW_NS] window lapses, then it quiesces (no idle cost: with
     * nothing rendering there are no presents, so this is never called).
     */
    private fun maybeScheduleVsyncFrame() {
        if (System.nanoTime() - lastScrollNanos >= SCROLL_PUMP_WINDOW_NS) return
        if (vsyncFrameQueued) return
        vsyncFrameQueued = true
        TaoMainDispatcher.dispatch(EmptyCoroutineContext) {
            vsyncFrameQueued = false
            if (System.nanoTime() - lastScrollNanos < SCROLL_PUMP_WINDOW_NS) onRedrawRequested()
        }
    }

    fun onKeyEvent(
        type: Int,
        vkCode: Int,
        keyLocation: Int,
        modifiers: Int,
        codePoint: Int,
    ): Boolean {
        val sc = scene ?: return false
        currentKeyboardModifiers = taoKeyboardModifiers(modifiers)
        windowInfo.keyboardModifiers = currentKeyboardModifiers
        val isCtrl = (modifiers and TaoModifierMask.CONTROL) != 0
        val isMeta = (modifiers and TaoModifierMask.META) != 0
        val isAlt = (modifiers and TaoModifierMask.ALT) != 0
        val isShift = (modifiers and TaoModifierMask.SHIFT) != 0
        val composeEvent =
            when (type) {
                TaoEventCode.KEY_DOWN, TaoEventCode.KEY_UP ->
                    taoKeyEvent(
                        keyDown = type == TaoEventCode.KEY_DOWN,
                        vkCode = vkCode,
                        keyLocation = keyLocation,
                        isShift = isShift,
                        isCtrl = isCtrl,
                        isAlt = isAlt,
                        isMeta = isMeta,
                        codePoint = codePoint,
                    )
                TaoEventCode.KEY_TYPED ->
                    taoTypedKeyEvent(codePoint, keyLocation, isShift, isCtrl, isAlt, isMeta)
                else -> return false
            }
        if (previewKeyHandler?.invoke(composeEvent) == true) return true
        // Overlay/popup scenes get a chance to consume the event before
        // the main scene. Mirrors the macOS popupKeyHandlers chain.
        for (handler in popupKeyHandlers.values) {
            if (handler(composeEvent)) return true
        }
        if (sc.sendKeyEvent(composeEvent)) return true
        return keyHandler?.invoke(composeEvent) == true
    }

    /** Push the latest title-bar height (in dp) down to the deco WndProc so
     *  the caption hit-test zone matches the Compose layout. */
    fun syncTitleBarHeight() {
        if (hwnd == 0L) return
        val px = (titleBarHeightDpState.value * scale).toInt().coerceAtLeast(0)
        NativeTaoWindowsDecoBridge.nativeSetTitleBarHeight(hwnd, px)
    }

    fun setTitleBarBackgroundColor(argb: Int) {
        if (hwnd != 0L) NativeTaoWindowsDecoBridge.nativeSetBackgroundColor(hwnd, argb)
    }

    /** Current scale factor (logical→physical multiplier). */
    fun density(): Float = scale

    fun popupHost(): TaoPopupHostWindows? {
        if (hwnd == 0L) return null
        val ctx = directContext ?: return null
        val outer = this
        return object : TaoPopupHostWindows {
            override val parentHwnd: Long get() = outer.hwnd
            override val scale: Float get() = outer.scale
            override val parentWindowSize: IntSize get() = IntSize(outer.widthPx, outer.heightPx)
            override val workAreaSize: IntSize get() {
                // Use the primary monitor's work area resolved via the
                // existing JNI bridge — avoids touching AWT
                // (GraphicsEnvironment.getLocalGraphicsEnvironment) on the
                // Tao UI thread, which on Windows can lazily initialise
                // Java2D's D3D pipeline and conflict with the WGL context
                // bound to this thread (manifested as a hang + crash when
                // a second host attached, e.g. on DecoratedDialog open).
                if (!NativeTaoWindowsDecoBridge.isLoaded) return parentWindowSize
                val area =
                    NativeTaoWindowsDecoBridge.nativeGetPrimaryMonitorWorkArea()
                        ?: return parentWindowSize
                if (area.size < 4) return parentWindowSize
                val w = area[2].toInt().coerceAtLeast(1)
                val h = area[3].toInt().coerceAtLeast(1)
                return IntSize(w, h)
            }
            override val sceneCoroutineContext: kotlin.coroutines.CoroutineContext
                get() = outer.coroutineContext + outer.frameClock + outer.flushingDispatcher
            override val hostDirectContext: DirectContext get() = ctx

            override fun requestRedraw() = outer.window.requestRedraw()

            override fun registerRenderer(
                token: Any,
                render: () -> Unit,
            ) {
                outer.popupRenderers[token] = render
                // Registration site does DirectContext.makeGL() which
                // switches the WGL context behind Skia's back — the
                // host's GL state cache is now stale.
                outer.hostContextDirtied = true
            }

            override fun unregisterRenderer(token: Any) {
                outer.popupRenderers.remove(token)
                outer.hostContextDirtied = true
            }

            override fun registerKeyHandler(
                token: Any,
                handler: (KeyEvent) -> Boolean,
            ) {
                outer.popupKeyHandlers[token] = handler
            }

            override fun unregisterKeyHandler(token: Any) {
                outer.popupKeyHandlers.remove(token)
            }

            override fun registerOwnerMoveListener(
                token: Any,
                onMoved: () -> Unit,
            ) {
                outer.ownerMoveListeners[token] = onMoved
            }

            override fun unregisterOwnerMoveListener(token: Any) {
                outer.ownerMoveListeners.remove(token)
            }

            override fun registerOwnerFocusLostListener(
                token: Any,
                onLost: () -> Unit,
            ) {
                outer.ownerFocusLostListeners[token] = onLost
            }

            override fun unregisterOwnerFocusLostListener(token: Any) {
                outer.ownerFocusLostListeners.remove(token)
            }

            override fun registerOwnerFocusGainedListener(
                token: Any,
                onGained: () -> Unit,
            ) {
                outer.ownerFocusGainedListeners[token] = onGained
            }

            override fun unregisterOwnerFocusGainedListener(token: Any) {
                outer.ownerFocusGainedListeners.remove(token)
            }

            override fun notifyPopupClosing() {
                if (outer.popupClosingListeners.isEmpty()) return
                for (cb in outer.popupClosingListeners.values.toList()) cb()
            }

            override fun registerPopupClosingListener(
                token: Any,
                onClosing: () -> Unit,
            ) {
                outer.popupClosingListeners[token] = onClosing
            }

            override fun unregisterPopupClosingListener(token: Any) {
                outer.popupClosingListeners.remove(token)
            }
        }
    }

    /** Fired by the [TaoWindow.onMoved] hook installed in [attach]. */
    private fun onOwnerMoved() {
        if (ownerMoveListeners.isEmpty()) return
        for (cb in ownerMoveListeners.values.toList()) cb()
    }

    fun nativeViewHost(): dev.nucleusframework.window.tao.TaoNativeViewHost? {
        if (hwnd == 0L) return null
        if (!dev.nucleusframework.window.tao.NativeTaoWindowsNativeViewBridge.isLoaded) return null
        val parent = hwnd
        return object : dev.nucleusframework.window.tao.TaoNativeViewHost {
            override fun attach(childHandle: Long) {
                dev.nucleusframework.window.tao.NativeTaoWindowsNativeViewBridge
                    .nativeAttach(parent, childHandle)
            }

            override fun detach(childHandle: Long) {
                dev.nucleusframework.window.tao.NativeTaoWindowsNativeViewBridge
                    .nativeDetach(childHandle)
            }

            override fun setFrame(
                handle: Long,
                xPx: Int,
                yPx: Int,
                widthPx: Int,
                heightPx: Int,
            ) {
                dev.nucleusframework.window.tao.NativeTaoWindowsNativeViewBridge
                    .nativeSetFrame(parent, handle, xPx, yPx, widthPx, heightPx)
            }

            override fun setCornerRadius(
                handle: Long,
                radiusPx: Float,
            ) {
                dev.nucleusframework.window.tao.NativeTaoWindowsNativeViewBridge
                    .nativeSetCornerRadius(parent, handle, radiusPx)
            }
        }
    }

    // A11y sync is debounced on a timer rather than run once per render tick.
    // The SemanticsOwner walk in TaoSemanticsObserver is O(N); during a scroll
    // `onLayoutChange`/`onSemanticsChange` fire every frame, so a per-frame walk
    // stutters scrolling — most visibly once a UIA client (Narrator, NVDA) is
    // attached. Debouncing collapses a burst of changes into a single walk once
    // activity settles (trailing edge), with a max-wait so sustained activity
    // still refreshes the tree periodically for assistive tech. The tree
    // therefore stays fresh enough for on-demand AX queries without ever
    // running on the per-frame hot path. Mirrors the macOS [TaoComposeSceneHost].
    private val a11yScheduler =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "TaoA11yDebounce").apply { isDaemon = true }
        }

    @Volatile
    private var a11yPendingBlock: (() -> Unit)? = null

    @Volatile
    private var a11yFuture: ScheduledFuture<*>? = null
    private var a11yFirstRequestNs = 0L

    /**
     * Schedules [block] (a SemanticsOwner walk + snapshot push) to run on the
     * render thread after changes settle. Coalesces a burst of per-frame change
     * notifications into one debounced run; see the field comment above.
     */
    fun scheduleA11ySync(block: () -> Unit) {
        if (a11yScheduler.isShutdown) return
        a11yPendingBlock = block
        val now = System.nanoTime()
        if (a11yFirstRequestNs == 0L) a11yFirstRequestNs = now
        val waitedMs = (now - a11yFirstRequestNs) / 1_000_000L
        val delayMs = if (waitedMs >= A11Y_SYNC_MAX_WAIT_MS) 0L else A11Y_SYNC_DEBOUNCE_MS
        a11yFuture?.cancel(false)
        a11yFuture =
            try {
                a11yScheduler.schedule(
                    {
                        val b = a11yPendingBlock
                        a11yPendingBlock = null
                        a11yFirstRequestNs = 0L
                        if (b != null) {
                            // Hop to the render thread — the walk touches Compose state.
                            flushingDispatcher.enqueue(Runnable { b() })
                            window.requestRedraw()
                        }
                    },
                    delayMs,
                    TimeUnit.MILLISECONDS,
                )
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                null
            }
    }

    fun detach() {
        a11yFuture?.cancel(false)
        a11yScheduler.shutdownNow()
        textToolbar.hide()
        // Stop the pinch idle timer; the scene is going away so no Release needed.
        pinchEndJob?.cancel()
        pinchEndJob = null
        pinchActive = false
        gestureScope.cancel()
        // Stop the presenter before touching GL: it must not be parked inside
        // `SwapBuffers` (holding the WGL context on its thread) while we make
        // the context current here and destroy Skia resources. shutdownAndJoin
        // waits for any in-flight present to finish and release the context.
        swapThread?.shutdownAndJoin()
        swapThread = null
        // Make THIS host's GL context current before tearing down Skia
        // resources. A sibling host (e.g. the main window opened while this
        // one — the onboarding window — closes) may have left its own HGLRC
        // current on the shared event-loop thread after its last frame.
        // Destroying our scene + DirectContext against a foreign context makes
        // Skia issue glDelete* on the wrong context and faults inside the
        // driver (0xC0000005). Same defensive make-current as onRedrawRequested.
        if (attachmentHandle != 0L) {
            NativeTaoGlBridge.nativeMakeCurrent(attachmentHandle)
        }
        scene?.close()
        scene = null
        if (directContext != null) {
            directContext?.close()
            directContext = null
            attachedHostCount.decrementAndGet()
        }
        if (attachmentHandle != 0L) {
            NativeTaoGlBridge.nativeDetach(attachmentHandle)
            attachmentHandle = 0L
        }
        if (hwnd != 0L) {
            if (dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.isLoaded) {
                dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge
                    .nativeRevoke(hwnd)
            }
            NativeTaoWindowsDecoBridge.nativeUninstallDecoration(hwnd)
            hwnd = 0L
        }
    }

    private companion object {
        // ponytail: how long after a scroll event the vsync heartbeat keeps
        // re-pumping frames. Must outlast Compose's smooth-scroll tween (≤100 ms
        // animation + 50 ms ScrollProgressTimeout); 180 ms covers it with margin.
        // Each event resets the window, so this only governs the post-flick tail.
        private const val SCROLL_PUMP_WINDOW_NS: Long = 180_000_000L

        /** Native backend kind reported by [NativeTaoGlBridge.nativeBackend] (EGL/ANGLE). */
        private const val BACKEND_EGL: Int = 1

        // Wire scales — must match Rust `CURSOR_FIXED_SCALE` and
        // `TOUCH_FORCE_FIXED_SCALE` in `events.rs`.
        private const val TOUCH_POSITION_SCALE: Float = 1024f
        private const val TOUCH_FORCE_SCALE: Float = 10_000f

        /**
         * Trackpad pinch (Ctrl+wheel → magnify) wire scale — matches Rust
         * `TRACKPAD_VALUE_FIXED_SCALE` in `events.rs`.
         */
        private const val TRACKPAD_VALUE_SCALE: Float = 10_000f

        /** Half-distance of the synthetic two-finger pair at scale 1.0. */
        private const val PINCH_BASE_RADIUS_PX: Float = 120f

        // Stable ids well clear of real touch ids (raw WM_POINTER finger ids).
        private const val PINCH_POINTER_ID_A: Long = 0xA001L
        private const val PINCH_POINTER_ID_B: Long = 0xA002L

        /** Idle gap after the last tick before the synthetic pinch releases. */
        private const val PINCH_IDLE_END_MS: Long = 120L

        // A11y debounce: run the SemanticsOwner walk ~this long after the last
        // change (so a scroll's per-frame change burst collapses to one walk
        // once it settles), but never wait longer than the max so assistive
        // tech still sees periodic refreshes during sustained scrolling.
        private const val A11Y_SYNC_DEBOUNCE_MS: Long = 120L
        private const val A11Y_SYNC_MAX_WAIT_MS: Long = 600L

        /**
         * Live attached-host count across the JVM. When > 1, every host
         * shares the process with at least one sibling that owns its own
         * HGLRC and DirectContext (e.g., main window + DecoratedDialog).
         * Skia's per-DirectContext GL state cache can drift any time the
         * other host's onRedrawRequested swaps WGL contexts behind our
         * back, so we resetGLAll on every frame entry in that regime.
         * The flag-gated path stays for the single-host case to keep the
         * single-window hot path cheap.
         */
        private val attachedHostCount =
            java
                .util
                .concurrent
                .atomic
                .AtomicInteger(0)
    }

    /**
     * Presents finished frames on a dedicated thread so the event-loop thread
     * never blocks in `SwapBuffers` (which waits for vsync with
     * `wglSwapIntervalEXT(1)`). The event-loop thread renders, releases the WGL
     * context, then calls [requestSwap]; the swap thread re-binds via
     * `nativeMakeCurrent`, presents (blocking on the refresh), and releases the
     * context again. [waitForIdle] synchronises the next render — that's what
     * gives hardware-vsync pacing for free.
     *
     * The two threads never hold the context simultaneously: the render thread
     * always releases before `requestSwap`, the swap thread waits on the work
     * signal before binding and releases before signalling done. Mirrors the
     * Linux EGL swap thread (`TaoComposeSceneHostLinux.SwapThread`).
     */
    private inner class SwapThread(
        private val handle: Long,
    ) : Thread("TaoSwapThread-${java.lang.Long.toHexString(handle)}") {
        private val lock = ReentrantLock()
        private val workCond = lock.newCondition()
        private val idleCond = lock.newCondition()
        private var swapPending = false
        private var swapping = false
        private var shutdown = false

        init {
            isDaemon = true
        }

        /** Called on the event-loop thread after `flushAndSubmit` + release. */
        fun requestSwap() {
            lock.withLock {
                swapPending = true
                workCond.signal()
            }
        }

        /**
         * Called on the event-loop thread at the start of the next render
         * cycle. Blocks until the swap thread has finished any in-flight
         * `SwapBuffers` and released the WGL context, or the timeout elapses.
         * Returns `true` if the context is free to bind, `false` if the swap is
         * still in flight (the caller must then skip the frame to avoid two
         * threads holding the context at once). The timeout guards against a
         * present that never returns — e.g. a minimised window whose driver
         * stops pacing — so input handling on this thread can't freeze.
         */
        fun waitForIdle(timeoutMs: Long = 100): Boolean {
            lock.withLock {
                if (!swapPending && !swapping) return true
                val deadline = System.nanoTime() + timeoutMs * 1_000_000
                while (swapPending || swapping) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) return false
                    idleCond.awaitNanos(remaining)
                }
                return true
            }
        }

        fun shutdownAndJoin() {
            lock.withLock {
                shutdown = true
                workCond.signalAll()
            }
            // Best-effort join. If parked inside `SwapBuffers`, the join can
            // take up to one vsync interval; a little headroom is plenty.
            join(50)
        }

        @Suppress("NestedBlockDepth", "TooGenericExceptionCaught", "PrintStackTrace")
        override fun run() {
            try {
                while (true) {
                    val doSwap =
                        lock.withLock {
                            while (!shutdown && !swapPending) workCond.await()
                            if (shutdown) return
                            swapPending = false
                            swapping = true
                            true
                        }
                    if (doSwap) {
                        try {
                            NativeTaoGlBridge.nativeMakeCurrent(handle)
                            NativeTaoGlBridge.nativePresent(handle)
                        } catch (t: Throwable) {
                            t.printStackTrace()
                        } finally {
                            try {
                                NativeTaoGlBridge.nativeReleaseCurrent(handle)
                            } catch (_: Throwable) {
                                // Detached underneath us; detach() handles cleanup.
                            }
                            lock.withLock {
                                swapping = false
                                idleCond.signalAll()
                            }
                        }
                        // Present done (and the WGL context released). If a scroll
                        // is active, re-pump the next frame so the smooth-scroll
                        // tween keeps ticking at vsync — the present we just did is
                        // the heartbeat. Outside the scroll window this is a cheap
                        // no-op and nothing re-arms.
                        maybeScheduleVsyncFrame()
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private inner class FlushingMainDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(
            context: KCoroutineContext,
            block: Runnable,
        ) {
            queue.add(block)
            window.requestRedraw()
        }

        fun enqueue(block: Runnable) {
            queue.add(block)
        }

        fun drain() {
            var remaining = queue.size
            while (remaining-- > 0) {
                val runnable = queue.poll() ?: break
                runnable.run()
            }
        }
    }

    private fun mapButton(code: Int): PointerButton =
        when (code) {
            dev.nucleusframework.window.tao.TaoMouseButton.LEFT ->
                PointerButton.Primary
            dev.nucleusframework.window.tao.TaoMouseButton.RIGHT ->
                PointerButton.Secondary
            dev.nucleusframework.window.tao.TaoMouseButton.MIDDLE ->
                PointerButton.Tertiary
            else -> PointerButton.Primary
        }
}

internal class WindowsTaoWindowInfo : androidx.compose.ui.platform.WindowInfo {
    override var isWindowFocused: Boolean by androidx.compose.runtime.mutableStateOf(true)
    override var keyboardModifiers: PointerKeyboardModifiers
        by androidx.compose.runtime.mutableStateOf(PointerKeyboardModifiers())
    override var containerSize: IntSize by androidx.compose.runtime.mutableStateOf(IntSize.Zero)
    override var containerDpSize: DpSize by androidx.compose.runtime.mutableStateOf(DpSize.Zero)
}

@OptIn(InternalComposeUiApi::class)
private class WindowsTaoPlatformContext(
    private val windowHandle: Long,
    private val topInsetPx: () -> Int,
    override val windowInfo: androidx.compose.ui.platform.WindowInfo,
    override val semanticsOwnerListener: androidx.compose.ui.platform.PlatformContext.SemanticsOwnerListener? = null,
    override val dragAndDropManager: androidx.compose.ui.platform.PlatformDragAndDropManager,
    override val textToolbar: androidx.compose.ui.platform.TextToolbar,
) : androidx.compose.ui.platform.PlatformContext.Empty() {
    override val windowInsets: androidx.compose.ui.platform.PlatformWindowInsets =
        object : androidx.compose.ui.platform.PlatformWindowInsets {
            override val systemBars: androidx.compose.ui.platform.PlatformInsets =
                androidx.compose.ui.platform
                    .PlatformInsets(getTop = topInsetPx)
            override val captionBar: androidx.compose.ui.platform.PlatformInsets get() = systemBars
        }

    override fun setPointerIcon(pointerIcon: androidx.compose.ui.input.pointer.PointerIcon) {
        NativeTaoBridge.nativeSetCursorIcon(
            windowHandle,
            mapPointerIcon(pointerIcon),
        )
    }

    private fun mapPointerIcon(icon: androidx.compose.ui.input.pointer.PointerIcon): Int {
        when {
            icon === androidx.compose.ui.input.pointer.PointerIcon.Default ->
                return dev.nucleusframework.window.tao.TaoCursorIcon.DEFAULT
            icon === androidx.compose.ui.input.pointer.PointerIcon.Text ->
                return dev.nucleusframework.window.tao.TaoCursorIcon.TEXT
            icon === androidx.compose.ui.input.pointer.PointerIcon.Hand ->
                return dev.nucleusframework.window.tao.TaoCursorIcon.HAND
            icon === androidx.compose.ui.input.pointer.PointerIcon.Crosshair ->
                return dev.nucleusframework.window.tao.TaoCursorIcon.CROSSHAIR
        }
        return runCatching {
            val cursor = icon.javaClass.getMethod("getCursor").invoke(icon) as? java.awt.Cursor
            when (cursor?.type) {
                java.awt.Cursor.TEXT_CURSOR -> dev.nucleusframework.window.tao.TaoCursorIcon.TEXT
                java.awt.Cursor.HAND_CURSOR -> dev.nucleusframework.window.tao.TaoCursorIcon.HAND
                java.awt.Cursor.CROSSHAIR_CURSOR -> dev.nucleusframework.window.tao.TaoCursorIcon.CROSSHAIR
                java.awt.Cursor.WAIT_CURSOR -> dev.nucleusframework.window.tao.TaoCursorIcon.WAIT
                java.awt.Cursor.MOVE_CURSOR -> dev.nucleusframework.window.tao.TaoCursorIcon.MOVE
                java.awt.Cursor.E_RESIZE_CURSOR, java.awt.Cursor.W_RESIZE_CURSOR ->
                    dev.nucleusframework.window.tao.TaoCursorIcon.EW_RESIZE
                java.awt.Cursor.N_RESIZE_CURSOR, java.awt.Cursor.S_RESIZE_CURSOR ->
                    dev.nucleusframework.window.tao.TaoCursorIcon.NS_RESIZE
                java.awt.Cursor.NE_RESIZE_CURSOR, java.awt.Cursor.SW_RESIZE_CURSOR ->
                    dev.nucleusframework.window.tao.TaoCursorIcon.NESW_RESIZE
                java.awt.Cursor.NW_RESIZE_CURSOR, java.awt.Cursor.SE_RESIZE_CURSOR ->
                    dev.nucleusframework.window.tao.TaoCursorIcon.NWSE_RESIZE
                else -> dev.nucleusframework.window.tao.TaoCursorIcon.DEFAULT
            }
        }.getOrDefault(dev.nucleusframework.window.tao.TaoCursorIcon.DEFAULT)
    }
}
