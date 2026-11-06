package com.arjangconsulting.codeeditor

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.test.assertTextEquals
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
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ControlledValueEditorUiTest {
    private fun ComposeUiTest.input() = onNodeWithTag(CODE_EDITOR_INPUT_TAG)

    private fun KeyInjectionScope.shortcut(key: Key) =
        withKeyDown(if (isApplePlatform) Key.MetaLeft else Key.CtrlLeft) { pressKey(key) }

    @Test
    fun `external values replace the text without callbacks`() = runComposeUiTest {
        var value by mutableStateOf("initial")
        val callbacks = mutableListOf<String>()
        setContent {
            MaterialTheme {
                CodeEditor(value, { callbacks += it }, Modifier.size(400.dp, 100.dp))
            }
        }
        waitForIdle()
        input().assertTextEquals("initial")
        assertTrue(callbacks.isEmpty())
        for (replacement in listOf("newest command", "older command", "newest command", "")) {
            runOnIdle { value = replacement }
            waitForIdle()
            input().assertTextEquals(replacement)
            assertTrue(callbacks.isEmpty(), "external value: $replacement")
        }
    }

    @Test
    fun `typing undo and redo report changes after external synchronization`() = runComposeUiTest {
        var value by mutableStateOf("initial")
        val callbacks = mutableListOf<String>()
        setContent {
            MaterialTheme {
                CodeEditor(
                    value,
                    {
                        callbacks += it
                        value = it
                    },
                    Modifier.size(400.dp, 100.dp),
                )
            }
        }
        input().requestFocus()
        runOnIdle { value = "replacement" }
        waitForIdle()
        assertTrue(callbacks.isEmpty())
        input().performTextInput("!")
        waitForIdle()
        assertEquals(listOf("replacement!"), callbacks)
        input().performKeyInput { shortcut(Key.Z) }
        waitForIdle()
        input().performKeyInput { shortcut(Key.Z) }
        waitForIdle()
        input().performKeyInput { withKeyDown(Key.ShiftLeft) { shortcut(Key.Z) } }
        waitForIdle()
        input().performKeyInput { withKeyDown(Key.ShiftLeft) { shortcut(Key.Z) } }
        waitForIdle()
        assertEquals(listOf("replacement!", "replacement", "initial", "replacement", "replacement!"), callbacks)
        assertEquals("replacement!", value)
        input().assertTextEquals(value)
    }

    @Test
    fun `completion acceptance reports one change after external synchronization`() = runComposeUiTest {
        var value by mutableStateOf("")
        val callbacks = mutableListOf<String>()
        val rules = tokenRules("commands") { keywords("print") }
        val service = KeywordLanguageService(rules)
        setContent {
            MaterialTheme {
                CodeEditor(
                    value,
                    {
                        callbacks += it
                        value = it
                    },
                    Modifier.size(400.dp, 200.dp),
                    language = rules,
                    languageService = service,
                    serviceOptions = LanguageServiceOptions(completionDebounceMillis = 0),
                )
            }
        }
        input().requestFocus()
        runOnIdle { value = "pr" }
        waitForIdle()
        assertTrue(callbacks.isEmpty())
        input().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) } }
        waitUntil(timeoutMillis = 3_000) {
            onAllNodesWithTag(COMPLETION_LIST_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        input().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf("print"), callbacks)
        assertEquals("print", value)
        input().assertTextEquals(value)
    }

    @Test
    fun `repeated history arrows preserve the host history position`() = runComposeUiTest {
        val commands = listOf("first command", "second command")
        var historyIndex: Int? = null
        var value by mutableStateOf("")
        val callbacks = mutableListOf<String>()
        setContent {
            MaterialTheme {
                CodeEditor(
                    value,
                    {
                        callbacks += it
                        value = it
                        historyIndex = null
                    },
                    Modifier.size(400.dp, 100.dp),
                    singleLine = true,
                    onHistory = { delta ->
                        val next = ((historyIndex ?: commands.size) + delta).coerceIn(0, commands.size)
                        historyIndex = next.takeIf { it < commands.size }
                        value = commands.getOrNull(next).orEmpty()
                    },
                )
            }
        }
        input().requestFocus()
        val steps = listOf(
            Key.DirectionUp to 1,
            Key.DirectionUp to 0,
            Key.DirectionUp to 0,
            Key.DirectionDown to 1,
            Key.DirectionDown to null,
            Key.DirectionDown to null,
            Key.DirectionUp to 1,
            Key.DirectionUp to 0,
        )
        for ((key, expectedIndex) in steps) {
            input().performKeyInput { pressKey(key) }
            waitForIdle()
            assertEquals(expectedIndex, historyIndex)
            assertEquals(commands.getOrNull(expectedIndex ?: commands.size).orEmpty(), value)
            input().assertTextEquals(value)
            assertTrue(callbacks.isEmpty())
        }
        input().performTextInput("!")
        waitForIdle()
        assertEquals(listOf("first command!"), callbacks)
        assertEquals(null, historyIndex)
        input().performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()
        assertEquals(1, historyIndex)
        input().assertTextEquals("second command")
        assertEquals(listOf("first command!"), callbacks)
    }

    @Test
    fun `delayed parent echoes do not overwrite newer typing`() = runComposeUiTest {
        var value by mutableStateOf("")
        val callbacks = mutableListOf<String>()
        setContent {
            MaterialTheme {
                CodeEditor(value, { callbacks += it }, Modifier.size(400.dp, 100.dp))
            }
        }
        input().requestFocus()
        input().performTextInput("a")
        waitForIdle()
        input().performTextInput("b")
        waitForIdle()
        runOnIdle { value = "a" }
        waitForIdle()
        input().assertTextEquals("ab")
        assertEquals(listOf("a", "ab"), callbacks)
        runOnIdle { value = "ab" }
        waitForIdle()
        input().performTextInput("c")
        waitForIdle()
        input().assertTextEquals("abc")
        assertEquals(listOf("a", "ab", "abc"), callbacks)
        runOnIdle { value = "external" }
        waitForIdle()
        input().assertTextEquals("external")
        assertEquals(listOf("a", "ab", "abc"), callbacks)
    }
}
