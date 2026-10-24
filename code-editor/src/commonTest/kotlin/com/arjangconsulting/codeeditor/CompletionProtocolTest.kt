package com.arjangconsulting.codeeditor

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CompletionProtocolTest {
    private val request = CompletionRequest(
        requestId = "request-1",
        context = CompletionContext("session-1", 3, mapOf("deviceId" to "simulator")),
        languageId = "tools",
        text = "# 😀\r\ntap id=\"sign\"",
        documentVersion = 7,
        position = TextPosition(1, 12),
        trigger = CompletionTrigger.character('"'),
    )

    @Test
    fun `request JSON round trips context trigger unicode and CRLF`() {
        assertEquals(request, CompletionProtocol.decodeRequest(CompletionProtocol.encodeRequest(request)))
        val encoded = CompletionProtocol.encodeRequest(request)
        assertTrue(encoded.contains("\"protocolVersion\":1"))
        assertTrue(encoded.contains("TRIGGER_CHARACTER"))
        assertEquals(request, CompletionProtocol.decodeRequest(encoded.dropLast(1) + ",\"futureField\":true}"))
    }

    @Test
    fun `response JSON preserves full completion items and original coordinate edits`() {
        val range = TextRange(TextPosition(1, 8), TextPosition(1, 12))
        val item = CompletionItem(
            label = "sign-in",
            kind = CompletionItemKind.VALUE,
            detail = "Button",
            documentation = MarkupContent.markdown("**Sign in**"),
            insertText = "sign-in",
            textEdit = TextEdit(range, "sign-in"),
            filterText = "sign",
            sortText = "0",
            insertTextFormat = InsertTextFormat.SNIPPET,
            additionalTextEdits = listOf(TextEdit.insert(TextPosition.Zero, "# header\r\n")),
            preselect = true,
            commitCharacters = setOf(' ', '"'),
            id = "opaque-backend-id",
        )
        val response = request.response(CompletionList(listOf(item), isIncomplete = true))
        assertEquals(response, CompletionProtocol.decodeResponse(CompletionProtocol.encodeResponse(response)))
        for (list in listOf(null, CompletionList.Empty)) {
            val emptyResponse = request.response(list)
            val encoded = CompletionProtocol.encodeResponse(emptyResponse)
            assertEquals(emptyResponse, CompletionProtocol.decodeResponse(encoded))
        }
    }

    @Test
    fun `rejects incompatible versions malformed messages and invalid coordinates`() {
        val encoded = CompletionProtocol.encodeRequest(request)
        assertFailsWith<IllegalArgumentException> {
            CompletionProtocol.decodeRequest(encoded.replace("\"protocolVersion\":1", "\"protocolVersion\":2"))
        }
        assertFailsWith<IllegalArgumentException> {
            CompletionProtocol.encodeRequest(request.copy(protocolVersion = 2))
        }
        assertFailsWith<IllegalArgumentException> {
            CompletionProtocol.encodeResponse(request.response(null).copy(protocolVersion = 2))
        }
        assertFailsWith<SerializationException> { CompletionProtocol.decodeRequest("{}") }
        assertFailsWith<IllegalArgumentException> { request.copy(position = TextPosition(2, 0)) }
        assertFailsWith<IllegalArgumentException> { request.copy(position = TextPosition(1, 999)) }
        assertFailsWith<IllegalArgumentException> { CompletionContext(" ") }
        assertFailsWith<IllegalArgumentException> { CompletionContext("session", -1) }
        val reversedEdit = """{"start":{"line":0,"character":2},"end":{"line":0,"character":1}}"""
        val response = CompletionProtocol.encodeResponse(request.response(CompletionList(listOf(
            CompletionItem("x", textEdit = TextEdit(TextRange.at(TextPosition.Zero), "x")),
        ))))
        val originalRange = """{"start":{"line":0,"character":0},"end":{"line":0,"character":0}}"""
        assertFailsWith<IllegalArgumentException> {
            CompletionProtocol.decodeResponse(response.replace(originalRange, reversedEdit))
        }
    }
}
