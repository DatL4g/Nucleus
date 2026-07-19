// Windows accessibility bridge.
//
// Mirrors the macOS bridge (`platform::macos::a11y`) but the native projection
// lives in a sibling DLL (`nucleus_tao_a11y.dll`). We resolve its entry points
// lazily via `GetProcAddress` against an already-loaded module — Kotlin's
// `NativeLibraryLoader` loads the DLL by absolute path before the first JNI
// call here. The DLL also exposes "register callback" entry points that we
// wire up to local trampolines so UIA action invocations can call back into
// the JVM through `JAVA_VM`.

use std::ffi::c_void;
use std::sync::Mutex;

use jni::objects::{JByteArray, JClass, JValue};
use jni::sys::{jboolean, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use crate::state::JAVA_VM;

// ── DLL function-pointer types ─────────────────────────────────────────────

type AttachFn = unsafe extern "system" fn(hwnd: i64);
type DetachFn = unsafe extern "system" fn(hwnd: i64);
type ApplyFn = unsafe extern "system" fn(hwnd: i64, bytes: *const u8, len: usize) -> i32;
type IsActiveFn = unsafe extern "system" fn(hwnd: i64) -> i32;
type ConsumeResyncFn = unsafe extern "system" fn(hwnd: i64) -> i32;
type NotePushedFn = unsafe extern "system" fn(hwnd: i64);
type RegisterInvokeCbFn = unsafe extern "system" fn(
    cb: extern "system" fn(hwnd: i64, node_id: u64, action: u16),
);
type RegisterSetTextCbFn = unsafe extern "system" fn(
    cb: extern "system" fn(hwnd: i64, node_id: u64, utf8: *const u8, len: i32),
);
type RegisterSetSelectionCbFn = unsafe extern "system" fn(
    cb: extern "system" fn(hwnd: i64, node_id: u64, start: i32, end: i32),
);
type RegisterScrollByCbFn = unsafe extern "system" fn(
    cb: extern "system" fn(hwnd: i64, node_id: u64, dx: f32, dy: f32),
);
type RegisterCustomActionCbFn = unsafe extern "system" fn(
    cb: extern "system" fn(hwnd: i64, node_id: u64, index: i32),
);
type RegisterSetValueCbFn = unsafe extern "system" fn(
    cb: extern "system" fn(hwnd: i64, node_id: u64, value: f64),
);

struct A11yApi {
    attach: AttachFn,
    detach: DetachFn,
    apply: ApplyFn,
    is_active: IsActiveFn,
    consume_resync: ConsumeResyncFn,
    #[allow(dead_code)]
    note_pushed: NotePushedFn,
}

static API: Mutex<Option<A11yApi>> = Mutex::new(None);

/// HWNDs with a live UIA projection (attached, not yet detached). The Kotlin
/// `nativeA11yIsActive` / `nativeA11yConsumeResync` exports take no handle
/// (mirroring macOS' process-global gate), so we keep this registry to answer
/// "is *any* tracked window active" by polling the per-HWND state the C DLL
/// tracks. Updated by `nativeA11yAttach` / `nativeA11yDetach`.
static TRACKED_HWNDS: Mutex<Vec<i64>> = Mutex::new(Vec::new());

/// Snapshot of the tracked HWNDs, copied out so the C-DLL FFI calls below run
/// without holding the registry lock.
fn tracked_hwnds() -> Vec<i64> {
    TRACKED_HWNDS.lock().map(|g| g.clone()).unwrap_or_default()
}

extern "system" {
    fn LoadLibraryW(name: *const u16) -> *mut c_void;
    fn GetModuleHandleW(name: *const u16) -> *mut c_void;
    fn GetProcAddress(module: *mut c_void, name: *const u8) -> *mut c_void;
}

fn to_wide_nul(s: &str) -> Vec<u16> {
    let mut v: Vec<u16> = s.encode_utf16().collect();
    v.push(0);
    v
}

/// Resolve and cache the API on first use. Returns `None` if the sibling DLL
/// isn't loaded yet (Kotlin must load it before any A11y JNI export is
/// invoked — `NativeLibraryLoader` does this at module init time).
fn api() -> Option<&'static A11yApi> {
    let mut guard = API.lock().ok()?;
    if guard.is_some() {
        // Re-borrow as 'static via Option::as_ref + leaking the lock guard
        // is fine because API is a static and we never drop the contents.
        // SAFETY: the API struct is initialised once and never mutated.
        let p = guard.as_ref().unwrap() as *const A11yApi;
        unsafe { return Some(&*p); }
    }
    let name = to_wide_nul("nucleus_tao_a11y.dll");
    let mut h = unsafe { GetModuleHandleW(name.as_ptr()) };
    if h.is_null() {
        // Last-ditch fallback: attempt LoadLibraryW (may fail if the DLL
        // isn't on PATH — typically it's been extracted by Kotlin's
        // NativeLibraryLoader which calls System.load with a full path).
        h = unsafe { LoadLibraryW(name.as_ptr()) };
    }
    if h.is_null() {
        return None;
    }
    let resolve = |name: &str| unsafe {
        let mut cstr: Vec<u8> = name.bytes().collect();
        cstr.push(0);
        GetProcAddress(h, cstr.as_ptr())
    };
    let attach     = resolve("nucleus_tao_a11y_attach_win");
    let detach     = resolve("nucleus_tao_a11y_detach_win");
    let apply      = resolve("nucleus_tao_a11y_apply_snapshot_win");
    let active     = resolve("nucleus_tao_a11y_is_active_win");
    let resync     = resolve("nucleus_tao_a11y_consume_resync_win");
    let pushed     = resolve("nucleus_tao_a11y_note_pushed_win");
    let reg_invoke = resolve("nucleus_tao_a11y_register_action_callback_win");
    let reg_settxt = resolve("nucleus_tao_a11y_register_set_text_callback_win");
    let reg_selrng = resolve("nucleus_tao_a11y_register_set_selection_callback_win");
    let reg_scroll = resolve("nucleus_tao_a11y_register_scroll_by_callback_win");
    let reg_custom = resolve("nucleus_tao_a11y_register_custom_action_callback_win");
    let reg_setval = resolve("nucleus_tao_a11y_register_set_value_callback_win");
    if attach.is_null() || detach.is_null() || apply.is_null() ||
       active.is_null() || resync.is_null() || pushed.is_null() ||
       reg_invoke.is_null() || reg_settxt.is_null() || reg_selrng.is_null() ||
       reg_scroll.is_null() || reg_custom.is_null() || reg_setval.is_null() {
        return None;
    }
    let api = A11yApi {
        attach:         unsafe { std::mem::transmute(attach) },
        detach:         unsafe { std::mem::transmute(detach) },
        apply:          unsafe { std::mem::transmute(apply) },
        is_active:      unsafe { std::mem::transmute(active) },
        consume_resync: unsafe { std::mem::transmute(resync) },
        note_pushed:    unsafe { std::mem::transmute(pushed) },
    };
    // Wire the action callbacks so the C DLL can route into the JVM.
    let r_invoke: RegisterInvokeCbFn        = unsafe { std::mem::transmute(reg_invoke) };
    let r_settxt: RegisterSetTextCbFn       = unsafe { std::mem::transmute(reg_settxt) };
    let r_selrng: RegisterSetSelectionCbFn  = unsafe { std::mem::transmute(reg_selrng) };
    let r_scroll: RegisterScrollByCbFn      = unsafe { std::mem::transmute(reg_scroll) };
    let r_custom: RegisterCustomActionCbFn  = unsafe { std::mem::transmute(reg_custom) };
    let r_setval: RegisterSetValueCbFn      = unsafe { std::mem::transmute(reg_setval) };
    unsafe {
        r_invoke(invoke_action_trampoline);
        r_settxt(set_text_trampoline);
        r_selrng(set_selection_trampoline);
        r_scroll(scroll_by_trampoline);
        r_custom(custom_action_trampoline);
        r_setval(set_value_trampoline);
    }
    *guard = Some(api);
    let p = guard.as_ref().unwrap() as *const A11yApi;
    unsafe { Some(&*p) }
}

