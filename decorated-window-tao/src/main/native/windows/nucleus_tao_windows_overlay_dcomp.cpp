/**
 * EGL/ANGLE + DirectComposition rendering bridge for the Tao Windows
 * overlay & popup HWNDs — the ANGLE-host counterpart of the WGL
 * single-HGLRC path in nucleus_tao_windows_overlay_gl.c.
 *
 * Why not the blur-behind trick here: ANGLE presents HWND surfaces
 * through a blt-model DXGI swapchain that drops the alpha channel when
 * copying into DWM's redirection surface, and CreateSwapChainForHwnd
 * categorically rejects DXGI_ALPHA_MODE_PREMULTIPLIED. Per-pixel alpha
 * on Windows with a D3D-backed renderer requires a *composition*
 * swapchain — the same architecture Chromium uses for transparent
 * windows.
 *
 * Per-HWND chain:
 *
 *   Compose (Skia GL, host's EGLContext)
 *     -> EGL pbuffer bound to our ID3D11Texture2D
 *        (EGL_ANGLE_d3d_texture_client_buffer, texture created on
 *         ANGLE's own ID3D11Device pulled via EGL_EXT_device_query)
 *     -> CopyResource into the composition swapchain's back buffer
 *        (CreateSwapChainForComposition, DXGI_ALPHA_MODE_PREMULTIPLIED)
 *     -> Present(0) + IDCompositionDevice::Commit()
 *     -> DComp visual tree targeted at the overlay/popup HWND
 *        (created with WS_EX_NOREDIRECTIONBITMAP — no GDI redirection
 *         surface behind the visuals)
 *
 * Single-context architecture: like the WGL path, no second GL/EGL
 * context is ever created. The host's EGLContext binds to the pbuffer
 * for the overlay frame and back to the host's window surface for the
 * main frame; the shared Skia DirectContext re-syncs via resetGLAll().
 * Presents run inline on the event-loop thread — ANGLE's per-display
 * D3D11 device deadlocks on cross-thread present (see
 * TaoComposeSceneHostWindows.attach), and Present(0)/Commit don't
 * block on vsync anyway (pacing comes from the host's swap interval).
 *
 * The Y-flip transform on the visual compensates Skia's BOTTOM_LEFT
 * surface origin (renderGlFrame): GL row 0 = image bottom lands in
 * texture row 0 = D3D top, so the raw texture is upside down.
 *
 * Everything is resolved dynamically (dcomp.dll, EGL entry points via
 * nucleus_tao_gl.dll's nucleus_tao_host_egl_proc) — no new import
 * libs. Compiled as C++ (dcomp.h has no C bindings) but /NODEFAULTLIB
 * clean: no CRT, no exceptions, no new/delete, no non-trivial statics.
 *
 * Threading: every entry point runs on the Tao event-loop thread.
 *
 * Linked into nucleus_tao_windows_native_view.dll.
 */

#include <windows.h>
#include <d3d11.h>
#include <dxgi1_2.h>
#include <dcomp.h>

#define EGL_EGL_PROTOTYPES 0
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <EGL/eglext_angle.h>

#include "nucleus_tao_windows_overlay_internal.h"

/* ================================================================== */
/*  Host bridge (nucleus_tao_gl.dll exports)                           */
/* ================================================================== */

typedef int   (*PFN_host_backend)(void);
typedef void *(*PFN_host_egl_display)(void);
typedef void *(*PFN_host_egl_context)(void);
typedef void *(*PFN_host_egl_config)(void);
typedef void *(*PFN_host_egl_proc)(const char *name);

static PFN_host_backend     pHostBackend    = NULL;
static PFN_host_egl_display pHostEglDisplay = NULL;
static PFN_host_egl_context pHostEglContext = NULL;
static PFN_host_egl_config  pHostEglConfig  = NULL;
static PFN_host_egl_proc    pHostEglProc    = NULL;
static BOOL sHostResolved = FALSE;

/* EGL entry points, resolved through the host bridge. */
static PFNEGLQUERYDISPLAYATTRIBEXTPROC        pEglQueryDisplayAttribEXT        = NULL;
static PFNEGLQUERYDEVICEATTRIBEXTPROC         pEglQueryDeviceAttribEXT         = NULL;
static PFNEGLCREATEPBUFFERFROMCLIENTBUFFERPROC pEglCreatePbufferFromClientBuffer = NULL;
static PFNEGLDESTROYSURFACEPROC               pEglDestroySurface               = NULL;
static PFNEGLMAKECURRENTPROC                  pEglMakeCurrent                  = NULL;
static PFNEGLGETCURRENTSURFACEPROC            pEglGetCurrentSurface            = NULL;
static PFNEGLGETERRORPROC                     pEglGetError                     = NULL;

