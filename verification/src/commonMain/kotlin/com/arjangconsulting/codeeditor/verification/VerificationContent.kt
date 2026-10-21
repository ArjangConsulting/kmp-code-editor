package com.arjangconsulting.codeeditor.verification

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.arjangconsulting.codeeditor.CodeEditor
import com.arjangconsulting.codeeditor.CodeEditorState
import com.arjangconsulting.codeeditor.KotlinRules

private val initialText = "fun greet() {\n    val message = \"hello world\"\n    println(message)\n}\nend"

/** A fixture editor with visible state and a native composition probe supplied by the host app. */
@Composable
public fun VerificationContent(
    runCompositionProbe: ((String) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
): Unit {
    val state = remember { CodeEditorState(initialText, 0 until 0) }
    var readOnly by remember { mutableStateOf(false) }
    var probeResult by remember { mutableStateOf("Not run") }
    MaterialTheme {
        Surface(modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp)) {
                Text("Editor verification", style = MaterialTheme.typography.titleLarge)
                Row {
                    Button(onClick = { state.resetText(initialText) }) { Text("Reset") }
                    Button(onClick = { state.resetText("") }) { Text("Empty") }
                    Button(onClick = { readOnly = !readOnly }) { Text(if (readOnly) "Make editable" else "Make read only") }
                }
                Button(onClick = { runCompositionProbe { probeResult = it + "; text=" + state.text } }) {
                    Text("Probe composition")
                }
                Text("Probe result: $probeResult")
                Text("Selection: ${state.selectionStart}..${state.selectionEnd}")
                Text("Folds: ${state.foldedLines.sorted().joinToString()}")
                CodeEditor(
                    state = state,
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("verification-editor"),
                    language = KotlinRules,
                    readOnly = readOnly,
                )
                Text("Text: ${state.text.replace("\n", "⏎")}", maxLines = 4)
            }
        }
    }
}
