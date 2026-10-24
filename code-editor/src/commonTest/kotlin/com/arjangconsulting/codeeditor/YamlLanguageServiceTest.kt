package com.arjangconsulting.codeeditor

import it.krzeminski.snakeyaml.engine.kmp.api.LoadSettings
import it.krzeminski.snakeyaml.engine.kmp.api.lowlevel.Parse
import it.krzeminski.snakeyaml.engine.kmp.events.ScalarEvent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YamlLanguageServiceTest {
    private val service = YamlLanguageService()
    private val options = FormattingOptions(tabSize = 2)

    private suspend fun formatted(text: String, options: FormattingOptions = this.options): String =
        applyTextEdits(text, service.format(DocumentSnapshot(text, 0), options)!!)

    @Test
    fun `formats nested mappings sequences and flow collections idempotently`() = runTest {
        val original = "root:\n    child: {one: 1,two: [true,false,null]}\n" +
            "    items:\n      - name: first\n        enabled: true"
        val output = formatted(original)
        val expected = "root:\n  child: {one: 1, two: [true, false, null]}\n" +
            "  items:\n  - name: first\n    enabled: true\n"
        assertEquals(expected, output)
        assertTrue(service.format(DocumentSnapshot(output, 1), options)!!.isEmpty())
        assertTrue(service.diagnostics(DocumentSnapshot(output, 1)).isEmpty())
        assertTrue(formatted(original, FormattingOptions(tabSize = 4)).contains("\n    child:"))
    }

    @Test
    fun `preserves comments anchors tags duplicate keys scalar values and documents`() = runTest {
        val original = """
            %YAML 1.2
            ---
            # Configuration
            defaults: &defaults
                value: !!str 001
            copy: *defaults
            value: 123456789012345678901234567890
            value: -0.00100E+9999
            quote: 'it''s YAML'
            escaped: "\u0041\n"
            script: |-
                echo "hello"
                # This is scalar content
            folded: >+
                first line
                second line

            flow: [one,two] # Keep this comment
            ...
            ---
            second: document
        """.trimIndent()
        val output = formatted(original)
        val fragments = listOf(
            "%YAML 1.2", "# Configuration", "&defaults", "*defaults", "!!str", "# Keep this comment", "...", "---",
        )
        for (fragment in fragments) {
            assertTrue(output.contains(fragment), fragment)
        }
        fun scalarValues(text: String): List<String> =
            Parse(LoadSettings()).parse(text).filterIsInstance<ScalarEvent>().map { it.value }
        assertEquals(scalarValues(original), scalarValues(output))
        assertEquals(2, Regex("(?m)^value:").findAll(output).count())
        assertTrue(service.format(DocumentSnapshot(output, 1), options)!!.isEmpty())
    }

    @Test
    fun `invalid input is never rewritten and diagnostics are localizable`() = runTest {
        val localized = YamlLanguageService(YamlLanguageServiceLabels(invalidSyntax = "Check YAML", source = "local"))
        for (text in listOf("key: [1, 2", "a: \"unterminated", "root:\n\tchild: value", "key: {a: b")) {
            assertNull(service.format(DocumentSnapshot(text, 0), options), text)
            val diagnostic = localized.diagnostics(DocumentSnapshot(text, 0)).single()
            assertEquals("Check YAML", diagnostic.message)
            assertEquals("local", diagnostic.source)
        }
        val unicode = "key: [😀, }"
        val unicodeDiagnostic = localized.diagnostics(DocumentSnapshot(unicode, 0)).single()
        assertEquals(TextPosition(0, unicode.indexOf('}')), unicodeDiagnostic.range.start)
    }

    @Test
    fun `preserves line ending style and accepts empty scalar and comment only documents`() = runTest {
        for (newline in listOf("\n", "\r\n", "\r")) {
            assertEquals("root:${newline}  value: yes$newline", formatted("root:${newline}    value: yes", options))
        }
        for (text in listOf("", "  ", "# Only a comment\n", "true", "日本 😀", "---\n...")) {
            assertTrue(service.diagnostics(DocumentSnapshot(text, 0)).isEmpty(), text)
            val output = formatted(text)
            assertTrue(service.diagnostics(DocumentSnapshot(output, 1)).isEmpty(), text)
        }
        for (text in listOf("", "  ", "# Only a comment\n")) {
            assertEquals(text, formatted(text))
        }
    }

    @Test
    fun `resource limits and unsupported indentation refuse formatting`() = runTest {
        val limited = YamlLanguageService(YamlLanguageServiceLabels(nestingLimit = "Too deep"), maxNestingDepth = 2)
        assertTrue(limited.diagnostics(DocumentSnapshot("[{}]", 0)).isEmpty())
        val nested = DocumentSnapshot("[[[]]]", 0)
        assertEquals("Too deep", limited.diagnostics(nested).single().message)
        assertNull(limited.format(nested, options))
        assertTrue(service.diagnostics(DocumentSnapshot("[".repeat(128) + "0" + "]".repeat(128), 0)).isEmpty())
        assertEquals(1, service.diagnostics(DocumentSnapshot("[".repeat(129) + "0" + "]".repeat(129), 0)).size)
        val oversized = DocumentSnapshot("x".repeat(3 * 1024 * 1024 + 1), 0)
        assertEquals("YAML document size limit exceeded", service.diagnostics(oversized).single().message)
        assertNull(service.format(oversized, options))
        assertFailsWith<IllegalArgumentException> { YamlLanguageService(maxNestingDepth = 0) }
        assertFailsWith<IllegalArgumentException> { YamlLanguageService(maxNestingDepth = 129) }
        for (unsupported in listOf(FormattingOptions(insertSpaces = false), FormattingOptions(tabSize = 11))) {
            assertNull(service.format(DocumentSnapshot("a: b", 0), unsupported))
        }
    }

    @Test
    fun `formatting through editor state is one undoable change`() = runTest {
        val original = "root:\n    child: [a,b]"
        val state = CodeEditorState(original)
        val timing = LanguageServiceOptions(dispatcher = StandardTestDispatcher(testScheduler))
        assertEquals(FormatResult.APPLIED, state.format(service, options, timing))
        assertEquals(FormatResult.UNCHANGED, state.format(service, options, timing))
        state.undo()
        assertEquals(original, state.text)
        state.redo()
        assertEquals("root:\n  child: [a, b]\n", state.text)
    }
}
