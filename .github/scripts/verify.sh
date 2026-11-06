#!/usr/bin/env bash
# The one list of Gradle tasks that gates CI, releases, and release preflight.
# Extra arguments (for example --no-configuration-cache) are passed through to Gradle.
set -euo pipefail

cd "$(dirname "$0")/../.."
./gradlew :code-editor:allTests :code-editor:checkKotlinAbi :code-editor:assemble :sample:compileKotlinJvm "$@"
