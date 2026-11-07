# Colors and themes

Editor chrome (background, gutter, selection, popups) comes from `MaterialTheme.colorScheme`. Token
colors come from a separate syntax palette, `CodeEditorSyntaxTheme`, which has a light and a dark
variant. The variant is picked from the editor background's luminance, so tokens stay readable on any
background, including a custom one.

| Theme | Contrast |
| --- | --- |
| `CodeEditorSyntaxTheme.Default` | ≥ 4.5:1 (WCAG AA) on white through `#D6D6D6` and on `#0F0D13` through `#404040`, so also on Material's tinted containers |
| `CodeEditorSyntaxTheme.HighContrast` | ≥ 7:1 (WCAG AAA) on white through `#D6D6D6` and on black through `#2E2E2E` |

Pick a palette:

```kotlin
CodeEditor(state, colors = CodeEditorDefaults.colors(CodeEditorSyntaxTheme.HighContrast))
```

The syntax variant follows a custom background automatically:

```kotlin
val colors = CodeEditorDefaults.colors().copy(background = panel)
```

Let people recolor tokens from a settings screen, then warn about unreadable picks:

```kotlin
val theme = CodeEditorSyntaxTheme.Default.let { it.copy(dark = it.dark.with(TokenKind.STRING, picked)) }
val tooFaint = theme.dark.lowContrastKinds(background)
CodeEditor(state, colors = CodeEditorDefaults.colors(theme))
```

A role set to `Color.Unspecified` uses the editor foreground; `variable` does by default.
`CodeEditorSyntaxTheme(colors)` uses one palette for every background. Persisting
someone's choices is up to the app: store the edited `CodeEditorSyntaxTheme` however you store
other settings.

The desktop sample's syntax color editor shows palette presets and per-token colors with a contrast
check.
