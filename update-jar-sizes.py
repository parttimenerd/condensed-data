#!/usr/bin/env python3
"""
Update the size numbers in docs/jar-releases.md from actual built JARs.

Usage:
    python3 update-jar-sizes.py                        # uses default output dirs
    python3 update-jar-sizes.py --universal target/condensed-data.jar \
        --platform-dir target/platform-jars/ \
        --minimal-dir target/platform-jars-minimal/
"""
import argparse
import os
import re
import sys
from pathlib import Path


def fmt_size(n: int) -> str:
    """Format bytes as 'X.X MB' or 'XXX KB'."""
    if n >= 1_000_000:
        return f"{n / 1_000_000:.1f} MB"
    return f"{round(n / 1000)} KB"


def find_jar(directory: Path, *patterns: str) -> Path | None:
    """Return first jar in directory whose name matches all patterns (case-insensitive)."""
    if not directory.is_dir():
        return None
    for f in sorted(directory.iterdir()):
        name = f.name.lower()
        if f.suffix == ".jar" and all(p.lower() in name for p in patterns):
            return f
    return None


def measure_sizes(universal: Path, platform_dir: Path, minimal_dir: Path) -> dict:
    sizes = {}

    if universal and universal.exists():
        sizes["universal"] = universal.stat().st_size

    # Pick linux-amd64 as the representative platform
    if platform_dir.is_dir():
        p = find_jar(platform_dir, "linux-amd64")
        if p and "inflaterless" not in p.name and "minimal" not in p.name:
            sizes["platform"] = p.stat().st_size
        p = find_jar(platform_dir, "linux-amd64", "inflaterless")
        if p and "minimal" not in p.name:
            sizes["platform_inflaterless"] = p.stat().st_size
        p = find_jar(platform_dir, "universal", "inflaterless")
        if p:
            sizes["universal_inflaterless"] = p.stat().st_size

    if minimal_dir.is_dir():
        p = find_jar(minimal_dir, "linux-amd64")
        if p and "inflaterless" not in p.name:
            sizes["platform_minimal"] = p.stat().st_size
        p = find_jar(minimal_dir, "linux-amd64", "inflaterless")
        if p:
            sizes["platform_inflaterless_minimal"] = p.stat().st_size

    return sizes


def patch_doc(doc: Path, sizes: dict) -> bool:
    """Patch the variant table in the doc. Returns True if anything changed."""
    text = doc.read_text()

    replacements = {
        # (variant_key, regex pattern for the size cell in the table row)
        "universal": r"(\|\s*\*\*Universal\*\*\s*\|[^|]*\|)\s*[\d.]+ [KMG]B\s*(\|)",
        "universal_inflaterless": r"(\|\s*\*\*Universal-inflaterless\*\*\s*\|[^|]*\|)\s*[\d.]+ [KMG]B\s*(\|)",
        "platform": r"(\|\s*\*\*Platform\*\*\s*\|[^|]*\|)\s*[\d.]+ [KMG]B\s*(\|)",
        "platform_inflaterless": r"(\|\s*\*\*Platform-inflaterless\*\*\s*\|[^|]*\|)\s*[\d.]+ [KMG]B\s*(\|)",
        "platform_minimal": r"(\|\s*\*\*Platform-minimal\*\*\s*\|[^|]*\|)\s*[\d.]+ [KMG]B\s*(\|)",
        "platform_inflaterless_minimal": r"(\|\s*\*\*Platform-inflaterless-minimal\*\*\s*\|[^|]*\|)\s*[\d.]+ [KMG]B\s*(\|)",
    }

    changed = False
    for key, pattern in replacements.items():
        if key not in sizes:
            continue
        new_size = fmt_size(sizes[key])
        new_text = re.sub(pattern, lambda m, s=new_size: f"{m.group(1)} {s} {m.group(2)}", text)
        if new_text != text:
            text = new_text
            changed = True
            print(f"  {key}: updated to {new_size}")

    if changed:
        doc.write_text(text)
    return changed


def main():
    parser = argparse.ArgumentParser(description="Update JAR size numbers in docs/jar-releases.md")
    parser.add_argument("--universal", type=Path, default=Path("target/condensed-data.jar"))
    parser.add_argument("--platform-dir", type=Path, default=Path("target/platform-jars"))
    parser.add_argument("--minimal-dir", type=Path, default=Path("target/platform-jars-minimal"))
    parser.add_argument("--doc", type=Path, default=Path("docs/jar-releases.md"))
    args = parser.parse_args()

    sizes = measure_sizes(args.universal, args.platform_dir, args.minimal_dir)
    if not sizes:
        print("No JARs found — build first with: mvn package -DskipTests && python3 reduce-jar.py matrix ...")
        sys.exit(1)

    print(f"Measured sizes: { {k: fmt_size(v) for k, v in sizes.items()} }")
    changed = patch_doc(args.doc, sizes)
    if changed:
        print(f"Updated {args.doc}")
    else:
        print(f"No changes needed in {args.doc}")


if __name__ == "__main__":
    main()
