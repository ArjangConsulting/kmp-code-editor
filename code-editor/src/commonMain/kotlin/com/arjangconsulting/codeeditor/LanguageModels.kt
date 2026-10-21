package com.arjangconsulting.codeeditor

import androidx.compose.runtime.Immutable

/** How [MarkupContent.value] should be rendered, mirroring LSP's `MarkupKind`. */
public enum class MarkupKind {
    /** Rendered verbatim. */
    PLAIN_TEXT,

    /**
     * Markdown. The built-in engines render a safe subset (paragraphs, inline code, fenced code, bold,
     * and italics) and show anything else as plain text; no HTML or links are interpreted.
     */
    MARKDOWN,
}

/** Documentation or hover text, mirroring LSP's `MarkupContent`. */
@Immutable
public data class MarkupContent(val value: String, val kind: MarkupKind = MarkupKind.PLAIN_TEXT) {
    public companion object {
        /** Plain text content. */
        public fun plain(value: String): MarkupContent = MarkupContent(value, MarkupKind.PLAIN_TEXT)

        /** Markdown content. */
        public fun markdown(value: String): MarkupContent = MarkupContent(value, MarkupKind.MARKDOWN)
    }
}

/** The kind of a completion item, mirroring LSP's `CompletionItemKind`. Drives the popup's badge. */
public enum class CompletionItemKind {
    TEXT,
    METHOD,
    FUNCTION,
    CONSTRUCTOR,
    FIELD,
    VARIABLE,
    CLASS,
    INTERFACE,
    MODULE,
    PROPERTY,
    UNIT,
    VALUE,
    ENUM,
    KEYWORD,
    SNIPPET,
    COLOR,
    FILE,
    REFERENCE,
    FOLDER,
    ENUM_MEMBER,
    CONSTANT,
    STRUCT,
    EVENT,
    OPERATOR,
    TYPE_PARAMETER,
}

/** How [CompletionItem.insertText] or the [CompletionItem.textEdit]'s text is interpreted. */
public enum class InsertTextFormat {
    /** Inserted verbatim. */
    PLAIN_TEXT,

    /**
     * An LSP snippet. `$1`, `${1:placeholder}`, `$0`, and `\$` escapes are supported: the placeholder
     * text is inserted and the caret selects the first tab stop (or lands on `$0`). Choice and variable
     * syntax is inserted literally, and there is no tab-stop navigation after the first one.
     */
    SNIPPET,
}

/**
 * One completion proposal, mirroring LSP's `CompletionItem`.
 *
 * When accepted, [textEdit] is applied if present; otherwise [insertText] (or [label]) replaces the
 * identifier prefix before the caret. [additionalTextEdits] (for example an import) are applied in
 * the same undo step and must not overlap the main edit.
 *
 * Client-side filtering matches against [filterText] (or [label]); ties are ordered by [sortText]
 * (or [label]). [id] is an opaque value a service can use to resolve the item later in
 * [LanguageService.resolveCompletionItem]; the editor never interprets it.
 */
@Immutable
public data class CompletionItem(
    val label: String,
    val kind: CompletionItemKind? = null,
    val detail: String? = null,
    val documentation: MarkupContent? = null,
    val insertText: String? = null,
    val textEdit: TextEdit? = null,
    val filterText: String? = null,
    val sortText: String? = null,
    val insertTextFormat: InsertTextFormat = InsertTextFormat.PLAIN_TEXT,
    val additionalTextEdits: List<TextEdit> = emptyList(),
    val preselect: Boolean = false,
    val commitCharacters: Set<Char> = emptySet(),
    val id: String? = null,
)

/**
 * A completion response, mirroring LSP's `CompletionList`.
 *
 * When [isIncomplete] is false the editor filters [items] locally as the user keeps typing. When it
 * is true the editor asks the service again (debounced) on every edit while the popup is open.
 */
