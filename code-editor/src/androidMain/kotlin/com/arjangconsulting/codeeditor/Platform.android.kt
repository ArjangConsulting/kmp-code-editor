package com.arjangconsulting.codeeditor

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard

@Composable
internal actual fun rememberEditorClipboard(): EditorClipboard {
    val clipboard = LocalClipboard.current
    return remember(clipboard) {
        object : EditorClipboard {
            override suspend fun getText(): String? = runCatching {
                clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            }.getOrNull()

            override suspend fun setText(text: String) {
                runCatching { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", text))) }
            }
        }
    }
}

internal actual val isApplePlatform: Boolean = false

internal actual val rSyntaxTextAreaAvailable: Boolean = false

@Composable
internal actual fun RSyntaxTextAreaEditor(
    state: CodeEditorState,
    controller: LanguageServiceController,
    config: EditorConfig,
    modifier: Modifier,
): Unit = ComposeCodeEditor(state, controller, config, modifier)
