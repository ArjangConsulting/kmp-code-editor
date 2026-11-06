package com.arjangconsulting.codeeditor

/**
 * True when every edit of [item] names positions that exist in [doc] exactly, without the LSP
 * clamping of out-of-range lines or characters. Used for untrusted wire payloads; the controller
 * accepts clamped positions from direct services.
 */
internal fun hasInDocumentPositions(item: CompletionItem, doc: DocumentSnapshot): Boolean {
    val edits = listOfNotNull(item.textEdit) + item.additionalTextEdits
    return edits.all { edit ->
        doc.positionOf(doc.offsetOf(edit.range.start)) == edit.range.start &&
            doc.positionOf(doc.offsetOf(edit.range.end)) == edit.range.end
    }
}

internal fun hasNonOverlappingReplacements(replacements: List<Replacement>): Boolean {
    val ordered = replacements.sortedWith(compareBy({ it.start }, { it.end }))
    return ordered.zipWithNext().all { (before, after) -> before.end <= after.start }
}