typedef void (APIENTRY *PFN_glFlush)(void);
static PFN_glFlush pglFlush = NULL;

typedef HRESULT (WINAPI *PFN_DCompositionCreateDevice)(IDXGIDevice *, REFIID, void **);
static PFN_DCompositionCreateDevice pDCompositionCreateDevice = NULL;

static void resolveHost(void) {
    if (sHostResolved) return;
    sHostResolved = TRUE;

    HMODULE m = GetModuleHandleW(L"nucleus_tao_gl.dll");
    if (!m) return;
    pHostBackend    = (PFN_host_backend)    GetProcAddress(m, "nucleus_tao_host_backend");
    pHostEglDisplay = (PFN_host_egl_display)GetProcAddress(m, "nucleus_tao_host_egl_display");
    pHostEglContext = (PFN_host_egl_context)GetProcAddress(m, "nucleus_tao_host_egl_context");
    pHostEglConfig  = (PFN_host_egl_config) GetProcAddress(m, "nucleus_tao_host_egl_config");
    pHostEglProc    = (PFN_host_egl_proc)   GetProcAddress(m, "nucleus_tao_host_egl_proc");
    if (!pHostEglProc) return;

    pEglQueryDisplayAttribEXT = (PFNEGLQUERYDISPLAYATTRIBEXTPROC)
        pHostEglProc("eglQueryDisplayAttribEXT");
    pEglQueryDeviceAttribEXT = (PFNEGLQUERYDEVICEATTRIBEXTPROC)
        pHostEglProc("eglQueryDeviceAttribEXT");
    pEglCreatePbufferFromClientBuffer = (PFNEGLCREATEPBUFFERFROMCLIENTBUFFERPROC)
        pHostEglProc("eglCreatePbufferFromClientBuffer");
    pEglDestroySurface    = (PFNEGLDESTROYSURFACEPROC)    pHostEglProc("eglDestroySurface");
    pEglMakeCurrent       = (PFNEGLMAKECURRENTPROC)       pHostEglProc("eglMakeCurrent");
    pEglGetCurrentSurface = (PFNEGLGETCURRENTSURFACEPROC) pHostEglProc("eglGetCurrentSurface");
    pEglGetError          = (PFNEGLGETERRORPROC)          pHostEglProc("eglGetError");
    pglFlush              = (PFN_glFlush)                 pHostEglProc("glFlush");

    HMODULE dcomp = LoadLibraryW(L"dcomp.dll");
    if (dcomp) {
        pDCompositionCreateDevice = (PFN_DCompositionCreateDevice)
            GetProcAddress(dcomp, "DCompositionCreateDevice");
    }
}

extern "C" BOOL nucleus_tao_overlay_backend_is_dcomp(void) {
    resolveHost();
    return (pHostBackend && pHostBackend() == 1 /* BACKEND_EGL */) ? TRUE : FALSE;
}

/* ================================================================== */
/*  Shared IDCompositionDevice (per ANGLE D3D11 device)                */
/* ================================================================== */

static IDCompositionDevice *sDcompDevice       = NULL;
static ID3D11Device        *sDcompSourceDevice = NULL; /* identity key, not owned */

static IDCompositionDevice *acquireDcompDevice(ID3D11Device *d3d) {
    if (sDcompDevice && sDcompSourceDevice == d3d) {
        sDcompDevice->AddRef();
        return sDcompDevice;
    }
    if (!pDCompositionCreateDevice) return NULL;

    IDXGIDevice *dxgi = NULL;
    if (FAILED(d3d->QueryInterface(__uuidof(IDXGIDevice), (void **)&dxgi))) return NULL;
    IDCompositionDevice *dev = NULL;
    HRESULT hr = pDCompositionCreateDevice(dxgi, __uuidof(IDCompositionDevice), (void **)&dev);
    dxgi->Release();
    if (FAILED(hr)) return NULL;

    /* Swap the cached singleton (old one keeps living through the refs
     * still held by existing surfaces). */
    if (sDcompDevice) sDcompDevice->Release();
    sDcompDevice = dev;
    sDcompSourceDevice = d3d;
    sDcompDevice->AddRef(); /* the cache's own ref */
    return dev;
}

/* ================================================================== */
/*  DcompSurface                                                       */
/* ================================================================== */

struct DcompSurface {
    HWND hwnd;
    int  widthPx;
    int  heightPx;

    EGLDisplay dpy;
    EGLContext ctx;      /* host's — borrowed, never destroyed here */
    EGLConfig  cfg;
    EGLSurface pbuffer;

    ID3D11Device        *device;  /* ANGLE's — AddRef'd */
    ID3D11DeviceContext *imCtx;
    ID3D11Texture2D     *texture;
    IDXGISwapChain1     *swapChain;

    IDCompositionDevice *dcompDevice;
    IDCompositionTarget *target;
    IDCompositionVisual *visual;
};

