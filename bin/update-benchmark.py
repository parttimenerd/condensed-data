#!/usr/bin/python3

"""
Updates the ### Current Results section of README.md with fresh benchmark output.

Runs: ./cjfr benchmark -c lossless -c default -c reduced -c archival-max
Emits the table as plain Markdown (not a code block).
"""

from datetime import datetime
from pathlib import Path
import subprocess

BASE_DIR = Path(__file__).parent.parent
README_PATH = BASE_DIR / "README.md"

CONFIGURATIONS = ["lossless", "default", "reduced", "archival-max"]


def run_benchmark() -> str:
    cmd = ["./cjfr", "benchmark"] + [arg for c in CONFIGURATIONS for arg in ["-c", c]]
    out = subprocess.check_output(cmd, cwd=BASE_DIR, text=True, stderr=subprocess.DEVNULL)
    lines = [l for l in out.splitlines() if not l.startswith("Benchmarked")]
    return "\n".join(lines)


def update_readme(table: str) -> None:
    text = README_PATH.read_text()
    lines = text.splitlines(keepends=True)

    section_line = next(
        (i for i, l in enumerate(lines) if l.strip() == "### Current Results"), None
    )
    if section_line is None:
        raise ValueError("'### Current Results' not found in README.")

    i = section_line + 1
    while i < len(lines) and lines[i].strip() == "":
        i += 1

    if i < len(lines) and lines[i].startswith("```"):
        # Old-style code fence — find the closing ```
        start = i
        end = start + 1
        while end < len(lines) and not lines[end].startswith("```"):
            end += 1
        end += 1  # include the closing ```
    else:
        # Bare table — find the last table row
        start = i
        end = i
        while end < len(lines) and ("|" in lines[end] or lines[end].strip().startswith("**")):
            end += 1

    now = datetime.now().strftime("%Y-%m-%d %H:%M")
    new_block = f"**Benchmark run on {now}**\n\n{table.strip()}\n"

    README_PATH.write_text("".join(lines[:start] + [new_block] + lines[end:]))


if __name__ == "__main__":
    print("Running benchmark (lossless / default / reduced / archival-max)…")
    table = run_benchmark()
    print("Updating README.md…")
    update_readme(table)
    print("Done.")
