package com.arjangconsulting.codeeditor

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolCompletionBackendTest {
    private val tools = """
        [
          {
            "name": "tap_element",
            "description": "Tap a matching element",
            "inputSchema": {
              "type": "object",
              "properties": {
                "id": {"type": "string", "description": "Accessibility identifier"},
                "scope": {"type": "string", "enum": ["app", "system"]},
                "first": {"type": "boolean"},
                "limit": {"type": "integer", "enum": [1, 10]},
                "options": {"type": "object"}
              },
              "required": ["id"]
            }
          },
          {"name": "press_home", "inputSchema": {"type": "object"}}
        ]
    """.trimIndent()
    private val catalog = ToolCommandCatalog.fromMcpToolsJson(tools)

    private suspend fun complete(
        text: String,
        caret: Int = text.length,
        backend: CompletionBackend = ToolCompletionBackend(catalog),
    ): CompletionList {
        val snapshot = DocumentSnapshot(text, 7)
        val request = CompletionRequest(
            "request", CompletionContext("external-tool", 3), "tools", text, 7,
            snapshot.positionOf(caret), CompletionTrigger.Invoked,
        )
        val response = backend.complete(request)!!
        assertEquals("external-tool", response.sessionId)
        assertEquals(3, response.contextRevision)
        return response.completions!!
    }

    private fun accept(text: String, item: CompletionItem): String {
        val edit = item.textEdit!!
        val inserted = if (item.insertTextFormat == InsertTextFormat.SNIPPET) {
            parseSnippet(edit.newText).text
        } else {
            edit.newText
        }
        return applyTextEdits(text, listOf(edit.copy(newText = inserted)))
    }

    @Test
    fun `imports bare lists MCP results and full JSON RPC responses`() {
        val envelopes = listOf(tools, "{\"tools\":$tools}", "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":$tools}}")
        for (value in envelopes) {
            assertEquals(catalog, ToolCommandCatalog.fromMcpToolsJson(value))
        }
        val parameters = catalog.commands.first().parameters
        assertTrue(parameters.first().required)
        assertEquals("Accessibility identifier", parameters.first().description)
        assertEquals(listOf("app", "system"), parameters[1].values)
        assertEquals(ToolValueKind.BOOLEAN, parameters[2].kind)
        assertEquals(listOf("1", "10"), parameters[3].values)
        assertEquals(ToolValueKind.JSON, parameters[4].kind)
        assertEquals(TokenKind.FUNCTION, catalog.languageRules().tokenizeLine("tap_element").kindAt(0))
        assertFailsWith<IllegalArgumentException> { ToolCommandCatalog.fromMcpToolsJson("{\"error\":{}}") }
        assertFailsWith<IllegalArgumentException> { ToolCommandCatalog(listOf(ToolCommand("bad name"))) }
    }

    @Test
    fun `suggests command names and replaces the whole word from its middle`() = runTest {
        assertEquals(listOf("tap_element", "press_home"), complete("").items.map { it.label })
        val text = "tap_eleBROKEN"
        assertEquals("tap_element", accept(text, complete(text, 7).items.first()))
        assertTrue(complete("unknown ").items.isEmpty())
        assertTrue(complete("# tap").items.isEmpty())
        assertTrue(complete("tap_element id=one # comment").items.isEmpty())
    }

    @Test
    fun `suggests arguments with documentation required labels and snippets excluding used names`() = runTest {
        val backend = ToolCompletionBackend(catalog, labels = ToolCompletionLabels(required = "necessary"))
        val items = complete("tap_element ", backend = backend).items
        val id = items.single { it.label == "id=" }
        assertEquals("necessary", id.detail)
        assertEquals("Accessibility identifier", id.documentation?.value)
        assertEquals("tap_element id=\"\"", accept("tap_element ", id))
        val used = complete("tap_element id=\"Sign in\" ").items
        assertTrue(used.none { it.label == "id=" })
        assertTrue(used.any { it.label == "scope=" })
        val text = "tap_element scBROKEN=system"
        val scope = complete(text, text.indexOf("BROKEN")).items.single { it.label == "scope=" }
        assertEquals("tap_element scope=system", accept(text, scope))
    }

    @Test
    fun `schema values replace quoted values and suffixes without disturbing following arguments`() = runTest {
        val text = "tap_element scope=\"syBROKEN\" id=one"
        val item = complete(text, text.indexOf("BROKEN")).items.single { it.label == "system" }
        assertEquals("tap_element scope=system id=one", accept(text, item))
        assertEquals(listOf("true", "false"), complete("tap_element first=").items.map { it.label })
        assertEquals(listOf("1", "10"), complete("tap_element limit=").items.map { it.label })
    }

    @Test
    fun `runtime values receive session and decoded prefix and are safely quoted on insertion`() = runTest {
        val requests = mutableListOf<ToolValueRequest>()
        val backend = ToolCompletionBackend(catalog, ToolValueProvider { request ->
            requests += request
            listOf("Sign in", "a\"b\\c", "Sign in")
        })
        val text = "tap_element id=\"Sign inBROKEN\" scope=app"
        val list = complete(text, text.indexOf("BROKEN"), backend)
        assertTrue(list.isIncomplete)
        assertEquals(2, list.items.size)
        assertEquals("Sign in", requests.single().prefix)
        assertEquals("id", requests.single().parameter.name)
        assertEquals("external-tool", requests.single().request.context.sessionId)
        assertEquals("tap_element id=\"Sign in\" scope=app", accept(text, list.items.first()))
        assertEquals("tap_element id=\"a\\\"b\\\\c\" scope=app", accept(text, list.items.last()))
        complete("tap_element id='Sign in", backend = backend)
        assertEquals("Sign in", requests.last().prefix)
        complete("tap_element id=Sign\\ in", backend = backend)
        assertEquals("Sign in", requests.last().prefix)
    }

    @Test
    fun `multiline requests use original UTF16 positions and preserve other lines`() = runTest {
        val text = "# 😀\r\ntap_element scope=syBROKEN"
        val list = complete(text, text.indexOf("BROKEN"))
        assertEquals("# 😀\r\ntap_element scope=system", accept(text, list.items.single { it.label == "system" }))
    }
}
