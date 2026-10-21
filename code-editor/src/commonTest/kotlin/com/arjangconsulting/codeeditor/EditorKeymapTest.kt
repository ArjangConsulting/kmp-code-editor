package com.arjangconsulting.codeeditor

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EditorKeymapTest {
    private fun apple(chord: KeyChord) = mapKey(chord, apple = true)

    private fun other(chord: KeyChord) = mapKey(chord, apple = false)

    @Test
    fun `apple platforms use command and option`() {
        assertEquals(KeyBinding(EditorCommand.UNDO), apple(KeyChord(Key.Z, meta = true)))
        assertEquals(KeyBinding(EditorCommand.REDO), apple(KeyChord(Key.Z, meta = true, shift = true)))
        assertEquals(KeyBinding(EditorCommand.WORD_LEFT, extend = true), apple(KeyChord(Key.DirectionLeft, alt = true, shift = true)))
        assertEquals(KeyBinding(EditorCommand.LINE_END), apple(KeyChord(Key.DirectionRight, meta = true)))
        assertEquals(KeyBinding(EditorCommand.DOCUMENT_START), apple(KeyChord(Key.DirectionUp, meta = true)))
        assertEquals(KeyBinding(EditorCommand.FIND_NEXT), apple(KeyChord(Key.G, meta = true)))
        assertEquals(KeyBinding(EditorCommand.DELETE_TO_LINE_START), apple(KeyChord(Key.Backspace, meta = true)))
        assertEquals(KeyBinding(EditorCommand.LINE_START), apple(KeyChord(Key.A, ctrl = true)))
        assertNull(apple(KeyChord(Key.Z, ctrl = true)))
    }

    @Test
    fun `other platforms use control`() {
        assertEquals(KeyBinding(EditorCommand.UNDO), other(KeyChord(Key.Z, ctrl = true)))
        assertEquals(KeyBinding(EditorCommand.REDO), other(KeyChord(Key.Y, ctrl = true)))
        assertEquals(KeyBinding(EditorCommand.WORD_RIGHT), other(KeyChord(Key.DirectionRight, ctrl = true)))
        assertEquals(KeyBinding(EditorCommand.DOCUMENT_END, extend = true), other(KeyChord(Key.MoveEnd, ctrl = true, shift = true)))
        assertEquals(KeyBinding(EditorCommand.FIND), other(KeyChord(Key.F, ctrl = true)))
        assertEquals(KeyBinding(EditorCommand.TOGGLE_COMMENT), other(KeyChord(Key.Slash, ctrl = true)))
        assertNull(other(KeyChord(Key.G, meta = true)))
    }

    @Test
    fun `shared bindings`() {
        for (apple in listOf(true, false)) {
            assertEquals(KeyBinding(EditorCommand.TRIGGER_COMPLETION), mapKey(KeyChord(Key.Spacebar, ctrl = true), apple))
            assertEquals(KeyBinding(EditorCommand.OUTDENT), mapKey(KeyChord(Key.Tab, shift = true), apple))
            assertEquals(KeyBinding(EditorCommand.ENTER), mapKey(KeyChord(Key.NumPadEnter), apple))
            assertEquals(KeyBinding(EditorCommand.FIND_PREVIOUS), mapKey(KeyChord(Key.F3, shift = true), apple))
            assertEquals(KeyBinding(EditorCommand.PAGE_DOWN, extend = true), mapKey(KeyChord(Key.PageDown, shift = true), apple))
            assertNull(mapKey(KeyChord(Key.A), apple), "plain characters go through text input")
            assertNull(mapKey(KeyChord(Key.Spacebar), apple))
        }
    }

    @Test
    fun `markdown subset parses code blocks and paragraphs`() {
        val blocks = parseMarkdownBlocks("# Title\nSome `code` here.\n\n```kotlin\nval x = 1\n```\n- a\n- b")
        assertEquals(
            listOf(
                MarkdownBlock.Paragraph("**Title** Some `code` here."),
                MarkdownBlock.Code("val x = 1"),
                MarkdownBlock.Paragraph("- a\n- b"),
            ),
            blocks,
        )
    }

    @Test
    fun `display lines expand tabs and map columns back`() {
        val line = DisplayLine.of("\tab\tc", tabSize = 4)
        assertEquals("    ab  c", line.text)
        assertEquals(4, line.toDisplay(1))
        assertEquals(8, line.toDisplay(4))
        assertEquals(1, line.toDocument(4))
        assertEquals(0, line.toDocument(1))
        assertEquals(1, line.toDocument(3))
        assertEquals(5, line.toDocument(9))
    }
    @Test
    fun `fold shortcuts preserve plain bracket input`() {
        assertEquals(KeyBinding(EditorCommand.FOLD), apple(KeyChord(Key.LeftBracket, meta = true, alt = true)))
        assertEquals(KeyBinding(EditorCommand.UNFOLD), apple(KeyChord(Key.RightBracket, meta = true, alt = true)))
        assertEquals(KeyBinding(EditorCommand.FOLD), other(KeyChord(Key.LeftBracket, ctrl = true, shift = true)))
        assertEquals(KeyBinding(EditorCommand.UNFOLD), other(KeyChord(Key.RightBracket, ctrl = true, shift = true)))
        for (apple in listOf(true, false)) {
            assertNull(mapKey(KeyChord(Key.LeftBracket), apple))
            assertNull(mapKey(KeyChord(Key.RightBracket), apple))
        }
    }

}
