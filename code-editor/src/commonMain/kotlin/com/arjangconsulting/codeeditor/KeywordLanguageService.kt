package com.arjangconsulting.codeeditor

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Local completion from [language]'s highlighting vocabulary: keywords, constants, and known types.
 *
 * Returns the full vocabulary for the editor to filter as the prefix grows. Ordinary suggestions
 * are suppressed inside strings, escapes, and comments, and after a member-access dot. This is
 * lexical completion, not scope or type analysis; runtime members belong in a host [LanguageService].
 * Highlighting is configured separately by passing the same [language] to [CodeEditor].
 *
 * Instances are safe to share between editors and do not require a compiler or language server.
 */
public class KeywordLanguageService(private val language: TokenRules) : LanguageService {
    private val items = language.keywordKinds.map { (word, kind) ->
        CompletionItem(
            label = word,
            kind = when (kind) {
                TokenKind.KEYWORD -> CompletionItemKind.KEYWORD
                TokenKind.CONSTANT -> CompletionItemKind.CONSTANT
                TokenKind.TYPE -> CompletionItemKind.CLASS
                TokenKind.FUNCTION -> CompletionItemKind.FUNCTION
                TokenKind.VARIABLE -> CompletionItemKind.VARIABLE
                TokenKind.PROPERTY, TokenKind.ATTRIBUTE -> CompletionItemKind.PROPERTY
                else -> CompletionItemKind.TEXT
            },
        )
    }

    /** Proposes the configured vocabulary at code positions, leaving prefix filtering to the editor. */
    override suspend fun complete(doc: DocumentSnapshot, position: TextPosition, trigger: CompletionTrigger): CompletionList {
        if (items.isEmpty()) return CompletionList.Empty
        val caret = doc.offsetOf(position)
        val prefixStart = completionPrefixStart(doc.text, caret, language)
        var previous = prefixStart - 1
        while (previous >= 0 && doc.text[previous].isWhitespace()) previous--
        if (doc.text.getOrNull(previous) == '.') return CompletionList.Empty
        val actualPosition = doc.positionOf(caret)
        var state = LexerState.Initial
        for (line in 0 until actualPosition.line) {
            currentCoroutineContext().ensureActive()
            state = language.tokenizeLine(doc.line(line), state).endState
        }
        val beforeCaret = doc.line(actualPosition.line).take(actualPosition.character)
        val context = language.tokenizeLine(beforeCaret + "a", state).kindAt(beforeCaret.length)
        if (context == TokenKind.STRING || context == TokenKind.COMMENT || context == TokenKind.ESCAPE) {
            return CompletionList.Empty
        }
        return CompletionList(items)
    }
}
