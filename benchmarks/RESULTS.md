# Large-document benchmark evidence

Measured 2026-11-06 on Apple M4 Max (16 cores), 48 GB RAM, macOS 27.3, Temurin JDK 21.0.13.
Gradle test workers use the repository's default 512 MB heap and headless Skiko renderer. The
machine is shared; these are local latency measurements, not hardware-independent thresholds.

Baseline: `098d2bec8a4b7997a42a6785fb3f149cae1d759a`, in an isolated source archive with the same
benchmark sources copied in. Unrelated completion/release work in the shared checkout is excluded
from the baseline. The revised measurements use this working tree's Compose implementation.

## Reproduce

```bash
benchmarks/run.sh all
python3 benchmarks/summarize.py
```

`before` and `after` can be run separately. `KMP_EDITOR_BASELINE_REF` overrides the baseline commit.
The script creates and removes its own temporary checkout, forces test execution, and runs the
stages sequentially. Dependencies must already be reachable through the usual Gradle repositories.
The complete baseline includes two formatting cases taking over a minute each.

All fixtures are generated deterministically by `largeJsonFixture`: an array of objects with
`id`, `name`, and `ok`, either pretty printed or minified, and one object with a single long `x`
string. Targets are decimal 100 KB, 1 MB, and 5 MB. Actual ASCII byte lengths are in the CSVs;
array fixtures differ from the target by less than one object.

## What is timed

- `display-*.csv`: real `CodeEditor`, 800 × 400 logical pixels, JSON highlighting, default
  unwrapped Compose engine, caret initially at source start. Time from `setContent` through idle
  and capture of the first rendered frame. Includes layout/drawing and bitmap readback, excludes
  payload generation and PNG encoding. Large-document syntax/folding may still be analyzing;
  complete color/diagnostic readiness is not the first-frame metric. The old engine truncates
  visible lines at 10,000 characters even though its hidden native field mirrors the whole line.
- `before.csv` / `after.csv`: model operations with a real Compose `TextMeasurer`, 800 × 400
  viewport, 14 sp monospace. Initial model/window plus first-tile layouts of up to 23 logical
  lines; typing `x` at offsets 10, length/2, and length−3; 16 KB paste at the middle; undo/redo
  of that paste; literal case-sensitive find of `fixture`; explicit JSON formatting. Edit
  measurements include snapshot/index/geometry work, visible logical-line tile requests, scroll
  adjustment, and input-window synchronization. They do not include a presented frame for every
  edit, native keyboard dispatch, or clipboard transfer latency. Undo restores each typing case;
  formatting starts from the original valid JSON.
- Each fixture has one discarded warm-up and three measured repetitions. Model timings have a
  separate deferred syntax worker; they measure immediate UI work, not its eventual completion.
- `format-*.csv`: one separate 5 MB formatting observation per shape after a 100 KB warm-up.
  This avoids replaying the baseline's quadratic formatting undo during the whole operation
  matrix. The summary uses these matched isolated measurements for 5 MB formatting on both sides.
  Every format result was asserted `APPLIED`. All other reported values are medians of three.
- Find retains the first 10,000 matches, a pre-existing limit. `fixture` reaches that cap in the
  larger arrays; the no-match string case scans the whole document. A unique marker beyond
  character 15,000 is separately covered by the interaction tests.

## Findings and tradeoffs

The old hidden field shaped multi-megabyte logical lines, in addition to the visible 10,000-unit
paragraph. The revised bridge caps its native window at 4,096 UTF-16 units, with surrogate-boundary
adjustments, and the renderer shapes viewport tiles. Initial display now includes access to the
whole source. Long-string/minified layout and navigation therefore improve substantially.

Formatting repeatedly shifted a full `StringBuilder` per whitespace edit. History replay also
replaced a full string per fragment. Both now assemble fragments in one pass; formatting no longer
constructs its full result twice. The line index uses primitive arrays. Background highlighting
and folding preserve incremental caches, check cancellation, and reject obsolete versions. Token
lookup for ordinary typing is lazy so it does not trigger unused whole-document comparisons.

Visual geometry still scans edited long lines and compares unchanged logical lines. It shares
unchanged checkpoint/row arrays between versions without retaining older source strings. The text
model remains string-backed, so edits and line-index rebuilding are still O(document size).
Small pretty-printed documents can pay extra startup/geometry overhead; not every measurement
improves. The full operation table records regressions as well as improvements. The very long
string formatter only changes a small amount of whitespace, so it does not exhibit the array
formatter's speedup.

The tiled renderer disables ligatures/kerning. Cross-tile bidirectional shaping and combining
sequences exceeding a tile remain limitations. Wrapping is character based and preserves ordinary
surrogate, combining-mark, and ZWJ boundaries; an indivisible wide glyph or tab can overflow an
extremely narrow viewport. No source formatting or inserted line breaks are used for display.

## Results

The tables below are generated from the delivered raw observations. See [operations.md](operations.md)
for every payload shape/size and operation, including before/after typing at all three positions,
paste, find, formatting, and undo/redo.

| Shape | Target bytes | First frame before ms | First frame after ms |
| --- | ---: | ---: | ---: |
| pretty | 100,000 | 8.23 | 12.99 |
| pretty | 1,000,000 | 29.01 | 28.10 |
| pretty | 5,000,000 | 77.12 | 51.86 |
| minified | 100,000 | 51.12 | 10.92 |
| minified | 1,000,000 | 450.28 | 14.93 |
| minified | 5,000,000 | 7895.44 | 31.49 |
| string | 100,000 | 34.99 | 17.33 |
| string | 1,000,000 | 188.88 | 12.19 |
| string | 5,000,000 | 2182.17 | 25.50 |

Raw observations: [before](before.csv), [after](after.csv), [frames before](display-before.csv), [frames after](display-after.csv), [5 MB format before](format-before.csv), [5 MB format after](format-after.csv).

Verification and screenshot coverage: [VERIFICATION.md](VERIFICATION.md).
