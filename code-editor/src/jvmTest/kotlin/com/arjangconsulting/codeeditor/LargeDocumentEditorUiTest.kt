package com.arjangconsulting.codeeditor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.input.SetComposingTextCommand
import androidx.compose.ui.text.input.CommitTextCommand
import kotlinx.coroutines.awaitCancellation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LargeDocumentEditorUiTest {
    private fun save(image: ImageBitmap, name: String) {
        val file = File("build/reports/long-lines/$name.png")
        file.parentFile.mkdirs()
        Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.use { data -> file.writeBytes(data.bytes) }
    }

    @Test
    fun longLineNavigationSelectionClipboardFindAndDiagnostics() = runComposeUiTest {
        val original = "{\"value\":\"" + "x".repeat(15_000) + "😀é\\tEND_MARKER\"}"
        val marker = original.indexOf("END_MARKER")
        val state = CodeEditorState(original)
        val clipboard = object : Clipboard {
            var entry: ClipEntry? = null
            override suspend fun getClipEntry(): ClipEntry? = entry
            override suspend fun setClipEntry(clipEntry: ClipEntry?) { entry = clipEntry }
        }
        val diagnostic = Diagnostic(TextRange(TextPosition(0, marker), TextPosition(0, marker + 10)), "Marker diagnostic", DiagnosticSeverity.ERROR)
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                MaterialTheme {
                    CodeEditor(state, Modifier.size(640.dp, 240.dp).testTag("editor"), language = JsonRules, diagnostics = listOf(diagnostic))
                }
            }
        }
        val input = onNodeWithTag(CODE_EDITOR_INPUT_TAG)
        input.requestFocus()
        input.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveEnd) } }
        waitForIdle()
        assertEquals(original.length, state.selectionEnd)
        input.performKeyInput { withKeyDown(Key.ShiftLeft) { repeat(12) { pressKey(Key.DirectionLeft) } } }
        waitForIdle()
        val selected = state.selectedText
        assertEquals("END_MARKER\"}", selected)
        save(onNodeWithTag("editor").captureToImage(), "unwrapped-after-15000")
        onNodeWithTag("editor").performMouseInput { moveTo(Offset(550f, 15f)) }
        waitUntil(timeoutMillis = 3_000) { onAllNodes(androidx.compose.ui.test.hasTestTag(HOVER_TAG)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Marker diagnostic", substring = true).assertIsDisplayed()
        save(onAllNodes(isRoot())[1].captureToImage(), "tooltip-after-15000")
        onNodeWithTag("editor").performMouseInput { moveTo(Offset(400f, 100f)) }
        input.performKeyInput { withKeyDown(if (isApplePlatform) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.C) } }
        waitForIdle()
        input.performKeyInput { pressKey(Key.DirectionRight) }
        input.performKeyInput { withKeyDown(if (isApplePlatform) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.V) } }
        waitForIdle()
        assertEquals(original + selected, state.text)
        input.performKeyInput { withKeyDown(if (isApplePlatform) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.Z) } }
        waitForIdle()
        assertEquals(original, state.text)
        input.performKeyInput { withKeyDown(if (isApplePlatform) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.F) } }
        onNodeWithTag(FIND_FIELD_TAG).performTextInput("END_MARKER")
        waitForIdle()
        assertEquals(marker, state.selectionStart)
        assertEquals(marker + 10, state.selectionEnd)
        assertEquals(original, state.text)
    }

    @Test
    fun wrappingReflowsAfterResizeAndPointerSelectionUsesVisualRows() = runComposeUiTest {
        val original = "{\"value\":\"" + "abcdefghij".repeat(1600) + "😀e\u0301END\"}\r\n\tsecond line"
        val state = CodeEditorState(original, 0 until 0)
        var width by mutableStateOf(500.dp)
        var wrap by mutableStateOf(true)
        val diagnostic = Diagnostic(TextRange(TextPosition(0, 70), TextPosition(0, 80)), "Wrapped diagnostic")
        setContent {
            MaterialTheme {
                CodeEditor(state, Modifier.size(width, 260.dp).testTag("editor"), language = JsonRules, diagnostics = listOf(diagnostic), colors = CodeEditorDefaults.colors().copy(diagnosticError = Color.Red), options = CodeEditorOptions(softWrap = wrap))
            }
        }
        val input = onNodeWithTag(CODE_EDITOR_INPUT_TAG)
        input.requestFocus()
        input.performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        val firstRowWidth = state.selectionEnd
        assertTrue(firstRowWidth in 20..100)
        assertEquals(TextPosition(0, firstRowWidth), state.caret)
        input.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionDown) } }
        waitForIdle()
        assertTrue(state.selectedText.isNotEmpty())
        val wideImage = onNodeWithTag("editor").captureToImage()
        save(wideImage, "wrapped-wide")
        val pixels = wideImage.toPixelMap()
        assertTrue((20 until 60).sumOf { y -> (50 until pixels.width).count { x -> pixels[x, y].red > 0.8f && pixels[x, y].green < 0.2f } } > 5)
        width = 280.dp
        waitForIdle()
        input.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) }; pressKey(Key.DirectionDown) }
        waitForIdle()
        assertTrue(state.selectionEnd < firstRowWidth)
        save(onNodeWithTag("editor").captureToImage(), "wrapped-narrow")
        onNodeWithTag("editor").performMouseInput { click(Offset(130f, 55f)) }
        waitForIdle()
        assertEquals(0, state.caret.line)
        assertTrue(state.caret.character > 0)
        assertEquals(original, state.text)
        input.performTextInput("日")
        waitForIdle()
        input.performKeyInput { withKeyDown(if (isApplePlatform) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.Z) } }
        waitForIdle()
        assertEquals(original, state.text)
        wrap = false
        waitForIdle()
        input.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveEnd) } }
        wrap = true
        waitForIdle()
        input.performKeyInput { pressKey(Key.DirectionLeft) }
        assertEquals(original.length - 1, state.selectionEnd)
        assertEquals(original, state.text)
    }

    @Test
    fun foldedHeaderWrapsAndKeepsTheLogicalCaretVisible() = runComposeUiTest {
        val original = "{\"value\":\"" + "x".repeat(220) + "\",\n  \"body\": 1,\n  \"other\": 2\n}"
        val state = CodeEditorState(original, 0 until 0)
        setContent {
            MaterialTheme {
                CodeEditor(state, Modifier.size(400.dp, 220.dp).testTag("editor"), language = JsonRules, options = CodeEditorOptions(softWrap = true))
            }
        }
        waitUntil { state.foldingRanges.any { it.startLine == 0 } }
        runOnIdle {
            state.select(state.snapshot().lineStart(1) + 3)
            state.fold(0)
        }
        waitForIdle()
        assertEquals(0, state.caret.line)
        assertEquals(3, state.caret.character)
        assertTrue(0 in state.foldedLines)
        assertEquals(original, state.text)
        save(onNodeWithTag("editor").captureToImage(), "wrapped-fold-header")
    }

    @Test
    fun fiveMegabyteLineCanNavigateAndHitTestAtItsTail() = runComposeUiTest {
        val original = "x".repeat(5_000_000) + " TAIL_MARKER"
        val state = CodeEditorState(original, 0 until 0)
        setContent {
            MaterialTheme { CodeEditor(state, Modifier.size(640.dp, 200.dp).testTag("editor"), showLineNumbers = false, folding = false) }
        }
        val input = onNodeWithTag(CODE_EDITOR_INPUT_TAG)
        input.requestFocus()
        input.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveEnd) } }
        waitForIdle()
        assertEquals(original.length, state.selectionEnd)
        save(onNodeWithTag("editor").captureToImage(), "unwrapped-5MB-tail")
        onNodeWithTag("editor").performMouseInput { click(Offset(600f, 15f)) }
        waitForIdle()
        assertTrue(state.selectionEnd > 5_000_000)
        input.performTextInput("!")
        input.performKeyInput { withKeyDown(if (isApplePlatform) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.Z) } }
        waitForIdle()
        assertEquals(original, state.text)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun nativeCompositionBeyondOldLimitKeepsTheDocumentAndMarkedRange() = runComposeUiTest {
        val prefix = "x".repeat(15_000)
        val suffix = " tail"
        val state = CodeEditorState(prefix + suffix, prefix.length until prefix.length)
        var request: PlatformTextInputMethodRequest? = null
        setContent {
            InterceptPlatformTextInput(interceptor = { current, _ ->
                request = current
                awaitCancellation()
            }) {
                MaterialTheme {
                    CodeEditor(state, Modifier.size(400.dp, 200.dp), options = CodeEditorOptions(softWrap = true))
                }
            }
        }
        onNodeWithTag(CODE_EDITOR_INPUT_TAG).requestFocus()
        waitUntil { request != null }
        val input = kotlin.test.assertNotNull(request)
        runOnIdle { input.onEditCommand(listOf(SetComposingTextCommand("に", 1))) }
        waitForIdle()
        assertEquals(prefix + "に" + suffix, state.text)
        runOnIdle { kotlin.test.assertNotNull(input.value().composition) }
        runOnIdle { input.onEditCommand(listOf(SetComposingTextCommand("にほ", 1))) }
        waitForIdle()
        assertEquals(prefix + "にほ" + suffix, state.text)
        runOnIdle { kotlin.test.assertNotNull(input.value().composition) }
        runOnIdle { input.onEditCommand(listOf(CommitTextCommand("日本", 1))) }
        waitForIdle()
        assertEquals(prefix + "日本" + suffix, state.text)
        assertEquals(prefix.length + 2, state.selectionEnd)
    }

    @Test
    fun readOnlyLongSelectionCopyFocusTraversalAndComposeOverlays() = runComposeUiTest {
        val original = "x".repeat(12_000) + " final readable text"
        val state = CodeEditorState(original)
        var dialog by mutableStateOf(false)
        var menu by mutableStateOf(false)
        var readOnly by mutableStateOf(true)
        setContent {
            MaterialTheme {
                Column {
                    CodeEditor(state, Modifier.size(500.dp, 220.dp).testTag("editor"), readOnly = readOnly, tabBehavior = TabBehavior.MOVE_FOCUS, options = CodeEditorOptions(softWrap = true), labels = CodeEditorLabels(editorDescription = "Response body"))
                    Button(onClick = { dialog = true }, modifier = Modifier.testTag("after")) { Text("Dialog") }
                    Button(onClick = { menu = true }) { Text("Menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Overlay menu") }, onClick = { menu = false })
                    }
                    if (dialog) AlertDialog(onDismissRequest = { dialog = false }, confirmButton = {
                        TextButton(onClick = { dialog = false }) { Text("Close") }
                    }, text = { Text("Overlay dialog") })
                }
            }
        }
        val input = onNodeWithTag(CODE_EDITOR_INPUT_TAG)
        input.requestFocus()
        input.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveEnd) } }
        input.performKeyInput { withKeyDown(Key.ShiftLeft) { repeat(19) { pressKey(Key.DirectionLeft) } } }
        waitForIdle()
        assertEquals("final readable text", state.selectedText)
        val semantics = input.fetchSemanticsNode().config
        assertEquals(listOf("Response body"), semantics[SemanticsProperties.ContentDescription])
        assertTrue(semantics.contains(SemanticsActions.SetSelection))
        assertTrue(semantics.contains(SemanticsActions.CopyText))
        input.performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("after").assertIsFocused().performClick()
        onNodeWithText("Overlay dialog").assertIsDisplayed()
        save(onAllNodes(isRoot())[1].captureToImage(), "dialog-over-editor")
        onNodeWithText("Close").performClick()
        onNodeWithText("Menu").performClick()
        onNodeWithText("Overlay menu").assertIsDisplayed()
        save(onAllNodes(isRoot())[1].captureToImage(), "menu-over-editor")
        onNodeWithText("Overlay menu").performClick()
        readOnly = false
        waitForIdle()
        input.requestFocus()
        input.performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("after").assertIsFocused()
        onNodeWithTag("after").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        input.assertIsFocused()
        assertEquals(original, state.text)
    }
}
