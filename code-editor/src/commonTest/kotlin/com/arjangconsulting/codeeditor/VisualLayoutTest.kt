package com.arjangconsulting.codeeditor

import kotlinx.coroutines.CancellationException
import kotlin.test.assertFailsWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisualLayoutTest {
    private fun layout(text: String, width: Float = 0f, fold: FoldLayout? = null): VisualLayout {
        val snapshot = DocumentSnapshot(text, 0)
        return VisualLayout(snapshot, fold ?: FoldLayout.unfolded(snapshot.lineCount), 4, 10f, width, advance = { glyph ->
            if (glyph == "😀") 20f else 10f
        })
    }

    @Test
    fun completeLongStringHasGeometryBeyondOldLimit() {
        val text = "{\"value\":\"" + "x".repeat(50_000) + "😀éEND\"}"
        val visual = layout(text)
        assertEquals(text.length, visual.end(0))
        for (offset in listOf(10_001, 24_999, 49_999, text.length)) {
            assertEquals(offset, visual.offsetAt(0, visual.xAt(0, offset)))
        }
        assertTrue(visual.width > 500_000f)
    }

    @Test
    fun wrapsDoNotChangeLogicalPositionsOrCrLf() {
        val text = "abcdef\r\n\txyz\r\n😀e\u0301終z"
        val visual = layout(text, 40f)
        assertEquals(6, visual.rowCount)
        assertEquals(listOf(0, 0, 1, 1, 2, 2), (0 until visual.rowCount).map(visual::lineAt))
        assertEquals(4, visual.start(1))
        assertEquals(8, visual.start(2))
        assertEquals(9, visual.start(3))
        assertEquals(40f, visual.xAt(2, 9))
        assertEquals(9, visual.offsetAt(2, 40f))
        assertEquals(TextPosition(1, 1), visual.snapshot.positionOf(visual.start(3)))
        assertEquals(text, visual.snapshot.text)
    }

    @Test
    fun resizeReflowsWithoutSplittingSurrogatesOrCombiningSequences() {
        val text = "ab😀e\u0301終z".repeat(100)
        val narrow = layout(text, 30f)
        val wide = layout(text, 100f)
        assertTrue(narrow.rowCount > wide.rowCount)
        for (visual in listOf(narrow, wide)) {
            for (row in 0 until visual.rowCount) {
                val start = visual.start(row)
                assertTrue(!text[start].isLowSurrogate())
                assertTrue(text[start] != '\u0301')
                assertEquals(start, visual.offsetAt(row, 0f))
                assertEquals(visual.end(row), visual.offsetAt(row, 10_000f))
            }
        }
    }

    @Test
    fun foldsHideWholeLogicalLinesAndKeepWrappedHeaderRows() {
        val text = "long header line\nbody\nbody\ntail"
        val snapshot = DocumentSnapshot(text, 0)
        val fold = FoldLayout(snapshot.lineCount, listOf(FoldingRange(0, 2)), setOf(0))
        val visual = layout(text, 50f, fold)
        assertEquals(listOf(0, 0, 0, 0, 3), (0 until visual.rowCount).map(visual::lineAt))
        assertEquals(0, visual.rowOf(2, 3))
        assertEquals(4, visual.rowOf(3, 0))
        assertTrue(visual.isLastRow(3))
        assertTrue(visual.isFirstRow(4))
    }

    @Test
    fun tilesCoverEverySourceCharacterWithBoundedParagraphs() {
        val text = ("\tab😀e\u0301".repeat(4000))
        val geometry = layout(text).geometry(0)
        var offset = 0
        val restored = StringBuilder()
        while (offset < text.length) {
            val end = geometry.tileEnd(offset, text.length)
            assertTrue(end - offset <= RENDER_TILE_CHARS + 2)
            assertTrue(!text.getOrElse(end) { ' ' }.isLowSurrogate())
            restored.append(text, offset, end)
            offset = end
        }
        assertEquals(text, restored.toString())
    }

    @Test
    fun longLexerScansCooperateWithCancellation() {
        for (source in listOf("\"" + "\\n".repeat(100_000) + "\"", "9".repeat(200_000))) {
            var checks = 0
            assertFailsWith<CancellationException> {
                JsonRules.tokenizeLineChecked(source, LexerState.Initial) {
                    if (++checks == 3) throw CancellationException("superseded")
                }
            }
            assertEquals(3, checks)
        }
    }

    @Test
    fun manyFormattingFragmentsUndoAndRedoExactly() {
        val source = (0..2000).joinToString(",", "[", "]") { "{\"id\":$it}" }
        val state = CodeEditorState(source)
        val snapshot = state.snapshot()
        val edits = source.indices.filter { source[it] == ',' }.map {
            TextEdit.insert(snapshot.positionOf(it + 1), "\n  ")
        }
        state.edit(edits)
        val formatted = state.text
        assertTrue(formatted.length > source.length)
        assertTrue(state.undo())
        assertEquals(source, state.text)
        assertTrue(state.redo())
        assertEquals(formatted, state.text)
    }
}
