package com.arjangconsulting.codeeditor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class YamlLexerTest {
    private fun kinds(line: String): List<Pair<String, TokenKind>> =
        YamlRules.tokenizeLine(line).tokens.map { line.substring(it.start, it.end) to it.kind }

    @Test
    fun `highlights mappings scalars comments references and tags`() {
        val tokens = kinds(
            "{name: 'it''s YAML', enabled: true, count: -1.5e2, empty: ~, base: &base !!str value} # Config",
        )
        for (expected in listOf(
            "name" to TokenKind.PROPERTY,
            "'it''s YAML'" to TokenKind.STRING,
            "true" to TokenKind.CONSTANT,
            "-1.5e2" to TokenKind.NUMBER,
            "~" to TokenKind.CONSTANT,
            "&base" to TokenKind.VARIABLE,
            "!!str" to TokenKind.ANNOTATION,
            "# Config" to TokenKind.COMMENT,
        )) assertTrue(expected in tokens, expected.toString())
        assertTrue("*base" to TokenKind.VARIABLE in kinds("copy: *base"))
        assertTrue("\"quoted key\"" to TokenKind.PROPERTY in kinds("\"quoted key\": value"))
        assertTrue("true" to TokenKind.CONSTANT in kinds("{\"key\":true}"))
        assertTrue("---" to TokenKind.PREPROCESSOR in kinds("---"))
        assertTrue("%YAML 1.2" to TokenKind.PREPROCESSOR in kinds("%YAML 1.2"))
    }

    @Test
    fun `plain strings keep embedded punctuation and quotes literal`() {
        for (value in listOf("https://example.com/a#fragment", "it's YAML", "true story", "123abc", "yes", "a:b")) {
            assertTrue(value to TokenKind.STRING in kinds("key: $value"), value)
        }
    }

    @Test
    fun `block scalars end at dedentation and ignore syntax inside their body`() {
        for (header in listOf("script: |-", "script: >+", "script: |2", "- |", "- script: |")) {
            val lines = YamlRules.tokenize("$header\n    # true: [not syntax]\n\nnext: false")
            assertNotEquals(LexerState.Initial, lines[0].endState)
            assertEquals(listOf(TokenKind.STRING), lines[1].tokens.map { it.kind })
            assertEquals(TokenKind.PROPERTY, lines[3].tokens.first().kind)
            assertEquals(TokenKind.CONSTANT, lines[3].tokens.last().kind)
            assertEquals(LexerState.Initial, lines[3].endState)
        }
        val inferred = YamlRules.tokenize("script: |\n    indented\n  next: value")
        assertEquals(TokenKind.PROPERTY, inferred[2].tokens.first().kind)
    }

    @Test
    fun `quoted scalars and flow context continue across lines`() {
        val lines = YamlRules.tokenize("values: [\n  true,\n  \"first\n  second\",\n  null\n]")
        assertEquals(TokenKind.CONSTANT, lines[1].tokens.first().kind)
        assertEquals(TokenKind.STRING, lines[3].tokens.first().kind)
        assertEquals(TokenKind.CONSTANT, lines[4].tokens.first().kind)
        assertEquals(LexerState.Initial, lines.last().endState)
        val single = YamlRules.tokenize("key: 'first\nsecond'\nnext: value")
        assertEquals(TokenKind.STRING, single[1].tokens.first().kind)
        assertEquals(LexerState.Initial, single[1].endState)
    }

    @Test
    fun `incremental highlighting recovers when a block header is removed`() {
        val highlighter = Highlighter(YamlRules)
        highlighter.update(DocumentSnapshot("script: |\n  # body\nnext: true", 0))
        assertEquals(TokenKind.STRING, highlighter.line(1).tokens.first().kind)
        highlighter.update(DocumentSnapshot("script:\n  # body\nnext: true", 1))
        assertEquals(TokenKind.COMMENT, highlighter.line(1).tokens.first().kind)
        assertEquals(TokenKind.PROPERTY, highlighter.line(2).tokens.first().kind)
    }
}
