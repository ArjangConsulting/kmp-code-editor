package com.arjangconsulting.codeeditor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import platform.UIKit.UIPasteboard

@Composable
internal actual fun rememberEditorClipboard(): EditorClipboard = remember {
    object : EditorClipboard {
        override suspend fun getText(): String? = UIPasteboard.generalPasteboard.string

        override suspend fun setText(text: String) {
            UIPasteboard.generalPasteboard.string = text
        }
    }
}

internal actual val isApplePlatform: Boolean = true

internal actual val rSyntaxTextAreaAvailable: Boolean = false

@Composable
internal actual fun RSyntaxTextAreaEditor(
    state: CodeEditorState,
    controller: LanguageServiceController,
    config: EditorConfig,
    modifier: Modifier,
): Unit = ComposeCodeEditor(state, controller, config, modifier)
