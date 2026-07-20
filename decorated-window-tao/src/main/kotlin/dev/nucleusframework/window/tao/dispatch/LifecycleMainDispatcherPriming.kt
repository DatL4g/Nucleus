package dev.nucleusframework.window.tao.dispatch

/**
 * Pre-seeds AndroidX Lifecycle's `MainDispatcherChecker` with the Tao main
 * thread before any user code touches `Lifecycle.addObserver` — most notably
 * `NavController.setGraph` on the first composition pass.
 *
 * `MainDispatcherChecker.isMainDispatcherThread()` lazily probes the main
 * dispatcher thread by running `runBlocking(Dispatchers.Main.immediate) { … }`
 * on first call. Under the Tao backend, the same macOS main-runloop thread
 * that drives [TaoMainDispatcher.pump] is the one that ends up parked inside
 * `runBlocking`; under Lifecycle 2.10.x on real apps (Compose Multiplatform
 * `lifecycle-runtime-desktop`) that probe deadlocks during the first
 * `NavController.setGraph` call.
 *
 * Two layers of defence:
 *
 *  1. Write `mainDispatcherThread` directly to the captured Tao main thread.
 *     Subsequent `isMainDispatcherThread()` calls then hit the fast path
 *     `currentThread === mainDispatcherThread` without coroutine machinery.
 *
 *  2. As a belt-and-braces fallback, also flip `isMainDispatcherAvailable`
 *     to `false`. This makes `isMainDispatcherThread()` short-circuit to
 *     `true` on every thread without ever invoking the probe — matches the
 *     observed-working workaround shipped by downstream apps. We do this
 *     *in addition* to (1) so that off-thread Lifecycle calls still see a
 *     valid main-thread reference if anything reads the field directly.
 *
 * Best-effort by design — uses reflection so apps without Lifecycle on the
 * classpath are unaffected. Set `nucleus.tao.debug=true` to print priming
 * diagnostics to stderr.
 */
internal object LifecycleMainDispatcherPriming {
    private val debug: Boolean = System.getProperty("nucleus.tao.debug") == "true"
    private val logger: java.util.logging.Logger =
        java.util.logging.Logger
            .getLogger("dev.nucleusframework.window.tao.dispatch")

    fun primeWithCurrentThread() {
        val thread = Thread.currentThread()
        val result =
            runCatching {
                val cls = Class.forName("androidx.lifecycle.MainDispatcherChecker")
                cls
                    .getDeclaredField("mainDispatcherThread")
                    .apply { isAccessible = true }
                    .set(null, thread)
                cls
                    .getDeclaredField("isMainDispatcherAvailable")
                    .apply { isAccessible = true }
                    .setBoolean(null, false)
                cls.name
            }
        if (debug) {
            result.fold(
                onSuccess = {
                    logger.fine { "Primed $it main thread = ${thread.name} (id=${thread.id})" }
                },
                onFailure = { t ->
                    logger.fine { "Lifecycle priming skipped: ${t.javaClass.simpleName}: ${t.message}" }
                },
            )
        }
    }
}
