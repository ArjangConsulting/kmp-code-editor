package com.arjangconsulting.codeeditor

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Clipboard access for plain text; failures (no clipboard, headless) return null or are ignored. */
internal interface EditorClipboard {
    suspend fun getText(): String?

    suspend fun setText(text: String)
}

@Composable
internal expect fun rememberEditorClipboard(): EditorClipboard

/** True on macOS and iOS, where shortcuts use Command instead of Control. */
internal expect val isApplePlatform: Boolean

/** True when the RSyntaxTextArea engine can run on this platform. */
internal expect val rSyntaxTextAreaAvailable: Boolean

/** Hosts the RSyntaxTextArea engine. Only called when [rSyntaxTextAreaAvailable] is true. */
@Composable
internal expect fun RSyntaxTextAreaEditor(
    state: CodeEditorState,
    controller: LanguageServiceController,
    config: EditorConfig,
    modifier: Modifier,
)
