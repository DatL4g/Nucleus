package screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.launch

// ==================
// MARK: File dialogs (FileKit)
// ==================

/* OS-native Open / Save As / Directory dialogs via FileKit (io.github.vinceglb).
   FileKit exposes suspend pickers on the `FileKit` object, so each button
   launches a coroutine from the composition scope and writes the picked path
   back into snapshot state on resume. The result handler runs on the compose
   thread — no manual thread hop needed. */
@Composable
internal fun FileDialogsScreen() {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf("(no selection yet)") }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenTitle(
            "File dialogs",
            "FileKit native Open / Save As / Directory pickers — FileKit.openFilePicker / openFileSaver / openDirectoryPicker.",
        )

        Section("openFilePicker", "Single-selection OS Open dialog (all files)") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    scope.launch {
                        val file = FileKit.openFilePicker()
                        result = file?.path ?: "(cancelled)"
                    }
                }) { Text("Open file…") }
            }
        }

        Section("openFilePicker (images, multiple)", "Multi-select Open dialog filtered to images") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    scope.launch {
                        val files =
                            FileKit.openFilePicker(
                                type = FileKitType.Image,
                                mode = FileKitMode.Multiple(),
                            )
                        result = files
                            ?.takeIf { it.isNotEmpty() }
                            ?.joinToString(", ") { it.name }
                            ?: "(cancelled)"
                    }
                }) { Text("Open images…") }
            }
        }

        Section("openDirectoryPicker", "OS directory picker") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val dir = FileKit.openDirectoryPicker()
                        result = dir?.path ?: "(cancelled)"
                    }
                }) { Text("Pick directory…") }
            }
        }

        Section("openFileSaver", "OS Save As dialog seeded with a suggested name") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val file: PlatformFile? =
                            FileKit.openFileSaver(
                                suggestedName = "untitled",
                                defaultExtension = "txt",
                            )
                        result = file?.path ?: "(cancelled)"
                    }
                }) { Text("Save file…") }
            }
        }

        Section("Result", "The path returned by the last dialog") {
            Text(result, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
        }
    }
}
