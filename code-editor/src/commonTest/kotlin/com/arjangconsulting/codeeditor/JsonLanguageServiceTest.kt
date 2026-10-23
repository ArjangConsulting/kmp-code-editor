package com.arjangconsulting.codeeditor

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonLanguageServiceTest {
    private val service = JsonLanguageService()
    private val options = FormattingOptions(tabSize = 2)

    @Test
    fun `formats nested collections without changing tokens`() = runTest {
        val original = """ { "n":123456789012345678901234567890, "n":-0.00100E+9999, "s":"\u0041\n\"", "a":[true,false,null,{},[]] } """
        val doc = DocumentSnapshot(original, 0)
        val formatted = applyTextEdits(original, service.format(doc, options)!!)
        val expected = """
            {
              "n": 123456789012345678901234567890,
              "n": -0.00100E+9999,
              "s": "\u0041\n\"",
              "a": [
                true,
                false,
                null,
                {},
                []
              ]
            }
        """.trimIndent()
        assertEquals(expected, formatted)
        assertTrue(service.diagnostics(DocumentSnapshot(formatted, 1)).isEmpty())
        assertTrue(service.format(DocumentSnapshot(formatted, 1), options)!!.isEmpty())
    }

    @Test
    fun `accepts all JSON root types and legal string escapes`() = runTest {
        val valid = listOf(
            "{}", "[]", "true", "false", "null", "0", "-0", "1.25e-10", "1E+2",
            """"\"\\\/\b\f\n\r\t\u00Af"""", "\"日本 😀\"",
        )
        for (text in valid) {
            assertTrue(service.diagnostics(DocumentSnapshot(text, 0)).isEmpty(), text)
            assertEquals(text, applyTextEdits(text, service.format(DocumentSnapshot(text, 0), options)!!), text)
        }
    }

    @Test
    fun `rejects invalid JSON and refuses to format it`() = runTest {
        val invalid = listOf(
            "", " ", "{", "[", "[1,]", "{\"a\":1,}", "{a:1}", "{\"a\" 1}", "[1 2]",
            "01", "-", "+1", "1.", "1e", "1e+", "NaN", "Infinity", "1_000", "0x10",
            "true false", "nul", "// comment\n{}", "/* comment */{}", "\u00a0null", "[١]",
            "\"open", "\"line\nbreak\"", "\"tab\t\"", "\"\u0000\"", "\"\\x\"", "\"\\u123z\"", "\"\\u12\"",
        )
        for (text in invalid) {
            assertEquals(1, service.diagnostics(DocumentSnapshot(text, 0)).size, text)
            assertNull(service.format(DocumentSnapshot(text, 0), options), text)
        }
    }

    @Test
    fun `diagnostics use UTF16 positions and localized labels including EOF`() = runTest {
        val localized = JsonLanguageService(JsonLanguageServiceLabels(expectedValue = "Missing value", source = "local"))
        val text = "{\r\n  \"😀\": }"
        val diagnostic = localized.diagnostics(DocumentSnapshot(text, 0)).single()
        assertEquals(TextPosition(1, 8), diagnostic.range.start)
        assertEquals(TextPosition(1, 9), diagnostic.range.end)
        assertEquals("Missing value", diagnostic.message)
        assertEquals("local", diagnostic.source)
        val eof = service.diagnostics(DocumentSnapshot("[1,", 0)).single()
        assertEquals(TextRange.at(TextPosition(0, 3)), eof.range)
    }

    @Test
    fun `preserves line ending style and final newline while using requested indentation`() = runTest {
        for (newline in listOf("\n", "\r\n", "\r")) {
            val text = "{${newline}\"a\":[1]}${newline}  "
            val formatted = applyTextEdits(text, service.format(DocumentSnapshot(text, 0), FormattingOptions(insertSpaces = false))!!)
            assertEquals("{$newline\t\"a\": [$newline\t\t1$newline\t]$newline}$newline", formatted)
        }
        val empty = " { } "
        assertEquals("{}", applyTextEdits(empty, service.format(DocumentSnapshot(empty, 0), options)!!))
    }

    @Test
    fun `nesting limit reports a diagnostic and prevents formatting`() = runTest {
        val limited = JsonLanguageService(JsonLanguageServiceLabels(nestingLimit = "Too deep"), maxNestingDepth = 2)
        assertTrue(limited.diagnostics(DocumentSnapshot("[{}]", 0)).isEmpty())
        val doc = DocumentSnapshot("[[[]]]", 0)
        assertEquals("Too deep", limited.diagnostics(doc).single().message)
        assertNull(limited.format(doc, options))
        assertFailsWith<IllegalArgumentException> { JsonLanguageService(maxNestingDepth = 0) }
        assertFailsWith<IllegalArgumentException> { JsonLanguageService(maxNestingDepth = 129) }
        assertTrue(service.diagnostics(DocumentSnapshot("[".repeat(128) + "0" + "]".repeat(128), 0)).isEmpty())
        assertEquals(1, service.diagnostics(DocumentSnapshot("[".repeat(129) + "0" + "]".repeat(129), 0)).size)
    }
}