// ── Trampolines invoked by the DLL ────────────────────────────────────────

/// Trampoline invoked by `nucleus_tao_a11y.dll` when UIA dispatches an action
/// (Invoke, etc). Forwards into the JVM via the existing
/// `dispatchA11yActionByNsView` upcall — semantically the JNI side already
/// uses the "view handle" as an opaque key, so reusing it for HWND is fine.
/// The Kotlin registry indexes by this same handle.
extern "system" fn invoke_action_trampoline(hwnd: i64, node_id: u64, action: u16) {
    let Some(jvm) = JAVA_VM.get() else { return };
    if let Ok(mut env) = jvm.attach_current_thread() {
        let class = match env.find_class(
            "dev/nucleusframework/window/tao/ffi/NativeTaoBridge",
        ) {
            Ok(c) => c,
            Err(_) => return,
        };
        let _ = env.call_static_method(
            class,
            "dispatchA11yActionByNsView",
            "(JJI)V",
            &[
                JValue::Long(hwnd),
                JValue::Long(node_id as i64),
                JValue::Int(action as i32),
            ],
        );
    }
}

extern "system" fn set_text_trampoline(
    hwnd: i64, node_id: u64, utf8: *const u8, len: i32,
) {
    let Some(jvm) = JAVA_VM.get() else { return };
    if utf8.is_null() || len < 0 { return };
    let slice = unsafe { std::slice::from_raw_parts(utf8, len as usize) };
    let Ok(text) = std::str::from_utf8(slice) else { return };
    if let Ok(mut env) = jvm.attach_current_thread() {
        let class = match env.find_class(
            "dev/nucleusframework/window/tao/ffi/NativeTaoBridge",
        ) {
            Ok(c) => c,
            Err(_) => return,
        };
        let Ok(jstr) = env.new_string(text) else { return };
        let _ = env.call_static_method(
            class,
            "dispatchA11ySetText",
            "(JJLjava/lang/String;)V",
            &[
                JValue::Long(hwnd),
                JValue::Long(node_id as i64),
                JValue::Object(&jstr.into()),
            ],
        );
    }
}

