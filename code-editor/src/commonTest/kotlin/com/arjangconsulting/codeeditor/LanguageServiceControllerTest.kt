package com.arjangconsulting.codeeditor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LanguageServiceControllerTest {
    private val commands = listOf("tap", "type", "swipe", "scroll", "screenshot").map { CompletionItem(it, kind = CompletionItemKind.FUNCTION) }

    /** A scriptable service that records every call. */
    private class FakeService(
        var completions: (DocumentSnapshot, TextPosition, CompletionTrigger) -> CompletionList? = { _, _, _ -> null },
        var latencyMillis: Long = 0,
        var diagnosticsFor: (DocumentSnapshot) -> List<Diagnostic> = { emptyList() },
        override val triggerCharacters: Set<Char> = setOf('.'),
    ) : LanguageService {
        val completeCalls = mutableListOf<Pair<Long, CompletionTrigger>>()
        val diagnosticCalls = mutableListOf<Long>()
        var hoverCalls = 0

        override suspend fun complete(doc: DocumentSnapshot, position: TextPosition, trigger: CompletionTrigger): CompletionList? {
            completeCalls += doc.version to trigger
            delay(latencyMillis)
            return completions(doc, position, trigger)
        }

        override suspend fun diagnostics(doc: DocumentSnapshot): List<Diagnostic> {
            diagnosticCalls += doc.version
            delay(latencyMillis)
            return diagnosticsFor(doc)
        }

        override suspend fun hover(doc: DocumentSnapshot, position: TextPosition): Hover? {
            hoverCalls++
            delay(latencyMillis)
            return Hover(MarkupContent.plain("hover ${position.character}"))
        }

        override suspend fun resolveCompletionItem(item: CompletionItem): CompletionItem =
            item.copy(documentation = MarkupContent.plain("docs for ${item.label}"))
    }

    private class RecordingLogger : CodeEditorLogger {
        val errors = mutableListOf<String>()
        val debugs = mutableListOf<String>()

        override fun debug(message: String) {
            debugs += message
        }

        override fun error(message: String, throwable: Throwable?) {
            errors += message
        }
    }

    private fun TestScope.controller(service: LanguageService, logger: CodeEditorLogger = CodeEditorLogger.None): LanguageServiceController {
        val options = LanguageServiceOptions(
            completionDebounceMillis = 100,
            triggerDebounceMillis = 0,
            diagnosticsDebounceMillis = 300,
            hoverDelayMillis = 400,
            requestTimeoutMillis = 2_000,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        return LanguageServiceController(this, service, options, KotlinRules, logger)
    }

    /** Simulates typing [text] one character at a time into a document, returning the final snapshot. */
    private fun LanguageServiceController.type(start: String, text: String, firstVersion: Long): DocumentSnapshot {
        var current = start
        var version = firstVersion
        var snapshot = DocumentSnapshot(current, version)
        for (c in text) {
            current += c
            version++
            snapshot = DocumentSnapshot(current, version)
            documentChanged(snapshot, current.length, c)
        }
        return snapshot
    }

    @Test
    fun `typing an identifier debounces into a single request`() = runTest {
        val service = FakeService(completions = { _, _, _ -> CompletionList(commands) })
        val controller = controller(service)
        controller.type("", "sc", 0)
        advanceTimeBy(50)
        assertEquals(0, service.completeCalls.size, "still inside the debounce window")
        advanceUntilIdle()
        assertEquals(1, service.completeCalls.size)
        assertEquals(CompletionTriggerKind.INVOKED, service.completeCalls.single().second.kind)
        val session = assertNotNull(controller.completion.value)
        assertEquals(setOf("screenshot", "scroll"), session.items.map { it.item.label }.toSet())
        assertEquals(0, session.anchor)
    }

    @Test
    fun `complete lists are filtered locally without new requests`() = runTest {
        val service = FakeService(completions = { _, _, _ -> CompletionList(commands) })
        val controller = controller(service)
        val snapshot = controller.type("", "s", 0)
        advanceUntilIdle()
        assertEquals(3, controller.completion.value?.items?.size)
        controller.type(snapshot.text, "w", snapshot.version)
        advanceUntilIdle()
        assertEquals(1, service.completeCalls.size)
        assertEquals(listOf("swipe"), controller.completion.value?.items?.map { it.item.label })
    }

    @Test
    fun `incomplete lists are re-queried on every edit`() = runTest {
        val service = FakeService(completions = { doc, _, _ ->
            val prefix = doc.text.substringAfterLast(' ')
            CompletionList(commands.filter { it.label.startsWith(prefix) }, isIncomplete = true)
        })
        val controller = controller(service)
        val snapshot = controller.type("", "s", 0)
        advanceUntilIdle()
        controller.type(snapshot.text, "c", snapshot.version)
        advanceUntilIdle()
        assertEquals(2, service.completeCalls.size)
        assertEquals(CompletionTriggerKind.TRIGGER_FOR_INCOMPLETE_COMPLETIONS, service.completeCalls.last().second.kind)
        assertEquals(setOf("scroll", "screenshot"), controller.completion.value?.items?.map { it.item.label }?.toSet())
    }

    @Test
    fun `trigger characters request immediately`() = runTest {
        val service = FakeService(completions = { _, _, _ -> CompletionList(commands) })
        val controller = controller(service)
        controller.documentChanged(DocumentSnapshot("app.", 1), 4, '.')
        runCurrent()
        assertEquals(CompletionTrigger.character('.'), service.completeCalls.single().second)
        assertEquals(5, controller.completion.value?.items?.size)
        assertEquals(4, controller.completion.value?.anchor)
    }

    @Test
    fun `editing cancels in flight requests and stale results are dropped`() = runTest {
        val service = FakeService(completions = { doc, _, _ -> CompletionList(listOf(CompletionItem("v${doc.version}"))) }, latencyMillis = 500)
        val logger = RecordingLogger()
        val controller = controller(service, logger)
        controller.requestCompletion(DocumentSnapshot("v", 1), 1)
        advanceTimeBy(200)
        assertEquals(1, service.completeCalls.size)
        // A non-typing edit (e.g. paste) arrives while the request is still running.
        controller.documentChanged(DocumentSnapshot("v ", 2), 2)
        advanceUntilIdle()
        assertNull(controller.completion.value, "the result for version 1 must not be shown")
    }

    @Test
    fun `results for an older version are dropped when the request is not cancelled`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val logger = RecordingLogger()
        val service = object : LanguageService {
            override suspend fun diagnostics(doc: DocumentSnapshot): List<Diagnostic> {
                if (doc.version == 1L) gate.await()
                return listOf(Diagnostic(TextRange.at(TextPosition.Zero), "v${doc.version}"))
            }
        }
        val controller = controller(service, logger)
        controller.documentChanged(DocumentSnapshot("a", 1), 1)
        advanceTimeBy(301)
        runCurrent()
        // Version 2 arrives while version 1 is still being computed, then version 1 finishes.
        controller.documentChanged(DocumentSnapshot("ab", 2), 2)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, controller.diagnostics.value.version)
        assertEquals("v2", controller.diagnostics.value.value.single().message)
    }

    @Test
    fun `diagnostics are debounced to the last edit`() = runTest {
        val service = FakeService(diagnosticsFor = { doc -> listOf(Diagnostic(TextRange.at(TextPosition.Zero), "len ${doc.text.length}")) })
        val controller = controller(service)
        controller.type("", "abcd", 0)
        advanceTimeBy(299)
        assertTrue(service.diagnosticCalls.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf(4L), service.diagnosticCalls)
        assertEquals("len 4", controller.diagnostics.value.value.single().message)
    }

    @Test
    fun `a throwing service is logged and editing continues`() = runTest {
        val logger = RecordingLogger()
        val service = object : LanguageService {
            override suspend fun complete(doc: DocumentSnapshot, position: TextPosition, trigger: CompletionTrigger): CompletionList =
                error("boom")

            override suspend fun diagnostics(doc: DocumentSnapshot): List<Diagnostic> = throw IllegalStateException("bad")
        }
        val controller = controller(service, logger)
        controller.requestCompletion(DocumentSnapshot("x", 1), 1)
        advanceUntilIdle()
        assertTrue(logger.errors.any { "complete failed" in it })
        assertTrue(logger.errors.any { "diagnostics failed" in it } || controller.diagnostics.value.value.isEmpty())
        // An explicit request with a failure still shows the empty popup ("No suggestions").
        assertEquals(0, controller.completion.value?.items?.size)
        controller.documentChanged(DocumentSnapshot("xy", 2), 2, 'y')
        advanceUntilIdle()
        assertTrue(logger.errors.any { "diagnostics failed" in it })
    }

    @Test
    fun `a slow service times out without blocking other requests`() = runTest {
        val logger = RecordingLogger()
        val service = object : LanguageService {
            override suspend fun complete(doc: DocumentSnapshot, position: TextPosition, trigger: CompletionTrigger): CompletionList {
                delay(60_000)
                return CompletionList(listOf(CompletionItem("late")))
            }

            override suspend fun diagnostics(doc: DocumentSnapshot): List<Diagnostic> =
                listOf(Diagnostic(TextRange.at(TextPosition.Zero), "fast"))
        }
        val controller = controller(service, logger)
        controller.documentChanged(DocumentSnapshot("t", 1), 1, 't')
        advanceTimeBy(400)
        runCurrent()
        assertEquals("fast", controller.diagnostics.value.value.single().message, "diagnostics are not blocked by completion")
        advanceUntilIdle()
        assertTrue(logger.errors.any { "timed out" in it })
        assertNull(controller.completion.value)
    }

    @Test
    fun `moving the caret out of the prefix closes the popup`() = runTest {
        val service = FakeService(completions = { _, _, _ -> CompletionList(commands) })
        val controller = controller(service)
        val snapshot = controller.type("tap ", "s", 0)
        advanceUntilIdle()
        assertNotNull(controller.completion.value)
        controller.caretMoved(snapshot, 3)
        assertNull(controller.completion.value)
    }

    @Test
    fun `typing a non identifier character closes the popup`() = runTest {
        val service = FakeService(completions = { _, _, _ -> CompletionList(commands) })
        val controller = controller(service)
        val snapshot = controller.type("", "s", 0)
        advanceUntilIdle()
        controller.type(snapshot.text, " ", snapshot.version)
        assertNull(controller.completion.value)
    }

    @Test
    fun `selection moves wrap for single steps and clamp for pages`() = runTest {
        val service = FakeService(completions = { _, _, _ -> CompletionList(commands) })
        val controller = controller(service)
        controller.requestCompletion(DocumentSnapshot("", 1), 0)
        advanceUntilIdle()
        controller.moveSelection(-1)
        assertEquals(4, controller.completion.value?.selectedIndex)
        controller.moveSelection(10, wrap = false)
        assertEquals(4, controller.completion.value?.selectedIndex)
        controller.moveSelection(-10, wrap = false)
        assertEquals(0, controller.completion.value?.selectedIndex)
    }

    @Test
    fun `selected items are resolved for documentation`() = runTest {
        val service = FakeService(completions = { _, _, _ -> CompletionList(commands) })
        val controller = controller(service)
        controller.requestCompletion(DocumentSnapshot("", 1), 0)
        advanceUntilIdle()
        val selected = controller.completion.value?.selected
        assertEquals("docs for ${selected?.label}", selected?.documentation?.value)
    }

    @Test
    fun `hover waits for the delay and is cancelled by edits`() = runTest {
        val service = FakeService()
        val controller = controller(service)
        val snapshot = DocumentSnapshot("hello", 1)
        controller.documentChanged(snapshot, 0)
        controller.requestHover(snapshot, TextPosition(0, 2))
        advanceTimeBy(399)
        assertEquals(0, service.hoverCalls)
        advanceTimeBy(2)
        runCurrent()
        assertEquals("hover 2", controller.hover.value?.hover?.contents?.value)
        controller.requestHover(snapshot, TextPosition(0, 3))
        controller.documentChanged(DocumentSnapshot("hello!", 2), 6, '!')
        advanceUntilIdle()
        assertNull(controller.hover.value)
    }

    @Test
    fun `explicit requests show an empty popup and typing requests do not`() = runTest {
        val service = FakeService(completions = { _, _, _ -> CompletionList.Empty })
        val controller = controller(service)
        controller.type("", "q", 0)
        advanceUntilIdle()
        assertNull(controller.completion.value)
        controller.requestCompletion(DocumentSnapshot("q", 1), 1)
        advanceUntilIdle()
        assertEquals(true, controller.completion.value?.explicit)
    }
    @Test
    fun `moving the caret cancels a pending completion before a popup exists`() = runTest {
        val service = FakeService(completions = { _, _, _ -> CompletionList(commands) }, latencyMillis = 500)
        val controller = controller(service)
        val snapshot = DocumentSnapshot("s tap", 1)
        controller.requestCompletion(snapshot, 1)
        advanceTimeBy(100)
        controller.caretMoved(snapshot, 5)
        advanceUntilIdle()
        assertNull(controller.completion.value)
    }

    @Test
    fun `edits outside the completion prefix close the session`() = runTest {
        val service = FakeService(completions = { _, _, _ -> CompletionList(commands) })
        val controller = controller(service)
        controller.requestCompletion(DocumentSnapshot("s tail", 1), 1)
        advanceUntilIdle()
        assertNotNull(controller.completion.value)
        controller.documentChanged(DocumentSnapshot("s changed", 2), 1)
        assertNull(controller.completion.value)
    }

    @Test
    fun `moving the caret hides hover and cancels pending hover`() = runTest {
        val controller = controller(FakeService())
        val snapshot = DocumentSnapshot("hello", 1)
        controller.documentChanged(snapshot, 0)
        controller.requestHover(snapshot, TextPosition(0, 1))
        advanceUntilIdle()
        assertNotNull(controller.hover.value)
        controller.caretMoved(snapshot, 2)
        assertNull(controller.hover.value)
        controller.requestHover(snapshot, TextPosition(0, 3))
        controller.caretMoved(snapshot, 4)
        advanceUntilIdle()
        assertNull(controller.hover.value)
    }

    @Test
    fun `completion edits follow prefix growth and deletion without overwriting the suffix`() = runTest {
        val item = CompletionItem(
            "scroll",
            textEdit = TextEdit(TextRange(TextPosition.Zero, TextPosition(0, 2)), "scroll"),
            additionalTextEdits = listOf(TextEdit.insert(TextPosition(0, 7), "!")),
        )
        val controller = controller(FakeService(completions = { _, _, _ -> CompletionList(listOf(item)) }))
        controller.requestCompletion(DocumentSnapshot("sc tail", 1), 2)
        advanceUntilIdle()
        val grown = DocumentSnapshot("scr tail", 2)
        controller.documentChanged(grown, 3, 'r')
        val grownSession = assertNotNull(controller.completion.value)
        val grownState = CodeEditorState(grown.text)
        grownState.apply(completionAcceptOutcome(grown, item, grownSession, 3))
        assertEquals("scroll tail!", grownState.text)
        assertEquals(6, grownState.selectionEnd)
        assertTrue(grownState.undo())
        assertEquals(grown.text, grownState.text)

        val shrunk = DocumentSnapshot("s tail", 3)
        controller.documentChanged(shrunk, 1)
        val shrunkSession = assertNotNull(controller.completion.value)
        val shrunkState = CodeEditorState(shrunk.text)
        shrunkState.apply(completionAcceptOutcome(shrunk, item, shrunkSession, 1))
        assertEquals("scroll tail!", shrunkState.text)
    }

    @Test
    fun `switching services clears analysis even when the replacement service fails`() = runTest {
        val controller = controller(object : LanguageService {
            override suspend fun diagnostics(doc: DocumentSnapshot): List<Diagnostic> =
                listOf(Diagnostic(TextRange.at(TextPosition.Zero), "old service"))
            override suspend fun semanticTokens(doc: DocumentSnapshot): List<SemanticToken> =
                listOf(SemanticToken(0, 0, 1, TokenKind.KEYWORD))
            override suspend fun foldingRanges(doc: DocumentSnapshot): List<FoldingRange> = listOf(FoldingRange(0, 1))
        })
        controller.documentChanged(DocumentSnapshot("a\nb", 1), 1)
        advanceUntilIdle()
        assertTrue(controller.diagnostics.value.value.isNotEmpty())
        assertNotNull(controller.semanticTokens.value)
        assertNotNull(controller.foldingRanges.value)
        controller.service = object : LanguageService {
            override suspend fun diagnostics(doc: DocumentSnapshot): List<Diagnostic> = error("failed")
        }
        assertTrue(controller.diagnostics.value.value.isEmpty())
        assertNull(controller.semanticTokens.value)
        assertNull(controller.foldingRanges.value)
        advanceUntilIdle()
        assertTrue(controller.diagnostics.value.value.isEmpty())
    }

    @Test
    fun `session revision clears published results and refreshes analysis without editing`() = runTest {
        var context = "old"
        val controller = controller(object : LanguageService {
            override suspend fun complete(doc: DocumentSnapshot, position: TextPosition, trigger: CompletionTrigger): CompletionList =
                CompletionList(listOf(CompletionItem("value$context")))

            override suspend fun diagnostics(doc: DocumentSnapshot): List<Diagnostic> =
                listOf(Diagnostic(TextRange.at(TextPosition.Zero), context))

            override suspend fun semanticTokens(doc: DocumentSnapshot): List<SemanticToken> =
                listOf(SemanticToken(0, 0, 1, TokenKind.KEYWORD))

            override suspend fun foldingRanges(doc: DocumentSnapshot): List<FoldingRange> = listOf(FoldingRange(0, 1))

            override suspend fun hover(doc: DocumentSnapshot, position: TextPosition): Hover = Hover(MarkupContent.plain(context))
        })
        val snapshot = DocumentSnapshot("v\nx", 1)
        controller.documentChanged(snapshot, 1, 'v')
        controller.requestHover(snapshot, TextPosition(0, 1))
        advanceUntilIdle()
        assertNotNull(controller.completion.value)
        assertNotNull(controller.hover.value)
        assertNotNull(controller.semanticTokens.value)
        assertNotNull(controller.foldingRanges.value)
        assertEquals("old", controller.diagnostics.value.value.single().message)

        context = "new"
        controller.serviceRevision++
        assertNull(controller.completion.value)
        assertNull(controller.hover.value)
        assertNull(controller.semanticTokens.value)
        assertNull(controller.foldingRanges.value)
        assertTrue(controller.diagnostics.value.value.isEmpty())
        advanceUntilIdle()
        assertNull(controller.completion.value)
        assertEquals("new", controller.diagnostics.value.value.single().message)
        controller.requestCompletion(snapshot, 1)
        advanceUntilIdle()
        assertEquals("valuenew", controller.completion.value?.selected?.label)
        controller.serviceRevision = controller.serviceRevision
        assertNotNull(controller.completion.value)
    }

    @Test
    fun `session revision cancels pending completion and allows a fresh request at the same version`() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        var context = "old"
        val controller = controller(object : LanguageService {
            override suspend fun complete(doc: DocumentSnapshot, position: TextPosition, trigger: CompletionTrigger): CompletionList {
                val captured = context
                try {
                    delay(500)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    cancelled.complete(Unit)
                    throw e
                }
                return CompletionList(listOf(CompletionItem("value$captured")))
            }
        })
        val snapshot = DocumentSnapshot("v", 1)
        controller.requestCompletion(snapshot, 1)
        runCurrent()
        context = "new"
        controller.serviceRevision++
        runCurrent()
        assertTrue(cancelled.isCompleted)
        advanceUntilIdle()
        assertNull(controller.completion.value)
        controller.requestCompletion(snapshot, 1)
        advanceUntilIdle()
        assertEquals("valuenew", controller.completion.value?.selected?.label)
    }

    @Test
    fun `session revision cancels pending resolve hover and analysis for unchanged text`() = runTest {
        val started = mutableSetOf<String>()
        val cancelled = mutableSetOf<String>()
        var context = "old"
        suspend fun response(kind: String): String {
            val captured = context
            started += kind
            try {
                delay(1_000)
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled += kind
                throw e
            }
            return captured
        }
        val controller = controller(object : LanguageService {
            override suspend fun complete(doc: DocumentSnapshot, position: TextPosition, trigger: CompletionTrigger): CompletionList =
                CompletionList(listOf(CompletionItem("value")))

            override suspend fun resolveCompletionItem(item: CompletionItem): CompletionItem =
                item.copy(documentation = MarkupContent.plain(response("resolve")))

            override suspend fun hover(doc: DocumentSnapshot, position: TextPosition): Hover = Hover(MarkupContent.plain(response("hover")))

            override suspend fun diagnostics(doc: DocumentSnapshot): List<Diagnostic> =
                listOf(Diagnostic(TextRange.at(TextPosition.Zero), response("diagnostics")))

            override suspend fun semanticTokens(doc: DocumentSnapshot): List<SemanticToken> {
                response("tokens")
                return listOf(SemanticToken(0, 0, 1, TokenKind.KEYWORD))
            }

            override suspend fun foldingRanges(doc: DocumentSnapshot): List<FoldingRange> {
                response("folds")
                return listOf(FoldingRange(0, 1))
            }
        })
        val snapshot = DocumentSnapshot("v\nx", 1)
        controller.documentChanged(snapshot, 1)
        controller.requestCompletion(snapshot, 1)
        controller.requestHover(snapshot, TextPosition(0, 1))
        advanceTimeBy(500)
        runCurrent()
        assertEquals(setOf("resolve", "hover", "diagnostics", "tokens", "folds"), started)
        context = "new"
        controller.serviceRevision++
        runCurrent()
        assertEquals(started, cancelled)
        advanceUntilIdle()
        assertNull(controller.completion.value)
        assertNull(controller.hover.value)
        assertEquals("new", controller.diagnostics.value.value.single().message)
        controller.requestCompletion(snapshot, 1)
        advanceUntilIdle()
        assertEquals("new", controller.completion.value?.selected?.documentation?.value)
    }

    @Test
    fun `an additional insertion at the prefix start stays an insertion after typing`() = runTest {
        val item = CompletionItem("scroll", additionalTextEdits = listOf(TextEdit.insert(TextPosition.Zero, "import x\n")))
        val controller = controller(FakeService(completions = { _, _, _ -> CompletionList(listOf(item)) }))
        controller.requestCompletion(DocumentSnapshot("", 1), 0)
        advanceUntilIdle()
        val snapshot = DocumentSnapshot("sc", 2)
        controller.documentChanged(snapshot, 2, 'c')
        val session = assertNotNull(controller.completion.value)
        val state = CodeEditorState(snapshot.text)
        state.apply(completionAcceptOutcome(snapshot, item, session, 2))
        assertEquals("import x\nscroll", state.text)
        assertEquals(state.text.length, state.selectionEnd)
    }

}
