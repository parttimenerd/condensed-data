#!/usr/bin/env python3
"""
Reduce a fat JAR by removing unnecessary platform-specific native libraries
and (in the future) optional component classes.

A JSON manifest (jar-reduction-info.json) is embedded at the JAR root so the
Java application can discover at runtime what was stripped.

Usage examples:
    # Single reduced JAR
    ./reduce-jar.py reduce input.jar output.jar --platform darwin/aarch64
    ./reduce-jar.py reduce input.jar --list-platforms

    # Generate matrix of all platform variants into a folder
    ./reduce-jar.py matrix input.jar out-dir/
    ./reduce-jar.py matrix input.jar out-dir/ --platforms darwin/aarch64,linux/amd64
"""

import argparse
import json
import os
import shutil
import sys
import tempfile
import zipfile
from dataclasses import dataclass, field
from pathlib import PurePosixPath
from typing import List, Optional

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

NATIVE_LIB_PREFIX = "net/jpountz/util/"
REDUCTION_INFO_PATH = "jar-reduction-info.json"

# ---------------------------------------------------------------------------
# Reduction descriptors – add new reductions here
# ---------------------------------------------------------------------------

@dataclass
class ReductionResult:
    """Tracks what a single reduction step removed."""
    name: str
    description: str
    removed_prefixes: List[str] = field(default_factory=list)
    kept: Optional[str] = None
    extra: dict = field(default_factory=dict)


# ---------------------------------------------------------------------------
# Platform reduction
# ---------------------------------------------------------------------------

def discover_platforms(zf: zipfile.ZipFile) -> List[str]:
    """Return sorted list of platform paths like 'darwin/aarch64'."""
    platforms = set()
    for entry in zf.namelist():
        if not entry.startswith(NATIVE_LIB_PREFIX):
            continue
        rel = entry[len(NATIVE_LIB_PREFIX):]
        parts = PurePosixPath(rel).parts
        # Expect os/arch/lib or os/arch/ or os/
        if len(parts) >= 2:
            platforms.add(f"{parts[0]}/{parts[1]}")
    return sorted(platforms)


def reduce_platform(
    zf: zipfile.ZipFile,
    platform: str,
    available: List[str],
) -> ReductionResult:
    """Remove native libs for all platforms except *platform*."""
    if platform not in available:
        print(
            f"Error: unknown platform '{platform}'. "
            f"Available: {', '.join(available)}",
            file=sys.stderr,
        )
        sys.exit(1)

    keep_prefix = NATIVE_LIB_PREFIX + platform + "/"
    removed_prefixes = []
    for p in available:
        if p != platform:
            removed_prefixes.append(NATIVE_LIB_PREFIX + p + "/")

    return ReductionResult(
        name="platform",
        description=f"Kept only native libraries for {platform}",
        removed_prefixes=removed_prefixes,
        kept=platform,
        extra={"available_platforms": available},
    )


# ---------------------------------------------------------------------------
# (Future) JMC reduction – placeholder
# ---------------------------------------------------------------------------

# def reduce_jmc(zf: zipfile.ZipFile) -> ReductionResult:
#     return ReductionResult(
#         name="without-jmc",
#         description="Removed org.openjdk.jmc classes",
#         removed_prefixes=["org/openjdk/jmc/"],
#     )


# ---------------------------------------------------------------------------
# Core logic
# ---------------------------------------------------------------------------

def should_exclude(entry: str, reductions: List[ReductionResult]) -> bool:
    for r in reductions:
        for prefix in r.removed_prefixes:
            if entry.startswith(prefix):
                return True
    return False


def build_reduction_info(reductions: List[ReductionResult]) -> dict:
    info: dict = {"reductions": []}
    for r in reductions:
        entry = {"name": r.name, "description": r.description}
        if r.kept is not None:
            entry["kept"] = r.kept
        if r.extra:
            entry.update(r.extra)
        info["reductions"].append(entry)
    return info


def reduce_jar(
    input_path: str,
    output_path: str,
    reductions: List[ReductionResult],
) -> None:
    removed_count = 0
    kept_count = 0

    with zipfile.ZipFile(input_path, "r") as zf_in:
        # Write to a temp file first, then move to output_path to support
        # input_path == output_path safely.
        fd, tmp_path = tempfile.mkstemp(suffix=".jar", dir=os.path.dirname(output_path) or ".")
        os.close(fd)
        try:
            with zipfile.ZipFile(tmp_path, "w", compression=zipfile.ZIP_DEFLATED) as zf_out:
                for item in zf_in.infolist():
                    if item.filename == REDUCTION_INFO_PATH:
                        continue  # will re-add below
                    if should_exclude(item.filename, reductions):
                        removed_count += 1
                        continue
                    zf_out.writestr(item, zf_in.read(item.filename))
                    kept_count += 1

                # Embed the manifest
                if reductions:
                    info = build_reduction_info(reductions)
                    zf_out.writestr(REDUCTION_INFO_PATH, json.dumps(info, indent=2))

            shutil.move(tmp_path, output_path)
        except BaseException:
            os.unlink(tmp_path)
            raise

    print(f"Wrote {output_path}  (kept {kept_count}, removed {removed_count} entries)")