extern "system" fn set_selection_trampoline(
    hwnd: i64, node_id: u64, start: i32, end: i32,
) {
    let Some(jvm) = JAVA_VM.get() else { return };
    if let Ok(mut env) = jvm.attach_current_thread() {
        let class = match env.find_class(
            "dev/nucleusframework/window/tao/ffi/NativeTaoBridge",
        ) {
            Ok(c) => c,
            Err(_) => return,
        };
        let _ = env.call_static_method(
            class,
            "dispatchA11ySetSelection",
            "(JJII)V",
            &[
                JValue::Long(hwnd),
                JValue::Long(node_id as i64),
                JValue::Int(start),
                JValue::Int(end),
            ],
        );
    }
}

extern "system" fn scroll_by_trampoline(
    hwnd: i64, node_id: u64, dx: f32, dy: f32,
) {
    let Some(jvm) = JAVA_VM.get() else { return };
    if let Ok(mut env) = jvm.attach_current_thread() {
        let class = match env.find_class(
            "dev/nucleusframework/window/tao/ffi/NativeTaoBridge",
        ) {
            Ok(c) => c,
            Err(_) => return,
        };
        let _ = env.call_static_method(
            class,
            "dispatchA11yScrollBy",
            "(JJFF)V",
            &[
                JValue::Long(hwnd),
                JValue::Long(node_id as i64),
                JValue::Float(dx),
                JValue::Float(dy),
            ],
        );
    }
}

extern "system" fn set_value_trampoline(
    hwnd: i64, node_id: u64, value: f64,
) {
    let Some(jvm) = JAVA_VM.get() else { return };
    if let Ok(mut env) = jvm.attach_current_thread() {
        let class = match env.find_class(
            "dev/nucleusframework/window/tao/ffi/NativeTaoBridge",
        ) {
            Ok(c) => c,
            Err(_) => return,
        };
        let _ = env.call_static_method(
            class,
            "dispatchA11ySetValue",
            "(JJD)V",
            &[
                JValue::Long(hwnd),
                JValue::Long(node_id as i64),
                JValue::Double(value),
            ],
        );
    }
}

extern "system" fn custom_action_trampoline(
    hwnd: i64, node_id: u64, index: i32,
) {
    let Some(jvm) = JAVA_VM.get() else { return };
    if let Ok(mut env) = jvm.attach_current_thread() {
        let class = match env.find_class(
            "dev/nucleusframework/window/tao/ffi/NativeTaoBridge",
        ) {
            Ok(c) => c,
            Err(_) => return,
        };
        let _ = env.call_static_method(
            class,
            "dispatchA11yCustomAction",
            "(JJI)V",
            &[
                JValue::Long(hwnd),
                JValue::Long(node_id as i64),
                JValue::Int(index),
            ],
        );
    }
}

