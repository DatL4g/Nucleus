/**
 * DirectManipulation bridge for the Tao Windows backend — the same
 * OS-computed precision-touchpad pipeline Chrome uses on Windows
 * (`ui/base/win/direct_manipulation_helper`) and Flutter integrated in
 * engine PR #31594 (`shell/platform/windows/direct_manipulation.cc`).
 *
 * A DirectManipulation viewport is registered on the Tao HWND. When a
 * precision-touchpad gesture starts, Windows sends DM_POINTERHITTEST; we
 * hand the contact to the viewport and from then on the OS computes the
 * manipulation — pan, pinch scale, and post-lift INERTIA — and reports it
 * as content-transform updates. The synthesized WM_MOUSEWHEEL stream stops
 * automatically for contacts the viewport owns; mice keep the wheel path.
 *
 * Model: POLL, not push. Transform deltas accumulate here (single UI
 * thread, no locks) and the JVM drains them once per render tick via
 * nativeFetch. A per-window 8 ms timer pumps IDirectManipulationUpdateManager
 * while a gesture or its inertia is active and invalidates the window so
 * the render loop keeps draining even when nothing else animates; it is
 * killed as soon as the viewport returns to READY.
 *
 * Divergence from Flutter (deliberate): Flutter drops INERTIA-phase deltas
 * and re-synthesizes its own fling framework-side. We forward them — the
 * OS inertia curve IS the feature (Chromium does the same, tagging scroll
 * events with a momentum phase).
 *
 * Compiled as C++ (directmanipulation.h has no C bindings) but
 * /NODEFAULTLIB clean, matching nucleus_tao_windows_overlay_dcomp.cpp: no
 * CRT, no exceptions, no global new/delete, no non-trivial statics.
 * _fltused / memset shims come from nucleus_tao_windows_deco.c (same DLL).
 *
 * Threading: every entry point (JNI, subclass proc, DManip callbacks fired
 * synchronously inside Update) runs on the Tao event-loop thread.
 *
 * Linked into nucleus_tao_windows_deco.dll.
 */

#include <windows.h>
#include <commctrl.h>
#include <directmanipulation.h>
#include <jni.h>

/* Placement new without <new> — /NODEFAULTLIB forbids the CRT header. */
inline void* operator new(size_t, void* where) noexcept { return where; }

#define DMANIP_SUBCLASS_ID ((UINT_PTR)0xD3A1)
#define DMANIP_TIMER_ID ((UINT_PTR)0xD3A2)
#define DMANIP_TIMER_MS 8
#define DMANIP_PROP L"NucleusTaoDManip"

/* Wire statuses returned by nativeFetch — mirrored in NativeTaoDManipBridge. */
#define DMANIP_STATUS_IDLE 0
#define DMANIP_STATUS_RUNNING 1
#define DMANIP_STATUS_INERTIA 2

typedef BOOL(WINAPI* GetPointerTypeFn)(UINT32, POINTER_INPUT_TYPE*);

struct DManipState;

/* ── COM event handler (manual lifetime, HeapAlloc + placement new) ─────── */

class DManipEventHandler final : public IDirectManipulationViewportEventHandler {
 public:
  explicit DManipEventHandler(DManipState* state) : state_(state) {}

  /* IUnknown */
  STDMETHODIMP QueryInterface(REFIID iid, void** ppv) override {
    if (iid == IID_IUnknown || iid == __uuidof(IDirectManipulationViewportEventHandler)) {
      *ppv = static_cast<IDirectManipulationViewportEventHandler*>(this);
      AddRef();
      return S_OK;
    }
    *ppv = nullptr;
    return E_NOINTERFACE;
  }
  ULONG STDMETHODCALLTYPE AddRef() override {
    return (ULONG)InterlockedIncrement(&refCount_);
  }
  ULONG STDMETHODCALLTYPE Release() override {
    LONG rc = InterlockedDecrement(&refCount_);
    if (rc == 0) {
      this->~DManipEventHandler();
      HeapFree(GetProcessHeap(), 0, this);
    }
    return (ULONG)rc;
  }

  /* IDirectManipulationViewportEventHandler */
  HRESULT STDMETHODCALLTYPE OnViewportStatusChanged(
      IDirectManipulationViewport* viewport,
      DIRECTMANIPULATION_STATUS current,
      DIRECTMANIPULATION_STATUS previous) override;
  HRESULT STDMETHODCALLTYPE OnViewportUpdated(
      IDirectManipulationViewport* viewport) override {
    (void)viewport;
    return S_OK;
  }
  HRESULT STDMETHODCALLTYPE OnContentUpdated(
      IDirectManipulationViewport* viewport,
      IDirectManipulationContent* content) override;

  void Orphan() { state_ = nullptr; }

 private:
  ~DManipEventHandler() = default;
  DManipState* state_;
  volatile LONG refCount_ = 1;
};

