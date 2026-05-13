package dev.nucleusframework.application

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Schedules an automatic exit after [duration] when the JVM is in AOT training
 * mode (`-Dnucleus.aot.mode=training`). No-op otherwise.
 *
 * The [onTimeout] lambda runs on a non-daemon timer thread once the duration
 * elapses; by default it calls [exitProcess] so JBR's AOT shutdown hooks fire
 * and write the cache. Override it to dump a profile, flush logs, or call
 * [NucleusApplicationScope.exitApplication] for a Compose-only shutdown.
 *
 * Safe to call multiple times — only the first invocation per process arms
 * the timer.
 *
 * ```
 * nucleusApplication(args) {
 *     aotTraining(duration = 45.seconds)
 *     onDeepLink { … }
 *     …
 * }
 * ```
 */
fun NucleusApplicationScope.aotTraining(
    duration: Duration = 15.seconds,
    onTimeout: NucleusApplicationScope.() -> Unit = { exitProcess(0) },
) {
    if (!isAotTraining) return
    if (!aotTrainingArmed.compareAndSet(false, true)) return

    println("[AOT] Training mode — will exit in $duration")

    Thread({
        Thread.sleep(duration.inWholeMilliseconds)
        println("[AOT] Time's up, exiting…")
        onTimeout()
    }, "nucleus-aot-training-timer").apply {
        isDaemon = false
        start()
    }
}

private val aotTrainingArmed = AtomicBoolean(false)