// ── JNI exports ───────────────────────────────────────────────────────────

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11yAttach(
    _env: JNIEnv,
    _class: JClass,
    hwnd: jlong,
) {
    if hwnd == 0 { return; }
    if let Some(api) = api() {
        unsafe { (api.attach)(hwnd) };
        if let Ok(mut g) = TRACKED_HWNDS.lock() {
            if !g.contains(&hwnd) { g.push(hwnd); }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11yDetach(
    _env: JNIEnv,
    _class: JClass,
    hwnd: jlong,
) {
    if hwnd == 0 { return; }
    if let Some(api) = api() {
        unsafe { (api.detach)(hwnd) };
    }
    if let Ok(mut g) = TRACKED_HWNDS.lock() {
        g.retain(|&h| h != hwnd);
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11yApplySnapshot(
    env: JNIEnv,
    _class: JClass,
    hwnd: jlong,
    bytes: JByteArray,
) -> jboolean {
    if hwnd == 0 { return JNI_FALSE; }
    let Some(api) = api() else { return JNI_FALSE; };
    let len = match env.get_array_length(&bytes) {
        Ok(n) if n > 0 => n as usize,
        _ => return JNI_FALSE,
    };
    let mut buf = vec![0i8; len];
    if env.get_byte_array_region(&bytes, 0, &mut buf).is_err() {
        return JNI_FALSE;
    }
    let ok = unsafe { (api.apply)(hwnd, buf.as_ptr() as *const u8, len) };
    if ok != 0 { JNI_TRUE } else { JNI_FALSE }
}

/// The Kotlin bridge calls is_active without the handle (mirroring macOS'
/// process-global gate). The C DLL tracks per-HWND activity (a UIA client
/// queried the projection within the last ~5 min), so we answer "is *any*
/// tracked window active" by polling each registered HWND. Returning a real
/// signal here lets the JVM-side observer skip the O(N) SemanticsOwner walk
/// while no assistive tech is listening — see `TaoAccessibilityController`.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11yIsActive(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let Some(api) = api() else { return JNI_FALSE; };
    for hwnd in tracked_hwnds() {
        if unsafe { (api.is_active)(hwnd) } != 0 {
            return JNI_TRUE;
        }
    }
    JNI_FALSE
}

/// Consumes the "force resync" flag the C DLL sets whenever a UIA query lands
/// (so the next push is a full snapshot, recovering from any tree drift during
/// an idle window when walks were skipped). Polls every tracked HWND and
/// clears each — must not short-circuit, or a pending flag would linger.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11yConsumeResync(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let Some(api) = api() else { return JNI_FALSE; };
    let mut any = false;
    for hwnd in tracked_hwnds() {
        if unsafe { (api.consume_resync)(hwnd) } != 0 {
            any = true;
        }
    }
    if any { JNI_TRUE } else { JNI_FALSE }
}

/// No-op on Windows; per-HWND tracking lives in the C DLL.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11yNotePushed(
    _env: JNIEnv,
    _class: JClass,
) {
}

/// No screen-reader-detect API exposed yet on Windows; report false.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11yIsVoiceOverRunning(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    JNI_FALSE
}

/// TODO: emit `UIA_AutomationFocusChangedEventId` via `UiaRaiseAutomationEvent`.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11yPostFocusChanged(
    _env: JNIEnv,
    _class: JClass,
    _hwnd: jlong,
    _node_id: jlong,
) {
}

/// No-op on Windows — UIA reads the HWND title for the app name.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11ySetAppName(
    _env: JNIEnv,
    _class: JClass,
    _name: jni::objects::JString,
) {
}

/// No-op stub: the partial wire format is Linux-only at v7; the Windows
/// parser is still at v4 and rejects anything else. Returning `JNI_FALSE`
/// keeps the JVM-side controller from believing a partial succeeded.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeA11yApplyPartialSnapshot(
    _env: JNIEnv,
    _class: JClass,
    _ns_view: jlong,
    _bytes: JByteArray,
) -> jboolean {
    JNI_FALSE
}
