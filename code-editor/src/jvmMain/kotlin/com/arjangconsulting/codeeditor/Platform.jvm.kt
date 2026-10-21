package com.arjangconsulting.codeeditor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun rememberEditorClipboard(): EditorClipboard {
    val clipboard = LocalClipboard.current
    return remember(clipboard) {
        object : EditorClipboard {
            override suspend fun getText(): String? = runCatching {
                val transferable = clipboard.getClipEntry()?.nativeClipEntry as? Transferable
                transferable?.takeIf { it.isDataFlavorSupported(DataFlavor.stringFlavor) }
                    ?.getTransferData(DataFlavor.stringFlavor) as? String
            }.getOrNull()

            override suspend fun setText(text: String) {
                runCatching { clipboard.setClipEntry(ClipEntry(StringSelection(text))) }
            }
        }
    }
}

internal actual val isApplePlatform: Boolean =
    System.getProperty("os.name").orEmpty().lowercase().let { "mac" in it || "darwin" in it }

internal actual val rSyntaxTextAreaAvailable: Boolean = true

@Composable
internal actual fun RSyntaxTextAreaEditor(
    state: CodeEditorState,
    controller: LanguageServiceController,
    config: EditorConfig,
    modifier: Modifier,
): Unit = RstaCodeEditor(state, controller, config, modifier)
