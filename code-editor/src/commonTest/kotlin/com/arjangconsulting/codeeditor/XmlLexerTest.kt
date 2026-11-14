package com.arjangconsulting.codeeditor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class XmlLexerTest {
    @Test
    fun tagsAttributesValuesAndEntitiesUseDistinctKinds() {
        val source = "<soap:Body xmlns:soap='urn:test' enabled=\"a&amp;b\">&#x20; text</soap:Body>"
        val tokens = XmlRules.tokenizeLine(source).tokens.map { source.substring(it.start, it.end) to it.kind }
        for (expected in listOf(
            "soap:Body" to TokenKind.TYPE,
            "xmlns:soap" to TokenKind.ATTRIBUTE,
            "'urn:test'" to TokenKind.STRING,
            "&amp;" to TokenKind.ESCAPE,
            "&#x20;" to TokenKind.ESCAPE,
        )) assertTrue(expected in tokens, expected.toString())
    }

    @Test
    fun multilineConstructsResumeAndReturnToMarkup() {
        for ((opening, closing, kind) in listOf(
            Triple("<!-- <tag>", "comment -->", TokenKind.COMMENT),
            Triple("<![CDATA[<tag>", "&literal; ]]>", TokenKind.STRING),
            Triple("<?xml", "version='1.0'?>", TokenKind.PREPROCESSOR),
        )) {
            val lines = XmlRules.tokenize("$opening\n$closing<next/>")
            assertNotEquals(LexerState.Initial, lines[0].endState)
            assertEquals(kind, lines[1].tokens.first().kind)
            assertEquals(TokenKind.TYPE, lines[1].tokens.first { it.kind == TokenKind.TYPE }.kind)
            assertEquals(LexerState.Initial, lines[1].endState)
        }
        val lines = XmlRules.tokenize("<svg\nviewBox=\"0\n0 10 10\"\n><path/></svg>")
        assertEquals(TokenKind.ATTRIBUTE, lines[1].tokens.first().kind)
        assertEquals(TokenKind.STRING, lines[2].tokens.first().kind)
        assertEquals(LexerState.Initial, lines.last().endState)
    }

    @Test
    fun incrementalHighlightingInvalidatesCommentContext() {
        val highlighter = Highlighter(XmlRules)
        highlighter.update(DocumentSnapshot("<!--\n<svg/>\n-->\n<next/>", 0))
        assertEquals(TokenKind.COMMENT, highlighter.line(1).kindAt(1))
        highlighter.line(3)
        highlighter.update(DocumentSnapshot("text\n<svg/>\n-->\n<next/>", 1))
        assertEquals(TokenKind.TYPE, highlighter.line(1).kindAt(1))
        assertEquals(TokenKind.TYPE, highlighter.line(3).kindAt(1))
    }

    @Test
    fun incompleteMarkupAndPlainBodyTextRemainSafe() {
        for (source in listOf("<", "</", "&", "<tag a='", "<!DOCTYPE svg PUBLIC \"a>b\">", "plain & broken")) {
            val tokens = XmlRules.tokenizeLine(source).tokens
            assertTrue(tokens.zipWithNext().all { (a, b) -> a.end <= b.start })
            assertTrue(tokens.all { it.start >= 0 && it.end <= source.length && it.end > it.start })
        }
        assertTrue(XmlRules.tokenizeLine("plain text").tokens.isEmpty())
    }
}
