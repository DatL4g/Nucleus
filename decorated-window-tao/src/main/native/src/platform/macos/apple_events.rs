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
        let class = match env.find_class("dev/nucleusframework/window/tao/ffi/NativeTaoBridge") {
            Ok(c) => c,
            Err(_) => return,
        };
        let Ok(jstr) = env.new_string(url) else { return };
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
    }
}
