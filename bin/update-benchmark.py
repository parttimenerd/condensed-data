#!/usr/bin/python3

"""
A script to update the benchmark results in the README file.

They are in the `Benchmarking` section of the README, below "Current results:"
in the code block.

./cjfr benchmark
"""

from pathlib import Path
import re
import subprocess

BASE_DIR = Path(__file__).parent.parent
README_PATH = BASE_DIR / "README.md"

def run_benchmark() -> str:
    out = subprocess.check_output(["/bin/sh", "cjfr", "benchmark"], cwd=BASE_DIR).decode()
    # skip all lines that start with "Benchmarked"
    return "\n".join(l for l in out.splitlines() if not l.startswith("Benchmarked"))


def update_readme(benchmark_output: str) -> None:
    with open(README_PATH, "r") as file:
        readme_content = file.read()

    lines = readme_content.splitlines()
    section = "### Current Results"
    start_marker = "```shell"
    end_marker = "```"
    start_index = None
    end_index = None
    i = 0
    # walk till the benchmark section start start and end indices of the benchmark section

    for e, line in enumerate(lines):
        if line.strip() == section:
            i = e + 1
            break
    else:
        raise ValueError(f"Section '{section}' not found in README.")

    for e in range(i, len(lines)):
        if lines[e].strip() == start_marker:
            start_index = e
            break
    else:
        raise ValueError(f"Start marker '{start_marker}' not found in README.")

    for e in range(start_index + 1, len(lines)):
        if lines[e].strip() == end_marker:
            end_index = e
            break
    else:
        raise ValueError(f"End marker '{end_marker}' not found in README.")
    # add date to the benchmark output
    from datetime import datetime
    current_date = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    benchmark_output = f"**Benchmark run on {current_date}**\n\n" + benchmark_output
    # Update the benchmark section with the new output
    new_content = "\n".join(lines[:start_index + 1] + [benchmark_output.strip()] + lines[end_index:])
    with open(README_PATH, "w") as file:
        file.write(new_content)

if __name__ == "__main__":
    print("Running benchmark...")
    benchmark_output = run_benchmark()
    print("Benchmark completed. Updating README.md...")
    update_readme(benchmark_output)
    print("Benchmark results updated in README.md")