package com.arjangconsulting.codeeditor

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Indentation preferences matching LSP's `FormattingOptions`.
 *
 * @property tabSize Number of columns in an indentation level; must be positive.
 * @property insertSpaces Use spaces for indentation, or one tab per level when false.
 */
@Immutable
public data class FormattingOptions(val tabSize: Int = 4, val insertSpaces: Boolean = true) {
    init {
        require(tabSize > 0) { "tabSize must be positive" }
    }
}

/** Outcome of an explicitly requested [CodeEditorState.format] operation. */
public enum class FormatResult {
    /** Edits were applied as one undoable change. */
    APPLIED,

    /** The document was already formatted. */
    UNCHANGED,

    /** The service cannot format this document, including invalid input. */
    UNAVAILABLE,

    /** The document changed during the request; no edits were applied. */
    STALE,

    /** The provider failed, timed out, or returned invalid edits; no edits were applied. */
    FAILED,
}

/**
 * Requests formatting for an explicit Format or Save action. The provider runs on
 * [LanguageServiceOptions.dispatcher], with its configured timeout. Edits are applied only if
 * [version] still matches the requested snapshot, as one undo step with selection mapping.
 *
 * Call from the thread that owns this state, normally the UI thread. Cancellation propagates;
 * provider failures are logged and returned as [FormatResult.FAILED]. This does not save anything.
 * A host formatting before Save should persist [text] after [FormatResult.APPLIED] or
 * [FormatResult.UNCHANGED], and decide whether to retry or report other outcomes.
 */
public suspend fun CodeEditorState.format(
    service: LanguageService,
    options: FormattingOptions = FormattingOptions(),
    serviceOptions: LanguageServiceOptions = LanguageServiceOptions(),
    logger: CodeEditorLogger = CodeEditorLogger.None,
): FormatResult {
    val requested = snapshot()
    val edits = try {
        withTimeout(serviceOptions.requestTimeoutMillis) {
            withContext(serviceOptions.dispatcher ?: Dispatchers.Default) {
                service.format(requested, options)?.toList()
            }
        }
    } catch (failure: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        logger.error("LanguageService.format timed out after ${serviceOptions.requestTimeoutMillis} ms", failure)
        return FormatResult.FAILED
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        logger.error("LanguageService.format failed: ${failure::class.simpleName}", failure)
        return FormatResult.FAILED
    }
    if (version != requested.version) return FormatResult.STALE
    if (edits == null) return FormatResult.UNAVAILABLE
    val formatted = try {
        applyTextEdits(requested.text, edits)
    } catch (failure: IllegalArgumentException) {
        logger.error("LanguageService.format returned invalid edits", failure)
        return FormatResult.FAILED
    }
    if (formatted == requested.text) return FormatResult.UNCHANGED
    edit(edits)
    return FormatResult.APPLIED
}
