package dev.nucleusframework.fswatchersmoke

import dev.nucleusframework.fswatcher.FsWatchDeliveryMode
import dev.nucleusframework.fswatcher.FsWatchEvent
import dev.nucleusframework.fswatcher.FsWatcherConfig
import dev.nucleusframework.fswatcher.FsWatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val reportPath = reportPathFrom(args)
    val (report, exitCode) = runBlocking { runSmoke() }
    val reportJson = report.toJson()

    reportPath?.let { writeReportFile(it, reportJson) }
    println(reportJson)
    System.out.flush()
    System.err.flush()
    exitProcess(exitCode)
}

private suspend fun runSmoke(): Pair<SmokeReport, Int> {
    val config = FsWatcherConfig(deliveryMode = FsWatchDeliveryMode.Raw)
    if (!FsWatchers.isSupported(config)) {
        return SmokeReport(
            status = "unsupported",
            observedEventKind = null,
            targetPath = null,
            durationMs = 0,
            errorClass = null,
            errorMessage = "fs-watcher native smoke requires supported native watcher configuration",
            scenarios = emptyList(),
        ) to 2
    }

    val root = Files.createTempDirectory("fs-watcher-native-smoke")
    val target = root.resolve("created.txt")
    val startedAt = System.nanoTime()

    var report: SmokeReport? = null
    var exitCode = 1

    coroutineScope {
        FsWatchers.create(config).use { watcher ->
            watcher.watch(root, recursive = true)

            val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    watcher.events.collect { event -> seen += event }
                }

            try {
                val scenarios = mutableListOf<ScenarioResult>()

                Files.writeString(target, "hello-native-smoke")
                val createdEventKind = awaitEventKind(seen, target) { event ->
                    event is FsWatchEvent.Created || event is FsWatchEvent.Modified
                }
                scenarios +=
                    ScenarioResult(
                        name = "created",
                        status = "pass",
                        observedEventKind = createdEventKind,
                        errorMessage = null,
                    )

                Files.delete(target)
                val deletedEventKind = awaitEventKind(seen, target) { event ->
                    event is FsWatchEvent.Removed
                }
                scenarios +=
                    ScenarioResult(
                        name = "deleted",
                        status = "pass",
                        observedEventKind = deletedEventKind,
                        errorMessage = null,
                    )

                report =
                    SmokeReport(
                        status = "pass",
                        observedEventKind = createdEventKind,
                        targetPath = target.toString(),
                        durationMs = elapsedMillisSince(startedAt),
                        errorClass = null,
                        errorMessage = null,
                        scenarios = scenarios,
                    )
                exitCode = 0
            } catch (t: Throwable) {
                t.printStackTrace()
                report =
                    SmokeReport(
                        status = "fail",
                        observedEventKind = null,
                        targetPath = target.toString(),
                        durationMs = elapsedMillisSince(startedAt),
                        errorClass = t::class.qualifiedName,
                        errorMessage = t.message ?: t.toString(),
                        scenarios = emptyList(),
                    )
                exitCode = 1
            } finally {
                collector.cancelAndJoin()
                runCatching { Files.deleteIfExists(target) }
                runCatching { Files.deleteIfExists(root) }
            }
        }
    }

    return checkNotNull(report) to exitCode
}

private suspend fun awaitEventKind(
    seen: MutableList<FsWatchEvent>,
    target: Path,
    matches: (FsWatchEvent) -> Boolean,
): String =
    withTimeout(10_000) {
        while (true) {
            synchronized(seen) {
                seen.firstOrNull { event -> event.matchesTarget(target) && matches(event) }?.let { match ->
                    return@withTimeout eventKindOf(match)
                }
            }
            delay(25)
        }
        error("unreachable")
    }

private fun FsWatchEvent.matchesTarget(target: Path): Boolean =
    when (this) {
        is FsWatchEvent.Created -> path == target
        is FsWatchEvent.Modified -> path == target
        is FsWatchEvent.Removed -> path == target
        else -> false
    }

private fun eventKindOf(event: FsWatchEvent): String =
    when (event) {
        is FsWatchEvent.Created -> "created"
        is FsWatchEvent.Modified -> "modified"
        is FsWatchEvent.Removed -> "removed"
        is FsWatchEvent.Moved -> "moved"
        is FsWatchEvent.Overflow -> "overflow"
        is FsWatchEvent.Other -> "other"
    }

private fun elapsedMillisSince(startedAtNanos: Long): Long = (System.nanoTime() - startedAtNanos) / 1_000_000

private fun reportPathFrom(args: Array<String>): Path? {
    val reportIndex = args.indexOf("--report")
    if (reportIndex >= 0 && reportIndex + 1 < args.size) {
        return Path.of(args[reportIndex + 1])
    }

    val envPath = System.getenv("FS_WATCHER_SMOKE_REPORT")?.takeIf { it.isNotBlank() } ?: return null
    return Path.of(envPath)
}

private fun writeReportFile(
    path: Path,
    reportJson: String,
) {
    Files.createDirectories(path.parent ?: path.toAbsolutePath().parent)
    Files.writeString(path, reportJson)
}

private data class SmokeReport(
    val status: String,
    val observedEventKind: String?,
    val targetPath: String?,
    val durationMs: Long,
    val errorClass: String?,
    val errorMessage: String?,
    val scenarios: List<ScenarioResult>,
) {
    fun toJson(): String {
        return buildString {
            append('{')
            appendJsonField("status", status)
            append(',')
            appendJsonField("observedEventKind", observedEventKind)
            append(',')
            appendJsonField("targetPath", targetPath)
            append(',')
            append("\"durationMs\":").append(durationMs)
            append(',')
            appendJsonField("errorClass", errorClass)
            append(',')
            appendJsonField("errorMessage", errorMessage)
            append(',')
            append("\"scenarios\":[")
            scenarios.forEachIndexed { index, scenario ->
                if (index > 0) append(',')
                append(scenario.toJson())
            }
            append(']')
            append('}')
        }
    }
}

private data class ScenarioResult(
    val name: String,
    val status: String,
    val observedEventKind: String?,
    val errorMessage: String?,
) {
    fun toJson(): String =
        buildString {
            append('{')
            appendJsonField("name", name)
            append(',')
            appendJsonField("status", status)
            append(',')
            appendJsonField("observedEventKind", observedEventKind)
            append(',')
            appendJsonField("errorMessage", errorMessage)
            append('}')
        }
}

private fun StringBuilder.appendJsonField(name: String, value: String?) {
    append('"').append(name).append('"').append(':')
    if (value == null) {
        append("null")
    } else {
        append('"').append(value.jsonEscape()).append('"')
    }
}

private fun String.jsonEscape(): String =
    buildString(length) {
        for (ch in this@jsonEscape) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