/* Compensates Skia's BOTTOM_LEFT surface origin (see file header). */
static void applyFlipTransform(DcompSurface *s) {
    if (!s->visual) return;
    D2D_MATRIX_3X2_F m = {};
    m._11 = 1.0f;
    m._22 = -1.0f;
    m._32 = (FLOAT)s->heightPx;
    s->visual->SetTransform(m);
}

static void releasePbufferAndTexture(DcompSurface *s) {
    if (s->pbuffer != EGL_NO_SURFACE) {
        /* Never destroy a surface while it's bound on this thread. */
        if (pEglGetCurrentSurface && pEglGetCurrentSurface(EGL_DRAW) == s->pbuffer) {
            pEglMakeCurrent(s->dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
        pEglDestroySurface(s->dpy, s->pbuffer);
        s->pbuffer = EGL_NO_SURFACE;
    }
    if (s->texture) {
        s->texture->Release();
        s->texture = NULL;
    }
}

static BOOL createPbufferAndTexture(DcompSurface *s, int w, int h) {
    D3D11_TEXTURE2D_DESC desc = {};
    desc.Width = (UINT)w;
    desc.Height = (UINT)h;
    desc.MipLevels = 1;
    desc.ArraySize = 1;
    desc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    desc.SampleDesc.Count = 1;
    desc.Usage = D3D11_USAGE_DEFAULT;
    desc.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    ID3D11Texture2D *tex = NULL;
    if (FAILED(s->device->CreateTexture2D(&desc, NULL, &tex))) return FALSE;

    const EGLint pbAttribs[] = {
        EGL_TEXTURE_FORMAT, EGL_TEXTURE_RGBA,
        EGL_TEXTURE_TARGET, EGL_TEXTURE_2D,
        EGL_NONE
    };
    EGLSurface pb = pEglCreatePbufferFromClientBuffer(
        s->dpy, EGL_D3D_TEXTURE_ANGLE, (EGLClientBuffer)tex, s->cfg, pbAttribs);
    if (pb == EGL_NO_SURFACE) {
        tex->Release();
        return FALSE;
    }
    s->texture = tex;
    s->pbuffer = pb;
    return TRUE;
}

extern "C" void *nucleus_tao_overlay_dcomp_create(HWND hwnd) {
    resolveHost();
    if (!pHostEglDisplay || !pHostEglContext || !pHostEglConfig ||
        !pEglCreatePbufferFromClientBuffer || !pEglMakeCurrent ||
        !pEglQueryDisplayAttribEXT || !pEglQueryDeviceAttribEXT ||
        !pDCompositionCreateDevice) {
        return NULL;
    }

    EGLDisplay dpy = (EGLDisplay)pHostEglDisplay();
    EGLContext ctx = (EGLContext)pHostEglContext();
    EGLConfig  cfg = (EGLConfig)pHostEglConfig();
    if (dpy == EGL_NO_DISPLAY || ctx == EGL_NO_CONTEXT || !cfg) return NULL;

    /* ANGLE's own D3D11 device — textures the pbuffer extension accepts
     * must come from it, and the DComp device must wrap it so
     * CopyResource stays a same-device GPU-side blit. */
    EGLAttrib devAttr = 0;
    if (!pEglQueryDisplayAttribEXT(dpy, EGL_DEVICE_EXT, &devAttr)) return NULL;
    EGLAttrib d3dAttr = 0;
    if (!pEglQueryDeviceAttribEXT((EGLDeviceEXT)devAttr, EGL_D3D11_DEVICE_ANGLE, &d3dAttr)) {
        return NULL;
    }
    ID3D11Device *d3d = (ID3D11Device *)d3dAttr;

    RECT rc = {};
    GetClientRect(hwnd, &rc);
    int w = rc.right - rc.left;  if (w < 1) w = 1;
    int h = rc.bottom - rc.top;  if (h < 1) h = 1;

    DcompSurface *s = (DcompSurface *)HeapAlloc(
        GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(DcompSurface));
    if (!s) return NULL;
    s->hwnd = hwnd;
    s->widthPx = w;
    s->heightPx = h;
    s->dpy = dpy;
    s->ctx = ctx;
    s->cfg = cfg;
    s->pbuffer = EGL_NO_SURFACE;
    s->device = d3d;
    s->device->AddRef();
    s->device->GetImmediateContext(&s->imCtx);

    BOOL ok = FALSE;
    IDXGIDevice *dxgiDevice = NULL;
    IDXGIAdapter *adapter = NULL;
    IDXGIFactory2 *factory = NULL;
    do {
        s->dcompDevice = acquireDcompDevice(d3d);
        if (!s->dcompDevice) break;
        /* topmost=TRUE: the visual tree composes above the (absent —
         * WS_EX_NOREDIRECTIONBITMAP) window content. */
        if (FAILED(s->dcompDevice->CreateTargetForHwnd(hwnd, TRUE, &s->target))) break;
        if (FAILED(s->dcompDevice->CreateVisual(&s->visual))) break;

        if (FAILED(d3d->QueryInterface(__uuidof(IDXGIDevice), (void **)&dxgiDevice))) break;
        if (FAILED(dxgiDevice->GetAdapter(&adapter))) break;
        if (FAILED(adapter->GetParent(__uuidof(IDXGIFactory2), (void **)&factory))) break;

        DXGI_SWAP_CHAIN_DESC1 scDesc = {};
        scDesc.Width = (UINT)w;
        scDesc.Height = (UINT)h;
        scDesc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
        scDesc.SampleDesc.Count = 1;
        scDesc.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
        scDesc.BufferCount = 2;
        scDesc.Scaling = DXGI_SCALING_STRETCH;
        scDesc.SwapEffect = DXGI_SWAP_EFFECT_FLIP_SEQUENTIAL;
        scDesc.AlphaMode = DXGI_ALPHA_MODE_PREMULTIPLIED;
        if (FAILED(factory->CreateSwapChainForComposition(d3d, &scDesc, NULL, &s->swapChain))) {
            break;
        }

        if (FAILED(s->visual->SetContent(s->swapChain))) break;
        if (FAILED(s->target->SetRoot(s->visual))) break;
        applyFlipTransform(s);

        if (!createPbufferAndTexture(s, w, h)) break;

        s->dcompDevice->Commit();
        ok = TRUE;
    } while (0);

    if (factory) factory->Release();
    if (adapter) adapter->Release();
    if (dxgiDevice) dxgiDevice->Release();

    if (!ok) {
        nucleus_tao_overlay_dcomp_destroy(s);
        return NULL;
    }
    return s;
}

extern "C" void nucleus_tao_overlay_dcomp_destroy(void *surface) {
    DcompSurface *s = (DcompSurface *)surface;
    if (!s) return;
    releasePbufferAndTexture(s);
    if (s->visual)      { s->visual->SetContent(NULL); s->visual->Release(); }
    if (s->target)      { s->target->SetRoot(NULL); s->target->Release(); }
    if (s->swapChain)   s->swapChain->Release();
    if (s->dcompDevice) { s->dcompDevice->Commit(); s->dcompDevice->Release(); }
    if (s->imCtx)       s->imCtx->Release();
    if (s->device)      s->device->Release();
    HeapFree(GetProcessHeap(), 0, s);
}

extern "C" BOOL nucleus_tao_overlay_dcomp_make_current(void *surface) {
    DcompSurface *s = (DcompSurface *)surface;
    if (!s || s->pbuffer == EGL_NO_SURFACE) return FALSE;
    return pEglMakeCurrent(s->dpy, s->pbuffer, s->pbuffer, s->ctx) ? TRUE : FALSE;
}

extern "C" void nucleus_tao_overlay_dcomp_present(void *surface) {
    DcompSurface *s = (DcompSurface *)surface;
    if (!s || !s->swapChain || !s->texture) return;

    /* Skia's flushAndSubmit already flushed GL; this defensive flush
     * guarantees ANGLE submitted everything to the immediate context
     * before CopyResource enters the same command stream. */
    if (pglFlush) pglFlush();

    ID3D11Texture2D *backBuffer = NULL;
    if (SUCCEEDED(s->swapChain->GetBuffer(0, __uuidof(ID3D11Texture2D),
                                          (void **)&backBuffer))) {
        s->imCtx->CopyResource(backBuffer, s->texture);
        backBuffer->Release();
        /* Present(0): queue without blocking. Frame pacing comes from
         * the host's vsynced eglSwapBuffers on the same thread. */
        s->swapChain->Present(0, 0);
        s->dcompDevice->Commit();
    }
}

extern "C" void nucleus_tao_overlay_dcomp_resize(void *surface, int widthPx, int heightPx) {
    DcompSurface *s = (DcompSurface *)surface;
    if (!s) return;
    if (widthPx < 1) widthPx = 1;
    if (heightPx < 1) heightPx = 1;
    if (widthPx == s->widthPx && heightPx == s->heightPx) return;

    releasePbufferAndTexture(s);
    s->swapChain->ResizeBuffers(2, (UINT)widthPx, (UINT)heightPx,
                                DXGI_FORMAT_B8G8R8A8_UNORM, 0);
    s->widthPx = widthPx;
    s->heightPx = heightPx;
    applyFlipTransform(s);
    if (!createPbufferAndTexture(s, widthPx, heightPx)) {
        /* Leave pbuffer/texture NULL — make_current will fail and the
         * Kotlin side skips the frame; a later resize can recover. */
        return;
    }
}
