# Claude Code

@AGENTS.md

Everything above is shared with other agents. This file only adds Claude Code specifics; put any
rule that applies to all agents in `AGENTS.md`, not here.

- Repo facts and conventions belong in `AGENTS.md`, not in Claude's memory, so every agent sees
  them.
- `./gradlew :code-editor:allTests` builds the iOS targets and takes several minutes; run it with
  `run_in_background` and keep working instead of waiting.
- For Android/iOS checks, build with `verification/build-apps.sh`, then drive the app through the
  amoo MCP server (`driving-amoo` skill, or the `device-verifier` agent for a pass/fail report).
  Follow the checks in `verification/README.md`.
- Relevant skills: `compose-multiplatform-patterns` for UI work, `kotlin-tooling-agp9-migration`
  when touching the Android Gradle plugin.
