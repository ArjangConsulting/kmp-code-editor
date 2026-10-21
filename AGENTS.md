# KMP Code Editor Agent Instructions

`AGENTS.md` is the source of truth for agent guidance in this repo. `CLAUDE.md` only points here.
When this file and the checked-in build disagree, trust `settings.gradle.kts`,
`gradle/libs.versions.toml`, and the module build files, then fix this file.

## Purpose

A Compose Multiplatform code editor (Android, desktop JVM, iOS) with LSP-shaped, pluggable language
intelligence. Published as `com.arjangconsulting.codeeditor:code-editor`. Product-specific
languages, services, persistence, and DI stay in consuming apps (e.g. amoo-studio, moqserver).

## Repository map

- `code-editor/` — the only published module (package `com.arjangconsulting.codeeditor`).
  - `commonMain/` — text model (`TextModel.kt`), `TokenRules` DSL + lexer and presets,
    incremental `Highlighter`, pure `EditingOps` and `EditHistory`, `CodeEditorState`,
    `LanguageService` + `LanguageServiceController` (orchestration), the Compose engine
    (`ComposeCodeEditor`, `EditorModel`, `EditorOverlays`), `HighlightedText`, defaults/labels.
  - `jvmMain/` — the opt-in RSyntaxTextArea engine (`RstaEditorPanel`, `RstaCodeEditor`).
  - `commonTest/` — pure logic and virtual-time controller tests, run on every target.
  - `jvmTest/` — Compose UI tests (`runComposeUiTest`) and headless RSyntaxTextArea panel tests.
- `sample/` — unpublished desktop sample app with every feature (`./gradlew :sample:run`).
- `verification/` — standalone Android/iOS harness for native input and accessibility checks; a
  separate Gradle build that includes this repo (see `verification/README.md`).
- `docs/ARCHITECTURE.md` — internals. `README.md` — public API, engines, keyboard, known gaps.
- `.github/workflows/` — `ci.yml`, `release-please.yml` -> `release.yml` (Maven Central publish).

## API rules

1. **Stateless-first.** `CodeEditor(state, …)` with hoisted `CodeEditorState`, plus the
   `value`/`onValueChange` overload. Keep only ephemeral UI state internal.
2. **Parameter order:** required, `modifier: Modifier = Modifier`, optional, trailing content.
3. **No hardcoded user-visible strings without a parameter:** use `CodeEditorLabels`.
4. **Theme roles only.** Colors come from `CodeEditorColors` (defaults derived from
   `MaterialTheme.colorScheme`). There are no spacing tokens here: derive sizes from text metrics
   (line height, character width). A 1.dp border is the accepted exception.
5. **Accessibility.** Labels or `contentDescription` on interactive elements; polite live regions
   for dynamic messages.
6. **Compose-stable models.** Public models are `@Immutable` with `val`s; enum entries use
   SCREAMING_CASE.
7. **Pure logic out of composables,** covered in `commonTest`. LSP-shaped types stay close to LSP
   3.17 so adapters stay thin.
8. **KDoc** on every public declaration; `explicitApi()` is on.
9. **No inline (end-of-line) comments;** put comments on their own line.
10. Keep the engines' behavior aligned; document any engine-specific gap in `README.md`.

## Build and verification

```bash
./gradlew :code-editor:jvmTest                     # fastest: common + desktop UI + RSTA tests
./gradlew :code-editor:allTests                    # all targets (iOS needs macOS + Xcode)
./gradlew :code-editor:assemble :sample:compileKotlinJvm
```

CI runs `:code-editor:allTests :code-editor:assemble :sample:compileKotlinJvm` on macOS with JDK 21.
The build needs Google Maven for the Android Gradle plugin. Versions live in
`gradle/libs.versions.toml` only; declare Compose libraries through catalog entries. Keep Kotlin/JVM
bytecode at `JvmTarget.JVM_17`. `.editorconfig` holds the house style.

## Workflow and releases

- Never commit to `main`. Work on a branch (`feature/<name>`, `fix/<name>`) and open a pull request.
- Conventional Commits drive Release Please (`feat:` minor pre-1.0, `fix:` patch, `!` breaking);
  call out breaking public API changes in the commit body.
- Tags and versions are bare SemVer (`0.1.0`, never `v0.1.0`). Never hand-edit versions;
  `VERSION_NAME` is injected by `release.yml` from the tag, which must equal
  `.release-please-manifest.json`.
- Releases are gated: `release-please.yml` and `release.yml` only run when the repository variable
  `RELEASES_ENABLED` is `true`. Do not remove the gate or set the variable; the maintainer enables
  releases after reviewing the repo.
- Shared IntelliJ run configurations live in `.run/`; keep them in sync with the CI task list and
  the sample/verification entry points.
- Publishing needs `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_IN_MEMORY_KEY`, and
  `SIGNING_IN_MEMORY_KEY_PASSWORD` in the `secrets` environment, and the `com.arjangconsulting`
  namespace verified in the Central Portal (DNS TXT record on `arjangconsulting.com`).
