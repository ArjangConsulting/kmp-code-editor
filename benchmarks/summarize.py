import csv
import statistics
from pathlib import Path

root = Path(__file__).resolve().parent
operations = ["initial", "type_start", "type_middle", "type_end", "paste_16KB", "find", "format", "undo", "redo"]
records = {name: list(csv.DictReader((root / f"{name}.csv").open())) for name in ["before", "after"]}
for name in ["before", "after"]:
    supplement = root / f"format-{name}.csv"
    if supplement.exists():
        supplemental_rows = list(csv.DictReader(supplement.open()))
        replaced = {(row["shape"], row["bytes"], row["operation"]) for row in supplemental_rows}
        records[name] = [row for row in records[name] if (row["shape"], row["bytes"], row["operation"]) not in replaced] + supplemental_rows


def median(name, shape, size, operation):
    values = [float(row["milliseconds"]) for row in records[name] if row["shape"] == shape and abs(int(row["bytes"]) - size) < 100 and int(row["iteration"]) > 0 and row["operation"] == operation]
    return f"{statistics.median(values):.2f}" if values else "—"


lines = ["| Shape | Size | Operation | Before ms | After ms |", "| --- | --- | --- | ---: | ---: |"]
for shape in ["pretty", "minified", "string"]:
    for size in [100_000, 1_000_000, 5_000_000]:
        for operation in operations:
            lines.append(f"| {shape} | {size:,} | {operation} | {median('before', shape, size, operation)} | {median('after', shape, size, operation)} |")
(root / "operations.md").write_text("\n".join(lines) + "\n")
