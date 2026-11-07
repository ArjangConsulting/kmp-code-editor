# Engines

`engine = CodeEditorEngine.DEFAULT` picks **Compose on every platform, including desktop**.
`RSYNTAX_TEXT_AREA` is available on desktop as an opt-in and falls back to Compose elsewhere.

## Compose engine

The Compose engine draws viewport tiles of at most 256 UTF-16 units, with small extensions to
keep ordinary Unicode clusters together. Long logical lines have no display truncation. Horizontal
scrolling, hit testing, selection, diagnostics, and search use the complete source, including
minified JSON and long string values. Layout never reformats or inserts source line breaks.

### Soft wrap

Enable character wrapping for request/response bodies with:

```kotlin
CodeEditor(
    state = bodyState,
    language = JsonRules,
    languageService = JsonLanguageService(),
    options = CodeEditorOptions(softWrap = true),
    tabBehavior = TabBehavior.MOVE_FOCUS,
    labels = CodeEditorLabels(editorDescription = "Response body"),
)
```

Use `PlainText` and omit the language service for raw bodies. Set `readOnly = true` for previews;
selection, copying, find, and folding remain available. Existing callers keep unwrapped behavior.
`softWrap` works through both public overloads and is ignored with `singleLine = true`.

Wrapping is visual and breaks at character clusters rather than word boundaries. Positions,
service ranges, callbacks, and line numbers remain logical source positions; only the first row
of a logical line gets a gutter number and fold control. Up/Down and PageUp/PageDown follow visual
rows, and Home/End target the visual row edges. Document start/end still target source boundaries.
Tabs use logical-line tab stops and move intact to the next row when necessary. Resizing reflows
the view and keeps the focused caret visible. Content that fits the wrapped viewport has no
horizontal scrollbar; an indivisible glyph, tab, or fold label wider than the viewport can overflow.
Folding hides whole source lines and wraps the visible header, reserving space for its placeholder.

### Input and large documents

A hidden `BasicTextField` preserves native text input, composition, and accessibility. It mirrors
at most 4,096 UTF-16 units (with surrogate-boundary adjustments) around the selection/caret,
including line context when it fits. Large selections detach from this window; editor copy/cut
still use the complete logical selection. Native IME geometry is aligned to the drawn caret.
Highlighting and folding for long lines or more than 10,000 lines run off the UI thread, cancel
on replacement, and reject stale versions. Text can appear briefly without syntax colors while
analysis catches up. Formatting happens only through the host's explicit `state.format` action.

The default monospace style is recommended. The tiled renderer measures glyph advances and
turns off ligatures and kerning for consistent geometry. Bidirectional shaping across tile
boundaries and pathological combining sequences longer than a tile are not full-paragraph
shaping. See [benchmark evidence](../benchmarks/RESULTS.md) for reproducible performance measurements
and their limits.

## RSyntaxTextArea engine (desktop, opt-in)

The RSyntaxTextArea engine generalizes moqserver's `JsonCodeEditor`. It hosts `RSyntaxTextArea`
in a `SwingPanel` with:

- a `TokenMakerBase` adapter over the shared lexer, so custom languages and Swift work;
- `addLineHighlight` for line highlights;
- a `Parser` that turns diagnostics into notices, gutter icons, and tooltips;
- fifesoft `AutoCompletion` fed by the same controller, whose popup is a separate Swing window;
- the theme mapped onto `SyntaxScheme`;
- the shared fold computation;
- the shared Compose find and replace bar above the panel, with matches shown by RSTA's mark-all
  highlighter and replacements applied through `CodeEditorState`.

`CodeEditorState` stays the source of truth for text, selection, and undo.
`CodeEditorOptions.softWrap` also enables character wrapping in RSTA; Swing owns its native
wrapping, glyph shaping, and caret affinity, so exact row breaks can differ from Compose.

Not supported by the RSyntaxTextArea engine: semantic tokens, service folding ranges, hover
Markdown beyond escaped paragraphs, `TokenRules.indentUnit` other than tabs or spaces.

### Why RSyntaxTextArea is not the desktop default

The engine was probed in a real window under Compose Multiplatform 1.12.0. Compose renders into a
heavyweight `SkiaLayer` canvas, and the `SwingPanel` content sits in a separate Swing group inside
it. Compose `Popup`s, `Dialog`s, `DropdownMenu`s, and tooltips draw on the same canvas, so where
they overlap the editor, the editor wins. Approval dialogs and menus shown over an editor are
therefore hidden. Results by flag:

| JVM flag (set before Compose starts) | Popups and dialogs over RSTA | Notes |
| --- | --- | --- |
| none (default `compose.layers.type` = on same canvas) | **Hidden under the editor** | |
| `-Dcompose.layers.type=WINDOW` | Drawn above it: each popup and dialog becomes its own owned `JWindow` | Process-wide; changes popup windowing for the whole app. |
| `-Dcompose.layers.type=COMPONENT` | Not fixed (no separate windows) | |
| `-Dcompose.interop.blending=true` | Experimental. Not visually verified here; no separate windows. | Platform-dependent. |

Focus traversal is the second issue. Pressing Tab in a Compose text field above the panel left
AWT's focus owner `null` for several seconds before the `RSyntaxTextArea` got focus. Focus handoff
between Compose and Swing is not reliable enough for Studio's keyboard-first flows.

A library cannot set these flags safely: they must be set before Compose starts and they affect the
whole app. Compose is therefore the default everywhere. To use RSyntaxTextArea anyway:

1. Start the app with `-Dcompose.layers.type=WINDOW`, for example
   `compose.desktop.application { jvmArgs("-Dcompose.layers.type=WINDOW") }` or
   `System.setProperty("compose.layers.type", "WINDOW")` as the first line of `main`.
2. Check overlays with the sample's "Overlay check" card: `./gradlew :sample:run`, switch the
   engine to RSTA, and open the dialog and menu.
