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
