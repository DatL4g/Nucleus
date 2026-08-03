// X11 / Wayland window-handle JNI export. The JVM-side EGL helper picks the
// right `EGLNativeWindowType` (Window / wl_egl_window) based on the backend
// tag returned in slot 0.

use jni::objects::JClass;
use jni::sys::{jint, jlong, jlongArray};
use jni::JNIEnv;

use raw_window_handle::{HasDisplayHandle, HasWindowHandle, RawDisplayHandle, RawWindowHandle};
use tao::window::Window;

use crate::state::WINDOWS;

/// Returns the underlying X11 / Wayland window handles so the JVM can attach
/// an EGL context via the `nucleus_tao_egl` helper.
///
/// The returned `long[]` has length 3 with one of the following shapes:
///   `[0, 0, 0]`            → handle unavailable (window not yet realised).
///   `[1, display, xid]`    → Xlib backend; `display` is `Display*`,
///                            `xid` is the X11 `Window`.
///   `[2, display, surface]` → Wayland backend; `display` is `wl_display*`,
///                             `surface` is `wl_surface*`.
///
/// Tao's GTK-based Linux windowing layer wraps both X11 and Wayland — the
/// concrete backend is decided at GDK init time. We mirror its
/// `raw_window_handle_rwh_06`/`raw_display_handle_rwh_06` impls and expose the
/// underlying pointers directly.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeLinuxHandles(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlongArray {
    // Resolve handles inside the WINDOWS lock so the Tao Window can't be
    // dropped between the `window_handle()` and `display_handle()` calls.
    let mut out = [0i64; 3];
    if let Ok(guard) = WINDOWS.lock() {
        if let Some(map) = guard.as_ref() {
            if let Some(window) = map.get(&(handle as u64)) {
                fill_linux_handles(window, &mut out);
            }
        }
    }
    match env.new_long_array(3) {
        Ok(arr) => {
            let _ = env.set_long_array_region(&arr, 0, &out);
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

fn fill_linux_handles(window: &Window, out: &mut [jlong; 3]) {
    let Ok(wh) = window.window_handle() else { return };
    let Ok(dh) = window.display_handle() else { return };
    match (wh.as_raw(), dh.as_raw()) {
        (RawWindowHandle::Xlib(w), RawDisplayHandle::Xlib(_)) => {
            // Tao's `raw_display_handle_rwh_06` calls `XOpenDisplay(NULL)`
            // and returns a *fresh* X11 connection. GLX requires the context,
            // drawable and display to all share the same connection — using
            // tao's display with a GDK-owned XID makes `glXMakeCurrent` fail
            // silently. Pull GDK's actual `Display*` via `gdk_x11_*`.
            out[0] = 1;
            out[1] = gdk_x11_display_for_window(window).unwrap_or(0);
            out[2] = w.window as jlong;
        }
        (RawWindowHandle::Wayland(w), RawDisplayHandle::Wayland(d)) => {
            out[0] = 2;
            out[1] = d.display.as_ptr() as jlong;
            out[2] = w.surface.as_ptr() as jlong;
        }
        _ => {}
    }
}

/// Returns the underlying `GtkApplicationWindow*` (cast to `jlong`)
/// for the given Tao window, or 0 if the window is unavailable. The
/// pointer is the raw GObject* used by `libgtk-3`, suitable for
/// passing to the C-side widget embedding helpers in
/// `nucleus_tao_linux_widget.c`.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeLinuxGtkWindow(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlong {
    use tao::platform::unix::WindowExtUnix;

    let mut out: jlong = 0;
    if let Ok(guard) = WINDOWS.lock() {
        if let Some(map) = guard.as_ref() {
            if let Some(window) = map.get(&(handle as u64)) {
                let gtk_window = window.gtk_window();
                let raw: *mut gtk::ffi::GtkWindow =
                    glib::translate::ToGlibPtr::<*mut gtk::ffi::GtkWindow>::to_glib_none(
                        gtk_window,
                    ).0;
                out = raw as jlong;
            }
        }
    }
    out
}

/// Returns the origin of the content area — the child (default vbox) GTK
/// allocated inside any client-side decorations — in logical toplevel
/// coordinates, packed as `(x << 32) | (y & 0xffff_ffff)`. `(0, 0)` for plain
/// undecorated windows; equal to the theme's shadow margins when the
/// yaru.dart-style hidden-titlebar CSD is active. The EGL host positions the
/// content `wl_subsurface` at this offset so it fills exactly the visible
/// window area, leaving the margin ring to GTK's own shadow rendering.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeLinuxContentOrigin(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlong {
    use gtk::prelude::*;
    use tao::platform::unix::WindowExtUnix;

    let mut x: i32 = 0;
    let mut y: i32 = 0;
    if let Ok(guard) = WINDOWS.lock() {
        if let Some(map) = guard.as_ref() {
            if let Some(window) = map.get(&(handle as u64)) {
                if let Some(child) = window.gtk_window().child() {
                    let alloc = child.allocation();
                    // Pre-first-allocate the child reports a 1×1 dummy at
                    // (0,0) — the fallthrough (0,0) is the correct answer.
                    if alloc.width() > 1 || alloc.height() > 1 {
                        x = alloc.x();
                        y = alloc.y();
                    }
                }
            }
        }
    }
    ((x as i64) << 32) | ((y as i64) & 0xffff_ffff)
}

