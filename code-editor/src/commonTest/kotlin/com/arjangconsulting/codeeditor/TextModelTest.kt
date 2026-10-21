package com.arjangconsulting.codeeditor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextModelTest {
    private fun pos(line: Int, character: Int) = TextPosition(line, character)

    @Test
    fun `positions round trip through offsets`() {
        val doc = DocumentSnapshot("ab\ncde\n\nf", 1)
        assertEquals(4, doc.lineCount)
        for (offset in 0..doc.text.length) assertEquals(offset, doc.offsetOf(doc.positionOf(offset)))
        assertEquals(pos(1, 2), doc.positionOf(5))
        assertEquals(pos(2, 0), doc.positionOf(7))
        assertEquals(pos(3, 1), doc.endPosition)
    }

    @Test
    fun `out of range positions clamp like LSP`() {
        val doc = DocumentSnapshot("ab\ncd", 1)
        assertEquals(2, doc.offsetOf(pos(0, 99)))
        assertEquals(5, doc.offsetOf(pos(9, 0)))
        assertEquals(pos(1, 2), doc.positionOf(99))
        assertEquals(pos(0, 0), doc.positionOf(-3))
    }

    @Test
    fun `crlf and cr both end lines`() {
        val doc = DocumentSnapshot("a\r\nb\rc\nd", 1)
        assertEquals(listOf("a", "b", "c", "d"), (0 until doc.lineCount).map(doc::line))
        assertEquals(3, doc.offsetOf(pos(1, 0)))
        // An offset between \r and \n belongs to the end of the first line.
        assertEquals(pos(0, 1), doc.positionOf(2))
    }

    @Test
    fun `characters count utf16 code units so emoji take two`() {
        val doc = DocumentSnapshot("a😀b\n👍", 1)
        assertEquals(4, doc.lineLength(0))
        assertEquals(pos(0, 3), doc.positionOf(3))
        assertEquals(3, doc.offsetOf(pos(0, 3)))
        assertEquals(pos(1, 2), doc.endPosition)
        assertEquals("😀", doc.textIn(TextRange(pos(0, 1), pos(0, 3))))
    }

    @Test
    fun `carets never split surrogate pairs`() {
        val text = "a😀b"
        assertEquals(1, snapToCodePointBoundary(text, 2))
        assertEquals(3, snapToCodePointBoundary(text, 2, forward = true))
        assertEquals(1, previousCodePoint(text, 3))
        assertEquals(3, nextCodePoint(text, 1))
        assertEquals(4, nextCodePoint(text, 3))
    }

    @Test
    fun `text range normalizes and tests containment`() {
        val range = TextRange(pos(2, 0), pos(1, 4))
        assertEquals(pos(1, 4), range.start)
        assertTrue(pos(1, 9) in range)
        assertFalse(pos(2, 0) in range)
        assertTrue(TextRange.at(pos(3, 3)).let { pos(3, 3) in it })
        assertTrue(range.intersects(TextRange(pos(1, 8), pos(5, 0))))
        assertFalse(range.intersects(TextRange(pos(2, 0), pos(2, 1))))
    }

    @Test
    fun `applies edits against the original text`() {
        val text = "hello world"
        val edits = listOf(
            TextEdit(TextRange(pos(0, 6), pos(0, 11)), "there"),
            TextEdit(TextRange(pos(0, 0), pos(0, 5)), "hi"),
        )
        assertEquals("hi there", applyTextEdits(text, edits))
    }

    @Test
    fun `inserts at the same position keep list order`() {
        val edits = listOf(TextEdit.insert(pos(0, 1), "1"), TextEdit.insert(pos(0, 1), "2"))
        assertEquals("a12b", applyTextEdits("ab", edits))
    }

    @Test
    fun `multi line edits and deletes`() {
        val text = "one\ntwo\nthree"
        val result = applyTextEdits(text, listOf(TextEdit.delete(TextRange(pos(0, 3), pos(2, 0))), TextEdit.insert(pos(2, 5), "!")))
        assertEquals("onethree!", result)
    }

    @Test
    fun `overlapping edits are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            applyTextEdits("abcdef", listOf(TextEdit(TextRange(pos(0, 0), pos(0, 3)), "x"), TextEdit(TextRange(pos(0, 2), pos(0, 4)), "y")))
        }
    }

    @Test
    fun `offset mapping follows edits`() {
        val edit = TextEdit(TextRange(pos(0, 2), pos(0, 4)), "XYZ")
        val applied = applyTextEditsWithMapping("abcdef", listOf(edit))
        assertEquals("abXYZef", applied.text)
        assertEquals(1, applied.mapOffset(1))
        assertEquals(5, applied.mapOffset(3))
        assertEquals(6, applied.mapOffset(5))
        assertEquals(5, applied.endOf(edit))
    }

    @Test
    fun `diff finds the minimal replacement and honors the caret hint`() {
        assertEquals(TextDiff(2, 2, "X"), diffText("abcd", "abXcd"))
        assertEquals(TextDiff(1, 3, ""), diffText("abcd", "ad"))
        // Typing "a" with the caret at 0 in "a" inserts at 0, not 1.
        assertEquals(TextDiff(0, 0, "a"), diffText("a", "aa", caretHint = 0))
        assertTrue(diffText("same", "same").isNoOp)
    }
    @Test
    fun `maximum character positions clamp without integer overflow`() {
        val snapshot = DocumentSnapshot("a\nbc", 0)
        assertEquals(snapshot.lineEnd(1), snapshot.offsetOf(TextPosition(1, Int.MAX_VALUE)))
    }

}