/* ── Per-window state ───────────────────────────────────────────────────── */

struct DManipState {
  HWND hwnd;
  IDirectManipulationManager* manager;
  IDirectManipulationUpdateManager* updateManager;
  IDirectManipulationViewport* viewport;
  DManipEventHandler* handler;
  DWORD handlerCookie;
  GetPointerTypeFn getPointerType;

  /* Gesture tracking — Flutter's fields, same reset/rebase semantics. */
  BOOL duringSynthesizedReset;
  BOOL inertia;
  float initialScale; /* content transform at gesture start */
  float initialPanX;
  float initialPanY;
  float lastPanX; /* gesture-relative, since gesture start */
  float lastPanY;
  float lastScale;

  /* Accumulators drained by nativeFetch (UI thread only). */
  float pendingPanX;
  float pendingPanY;
  float pendingScale; /* multiplicative, 1.0 = no zoom */
  int status;         /* DMANIP_STATUS_* as of the last callback */
  BOOL timerActive;
};

static DManipState* GetState(HWND hwnd) {
  return (DManipState*)GetPropW(hwnd, DMANIP_PROP);
}

/* DirectManipulation reports updates with sub-pixel noise while fingers
 * rest on the pad. Chop two mantissa bits off the scale exactly like
 * Flutter/Chromium so a steady pinch doesn't jitter. */
static float ChopScaleJitter(float value) {
  const float factor = (float)((1 << 2) + 1);
  float c = factor * value;
  return c - (c - value);
}

static void ReadTransform(IDirectManipulationContent* content,
                          float* scale,
                          float* panX,
                          float* panY) {
  float transform[6];
  if (SUCCEEDED(content->GetContentTransform(transform, 6))) {
    *scale = ChopScaleJitter(transform[0]);
    *panX = transform[4];
    *panY = transform[5];
  } else {
    *scale = 1.0f;
    *panX = 0.0f;
    *panY = 0.0f;
  }
}

HRESULT STDMETHODCALLTYPE DManipEventHandler::OnViewportStatusChanged(
    IDirectManipulationViewport* viewport,
    DIRECTMANIPULATION_STATUS current,
    DIRECTMANIPULATION_STATUS previous) {
  DManipState* s = state_;
  if (!s) return S_OK;

  if (s->duringSynthesizedReset) {
    /* Swallow the synthetic ZoomToRect transition until READY. */
    s->duringSynthesizedReset = current != DIRECTMANIPULATION_READY;
    return S_OK;
  }

  s->inertia = current == DIRECTMANIPULATION_INERTIA;
  if (current == DIRECTMANIPULATION_RUNNING) {
    /* Gesture start: rebase all deltas on the current content transform. */
    IDirectManipulationContent* content = nullptr;
    if (SUCCEEDED(viewport->GetPrimaryContent(IID_PPV_ARGS(&content)))) {
      ReadTransform(content, &s->initialScale, &s->initialPanX, &s->initialPanY);
      content->Release();
    }
    if (s->initialScale == 0.0f) s->initialScale = 1.0f;
    s->lastPanX = 0.0f;
    s->lastPanY = 0.0f;
    s->lastScale = 1.0f;
    s->status = DMANIP_STATUS_RUNNING;
  } else if (current == DIRECTMANIPULATION_INERTIA) {
    s->status = DMANIP_STATUS_INERTIA;
  } else if (current == DIRECTMANIPULATION_READY) {
    if (previous == DIRECTMANIPULATION_INERTIA ||
        previous == DIRECTMANIPULATION_RUNNING) {
      /* Manipulation over — reset the content transform so the next
       * gesture starts from identity (Flutter's synthesized reset). */
      s->duringSynthesizedReset = TRUE;
      RECT rect;
      if (SUCCEEDED(viewport->GetViewportRect(&rect))) {
        viewport->ZoomToRect((float)rect.left, (float)rect.top,
                             (float)rect.right, (float)rect.bottom, FALSE);
      }
    }
    s->status = DMANIP_STATUS_IDLE;
  }
  return S_OK;
}

HRESULT STDMETHODCALLTYPE DManipEventHandler::OnContentUpdated(
    IDirectManipulationViewport* viewport,
    IDirectManipulationContent* content) {
  (void)viewport;
  DManipState* s = state_;
  if (!s || s->duringSynthesizedReset) return S_OK;

  float scale, panX, panY;
  ReadTransform(content, &scale, &panX, &panY);

  /* Gesture-relative values (rebased at RUNNING entry). */
  float relScale = (s->initialScale != 0.0f) ? scale / s->initialScale : 1.0f;
  float relPanX = panX - s->initialPanX;
  float relPanY = panY - s->initialPanY;

  s->pendingPanX += relPanX - s->lastPanX;
  s->pendingPanY += relPanY - s->lastPanY;
  if (s->lastScale != 0.0f) {
    s->pendingScale *= relScale / s->lastScale;
  }
  s->lastPanX = relPanX;
  s->lastPanY = relPanY;
  s->lastScale = relScale;
  return S_OK;
}

