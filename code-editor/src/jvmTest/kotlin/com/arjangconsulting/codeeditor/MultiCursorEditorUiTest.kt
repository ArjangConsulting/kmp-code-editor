package com.arjangconsulting.codeeditor

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.click
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MultiCursorEditorUiTest {
    private val shortcutKey = if (isApplePlatform) Key.MetaLeft else Key.CtrlLeft

    private fun KeyInjectionScope.shortcut(key: Key) = withKeyDown(shortcutKey) { pressKey(key) }

    private fun ComposeUiTest.input() = onNodeWithTag(CODE_EDITOR_INPUT_TAG)

    private fun ComposeUiTest.show(state: CodeEditorState) {
        setContent { MaterialTheme { CodeEditor(state, Modifier.size(500.dp, 120.dp).testTag("editor")) } }
        input().requestFocus()
        waitForIdle()
    }

    private fun ComposeUiTest.altClickAtLineEnd() {
        onNodeWithTag("editor").performMultiModalInput {
            key { keyDown(Key.AltLeft) }
            mouse { click(Offset(width - 4f, 6f)) }
            key { keyUp(Key.AltLeft) }
        }
        waitForIdle()
    }

    @Test
    fun `alt click adds and removes a caret and typing edits every caret`() = runComposeUiTest {
        val state = CodeEditorState("abc def", 0 until 0)
        show(state)
        altClickAtLineEnd()
        assertEquals(listOf(Selection(0), Selection(7)), state.selectionSet.all)
        input().performTextInput("X")
        waitForIdle()
        assertEquals("Xabc defX", state.text)
        altClickAtLineEnd()
        assertEquals(Selections(Selection(1)), state.selectionSet)
        input().performTextInput("Y")
        waitForIdle()
        assertEquals("XYabc defX", state.text)
    }

    @Test
    fun `add next occurrence then type replaces each occurrence`() = runComposeUiTest {
        val state = CodeEditorState("foo bar foo baz foo", 1 until 1)
        show(state)
        input().performKeyInput { shortcut(Key.D) }
        input().performKeyInput { shortcut(Key.D) }
        waitForIdle()
        assertEquals(listOf(Selection(0, 3), Selection(8, 11)), state.selectionSet.all)
        input().performTextInput("x")
        waitForIdle()
        assertEquals("x bar x baz foo", state.text)
    }

    @Test
    fun `select all occurrences edits all and undo restores the selections`() = runComposeUiTest {
        val state = CodeEditorState("foo bar foo baz foo", 1 until 1)
        show(state)
        input().performKeyInput { withKeyDown(shortcutKey) { withKeyDown(Key.ShiftLeft) { pressKey(Key.L) } } }
        waitForIdle()
        assertEquals(3, state.selectionSet.size)
        input().performTextInput("y")
        waitForIdle()
        assertEquals("y bar y baz y", state.text)
        input().performKeyInput { shortcut(Key.Z) }
        waitForIdle()
        assertEquals("foo bar foo baz foo", state.text)
        assertEquals(listOf(Selection(0, 3), Selection(8, 11), Selection(16, 19)), state.selectionSet.all)
    }

    @Test
    fun `alt enter in the find bar selects every match`() = runComposeUiTest {
        val state = CodeEditorState("foo bar foo baz foo", 0 until 0)
        show(state)
        input().performKeyInput { shortcut(Key.F) }
        waitForIdle()
        onNodeWithTag(FIND_FIELD_TAG).performTextInput("ba")
        onNodeWithTag(FIND_FIELD_TAG).performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.Enter) } }
        waitForIdle()
        assertFalse(onAllNodes(androidx.compose.ui.test.hasTestTag(FIND_FIELD_TAG)).fetchSemanticsNodes().isNotEmpty())
        input().performTextInput("Q")
        waitForIdle()
        assertEquals("foo Qr foo Qz foo", state.text)
    }

    @Test
    fun `arrows move every caret and escape collapses to the primary`() = runComposeUiTest {
        val state = CodeEditorState("ab\ncd")
        state.setSelections(listOf(TextRange.at(TextPosition(1, 0)), TextRange.at(TextPosition(0, 0))))
        show(state)
        input().performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        assertEquals(listOf(Selection(1), Selection(4)), state.selectionSet.all)
        input().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionRight) } }
        waitForIdle()
        assertEquals(listOf(Selection(1, 2), Selection(4, 5)), state.selectionSet.all)
        input().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertFalse(state.selectionSet.isMulti)
        assertEquals(Selection(4, 5), state.selectionInternal)
    }

    @Test
    fun `pasting one line per caret distributes the lines`() = runComposeUiTest {
        val state = CodeEditorState("a\nb")
        state.setSelections(listOf(TextRange.at(TextPosition(1, 1)), TextRange.at(TextPosition(0, 1))))
        show(state)
        input().performTextInput("1\n2")
        waitForIdle()
        assertEquals("a1\nb2", state.text)
        assertTrue(state.canUndo)
    }
}
