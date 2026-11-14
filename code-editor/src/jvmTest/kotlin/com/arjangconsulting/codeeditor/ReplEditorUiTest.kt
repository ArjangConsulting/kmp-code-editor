package com.arjangconsulting.codeeditor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ReplEditorUiTest {
    private val rules = tokenRules("repl") { keywords("tap", "type") }
    private val service = KeywordLanguageService(rules)
    private val options = LanguageServiceOptions(completionDebounceMillis = 0, diagnosticsDebounceMillis = 0)

    private fun save(image: ImageBitmap, name: String) {
        val file = File("build/reports/repl/$name.png")
        file.parentFile.mkdirs()
        Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.use { data -> file.writeBytes(data.bytes) }
    }

    private fun ComposeUiTest.input() = onNodeWithTag(CODE_EDITOR_INPUT_TAG)

    private fun ComposeUiTest.waitForCompletion(open: Boolean) {
        waitUntil(timeoutMillis = 3_000) {
            onAllNodesWithTag(COMPLETION_LIST_TAG).fetchSemanticsNodes().isNotEmpty() == open
        }
    }

    @Test
    fun `snippet Tab and Shift Tab navigate edited arguments before requesting completion`() = runComposeUiTest {
        val state = CodeEditorState("t")
        var requests = 0
        val snippets = object : LanguageService {
            override suspend fun complete(doc: DocumentSnapshot, position: TextPosition, trigger: CompletionTrigger): CompletionList {
                requests++
                return CompletionList(listOf(CompletionItem("tap", insertText = "tap id=\${1:id} text=\${2:text}$0", insertTextFormat = InsertTextFormat.SNIPPET)))
            }
        }
        setContent {
            MaterialTheme {
                CodeEditor(state, Modifier.size(400.dp, 80.dp), languageService = snippets, singleLine = true,
                    tabBehavior = TabBehavior.COMPLETE, options = CodeEditorOptions(completionOnTyping = false), serviceOptions = options)
            }
        }
        input().requestFocus()
        input().performKeyInput { pressKey(Key.Tab) }
        waitForCompletion(true)
        input().performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        assertEquals("id", state.selectedText)
        input().performTextInput("button")
        input().performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        assertEquals("text", state.selectedText)
        input().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        waitForIdle()
        assertEquals("button", state.selectedText)
        assertEquals(1, requests)
        input().performKeyInput { pressKey(Key.Escape) }
        input().performKeyInput { pressKey(Key.Tab) }
        waitUntil { requests == 2 }
    }

    @Test
    fun `finite line limits grow the input then scroll and shrink after reset`() = runComposeUiTest {
        val state = CodeEditorState()
        setContent {
            MaterialTheme {
                CodeEditor(state, Modifier.testTag("growing").width(400.dp),
                    options = CodeEditorOptions(minLines = 2, maxLines = 3))
            }
        }
        fun height() = onNodeWithTag("growing").fetchSemanticsNode().boundsInRoot.height
        val initial = height()
        save(onNodeWithTag("growing").captureToImage(), "minimum-lines")
        state.setText("one\ntwo\nthree")
        waitForIdle()
        val full = height()
        assertTrue(full > initial)
        state.setText("one\ntwo\nthree\nfour\nfive")
        waitForIdle()
        assertEquals(full, height())
        save(onNodeWithTag("growing").captureToImage(), "capped-lines")
        state.resetText("")
        waitForIdle()
        assertEquals(initial, height())
    }

    @Test
    fun `multi line enter inserts a newline and shortcut enter submits and resets history`() = runComposeUiTest {
        val state = CodeEditorState("tap")
        var submitted: String? = null
        setContent {
            MaterialTheme {
                CodeEditor(state, Modifier.size(400.dp, 120.dp), onSubmit = {
                    submitted = it
                    state.resetText("")
                })
            }
        }
        input().requestFocus()
        input().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals("tap\n", state.text)
        assertNull(submitted)
        input().performTextInput("type")
        input().performKeyInput { withKeyDown(if (isApplePlatform) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.Enter) } }
        waitForIdle()
        assertEquals("tap\ntype", submitted)
        assertEquals("", state.text)
        assertTrue(!state.canUndo)
    }

    @Test
    fun `tab explicitly requests completion with automatic completion disabled`() = runComposeUiTest {
        val state = CodeEditorState("t")
        setContent {
            MaterialTheme {
                CodeEditor(state, Modifier.size(400.dp, 80.dp), language = rules, languageService = service,
                    singleLine = true, tabBehavior = TabBehavior.COMPLETE,
                    options = CodeEditorOptions(completionOnTyping = false), serviceOptions = options)
            }
        }
        input().requestFocus()
        waitForCompletion(false)
        input().performKeyInput { pressKey(Key.Tab) }
        waitForCompletion(true)
        input().performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        assertEquals("tap", state.text)
    }

    @Test
    fun `placeholder is exposed only while empty and never enters the document`() = runComposeUiTest {
        val state = CodeEditorState()
        setContent {
            MaterialTheme {
                CodeEditor(state, Modifier.size(400.dp, 80.dp).testTag("placeholder-editor"), placeholder = "Enter a command", singleLine = true)
            }
        }
        input().assertContentDescriptionEquals("Code editor, Enter a command")
        save(onNodeWithTag("placeholder-editor").captureToImage(), "placeholder-empty")
        assertEquals("", state.text)
        input().requestFocus()
        input().performTextInput("tap")
        input().assertContentDescriptionEquals("Code editor")
        save(onNodeWithTag("placeholder-editor").captureToImage(), "placeholder-populated")
        state.resetText("")
        waitForIdle()
        input().assertContentDescriptionEquals("Code editor, Enter a command")
    }

    @Test
    fun `suggestion navigation precedes history and enter accepts before submitting with undo`() = runComposeUiTest {
        val state = CodeEditorState("")
        val history = mutableListOf<Int>()
        var submitted: String? = null
        setContent {
            MaterialTheme {
                CodeEditor(
                    state,
                    Modifier.size(400.dp, 80.dp),
                    language = rules,
                    languageService = service,
                    singleLine = true,
                    serviceOptions = options,
                    onSubmit = { submitted = it },
                    onHistory = { history += it },
                )
            }
        }
        input().requestFocus()
        input().performTextInput("t")
        waitForCompletion(true)
        input().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
        }
        waitForIdle()
        assertEquals("type", state.text)
        assertTrue(history.isEmpty())
        assertNull(submitted)
        assertTrue(state.undo())
        assertEquals("t", state.text)
        assertTrue(state.redo())
        waitForIdle()
        input().performKeyInput {
            pressKey(Key.DirectionUp)
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
        }
        waitForIdle()
        assertEquals(listOf(-1, 1), history)
        assertEquals("type", submitted)
    }

    @Test
    fun `single line find commands remain available to the host`() = runComposeUiTest {
        var handledByHost = 0
        setContent {
            MaterialTheme {
                CodeEditor(CodeEditorState("tap"), Modifier.size(400.dp, 80.dp).onPreviewKeyEvent {
                    if (it.key == Key.F && it.type == KeyEventType.KeyDown) handledByHost++
                    false
                }, singleLine = true)
            }
        }
        input().requestFocus()
        input().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.F) } }
        waitForIdle()
        assertTrue(onAllNodesWithTag(FIND_FIELD_TAG).fetchSemanticsNodes().isEmpty())
        assertEquals(1, handledByHost)
    }

    @Test
    fun `tab accepts a keyword in a single line prompt`() = runComposeUiTest {
        val state = CodeEditorState("")
        setContent {
            MaterialTheme {
                CodeEditor(state, Modifier.size(400.dp, 80.dp), language = rules, languageService = service, singleLine = true, serviceOptions = options)
            }
        }
        input().requestFocus()
        input().performTextInput("t")
        waitForCompletion(true)
        input().performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        assertEquals("tap", state.text)
    }

    @Test
    fun `session revision closes completion through the string overload without changing text`() = runComposeUiTest {
        var revision by mutableStateOf(0L)
        var value by mutableStateOf("t")
        setContent {
            MaterialTheme {
                CodeEditor(
                    value,
                    { value = it },
                    Modifier.size(400.dp, 80.dp),
                    language = rules,
                    languageService = service,
                    singleLine = true,
                    languageServiceRevision = revision,
                    serviceOptions = options,
                )
            }
        }
        input().requestFocus()
        input().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) } }
        waitForCompletion(true)
        revision++
        waitForCompletion(false)
        assertEquals("t", value)
        input().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) } }
        waitForCompletion(true)
    }

    @Test
    fun `prompt and submitted code use the same keyword color`() = runComposeUiTest {
        val keyword = Color.Red
        setContent {
            MaterialTheme {
                val defaults = CodeEditorDefaults.colors()
                val colors = defaults.copy(syntaxTheme = CodeEditorSyntaxTheme(defaults.syntax.with(TokenKind.KEYWORD, keyword)))
                Column {
                    CodeEditor(CodeEditorState("tap"), Modifier.size(400.dp, 80.dp).testTag("prompt"), language = rules, colors = colors, singleLine = true)
                    CodeSnippet("tap", Modifier.testTag("transcript"), language = rules, colors = colors)
                }
            }
        }
        waitForIdle()
        for (tag in listOf("prompt", "transcript")) {
            val pixels = onNodeWithTag(tag).captureToImage().toPixelMap()
            var keywordPixels = 0
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    val pixel = pixels[x, y]
                    if (pixel.red > 0.8f && pixel.green < 0.2f && pixel.blue < 0.2f) keywordPixels++
                }
            }
            assertTrue(keywordPixels > 20, "$tag renders the keyword color")
        }
    }
}
