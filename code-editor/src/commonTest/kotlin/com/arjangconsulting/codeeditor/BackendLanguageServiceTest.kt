package com.arjangconsulting.codeeditor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BackendLanguageServiceTest {
    private val context = CompletionContext("session", 4, mapOf("deviceId" to "device"))
    private val doc = DocumentSnapshot("tap", 2)
    private val caret = TextPosition(0, 3)

    @Test
    fun `backend receives complete context and unique request IDs`() = runTest {
        val requests = mutableListOf<CompletionRequest>()
        val service = BackendLanguageService(CompletionBackend { request ->
            requests += request
            request.response(CompletionList(listOf(CompletionItem("tap_element"))))
        }, "tools", context)
        repeat(2) {
            assertEquals("tap_element", service.complete(doc, caret, CompletionTrigger.Invoked)?.items?.single()?.label)
        }
        assertNotEquals(requests[0].requestId, requests[1].requestId)
        assertEquals(context, requests[0].context)
        assertEquals(doc.text, requests[0].text)
        assertEquals(doc.version, requests[0].documentVersion)
        assertEquals(caret, requests[0].position)
    }

    @Test
    fun `every response correlation field is checked`() = runTest {
        val transformations: List<(CompletionResponse) -> CompletionResponse> = listOf(
            { it.copy(requestId = "another-request") },
            { it.copy(sessionId = "another-session") },
            { it.copy(contextRevision = 3) },
            { it.copy(documentVersion = 1) },
            { it.copy(protocolVersion = 2) },
        )
        for (transform in transformations) {
            val service = BackendLanguageService(CompletionBackend { request ->
                transform(request.response(CompletionList(listOf(CompletionItem("wrong")))))
            }, "tools", context)
            assertNull(service.complete(doc, caret, CompletionTrigger.Invoked))
        }
    }

    @Test
    fun `unavailable or failed backends use fallback but deliberate empty results do not`() = runTest {
        var fallbackCalls = 0
        val fallback = object : LanguageService {
            override val triggerCharacters: Set<Char> = setOf('@')

            override suspend fun complete(
                doc: DocumentSnapshot,
                position: TextPosition,
                trigger: CompletionTrigger,
            ): CompletionList {
                fallbackCalls++
                return CompletionList(listOf(CompletionItem("local")))
            }
        }
        val failures = mutableListOf<String>()
        val logger = object : CodeEditorLogger {
            override fun error(message: String, throwable: Throwable?) {
                assertNull(throwable)
                failures += message
            }
        }
        val backends = listOf(
            CompletionBackend { null },
            CompletionBackend { it.response(null) },
            CompletionBackend { error("document contents must not be logged") },
        )
        for (backend in backends) {
            val service = BackendLanguageService(backend, "tools", context, fallback = fallback, logger = logger)
            assertTrue('@' in service.triggerCharacters)
            assertEquals("local", service.complete(doc, caret, CompletionTrigger.Invoked)?.items?.single()?.label)
        }
        assertEquals(3, fallbackCalls)
        assertEquals(1, failures.size)
        assertTrue(failures.none { "document contents" in it })
        val empty = BackendLanguageService(
            CompletionBackend { it.response(CompletionList.Empty) }, "tools", context, fallback = fallback,
        )
        assertEquals(CompletionList.Empty, empty.complete(doc, caret, CompletionTrigger.Invoked))
        assertEquals(3, fallbackCalls)
    }

    @Test
    fun `cancellation propagates without invoking fallback`() = runTest {
        val service = BackendLanguageService(
            CompletionBackend { throw CancellationException("cancelled") }, "tools", context,
        )
        assertFailsWith<CancellationException> { service.complete(doc, caret, CompletionTrigger.Invoked) }
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var returned = false
        val slow = BackendLanguageService(CompletionBackend { request ->
            withContext(NonCancellable) {
                entered.complete(Unit)
                finish.await()
                request.response(CompletionList.Empty)
            }
        }, "tools", context)
        val job = launch {
            slow.complete(doc, caret, CompletionTrigger.Invoked)
            returned = true
        }
        entered.await()
        job.cancel()
        finish.complete(Unit)
        job.cancelAndJoin()
        assertTrue(!returned)
    }

    @Test
    fun `candidates with out of bounds or overlapping edits are discarded`() = runTest {
        val outside = TextEdit(TextRange.at(TextPosition(0, 99)), "outside")
        val overlap = TextEdit(TextRange(TextPosition.Zero, caret), "overlap")
        val items = listOf(
            CompletionItem("valid"),
            CompletionItem("outside", textEdit = outside),
            CompletionItem("additional outside", additionalTextEdits = listOf(outside)),
            CompletionItem("overlap", textEdit = overlap, additionalTextEdits = listOf(overlap)),
        )
        val service = BackendLanguageService(
            CompletionBackend { it.response(CompletionList(items)) }, "tools", context,
        )
        assertEquals(listOf("valid"), service.complete(doc, caret, CompletionTrigger.Invoked)?.items?.map { it.label })
    }

    @Test
    fun `replacing a session drops an old backend result even if cancellation is ignored`() = runTest {
        val finish = CompletableDeferred<Unit>()
        val oldBackend = CompletionBackend { request ->
            withContext(NonCancellable) {
                finish.await()
                request.response(CompletionList(listOf(CompletionItem("tap_old"))))
            }
        }
        val controller = LanguageServiceController(
            this,
            BackendLanguageService(oldBackend, "tools", context),
            LanguageServiceOptions(dispatcher = StandardTestDispatcher(testScheduler)),
            PlainText,
            CodeEditorLogger.None,
        )
        controller.requestCompletion(doc, 3)
        runCurrent()
        controller.service = BackendLanguageService(
            CompletionBackend { it.response(CompletionList(listOf(CompletionItem("tap_new")))) },
            "tools",
            context.copy(revision = 5),
        )
        controller.requestCompletion(doc, 3)
        finish.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("tap_new"), controller.completion.value?.items?.map { it.item.label })
        controller.dispose()
    }
}
