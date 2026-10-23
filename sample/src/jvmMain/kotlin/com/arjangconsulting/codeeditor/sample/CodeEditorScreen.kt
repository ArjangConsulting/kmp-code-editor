package com.arjangconsulting.codeeditor.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.arjangconsulting.codeeditor.CodeEditor
import com.arjangconsulting.codeeditor.CodeEditorColors
import com.arjangconsulting.codeeditor.CodeEditorDefaults
import com.arjangconsulting.codeeditor.CodeEditorEngine
import com.arjangconsulting.codeeditor.CodeEditorOptions
import com.arjangconsulting.codeeditor.CodeSnippet
import com.arjangconsulting.codeeditor.FormatResult
import com.arjangconsulting.codeeditor.FormattingOptions
import com.arjangconsulting.codeeditor.JsonRules
import com.arjangconsulting.codeeditor.KotlinRules
import com.arjangconsulting.codeeditor.LanguageService
import com.arjangconsulting.codeeditor.LineHighlight
import com.arjangconsulting.codeeditor.LineHighlightRole
import com.arjangconsulting.codeeditor.PlainText
import com.arjangconsulting.codeeditor.ShellRules
import com.arjangconsulting.codeeditor.SwiftRules
import com.arjangconsulting.codeeditor.TabBehavior
import com.arjangconsulting.codeeditor.TextPosition
import com.arjangconsulting.codeeditor.TokenRules
import com.arjangconsulting.codeeditor.format
import com.arjangconsulting.codeeditor.rememberCodeEditorState
import kotlinx.coroutines.launch

private enum class DemoLanguage(val label: String, val rules: TokenRules, val sample: String) {
    Commands("Commands", CommandRules, COMMANDS_SAMPLE),
    Json("JSON", JsonRules, JSON_SAMPLE),
    Kotlin("Kotlin", KotlinRules, KOTLIN_SAMPLE),
    Swift("Swift", SwiftRules, SWIFT_TEST_SAMPLE),
    Shell("Shell", ShellRules, SHELL_SAMPLE),
    Plain("Plain text", PlainText, "Plain text has no highlighting.\nBrackets (still) match."),
}

