package dev.nucleusframework.taonativetest

import dev.nucleusframework.graalvm.GraalVmInitializer
import dev.nucleusframework.window.tao.TaoSceneTestBattery
import dev.nucleusframework.window.tao.headful.TaoHeadfulTestSuiteMain
import kotlin.system.exitProcess

/**
 * Runs the Tao test pyramid inside a GraalVM native image (also works on the
 * JVM via `:examples:tao-native-test:run --args=<mode>`):
 *
 * - `battery`: the stage-1 offscreen scene battery (reflection-free registry,
 *   [TaoSceneTestBattery]) — headless;
 * - `headful`: the stage-2 real-window suite ([TaoHeadfulTestSuiteMain]) —
 *   needs a display; terminates the process with its own exit code.
 *
 * The two modes are separate PROCESS runs on purpose: the offscreen battery
 * creates dozens of ComposeScenes whose global runtime state (snapshot
 * observers, dispatcher bootstrap) prevents the Tao event loop from starting
 * cleanly in the same process. CI compiles the native image once and executes
 * the binary once per mode.
 *
 * Exit code 0 = everything passed. Any assertion failure, missing GraalVM
 * reachability metadata (ClassNotFound/MissingReflection at runtime) or
 * native rendering breakage fails the run.
 */
fun main(args: Array<String>) {
    GraalVmInitializer.initialize()

    when (args.firstOrNull() ?: "battery") {
        "battery" -> {
            println("── Tao offscreen battery (native=${GraalVmInitializer.isNativeImage}) ──")
            val results = TaoSceneTestBattery.runAll()
            var failures = 0
            for (r in results) {
                if (r.failure != null) {
                    failures++
                    println("  [FAIL] ${r.name}")
                    r.failure?.printStackTrace(System.out)
                }
            }
            println("── ${results.size} run, $failures failed ──")
            exitProcess(if (failures > 0) 1 else 0)
        }
        "headful" -> {
            println("── Tao headful suite (native=${GraalVmInitializer.isNativeImage}) ──")
            TaoHeadfulTestSuiteMain.main(emptyArray())
        }
        else -> {
            System.err.println("usage: tao-native-test [battery|headful]")
            exitProcess(2)
        }
    }
}
