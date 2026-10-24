package com.arjangconsulting.codeeditor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ReplEditorUiTest {
    private val rules = tokenRules("repl") { keywords("tap", "type") }
    private val service = KeywordLanguageService(rules)
    private val options = LanguageServiceOptions(completionDebounceMillis = 0, diagnosticsDebounceMillis = 0)

    private fun ComposeUiTest.input() = onNodeWithTag(CODE_EDITOR_INPUT_TAG)

    private fun ComposeUiTest.waitForCompletion(open: Boolean) {
        waitUntil(timeoutMillis = 3_000) {
            onAllNodesWithTag(COMPLETION_LIST_TAG).fetchSemanticsNodes().isNotEmpty() == open
        }
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
