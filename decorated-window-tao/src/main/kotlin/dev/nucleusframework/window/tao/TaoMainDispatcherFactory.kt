package dev.nucleusframework.window.tao

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.internal.MainDispatcherFactory

/**
 * `MainDispatcherFactory` that routes `Dispatchers.Main` to the Tao main
 * thread when `nucleus.decorated-window-tao` is on the classpath.
 *
 * Discovered through the standard
 * `META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory`
 * service file. With [loadPriority] = `100`, it deterministically wins the
 * ServiceLoader pick against:
 *
 * - `kotlinx-coroutines-swing` (`SwingDispatcherFactory.loadPriority = 0`)
 * - `kotlinx-coroutines-javafx` (`JavaFxDispatcherFactory.loadPriority = 1`)
 *
 * `Int.MAX_VALUE` is reserved for `AndroidDispatcherFactory` and
 * `Int.MAX_VALUE - 1` for `TestMainDispatcherFactory`. `100` sits well clear
 * of both while staying ahead of any reasonable third-party factory.
 *
 * [createDispatcher] is intentionally cheap and side-effect-free: it only
 * returns the [ImmediateTaoMainDispatcher] singleton, with no JNI / native
 * interaction. The Tao runtime is initialised separately by
 * `nucleusApplication { … }`, which captures the main thread reference
 * eagerly via [TaoMainDispatcher.taoMainThread]. This shape is required by
 * `kotlinx-coroutines-test`'s [TestMainDispatcherFactory], which wraps the
 * resolved dispatcher as the delegate of `TestMainDispatcher`; if the factory
 * touched native code here, every test JVM would fail to initialise
 * `Dispatchers.Main`.
 */
@OptIn(InternalCoroutinesApi::class)
internal class TaoMainDispatcherFactory : MainDispatcherFactory {
    override val loadPriority: Int = 100

    override fun createDispatcher(allFactories: List<MainDispatcherFactory>): MainCoroutineDispatcher =
        ImmediateTaoMainDispatcher

    override fun hintOnError(): String =
        "Tao backend's Dispatchers.Main is not initialised. Either call " +
            "nucleusApplication(args) { … } from your main() before touching " +
            "Dispatchers.Main, or use Dispatchers.setMain(StandardTestDispatcher()) " +
            "from kotlinx-coroutines-test in unit tests."
}
