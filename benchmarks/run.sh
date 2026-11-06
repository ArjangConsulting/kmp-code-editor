#!/usr/bin/env bash
set -euo pipefail
benchmark_directory="$(cd "$(dirname "$0")" && pwd)"
repository_directory="$(cd "$benchmark_directory/.." && pwd)"
baseline_ref="${KMP_EDITOR_BASELINE_REF:-098d2bec8a4b7997a42a6785fb3f149cae1d759a}"
mode="${1:-all}"
run_benchmarks() {
    local checkout="$1"
    local label="$2"
    local baseline="$3"
    KMP_EDITOR_BASELINE="$baseline" KMP_EDITOR_BENCHMARK="$benchmark_directory/$label.csv" \
        "$checkout/gradlew" -p "$checkout" :code-editor:jvmTest --tests '*LargeDocumentBenchmarkTest*' --rerun-tasks
    rm -f "$benchmark_directory/display-$label.csv"
    KMP_EDITOR_DISPLAY_BENCHMARK="$benchmark_directory/display-$label.csv" \
        "$checkout/gradlew" -p "$checkout" :code-editor:jvmTest --tests '*LargeDocumentDisplayBenchmarkTest*' --rerun-tasks
    KMP_EDITOR_FORMAT_BENCHMARK="$benchmark_directory/format-$label.csv" \
        "$checkout/gradlew" -p "$checkout" :code-editor:jvmTest --tests '*LargeDocumentFormattingBenchmarkTest*' --rerun-tasks
}
if [[ "$mode" == before || "$mode" == all ]]; then
    baseline_directory="$(mktemp -d "${TMPDIR:-/tmp}/kmp-editor-benchmark.XXXXXX")"
    trap 'rm -rf "$baseline_directory"' EXIT
    git -C "$repository_directory" archive "$baseline_ref" | tar -x -C "$baseline_directory"
    for source in LargeDocumentBenchmarkTest.kt LargeDocumentDisplayBenchmarkTest.kt LargeDocumentFormattingBenchmarkTest.kt; do
        cp "$repository_directory/code-editor/src/jvmTest/kotlin/com/arjangconsulting/codeeditor/$source" \
            "$baseline_directory/code-editor/src/jvmTest/kotlin/com/arjangconsulting/codeeditor/$source"
    done
    run_benchmarks "$baseline_directory" before true
fi
if [[ "$mode" == after || "$mode" == all ]]; then
    run_benchmarks "$repository_directory" after false
fi
