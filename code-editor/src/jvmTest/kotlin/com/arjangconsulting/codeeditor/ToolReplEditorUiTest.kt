package com.arjangconsulting.codeeditor

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ToolReplEditorUiTest {
    @Test
    fun `external schema suggestions follow continued typing and undo as one edit`() = runComposeUiTest {
        val catalog = ToolCommandCatalog(listOf(ToolCommand(
            name = "tap_element",
            parameters = listOf(ToolParameter("id", values = listOf("sign-in")), ToolParameter("scope")),
        )))
        val rules = catalog.languageRules()
        val backend = ToolCompletionBackend(catalog)
        val service = BackendLanguageService(
            CompletionBackend { request ->
                val decoded = CompletionProtocol.decodeRequest(CompletionProtocol.encodeRequest(request))
                CompletionProtocol.decodeResponse(CompletionProtocol.encodeResponse(backend.complete(decoded)))
            },
            rules.name,
            CompletionContext("external-session"),
        )
        val original = "tap_element id=\"sBROKEN\" scope=app"
        val state = CodeEditorState(original)
        state.select(original.indexOf("BROKEN"))
        var submitted: String? = null
        setContent {
            MaterialTheme {
                CodeEditor(
                    state,
                    Modifier.size(600.dp, 80.dp),
                    language = rules,
                    languageService = service,
                    singleLine = true,
                    serviceOptions = LanguageServiceOptions(completionDebounceMillis = 0),
                    onSubmit = { submitted = it },
                )
            }
        }
        val input = onNodeWithTag(CODE_EDITOR_INPUT_TAG)
        input.requestFocus()
        input.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) } }
        waitUntil(timeoutMillis = 3_000) {
            onAllNodesWithText("sign-in").fetchSemanticsNodes().isNotEmpty()
        }
        input.performTextInput("i")
        waitForIdle()
        val beforeAcceptance = state.text
        assertEquals("tap_element id=\"siBROKEN\" scope=app", beforeAcceptance)
        input.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals("tap_element id=sign-in scope=app", state.text)
        assertNull(submitted)
        assertTrue(state.undo())
        assertEquals(beforeAcceptance, state.text)
        assertTrue(state.redo())
        waitForIdle()
        input.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(state.text, submitted)
    }
}