/* ── Timer + subclass ───────────────────────────────────────────────────── */

static void PumpAndInvalidate(DManipState* s) {
  if (s->updateManager) s->updateManager->Update(nullptr);
  if (s->status != DMANIP_STATUS_IDLE || s->pendingPanX != 0.0f ||
      s->pendingPanY != 0.0f || s->pendingScale != 1.0f) {
    /* Force a paint so the JVM render tick drains the accumulators. */
    InvalidateRect(s->hwnd, nullptr, FALSE);
  } else if (s->timerActive) {
    KillTimer(s->hwnd, DMANIP_TIMER_ID);
    s->timerActive = FALSE;
  }
}

static LRESULT CALLBACK DManipSubclassProc(HWND hwnd,
                                           UINT msg,
                                           WPARAM wParam,
                                           LPARAM lParam,
                                           UINT_PTR idSubclass,
                                           DWORD_PTR refData) {
  (void)idSubclass;
  DManipState* s = (DManipState*)refData;
  switch (msg) {
    case DM_POINTERHITTEST: {
      if (!s || !s->viewport) break;
      UINT32 pointerId = GET_POINTERID_WPARAM(wParam);
      POINTER_INPUT_TYPE type = PT_POINTER;
      if (s->getPointerType && s->getPointerType(pointerId, &type) &&
          type == PT_TOUCHPAD) {
        s->viewport->SetContact(pointerId);
        if (!s->timerActive) {
          SetTimer(hwnd, DMANIP_TIMER_ID, DMANIP_TIMER_MS, nullptr);
          s->timerActive = TRUE;
        }
      }
      break;
    }
    case WM_TIMER:
      if (wParam == DMANIP_TIMER_ID && s) {
        PumpAndInvalidate(s);
        return 0;
      }
      break;
    case WM_SIZE:
      if (s && s->viewport && wParam != SIZE_MINIMIZED) {
        RECT rect = {0, 0, (LONG)LOWORD(lParam), (LONG)HIWORD(lParam)};
        if (rect.right > 0 && rect.bottom > 0) s->viewport->SetViewportRect(&rect);
      }
      break;
    default:
      break;
  }
  return DefSubclassProc(hwnd, msg, wParam, lParam);
}

/* ── Attach / detach / fetch ────────────────────────────────────────────── */

static void DestroyState(DManipState* s) {
  if (!s) return;
  if (s->timerActive) {
    KillTimer(s->hwnd, DMANIP_TIMER_ID);
    s->timerActive = FALSE;
  }
  RemoveWindowSubclass(s->hwnd, DManipSubclassProc, DMANIP_SUBCLASS_ID);
  RemovePropW(s->hwnd, DMANIP_PROP);
  if (s->handler) s->handler->Orphan();
  if (s->viewport) {
    s->viewport->Stop();
    s->viewport->Disable();
    if (s->handlerCookie) s->viewport->RemoveEventHandler(s->handlerCookie);
    s->viewport->Abandon();
    s->viewport->Release();
  }
  if (s->manager) {
    s->manager->Deactivate(s->hwnd);
  }
  if (s->handler) s->handler->Release();
  if (s->updateManager) s->updateManager->Release();
  if (s->manager) s->manager->Release();
  HeapFree(GetProcessHeap(), 0, s);
}

