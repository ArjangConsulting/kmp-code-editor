# Development

## Try it

```bash
# Desktop sample: every feature on one screen
./gradlew :sample:run
# Android app with the editor (see verification/README.md)
verification/build-apps.sh android
# iOS simulator app (needs Xcode and xcodegen)
verification/build-apps.sh ios
```

In IntelliJ IDEA or Android Studio, open the repository and use the shared run configurations in
`.run/`: **Run** has *Sample (desktop)*, *Sample (desktop, window layers)* (adds
`-Dcompose.layers.type=WINDOW` for testing the RSyntaxTextArea engine with overlays), and the
Android and iOS verification builds; **Test** has *Tests (JVM)*, *Tests (all targets)*, and
*Check (CI)*.

The desktop sample has language and engine pickers, a toggle for every option, a demo
`LanguageService` for a small command language (with simulated latency), JSON completion from a
tiny schema, a read-only Swift test whose lines highlight from a clickable step list, a REPL, a
`CodeSnippet` list, light/dark themes, a syntax color editor (palette presets, per-token colors
with a contrast check), and a dialog-over-editor overlay check.

The sample uses [KMPComponents](https://github.com/ArjangConsulting/KMPComponents) 0.2.0 for `KmpTheme`,
kommon spacing tokens, `ThemeModeSelector`, and section cards. The
published editor has no KMPComponents dependency; its Material theme roles inherit the host's
colors when placed inside `KmpTheme`.

To try changes from a sibling KMPComponents checkout:

```bash
./gradlew :sample:run -PuseLocalKmpComponents=true
```

Add `-PkmpComponentsDir=/path/to/KMPComponents` for another checkout location. Without the opt-in,
the sample resolves the catalog version from Maven Central, including in CI.

## Build and test

```bash
# Common + desktop UI + RSyntaxTextArea panel tests
./gradlew :code-editor:jvmTest
# Also Android host and iOS simulator
./gradlew :code-editor:allTests
./gradlew :code-editor:checkKotlinAbi
# Desktop sample app
./gradlew :sample:run
```

The standalone [mobile verification harness](../verification/README.md) builds Android and iOS
simulator apps for native composition, keyboard, selection, toolbar, and accessibility checks.
Internals are described in [ARCHITECTURE.md](ARCHITECTURE.md). Contributor and AI-agent conventions
live in [AGENTS.md](../AGENTS.md).

## Using a local checkout

Include this repository as a composite build; Gradle substitutes
`com.arjangconsulting.codeeditor:code-editor` with the `:code-editor` project automatically:

```kotlin
// settings.gradle.kts of the consuming app
includeBuild("../kmp-code-editor")
```

## Releases

Releases are automated by Release Please from Conventional Commit messages (`feat:`, `fix:`,
`feat!:`), with bare SemVer tags (no `v` prefix). Until 0.1.0 is released, changes are committed
directly to `main`; after that they land through pull requests.

The initial release is `0.1.0`. While below `1.0.0`, `feat:` and breaking changes (`feat!:` or a
`BREAKING CHANGE:` footer) bump the minor version; `fix:` bumps the patch version. Breaking API
changes remain allowed: update the reviewed ABI baseline with `./gradlew :code-editor:updateKotlinAbi`
and describe consumer migration in the release PR. The ABI check makes those changes explicit.
See [RELEASING.md](RELEASING.md) for credential setup and the release preflight.

Dependabot opens one weekly pull request for all Gradle and GitHub Actions updates, which is
merged automatically once CI passes.

Releases are **off** until the GitHub repository variable `RELEASES_ENABLED` is set to `true`
(Settings > Secrets and variables > Actions > Variables). Until then no release PR is opened and
nothing is tagged or published, so the first release (0.1.0) waits for review.
