package dev.nucleusframework.zstddemo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.window.material.MaterialDecoratedWindow
import dev.nucleusframework.window.material.MaterialTitleBar
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.dialogs.openFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** UI state for the single compression flow. */
private sealed interface Status {
    data object Idle : Status

    data class Working(
        val name: String,
    ) : Status

    data class Done(
        val output: File,
        val originalSize: Long,
        val compressedSize: Long,
    ) : Status

    data class Failed(
        val message: String,
    ) : Status
}

fun main(args: Array<String>) =
    nucleusApplication(args) {
        val isDark = isSystemInDarkMode()
        MaterialTheme(colorScheme = if (isDark) darkColorScheme() else lightColorScheme()) {
            val state =
                rememberWindowState(
                    size = DpSize(560.dp, 460.dp),
                    position =
                        androidx.compose.ui.window.WindowPosition
                            .Aligned(Alignment.Center),
                )
            MaterialDecoratedWindow(
                onCloseRequest = ::exitApplication,
                state = state,
                title = "Zstd Demo — compress with zstd-kmp",
                minimumSize = DpSize(480.dp, 400.dp),
                nativePopupLayers = true,
            ) {
                MaterialTitleBar { Text("Zstd Demo") }
                Surface(modifier = Modifier.fillMaxSize()) {
                    CompressScreen()
                }
            }
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompressScreen() {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<Status>(Status.Idle) }

    fun run(
        pick: suspend () -> File?,
        compress: (File) -> File,
    ) {
        scope.launch {
            val selection = pick() ?: return@launch
            status = Status.Working(selection.name)
            runCatching {
                withContext(Dispatchers.IO) {
                    val originalSize = if (selection.isDirectory) selection.dirSize() else selection.length()
                    val output = compress(selection)
                    Triple(output, originalSize, output.length())
                }
            }.onSuccess { (output, original, compressed) ->
                status = Status.Done(output, original, compressed)
            }.onFailure { t ->
                status = Status.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Select a file or folder to compress with Zstandard (.zst).", style = MaterialTheme.typography.titleMedium)
        Text(
            "The zstd native library is loaded on demand from inside the zstd-kmp JAR — the exact " +
                "extract-and-load pattern the Nucleus store pipeline redirects in a sandboxed Pkg bundle.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val busy = status is Status.Working
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = !busy,
                onClick = {
                    run(
                        pick = { FileKit.openFilePicker()?.file },
                        compress = { file -> compressFile(file, File(file.parentFile, file.name + ".zst")) },
                    )
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Compress file…")
            }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    run(
                        pick = { FileKit.openDirectoryPicker()?.file },
                        compress = { dir -> compressDirectory(dir, File(dir.parentFile, dir.name + ".zip.zst")) },
                    )
                },
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Compress folder…")
            }
        }

        StatusCard(status)
    }
}

@Composable
private fun StatusCard(status: Status) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (status) {
                Status.Idle -> Text("No compression yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                is Status.Working -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.width(20.dp))
                        Text("Compressing ${status.name}…")
                    }
                }
                is Status.Done -> {
                    val ratio =
                        if (status.originalSize > 0) {
                            100.0 * status.compressedSize / status.originalSize
                        } else {
                            0.0
                        }
                    Text(
                        "Done ✓",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(status.output.absolutePath, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${status.originalSize.humanBytes()} → ${status.compressedSize.humanBytes()} " +
                            "(${"%.1f".format(ratio)}% of original)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is Status.Failed ->
                    Text("Failed: ${status.message}", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun File.dirSize(): Long = walkTopDown().filter { it.isFile }.sumOf { it.length() }

private fun Long.humanBytes(): String {
    if (this < 1024) return "$this B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = this.toDouble()
    var unitIndex = -1
    do {
        value /= 1024
        unitIndex++
    } while (value >= 1024 && unitIndex < units.lastIndex)
    return "%.1f %s".format(value, units[unitIndex])
}
