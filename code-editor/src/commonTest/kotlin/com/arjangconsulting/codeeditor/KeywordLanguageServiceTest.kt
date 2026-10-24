package com.arjangconsulting.codeeditor

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeywordLanguageServiceTest {
    private suspend fun complete(rules: TokenRules, text: String, caret: Int = text.length): CompletionList {
        val snapshot = DocumentSnapshot(text, 1)
        return KeywordLanguageService(rules).complete(snapshot, snapshot.positionOf(caret), CompletionTrigger.Invoked)
    }

    @Test
    fun `presets propose the vocabulary used for highlighting`() = runTest {
        for ((rules, keyword) in listOf(KotlinRules to "val", SwiftRules to "let", ShellRules to "if")) {
            val items = complete(rules, keyword.take(1)).items
            assertEquals(CompletionItemKind.KEYWORD, items.single { it.label == keyword }.kind)
            assertEquals(TokenKind.KEYWORD, rules.tokenizeLine(keyword).kindAt(0))
            assertTrue(items.size > 1)
        }
    }

    @Test
    fun `custom rules share keywords constants types and functions with completion`() = runTest {
        val rules = tokenRules("commands") {
            keywords("tap")
            constants("true")
            types("Device")
            keywords("launch", kind = TokenKind.FUNCTION)
        }
        val items = complete(rules, "").items.associate { it.label to it.kind }
        assertEquals(
            mapOf(
                "tap" to CompletionItemKind.KEYWORD,
                "true" to CompletionItemKind.CONSTANT,
                "Device" to CompletionItemKind.CLASS,
                "launch" to CompletionItemKind.FUNCTION,
            ),
            items,
        )
        assertEquals(CompletionList.Empty, complete(PlainText, ""))
    }

    @Test
    fun `comments strings escapes and multiline continuations suppress suggestions`() = runTest {
        for (text in listOf("// va", "/* va", "/* first\n va", "\"va", "\"va\\", "\"\"\"first\nva")) {
            assertEquals(CompletionList.Empty, complete(KotlinRules, text), text)
        }
        assertEquals(CompletionList.Empty, complete(SwiftRules, "// le"))
        assertEquals(CompletionList.Empty, complete(ShellRules, "# ec"))
        assertEquals(CompletionList.Empty, complete(ShellRules, "'ec"))
        assertEquals(CompletionList.Empty, complete(KotlinRules, "\"value\"", 3))
    }

    @Test
    fun `closed constructs and new lines restore code completion`() = runTest {
        for (text in listOf("\"value\"", "/* value */", "// value\n", "/* first\r\nsecond */\r\nva")) {
            assertTrue(complete(KotlinRules, text).items.any { it.label == "val" }, text)
        }
    }

    @Test
    fun `member access suppresses keywords including while a member prefix grows`() = runTest {
        for (text in listOf("device.", "device.va", "device?.va", "device. va")) {
            assertEquals(CompletionList.Empty, complete(KotlinRules, text), text)
        }
        assertTrue(complete(KotlinRules, "device.va", 0).items.isNotEmpty())
    }

    @Test
    fun `full vocabulary supports local filtering as the prefix changes`() = runTest {
        val list = complete(KotlinRules, "v")
        assertEquals(complete(KotlinRules, "va").items, list.items)
        assertTrue(filterAndRankCompletions(list.items, "va").any { it.item.label == "val" })
        assertEquals(CompletionItemKind.CONSTANT, list.items.single { it.label == "true" }.kind)
        assertEquals(CompletionItemKind.CLASS, list.items.single { it.label == "String" }.kind)
    }
}
