// Apple Events deep-link bridge.
//
// macOS delivers URL-scheme deep links (`nucleus://…`) as a `kAEGetURL` Apple
// Event. When `CFBundleURLTypes` is declared in the bundle's Info.plist,
// NSApplication installs its own handler for that event during
// `finishLaunching` and routes it to `application:openURLs:`. Tao's app
// delegate implements that selector and re-emits it as `Event::Opened`, which
// the event loop forwards here. This is the modern, recommended path and
// covers both cold start (the launch URL replayed after `finishLaunching`) and
// warm start.

use jni::objects::JValue;

use crate::state::JAVA_VM;

/// Forwards a deep-link URL to `NativeTaoBridge.dispatchDeepLink(String)` on the
/// JVM side. Called from the macOS `Event::Opened` arm of the event loop.
pub(crate) fn dispatch_deep_link(url: &str) {
    let Some(jvm) = JAVA_VM.get() else { return };
    if url.is_empty() {
        return;
    }
    if let Ok(mut env) = jvm.attach_current_thread() {
        // Runs on the AppKit main thread, which the Tao loop attached
        // *permanently* — the `find_class` + `new_string` local refs would never
        // be reclaimed. Bound them in a scoped local frame.
        let _ = env.with_local_frame(6, |env| -> Result<(), jni::errors::Error> {
            let class = env.find_class("dev/nucleusframework/window/tao/ffi/NativeTaoBridge")?;
            let jstr = env.new_string(url)?;
            let _ = env.call_static_method(
                class,
                "dispatchDeepLink",
                "(Ljava/lang/String;)V",
                &[JValue::Object(&jstr.into())],
            );
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_describe();
                let _ = env.exception_clear();
            }
            Ok(())
        });
    }
}