/** Every code editor feature: language and engine pickers, option toggles, demo services, and overlay checks. */
@Composable
fun CodeEditorScreen() {
    var language by remember { mutableStateOf(DemoLanguage.Commands) }
    var engine by remember { mutableStateOf(CodeEditorEngine.COMPOSE) }
    var readOnly by remember { mutableStateOf(false) }
    var showLineNumbers by remember { mutableStateOf(true) }
    var folding by remember { mutableStateOf(true) }
    var autoIndent by remember { mutableStateOf(true) }
    var autoClose by remember { mutableStateOf(true) }
    var currentLine by remember { mutableStateOf(true) }
    var matchBrackets by remember { mutableStateOf(true) }
    var completionOnTyping by remember { mutableStateOf(true) }
    var hover by remember { mutableStateOf(true) }
    var tabMovesFocus by remember { mutableStateOf(false) }
    var latency by remember { mutableStateOf(250f) }
    var caret by remember { mutableStateOf(TextPosition.Zero) }
    var syntaxPreset by remember { mutableStateOf(SyntaxPreset.DEFAULT) }
    var syntaxTheme by remember { mutableStateOf(SyntaxPreset.DEFAULT.theme) }
    val colors = CodeEditorDefaults.colors(syntaxTheme)

    val commandService = remember { CommandLanguageService { latency.toLong() } }
    val jsonService = remember { JsonSchemaLanguageService { latency.toLong() } }
    val service = when (language) {
        DemoLanguage.Commands -> commandService
        DemoLanguage.Json -> jsonService
        else -> LanguageService.None
    }
    val options = CodeEditorOptions(
        autoIndent = autoIndent,
        autoCloseBrackets = autoClose,
        highlightCurrentLine = currentLine,
        matchBrackets = matchBrackets,
        completionOnTyping = completionOnTyping,
        hover = hover,
    )
    val spacing = SampleSpacing

    Row(Modifier.fillMaxSize().padding(spacing.large), horizontalArrangement = Arrangement.spacedBy(spacing.large)) {
        Column(
            Modifier.width(spacing.large * 18).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            SectionCard(title = "Language") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                    DemoLanguage.entries.forEach {
                        FilterChip(selected = language == it, onClick = { language = it }, label = { Text(it.label) })
                    }
                }
            }
            SectionCard(title = "Engine", description = "RSyntaxTextArea is desktop-only and heavyweight; see the overlay check.") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(CodeEditorEngine.COMPOSE, CodeEditorEngine.RSYNTAX_TEXT_AREA).forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = engine == option,
                            onClick = { engine = option },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) { Text(if (option == CodeEditorEngine.COMPOSE) "Compose" else "RSTA") }
                    }
                }
            }
            SectionCard(title = "Options") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                    Toggle("Read-only", readOnly) { readOnly = it }
                    Toggle("Line numbers", showLineNumbers) { showLineNumbers = it }
                    Toggle("Folding", folding) { folding = it }
                    Toggle("Auto-indent", autoIndent) { autoIndent = it }
                    Toggle("Auto-close", autoClose) { autoClose = it }
                    Toggle("Current line", currentLine) { currentLine = it }
                    Toggle("Match brackets", matchBrackets) { matchBrackets = it }
                    Toggle("Complete on typing", completionOnTyping) { completionOnTyping = it }
                    Toggle("Hover", hover) { hover = it }
                    Toggle("Tab moves focus", tabMovesFocus) { tabMovesFocus = it }
                }
                Text("Simulated service latency: ${latency.toInt()} ms", style = MaterialTheme.typography.bodySmall)
                Slider(value = latency, onValueChange = { latency = it }, valueRange = 0f..2_000f)
            }
            SyntaxColorsEditor(
                preset = syntaxPreset,
                theme = syntaxTheme,
                colors = colors,
                onPresetChange = {
                    syntaxPreset = it
                    syntaxTheme = it.theme
                },
                onThemeChange = { syntaxTheme = it },
            )
            OverlayCheck()
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
            Text("${language.label} editor", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            // Keyed by language so switching starts from that language's sample with fresh history.
            key(language) {
                val keyedState = rememberCodeEditorState(language.sample)
                val actionScope = rememberCoroutineScope()
                var busy by remember { mutableStateOf(false) }
                var formatOnSave by remember { mutableStateOf(true) }
                var actionMessage by remember { mutableStateOf("") }
                var savedText by remember { mutableStateOf<String?>(null) }
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                    Button(enabled = language == DemoLanguage.Json && !readOnly && !busy, onClick = {
                        actionScope.launch {
                            busy = true
                            try {
                                actionMessage = keyedState.format(service, FormattingOptions(tabSize = 2)).message()
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text("Format") }
                    OutlinedButton(enabled = language == DemoLanguage.Json && !busy, onClick = {
                        actionScope.launch {
                            busy = true
                            try {
                                val requested = keyedState.snapshot()
                                val problems = service.diagnostics(requested)
                                actionMessage = if (keyedState.version != requested.version) {
                                    "Text changed; validate again"
                                } else {
                                    problems.firstOrNull()?.message ?: "Valid JSON"
                                }
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text("Validate") }
                    OutlinedButton(enabled = !readOnly && !busy, onClick = {
                        actionScope.launch {
                            busy = true
                            try {
                                val result = if (language == DemoLanguage.Json && formatOnSave) {
                                    keyedState.format(service, FormattingOptions(tabSize = 2))
                                } else {
                                    FormatResult.UNCHANGED
                                }
                                if (result == FormatResult.APPLIED || result == FormatResult.UNCHANGED) {
                                    savedText = keyedState.text
                                    actionMessage = "Snapshot saved in memory"
                                } else {
                                    actionMessage = result.message()
                                }
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text("Save snapshot") }
                    if (language == DemoLanguage.Json) {
                        Toggle("Format before save", formatOnSave) { formatOnSave = it }
                    }
                }
                Text(
                    if (busy) "Working…" else actionMessage,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall,
                )
                savedText?.let { saved ->
                    Text(
                        "Saved ${saved.length} characters in memory${if (saved != keyedState.text) " · unsaved changes" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                CodeEditor(
                    state = keyedState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    language = language.rules,
                    languageService = service,
                    engine = engine,
                    readOnly = readOnly,
                    showLineNumbers = showLineNumbers,
                    folding = folding,
                    onCaretChange = { caret = it },
                    tabBehavior = if (tabMovesFocus) TabBehavior.MOVE_FOCUS else TabBehavior.INSERT_INDENT,
                    colors = colors,
                    options = options,
                )
            }
            Text(
                "Ln ${caret.line + 1}, Col ${caret.character + 1}  ·  Ctrl+Space completes, Cmd/Ctrl+F finds, Cmd/Ctrl+/ comments",
                style = CodeEditorDefaults.textStyle(),
            )
            ReplDemo(engine, commandService, colors)
        }
        Column(Modifier.width(spacing.large * 22).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
            GeneratedTestDemo(engine, colors)
            SnippetDemo(colors)
        }
    }
}

private fun FormatResult.message(): String = when (this) {
    FormatResult.APPLIED -> "Formatted"
    FormatResult.UNCHANGED -> "Already formatted"
    FormatResult.UNAVAILABLE -> "Cannot format this document; check validation errors"
    FormatResult.STALE -> "Text changed; format again before saving"
    FormatResult.FAILED -> "Formatting failed; text was preserved"
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(selected = checked, onClick = { onChange(!checked) }, label = { Text(label) })
}

/** A single-line REPL: Enter submits, Up/Down browse history, completion works inline. */
@Composable
private fun ReplDemo(engine: CodeEditorEngine, service: LanguageService, colors: CodeEditorColors) {
    val state = rememberCodeEditorState("")
    val history = remember { mutableStateListOf<String>() }
    val output = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableStateOf(-1) }
    SectionCard(title = "REPL", description = "Single-line mode with onSubmit and onHistory.") {
        Column(Modifier.fillMaxWidth()) {
            output.takeLast(4).forEach { Text("> $it", style = CodeEditorDefaults.textStyle()) }
        }
        CodeEditor(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            language = CommandRules,
            languageService = service,
            engine = engine,
            singleLine = true,
            colors = colors,
            onSubmit = { command ->
                if (command.isNotBlank()) {
                    output += command
                    history += command
                }
                historyIndex = -1
                state.resetText("")
            },
            onHistory = { delta ->
                if (history.isEmpty()) return@CodeEditor
                historyIndex = when {
                    historyIndex == -1 && delta < 0 -> history.lastIndex
                    historyIndex == -1 -> return@CodeEditor
                    else -> historyIndex + delta
                }
                if (historyIndex > history.lastIndex) {
                    historyIndex = -1
                    state.setText("")
                } else {
                    historyIndex = historyIndex.coerceAtLeast(0)
                    state.setText(history[historyIndex])
                }
            },
        )
    }
}

private data class TestStep(val title: String, val lines: IntRange)

private val steps = listOf(
    TestStep("Launch the app", 7..8),
    TestStep("Tap Sign in", 10..10),
    TestStep("Type the email", 11..12),
    TestStep("Submit", 13..13),
    TestStep("Assert the inbox is shown", 15..16),
)

/** A read-only generated test whose lines highlight from a clickable step list (a source map). */
@Composable
private fun ColumnScope.GeneratedTestDemo(engine: CodeEditorEngine, colors: CodeEditorColors) {
    var selected by remember { mutableStateOf<TestStep?>(null) }
    var hovered by remember { mutableStateOf<TestStep?>(null) }
    val state = rememberCodeEditorState(SWIFT_TEST_SAMPLE)
    val spacing = SampleSpacing
    Text("Generated test", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    LazyColumn(Modifier.fillMaxWidth().padding(vertical = spacing.small)) {
        items(steps) { step ->
            val interaction = remember { MutableInteractionSource() }
            val isHovered by interaction.collectIsHoveredAsState()
            if (isHovered && hovered != step) hovered = step
            if (!isHovered && hovered == step) hovered = null
            Text(
                step.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (selected == step) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                    .hoverable(interaction)
                    .clickable { selected = step }
                    .padding(spacing.small),
            )
        }
    }
    val highlights = listOfNotNull(
        selected?.let { LineHighlight(it.lines, LineHighlightRole.SELECTED) },
        hovered?.takeIf { it != selected }?.let { LineHighlight(it.lines, LineHighlightRole.HOVER) },
    )
    CodeEditor(
        state = state,
        modifier = Modifier.fillMaxWidth().weight(1f),
        language = SwiftRules,
        engine = engine,
        readOnly = true,
        lineHighlights = highlights,
        revealLine = selected?.lines?.first,
        onLineClick = { line -> selected = steps.firstOrNull { line in it.lines } },
        colors = colors,
    )
}

private val snippets = listOf(
    CommandRules to "tap id=\"sign-in\"",
    CommandRules to "swipe direction=up # scroll the inbox",
    JsonRules to "{\"tool\": \"tap\", \"args\": {\"id\": \"sign-in\"}, \"ok\": true}",
    KotlinRules to "val result = runCatching { client.call(\"ping\") }",
    SwiftRules to "XCTAssertTrue(app.buttons[\"Send\"].exists)",
    ShellRules to "export OUTPUT=\"\${HOME}/results\"; echo \"\$OUTPUT\"",
)

/** CodeSnippet: cheap highlighted rows for timelines and logs, instead of an editor per row. */
@Composable
private fun ColumnScope.SnippetDemo(colors: CodeEditorColors) {
    Text("Snippets", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    LazyColumn(Modifier.fillMaxWidth().weight(0.6f), verticalArrangement = Arrangement.spacedBy(SampleSpacing.small)) {
        items(List(40) { snippets[it % snippets.size] }) { (rules, code) ->
            CodeSnippet(code, language = rules, colors = colors, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Opens a dialog and a menu over the editors to check that overlays draw above each engine. */
@Composable
private fun OverlayCheck() {
    var dialog by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    SectionCard(
        title = "Overlay check",
        description = "Switch to RSTA, then open these. A heavyweight SwingPanel can draw over Compose popups.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(SampleSpacing.small)) {
            Button(onClick = { dialog = true }) { Text("Dialog") }
            Box {
                OutlinedButton(onClick = { menu = true }) { Text("Menu") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    repeat(12) { index -> DropdownMenuItem(text = { Text("Approve step ${index + 1}") }, onClick = { menu = false }) }
                }
            }
        }
    }
    if (dialog) {
        AlertDialog(
            onDismissRequest = { dialog = false },
            title = { Text("Approve tool call?") },
            text = { Text("If this dialog is fully visible above the editor, overlays work with the current engine.") },
            confirmButton = { TextButton(onClick = { dialog = false }) { Text("Approve") } },
            dismissButton = { TextButton(onClick = { dialog = false }) { Text("Deny") } },
        )
    }
}

private val COMMANDS_SAMPLE = """
    # Demo command script. Try typing a command name, a space, or '='.
    launch bundle="com.example.mail"
    wait seconds=2
    tap id="sign-in"
    type text="me@example.com" submit=true
    swipe direction=up
    tapp id="typo"
    scroll direction=down
    assert id="inbox" color=red
""".trimIndent()

private val JSON_SAMPLE = """
    {
      "name": "Mail mock",
      "port": 8080,
      "mode": "mock",
      "routes": [
        {
          "path": "/messages",
          "method": "GET",
          "status": 200,
          "body": { "items": [] }
        }
      ],
      "colour": "blue"
    }
""".trimIndent()

private val KOTLIN_SAMPLE = """
    package demo

    /* A block comment
       spanning lines. */
    @JvmInline
    value class UserId(val raw: String)

    data class User(val id: UserId, val name: String, val age: Int = 0)

    fun greet(user: User): String {
        val greeting = if (user.age >= 18) "Hello" else "Hi"
        return "${'$'}greeting, ${'$'}{user.name}!"
    }

    fun main() {
        val users = listOf(User(UserId("1"), "Ada", 36), User(UserId("2"), "Linus"))
        users.forEach { println(greet(it)) }
    }
""".trimIndent()

private val SHELL_SAMPLE = """
    #!/usr/bin/env bash
    set -eu
    export OUTPUT="${'$'}{HOME}/results"
    mkdir -p "${'$'}OUTPUT"
    for file in *.json; do
        if test -f "${'$'}file"; then
            printf '%s\n' "Checking ${'$'}file"
            ./validate --strict "${'$'}file" > "${'$'}OUTPUT/report.txt"
        fi
    done
""".trimIndent()

private val SWIFT_TEST_SAMPLE = """
    import XCTest

    @MainActor
    final class SignInTests: XCTestCase {
        func testSignIn() throws {
            let app = XCUIApplication()
            // Step 1: launch
            app.launch()
            /* Step 2: sign in */
            app.buttons["sign-in"].tap()
            let email = app.textFields["email"]
            email.typeText("me@example.com")
            app.keyboards.buttons["Return"].tap()
            // Step 3: verify
            let inbox = app.otherElements["inbox"]
            XCTAssertTrue(inbox.waitForExistence(timeout: 5), "Inbox for \(email.label) not shown")
        }
    }
""".trimIndent()
