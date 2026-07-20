# Keep the MainDispatcherFactory implementation — ServiceLoader discovers it
# reflectively from META-INF/services and Compose Desktop's ProGuard pass
# would otherwise strip it as unreferenced, dropping `Dispatchers.Main` back
# to kotlinx-coroutines-swing and breaking AndroidX Lifecycle / Navigation
# under the Tao backend.
-keep class dev.nucleusframework.window.tao.dispatch.TaoMainDispatcherFactory {
    <init>(...);
    public *;
}
-keepnames class dev.nucleusframework.window.tao.dispatch.TaoMainCoroutineDispatcher
-keepnames class dev.nucleusframework.window.tao.dispatch.ImmediateTaoMainDispatcher

# kotlinx-coroutines' MainDispatcherLoader resolves the factory via the
# service file — keep it from being renamed or removed.
-keep class kotlinx.coroutines.internal.MainDispatcherFactory

# The Compose Hot Reload integration (org.jetbrains.compose.reload.*, .devtools.*) is a
# dev-only, compileOnly dependency: it is present when running under hot reload, never in a
# packaged release. TaoHotReloadBridgeImpl references it unconditionally, so suppress the
# unresolved-class warnings for release (ProGuard) builds.
-dontwarn dev.nucleusframework.window.tao.TaoHotReloadBridgeImpl*
-dontwarn org.jetbrains.compose.reload.**
-dontwarn org.jetbrains.compose.devtools.**

# ── JNI surface ──────────────────────────────────────────────────────────────
# The native library (libnucleus_tao) both exposes native methods to the JVM and calls back
# into ~20 callback interfaces (event, key, DnD, touch, popup, overlay) by method name across
# the tao packages. ProGuard cannot see these native call sites, so shrinking/optimization
# strips the callback methods and the app dies at runtime with NoSuchMethodError (e.g.
# `onEvent` from nativeRunBlocking) or UnsatisfiedLinkError. Keep the whole JNI surface,
# mirroring the module's native-image reachability metadata.
-keepclasseswithmembernames,includedescriptorclasses class dev.nucleusframework.window.tao.** {
    native <methods>;
}
-keep class dev.nucleusframework.window.tao.**Callback { *; }
-keep class dev.nucleusframework.window.tao.**Listener { *; }
-keep class dev.nucleusframework.window.tao.**Dispatcher { *; }