static BOOL AttachImpl(HWND hwnd) {
  if (!hwnd || GetState(hwnd)) return FALSE;

  /* The event-loop thread is already a COM STA in practice (OLE DnD); this
   * is defensive for bare windows like the smoke-test panel. S_FALSE and
   * RPC_E_CHANGED_MODE both mean "already initialized" — fine. */
  CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);

  DManipState* s = (DManipState*)HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY,
                                           sizeof(DManipState));
  if (!s) return FALSE;
  s->hwnd = hwnd;
  s->pendingScale = 1.0f;
  s->initialScale = 1.0f;
  s->lastScale = 1.0f;
  s->status = DMANIP_STATUS_IDLE;

  HMODULE user32 = GetModuleHandleW(L"user32.dll");
  if (user32) {
    s->getPointerType =
        (GetPointerTypeFn)GetProcAddress(user32, "GetPointerType");
  }
  if (!s->getPointerType) { /* pre-Win8: no pointer API, no DManip */
    HeapFree(GetProcessHeap(), 0, s);
    return FALSE;
  }

  HRESULT hr = CoCreateInstance(__uuidof(DirectManipulationManager), nullptr,
                                CLSCTX_INPROC_SERVER,
                                IID_PPV_ARGS(&s->manager));
  if (FAILED(hr)) {
    HeapFree(GetProcessHeap(), 0, s);
    return FALSE;
  }

  BOOL ok = FALSE;
  do {
    if (FAILED(s->manager->GetUpdateManager(IID_PPV_ARGS(&s->updateManager)))) break;
    if (FAILED(s->manager->CreateViewport(nullptr, hwnd,
                                          IID_PPV_ARGS(&s->viewport)))) break;
    DIRECTMANIPULATION_CONFIGURATION configuration =
        DIRECTMANIPULATION_CONFIGURATION_INTERACTION |
        DIRECTMANIPULATION_CONFIGURATION_TRANSLATION_X |
        DIRECTMANIPULATION_CONFIGURATION_TRANSLATION_Y |
        DIRECTMANIPULATION_CONFIGURATION_SCALING |
        DIRECTMANIPULATION_CONFIGURATION_TRANSLATION_INERTIA;
    if (FAILED(s->viewport->ActivateConfiguration(configuration))) break;
    if (FAILED(s->viewport->SetViewportOptions(
            DIRECTMANIPULATION_VIEWPORT_OPTIONS_MANUALUPDATE))) break;

    void* mem = HeapAlloc(GetProcessHeap(), 0, sizeof(DManipEventHandler));
    if (!mem) break;
    s->handler = new (mem) DManipEventHandler(s);
    if (FAILED(s->viewport->AddEventHandler(hwnd, s->handler,
                                            &s->handlerCookie))) break;

    RECT client = {0, 0, 1, 1};
    GetClientRect(hwnd, &client);
    if (client.right <= 0) client.right = 1;
    if (client.bottom <= 0) client.bottom = 1;
    RECT rect = {0, 0, client.right, client.bottom};
    if (FAILED(s->viewport->SetViewportRect(&rect))) break;
    if (FAILED(s->manager->Activate(hwnd))) break;
    if (FAILED(s->viewport->Enable())) break;
    if (FAILED(s->updateManager->Update(nullptr))) break;

    if (!SetWindowSubclass(hwnd, DManipSubclassProc, DMANIP_SUBCLASS_ID,
                           (DWORD_PTR)s)) break;
    SetPropW(hwnd, DMANIP_PROP, (HANDLE)s);
    ok = TRUE;
  } while (0);

  if (!ok) {
    /* DestroyState handles partially-initialized state but expects the
     * subclass/prop absent — both are only installed on full success. */
    if (s->handler) s->handler->Orphan();
    if (s->viewport) {
      if (s->handlerCookie) s->viewport->RemoveEventHandler(s->handlerCookie);
      s->viewport->Abandon();
      s->viewport->Release();
    }
    if (s->handler) s->handler->Release();
    if (s->updateManager) s->updateManager->Release();
    if (s->manager) {
      s->manager->Deactivate(hwnd);
      s->manager->Release();
    }
    HeapFree(GetProcessHeap(), 0, s);
  }
  return ok;
}

/* ── JNI exports ────────────────────────────────────────────────────────── */

extern "C" {

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoDManipBridge_nativeAttach(
    JNIEnv* env, jclass clazz, jlong hwndHandle) {
  (void)env;
  (void)clazz;
  return AttachImpl((HWND)(UINT_PTR)hwndHandle) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoDManipBridge_nativeDetach(
    JNIEnv* env, jclass clazz, jlong hwndHandle) {
  (void)env;
  (void)clazz;
  HWND hwnd = (HWND)(UINT_PTR)hwndHandle;
  DestroyState(GetState(hwnd));
}

/*
 * Drains accumulated manipulation deltas into out[0..2]:
 *   out[0] = pan delta X (physical px since last fetch)
 *   out[1] = pan delta Y (physical px since last fetch)
 *   out[2] = multiplicative scale delta since last fetch (1.0 = none)
 * Returns the viewport status (DMANIP_STATUS_*), or -1 when not attached.
 * Also pumps the update manager so callbacks fire even between timer ticks.
 */
JNIEXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoDManipBridge_nativeFetch(
    JNIEnv* env, jclass clazz, jlong hwndHandle, jfloatArray out) {
  (void)clazz;
  HWND hwnd = (HWND)(UINT_PTR)hwndHandle;
  DManipState* s = GetState(hwnd);
  if (!s || !out || env->GetArrayLength(out) < 3) return -1;

  if (s->updateManager) s->updateManager->Update(nullptr);

  jfloat values[3];
  values[0] = s->pendingPanX;
  values[1] = s->pendingPanY;
  values[2] = s->pendingScale;
  s->pendingPanX = 0.0f;
  s->pendingPanY = 0.0f;
  s->pendingScale = 1.0f;
  env->SetFloatArrayRegion(out, 0, 3, values);
  return (jint)s->status;
}

} /* extern "C" */
