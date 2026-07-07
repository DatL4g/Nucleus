/**
 * Internal contract between the overlay HWND lifecycle (overlay.c),
 * the popup HWND lifecycle (popup.c), the transparent WGL rendering
 * bridge (overlay_gl.c) and the EGL/ANGLE + DirectComposition bridge
 * (overlay_dcomp.cpp). All four files are linked into the same
 * nucleus_tao_windows_native_view.dll.
 *
 * `GlSurface` is the minimal HWND-plus-render state both lifecycles
 * need. Each owner (OverlayState / PopupState) embeds one as a field
 * and passes `&owner->gl` to the helpers below. The backend is picked
 * at init time from the HOST's render backend (nucleus_tao_gl.dll):
 *
 *   - Host WGL   -> single-HGLRC transparent WGL (overlay_gl.c):
 *                   the overlay HDC borrows the host's HGLRC, DWM
 *                   honors back-buffer alpha via the blur-behind trick.
 *   - Host ANGLE -> DirectComposition (overlay_dcomp.cpp): Compose
 *                   renders through the host's EGLContext into a
 *                   D3D11 texture (EGL_ANGLE_d3d_texture_client_buffer
 *                   pbuffer), presented on a composition swapchain
 *                   (DXGI_ALPHA_MODE_PREMULTIPLIED) attached to the
 *                   HWND via a DComp target/visual. The blur-behind
 *                   trick does NOT work under ANGLE: its HWND surfaces
 *                   present through a blt-model DXGI swapchain that
 *                   drops the alpha channel, and CreateSwapChainForHwnd
 *                   rejects premultiplied alpha outright.
 */
#ifndef NUCLEUS_TAO_WINDOWS_OVERLAY_INTERNAL_H
#define NUCLEUS_TAO_WINDOWS_OVERLAY_INTERNAL_H

#include <windows.h>

#ifdef __cplusplus
extern "C" {
#endif

#define NUCLEUS_TAO_OVERLAY_BACKEND_WGL   0
#define NUCLEUS_TAO_OVERLAY_BACKEND_DCOMP 1

typedef struct {
    HWND  hwnd;
    int   backend;   /* NUCLEUS_TAO_OVERLAY_BACKEND_* */
    /* WGL single-HGLRC state (backend == WGL). */
    HDC   hdc;
    HGLRC hglrc;
    /* Opaque DcompSurface* owned by overlay_dcomp.cpp (backend == DCOMP). */
    void *dcomp;
} GlSurface;

/**
 * Picks the backend from the host's render backend, then either
 * resolves the host's pixel format + HGLRC (WGL) or builds the DComp
 * target/visual/swapchain + EGL pbuffer chain (ANGLE). Applies the
 * matching DWM styling: blur-behind + polish for WGL, polish only for
 * DComp. Popups pass nativeWindowPolish=FALSE so shadows/elevation
 * come from Compose draw bounds, matching AWT WindowComposeSceneLayer.
 *
 * Caller must have set [gl]->hwnd before calling.
 */
BOOL nucleus_tao_overlay_gl_init(GlSurface *gl, BOOL nativeWindowPolish);

/** Tears down the backend state + HDC release. Safe on partial init. */
void nucleus_tao_overlay_gl_destroy(GlSurface *gl);

/** Re-arms `DwmEnableBlurBehindWindow` after WM_DWMCOMPOSITIONCHANGED.
 *  No-op on the DComp backend (alpha comes from the swapchain). */
void nucleus_tao_overlay_gl_rearm_blur(GlSurface *gl);

/** Binds the surface for rendering on the calling thread:
 *  wglMakeCurrent(hdc, hglrc) or eglMakeCurrent on the d3d pbuffer. */
BOOL nucleus_tao_overlay_gl_make_current(GlSurface *gl);

/** Presents the rendered frame: SwapBuffers + DwmFlush (WGL) or
 *  CopyResource + Present(0) + DComp Commit (DComp). */
void nucleus_tao_overlay_gl_present(GlSurface *gl);

/**
 * Notifies the backend of a surface size change (physical pixels).
 * WGL back buffers track the HWND automatically — no-op there. The
 * DComp backend resizes its swapchain and rebuilds the intermediate
 * texture + pbuffer. Call BEFORE the next make_current/render.
 */
void nucleus_tao_overlay_gl_resize(GlSurface *gl, int widthPx, int heightPx);

/**
 * TRUE when a freshly-inited surface would use the DComp backend
 * (host renders via ANGLE). Needed BEFORE CreateWindowEx: the DComp
 * backend requires WS_EX_NOREDIRECTIONBITMAP (no GDI redirection
 * surface behind the visual tree), which can only be set at window
 * creation, while the WGL backend needs the redirection surface for
 * its blt-model SwapBuffers.
 */
BOOL nucleus_tao_overlay_backend_is_dcomp(void);

/* --- implemented in overlay_dcomp.cpp, consumed by overlay_gl.c --- */

void *nucleus_tao_overlay_dcomp_create(HWND hwnd);
void  nucleus_tao_overlay_dcomp_destroy(void *surface);
BOOL  nucleus_tao_overlay_dcomp_make_current(void *surface);
void  nucleus_tao_overlay_dcomp_present(void *surface);
void  nucleus_tao_overlay_dcomp_resize(void *surface, int widthPx, int heightPx);

#ifdef __cplusplus
}
#endif

#endif