@Immutable
public data class CompletionList(val items: List<CompletionItem>, val isIncomplete: Boolean = false) {
    public companion object {
        /** No proposals. */
        public val Empty: CompletionList = CompletionList(emptyList())
    }
}

/** Why completion was requested, mirroring LSP's `CompletionTriggerKind`. */
public enum class CompletionTriggerKind {
    /** Typing an identifier or an explicit request (Ctrl+Space). */
    INVOKED,

    /** One of [LanguageService.triggerCharacters] was typed. */
    TRIGGER_CHARACTER,

    /** Re-query because the previous [CompletionList.isIncomplete] was true. */
    TRIGGER_FOR_INCOMPLETE_COMPLETIONS,
}

/** The context of a completion request, mirroring LSP's `CompletionContext`. */
@Immutable
public data class CompletionTrigger(val kind: CompletionTriggerKind, val character: Char? = null) {
    public companion object {
        /** An explicit or typing-driven request. */
        public val Invoked: CompletionTrigger = CompletionTrigger(CompletionTriggerKind.INVOKED)

        /** A re-query of an incomplete list. */
        public val Incomplete: CompletionTrigger = CompletionTrigger(CompletionTriggerKind.TRIGGER_FOR_INCOMPLETE_COMPLETIONS)

        /** A request caused by typing [character]. */
        public fun character(character: Char): CompletionTrigger =
            CompletionTrigger(CompletionTriggerKind.TRIGGER_CHARACTER, character)
    }
}

/** Severity of a [Diagnostic], mirroring LSP's `DiagnosticSeverity`. */
public enum class DiagnosticSeverity {
    ERROR,
    WARNING,
    INFORMATION,
    HINT,
}

/**
 * A problem in the document, mirroring LSP's `Diagnostic`. Shown as a squiggle under [range], a
 * gutter marker, and a tooltip with [message] (prefixed by [source] and [code] when present).
 */
@Immutable
public data class Diagnostic(
    val range: TextRange,
    val message: String,
    val severity: DiagnosticSeverity = DiagnosticSeverity.ERROR,
    val source: String? = null,
    val code: String? = null,
)

/** Hover information, mirroring LSP's `Hover`. [range] is the span the hover describes, if known. */
@Immutable
public data class Hover(val contents: MarkupContent, val range: TextRange? = null)

/** What a [LineHighlight] means, which picks its color from [CodeEditorColors]. */
public enum class LineHighlightRole {
    /** The current item, e.g. the executing step in a generated test. */
    SELECTED,

    /** A softer emphasis, e.g. the step under the pointer in a linked list. */
    HOVER,
    WARNING,
    ERROR,
    INFO,
}

/** A full-width background highlight for 0-based [lines], drawn behind the text. */
@Immutable
public data class LineHighlight(val lines: IntRange, val role: LineHighlightRole = LineHighlightRole.SELECTED) {
    public constructor(line: Int, role: LineHighlightRole = LineHighlightRole.SELECTED) : this(line..line, role)
}

/**
 * A semantic token from a [LanguageService], drawn over the lexer's token for the same span. This is
 * a decoded form of LSP's semantic tokens: map your legend to [TokenKind] in the adapter.
 */
@Immutable
public data class SemanticToken(val line: Int, val startCharacter: Int, val length: Int, val kind: TokenKind)

/** The kind of a folding region, mirroring LSP's `FoldingRangeKind`. */
public enum class FoldingRangeKind {
    COMMENT,
    IMPORTS,
    REGION,
}

/**
 * A foldable span of lines, mirroring LSP's `FoldingRange`. Folding hides lines
 * `startLine + 1..endLine` and keeps [startLine] visible with a fold marker.
 */
@Immutable
public data class FoldingRange(val startLine: Int, val endLine: Int, val kind: FoldingRangeKind = FoldingRangeKind.REGION) {
    init {
        require(startLine >= 0) { "startLine must be nonnegative" }
        require(endLine > startLine) { "A folding range must span at least two lines" }
    }
}
