package com.arjangconsulting.codeeditor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FormattingTest {
    @Test
    fun `JSON formatting preserves selected tokens and undoes in one step`() = runTest {
        val original = "{\"name\":\"Ada\",\"items\":[1,2]}"
        val state = CodeEditorState(original)
        val start = original.indexOf("Ada")
        state.select(start, start + 3)
        val result = state.format(
            JsonLanguageService(),
            FormattingOptions(tabSize = 2),
            LanguageServiceOptions(dispatcher = StandardTestDispatcher(testScheduler)),
        )
        assertEquals(FormatResult.APPLIED, result)
        assertEquals("Ada", state.selectedText)
        assertEquals(1, state.version)
        assertTrue(state.undo())
        assertEquals(original, state.text)
        assertEquals("Ada", state.selectedText)
        assertFalse(state.undo())
        assertTrue(state.redo())
        assertTrue(state.text.contains("\n  \"name\": \"Ada\""))
    }

    @Test
    fun `no provider and invalid JSON leave text and history alone`() = runTest {
        val state = CodeEditorState("{broken}")
        val timing = LanguageServiceOptions(dispatcher = StandardTestDispatcher(testScheduler))
        assertEquals(FormatResult.UNAVAILABLE, state.format(LanguageService.None, serviceOptions = timing))
        assertEquals(FormatResult.UNAVAILABLE, state.format(JsonLanguageService(), serviceOptions = timing))
        assertEquals("{broken}", state.text)
        assertEquals(0, state.version)
        assertFalse(state.canUndo)
    }

    @Test
    fun `already formatted input does not add an undo entry`() = runTest {
        val state = CodeEditorState("{}")
        assertEquals(
            FormatResult.UNCHANGED,
            state.format(JsonLanguageService(), serviceOptions = LanguageServiceOptions(dispatcher = StandardTestDispatcher(testScheduler))),
        )
        assertFalse(state.canUndo)
    }

    @Test
    fun `slow provider cannot overwrite newer typing`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val service = object : LanguageService {
            override suspend fun format(doc: DocumentSnapshot, options: FormattingOptions): List<TextEdit> {
                entered.complete(Unit)
                finish.await()
                return listOf(TextEdit(doc.fullRange, "formatted"))
            }
        }
        val state = CodeEditorState("original")
        val result = async {
            state.format(service, serviceOptions = LanguageServiceOptions(dispatcher = StandardTestDispatcher(testScheduler)))
        }
        entered.await()
        state.setText("new typing")
        finish.complete(Unit)
        assertEquals(FormatResult.STALE, result.await())
        assertEquals("new typing", state.text)
        assertTrue(state.undo())
        assertEquals("original", state.text)
    }

    @Test
    fun `failure timeout and overlapping edits leave the document intact`() = runTest {
        val failures = mutableListOf<String>()
        val logger = object : CodeEditorLogger {
            override fun error(message: String, throwable: Throwable?) { failures += message }
        }
        val throwing = object : LanguageService {
            override suspend fun format(doc: DocumentSnapshot, options: FormattingOptions): List<TextEdit> = error("offline")
        }
        val slow = object : LanguageService {
            override suspend fun format(doc: DocumentSnapshot, options: FormattingOptions): List<TextEdit> {
                delay(100)
                return emptyList()
            }
        }
        val overlapping = object : LanguageService {
            override suspend fun format(doc: DocumentSnapshot, options: FormattingOptions): List<TextEdit> =
                listOf(TextEdit(doc.fullRange, "first"), TextEdit(doc.fullRange, "second"))
        }
        val state = CodeEditorState("original")
        val timing = LanguageServiceOptions(requestTimeoutMillis = 10, dispatcher = StandardTestDispatcher(testScheduler))
        for (provider in listOf(throwing, slow, overlapping)) {
            assertEquals(FormatResult.FAILED, state.format(provider, serviceOptions = timing, logger = logger))
            assertEquals("original", state.text)
            assertFalse(state.canUndo)
        }
        assertEquals(3, failures.size)
    }

    @Test
    fun `cancelling an explicit request propagates cancellation`() = runTest {
        val entered = CompletableDeferred<Unit>()
        var returned = false
        val service = object : LanguageService {
            override suspend fun format(doc: DocumentSnapshot, options: FormattingOptions): List<TextEdit> {
                entered.complete(Unit)
                delay(1000)
                return listOf(TextEdit(doc.fullRange, "formatted"))
            }
        }
        val state = CodeEditorState("original")
        val job = launch {
            state.format(service, serviceOptions = LanguageServiceOptions(dispatcher = StandardTestDispatcher(testScheduler)))
            returned = true
        }
        entered.await()
        job.cancelAndJoin()
        assertFalse(returned)
        assertEquals("original", state.text)
    }

    @Test
    fun `formatting options require positive indentation width`() {
        assertFailsWith<IllegalArgumentException> { FormattingOptions(tabSize = 0) }
        assertFailsWith<IllegalArgumentException> { FormattingOptions(tabSize = -1) }
    }

    @Test
    fun `caller timeout propagates instead of becoming a provider failure`() = runTest {
        var returned = false
        val service = object : LanguageService {
            override suspend fun format(doc: DocumentSnapshot, options: FormattingOptions): List<TextEdit> {
                delay(1000)
                return emptyList()
            }
        }
        val state = CodeEditorState("original")
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(10) {
                state.format(service, serviceOptions = LanguageServiceOptions(dispatcher = StandardTestDispatcher(testScheduler)))
                returned = true
            }
        }
        assertFalse(returned)
        assertEquals("original", state.text)
    }
}