# ---------------------------------------------------------------------------
# Naming helpers
# ---------------------------------------------------------------------------

def platform_slug(platform: str) -> str:
    """Turn 'darwin/aarch64' into 'darwin-aarch64'."""
    return platform.replace("/", "-")


def matrix_jar_name(base_stem: str, platform: str) -> str:
    """Build an output filename like 'condensed-data-darwin-aarch64.jar'."""
    return f"{base_stem}-{platform_slug(platform)}.jar"


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def add_common_options(parser: argparse.ArgumentParser) -> None:
    """Add reduction flags shared by both subcommands (extend here)."""
    # Future:
    # parser.add_argument(
    #     "--without-jmc",
    #     action="store_true",
    #     help="Remove org.openjdk.jmc classes (not yet implemented)",
    # )
    pass


def cmd_reduce(args: argparse.Namespace) -> None:
    """Handler for the 'reduce' subcommand."""
    # --list-platforms: informational, no output JAR needed
    if args.list_platforms:
        with zipfile.ZipFile(args.input, "r") as zf:
            platforms = discover_platforms(zf)
        print("Available platforms:")
        for p in platforms:
            print(f"  {p}")
        return

    if args.output is None:
        print("Error: output path is required unless --list-platforms is used", file=sys.stderr)
        sys.exit(1)

    # Collect applicable reductions
    reductions: List[ReductionResult] = []

    with zipfile.ZipFile(args.input, "r") as zf:
        if args.platform:
            available = discover_platforms(zf)
            reductions.append(reduce_platform(zf, args.platform, available))

    if not reductions:
        print("Error: no reduction options specified. Use --platform or see --help.", file=sys.stderr)
        sys.exit(1)

    reduce_jar(args.input, args.output, reductions)


def cmd_matrix(args: argparse.Namespace) -> None:
    """Handler for the 'matrix' subcommand."""
    out_dir = args.output_dir
    os.makedirs(out_dir, exist_ok=True)

    base_stem = os.path.splitext(os.path.basename(args.input))[0]

    with zipfile.ZipFile(args.input, "r") as zf:
        available = discover_platforms(zf)

    # Determine which platforms to build
    if args.platforms:
        selected = [p.strip() for p in args.platforms.split(",")]
        for p in selected:
            if p not in available:
                print(
                    f"Error: unknown platform '{p}'. "
                    f"Available: {', '.join(available)}",
                    file=sys.stderr,
                )
                sys.exit(1)
    else:
        selected = available

    print(f"Generating {len(selected)} platform JARs into {out_dir}/")

    with zipfile.ZipFile(args.input, "r") as zf:
        for platform in selected:
            reductions: List[ReductionResult] = [
                reduce_platform(zf, platform, available),
            ]
            out_path = os.path.join(out_dir, matrix_jar_name(base_stem, platform))
            reduce_jar(args.input, out_path, reductions)

    # Also produce a universal (unreduced) copy
    universal_path = os.path.join(out_dir, f"{base_stem}-universal.jar")
    reduce_jar(args.input, universal_path, [])

    print(f"\nDone. {len(selected) + 1} JARs written to {out_dir}/")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Reduce a fat JAR by stripping unneeded native libs / components.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    # --- reduce ---
    p_reduce = subparsers.add_parser(
        "reduce",
        help="Produce a single reduced JAR",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p_reduce.add_argument("input", help="Input JAR path")
    p_reduce.add_argument("output", nargs="?", default=None, help="Output JAR path")
    p_reduce.add_argument(
        "--platform",
        metavar="OS/ARCH",
        help="Keep only native libraries for this platform (e.g. darwin/aarch64)",
    )
    p_reduce.add_argument(
        "--list-platforms",
        action="store_true",
        help="List available platforms in the JAR and exit",
    )
    add_common_options(p_reduce)
    p_reduce.set_defaults(func=cmd_reduce)

    # --- matrix ---
    p_matrix = subparsers.add_parser(
        "matrix",
        help="Generate a matrix of reduced JARs for every platform",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p_matrix.add_argument("input", help="Input JAR path")
    p_matrix.add_argument("output_dir", help="Output directory for generated JARs")
    p_matrix.add_argument(
        "--platforms",
        metavar="P1,P2,...",
        help="Comma-separated subset of platforms (default: all)",
    )
    add_common_options(p_matrix)
    p_matrix.set_defaults(func=cmd_matrix)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
