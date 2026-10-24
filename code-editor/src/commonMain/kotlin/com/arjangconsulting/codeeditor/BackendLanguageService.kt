package com.arjangconsulting.codeeditor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.uuid.Uuid

/**
 * Completion provider implemented by a tool, an external add-on, or a host transport adapter.
 *
 * The implementation may call a native endpoint, inspect a schema/catalog, or bridge existing APIs.
 * It must cooperate with coroutine cancellation and return the correlation fields from [request].
 * Suggestions must not execute user commands. The host owns transport and process lifecycle.
 */
public fun interface CompletionBackend {
    /** Returns a correlated result, or null when this provider is unavailable. */
    public suspend fun complete(request: CompletionRequest): CompletionResponse?
}

/**
 * Adapts any [CompletionBackend] to both editor engines, including single-line REPL prompts.
 *
 * Captures [context] for the lifetime of this service. Replace the service when runtime context
 * changes; service replacement invalidates outstanding editor requests. Hosts retaining a mutable
 * provider must also update `CodeEditor.languageServiceRevision` when that provider's data changes.
 *
 * Backend results take precedence, including an explicitly empty list. Null results, exceptions,
 * and mismatched response identities use [fallback]. Invalid edit candidates are discarded.
 * Cancellation always propagates. Optional
 * fallback completion runs only when needed; other language-service methods keep their defaults.
 * Providers should include documentation in their items; lazy backend item resolution is not part
 * of protocol version 1. Transport timeouts use the editor's [LanguageServiceOptions].
 *
 * @param backend Native, in-process, or external completion implementation.
 * @param languageId Language or command dialect understood by the backend.
 * @param context Immutable runtime identity and routing information for this service.
 * @param triggerCharacters Characters that request suggestions immediately.
 * @param fallback Optional completion provider used if the backend cannot supply a result.
 * @param logger Receives backend failure categories without document or exception contents.
 */
public class BackendLanguageService(
    private val backend: CompletionBackend,
    private val languageId: String,
    context: CompletionContext,
    triggerCharacters: Set<Char> = setOf(' ', '=', '.', '"', '\'', '-', '/', ':'),
    private val fallback: LanguageService = LanguageService.None,
    private val logger: CodeEditorLogger = CodeEditorLogger.None,
) : LanguageService {
    private val context = context.copy(attributes = context.attributes.toMap())

    /** Backend trigger characters together with any additional fallback triggers. */
    override val triggerCharacters: Set<Char> = triggerCharacters.toSet() + fallback.triggerCharacters

    init {
        require(languageId.isNotBlank()) { "languageId must not be blank" }
    }

    /** Requests backend suggestions and drops responses for another request, document, or context. */
    override suspend fun complete(
        doc: DocumentSnapshot,
        position: TextPosition,
        trigger: CompletionTrigger,
    ): CompletionList? {
        currentCoroutineContext().ensureActive()
        val request = CompletionRequest(
            requestId = Uuid.random().toString(),
            context = context,
            languageId = languageId,
            text = doc.text,
            documentVersion = doc.version,
            position = doc.positionOf(doc.offsetOf(position)),
            trigger = trigger,
        )
        val response = try {
            backend.complete(request)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            logger.error("CompletionBackend.complete failed: ${failure::class.simpleName}")
            null
        }
        currentCoroutineContext().ensureActive()
        if (response != null) {
            val matches = response.protocolVersion == CompletionProtocol.VERSION &&
                response.requestId == request.requestId && response.sessionId == context.sessionId &&
                response.contextRevision == context.revision && response.documentVersion == doc.version
            if (matches && response.completions != null) {
                val list = response.completions
                val items = list.items.filter { validEdits(it, doc) }
                if (items.size != list.items.size) logger.debug("Discarded completion candidates with invalid edits")
                return list.copy(items = items)
            }
            if (!matches) logger.debug("Discarded mismatched completion backend response")
        }
        return fallback.complete(doc, position, trigger)
    }
}

private fun validEdits(item: CompletionItem, doc: DocumentSnapshot): Boolean {
    val edits = listOfNotNull(item.textEdit) + item.additionalTextEdits
    if (edits.any { edit ->
        doc.positionOf(doc.offsetOf(edit.range.start)) != edit.range.start ||
            doc.positionOf(doc.offsetOf(edit.range.end)) != edit.range.end
    }) return false
    val ordered = edits.sortedWith(compareBy({ it.range.start }, { it.range.end }))
    return ordered.zipWithNext().all { (before, after) -> before.range.end <= after.range.start }
}