/// Styles the GTK-drawn client-side decorations to match the embedder's
/// chrome: rounds the decoration node (shadow frame outline) and the window
/// background to `radius` px on all four corners, so the native frame and the
/// Compose-carved content corners coincide exactly. Same mechanism
/// yaru_window_linux uses for `setBackground` (a `GtkCssProvider` on the
/// window's style context). No-op if the CSS fails to parse.
#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_window_tao_ffi_NativeTaoBridge_nativeLinuxSetCsdCornerRadius(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    radius: jint,
) {
    use gtk::prelude::*;
    use tao::platform::unix::WindowExtUnix;

    if let Ok(guard) = WINDOWS.lock() {
        if let Some(map) = guard.as_ref() {
            if let Some(window) = map.get(&(handle as u64)) {
                let r = radius.max(0);
                let provider = gtk::CssProvider::new();
                let css = format!(
                    "decoration {{ border-radius: {r}px; }} window.csd {{ border-radius: {r}px; }}",
                );
                if provider.load_from_data(css.as_bytes()).is_ok() {
                    window
                        .gtk_window()
                        .style_context()
                        .add_provider(&provider, gtk::STYLE_PROVIDER_PRIORITY_APPLICATION);
                }
            }
        }
    }
}

fn gdk_x11_display_for_window(window: &Window) -> Option<jlong> {
    use gtk::prelude::WidgetExt;
    use tao::platform::unix::WindowExtUnix;

    let gtk_window = window.gtk_window();
    let gdk_display = WidgetExt::display(gtk_window);
    // `gdk_display.to_glib_none().0` returns `*mut gdk_sys::GdkDisplay`. Tao's
    // transitive `gtk` crate doesn't expose the `gdk` crate publicly, so we
    // erase to `*mut c_void` and re-cast — `GdkX11Display` is a
    // newtype over `GdkDisplay` at the C level.
    let raw_display_ptr: *mut std::ffi::c_void =
        glib::translate::ToGlibPtr::<*mut gtk::gdk::ffi::GdkDisplay>::to_glib_none(&gdk_display).0
            as *mut std::ffi::c_void;
    if raw_display_ptr.is_null() {
        return None;
    }
    let xdisplay = unsafe {
        gdk_x11_sys::gdk_x11_display_get_xdisplay(raw_display_ptr as *mut gdk_x11_sys::GdkX11Display)
    };
    if xdisplay.is_null() {
        None
    } else {
        Some(xdisplay as jlong)
    }
}
