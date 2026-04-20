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

    # Recompress with femtojar (ProGuard + zopfli), output jars into a directory
    # Builds femtojar automatically if the CLI jar is not yet present.
    ./reduce-jar.py femtojar target/condensed-data.jar out-dir/
    ./reduce-jar.py femtojar target/condensed-data.jar out-dir/ --skip-proguard
"""

import argparse
import json
import os
import platform as platform_mod
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass, field
from pathlib import PurePosixPath
from typing import List, Optional
from urllib.request import Request, urlopen, urlretrieve

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

NATIVE_LIB_PREFIX = "net/jpountz/util/"
REDUCTION_INFO_PATH = "jar-reduction-info.json"

# Prefixes removed in inflaterless (without-JMC) builds
JMC_PREFIX = "org/openjdk/jmc/"
INFLATERLESS_EXTRA_PREFIXES = [
    "org/owasp/",
]

# ---------------------------------------------------------------------------
# Reduction descriptors – add new reductions here
# ---------------------------------------------------------------------------

@dataclass
class ReductionResult:
    """Tracks what a single reduction step removed."""
    name: str
    description: str
    removed_prefixes: List[str] = field(default_factory=list)
    removed_entries: List[str] = field(default_factory=list)
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
# JMC reduction
# ---------------------------------------------------------------------------

JMC_ANNOTATION = "me/bechberger/jfr/JMCDependent"
APP_CLASS_PREFIX = "me/bechberger/"


def _find_app_classes(zf: zipfile.ZipFile) -> List[str]:
    """Return fully-qualified class names for all app .class files."""
    classes = []
    for entry in zf.namelist():
        if entry.startswith(APP_CLASS_PREFIX) and entry.endswith(".class"):
            # e.g. me/bechberger/jfr/WritingJFRReader.class -> me.bechberger.jfr.WritingJFRReader
            fqcn = entry[: -len(".class")].replace("/", ".")
            classes.append(fqcn)
    return classes


def _detect_jmc_dependent_classes(
    jar_path: str,
    class_names: List[str],
    batch_size: int = 100,
) -> List[str]:
    """Use javap to detect classes annotated with @JMCDependent.

    Returns a list of class entry paths (e.g. 'me/bechberger/jfr/WritingJFRReader.class').
    """
    annotated: List[str] = []
    # Process in batches to avoid command-line length limits
    for i in range(0, len(class_names), batch_size):
        batch = class_names[i : i + batch_size]
        cmd = ["javap", "-verbose", "-cp", jar_path] + batch
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=120,
        )
        output = result.stdout
        # Parse javap output: class headers followed by annotation sections
        current_class = None
        in_annotations = False
        for line in output.splitlines():
            # Match class header: "public class me.bechberger.jfr.WritingJFRReader"
            class_match = re.match(
                r"^(?:public\s+|abstract\s+|final\s+)*"
                r"(?:class|interface|@interface|enum)\s+"
                r"(\S+)",
                line,
            )
            if class_match:
                current_class = class_match.group(1)
                in_annotations = False
                continue
            if "RuntimeInvisibleAnnotations" in line or "RuntimeVisibleAnnotations" in line:
                in_annotations = True
                continue
            if in_annotations and JMC_ANNOTATION in line and current_class:
                entry_path = current_class.replace(".", "/") + ".class"
                annotated.append(entry_path)
                in_annotations = False
                continue
            # A blank or non-indented line usually ends the annotation block
            if in_annotations and line and not line.startswith(" "):
                in_annotations = False
    return annotated


def reduce_jmc(jar_path: str, zf: zipfile.ZipFile) -> ReductionResult:
    """Remove org.openjdk.jmc classes, @JMCDependent annotated classes, and other unnecessary dependencies."""
    app_classes = _find_app_classes(zf)
    annotated = _detect_jmc_dependent_classes(jar_path, app_classes)

    all_prefixes = [JMC_PREFIX] + INFLATERLESS_EXTRA_PREFIXES

    result = ReductionResult(
        name="without-jmc",
        description="Removed org.openjdk.jmc classes, @JMCDependent annotated classes, and extra dependencies",
        removed_prefixes=all_prefixes,
        extra={"annotated_classes_removed": annotated},
    )
    # Add each annotated class as an exact-match entry path
    result.removed_entries = annotated
    return result


# ---------------------------------------------------------------------------
# Core logic
# ---------------------------------------------------------------------------

def should_exclude(entry: str, reductions: List[ReductionResult]) -> bool:
    for r in reductions:
        for prefix in r.removed_prefixes:
            if entry.startswith(prefix):
                return True
        if entry in r.removed_entries:
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
    parser.add_argument(
        "--without-jmc",
        action="store_true",
        help="Remove org.openjdk.jmc classes and @JMCDependent annotated classes",
    )


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
        if args.without_jmc:
            reductions.append(reduce_jmc(args.input, zf))

    if not reductions:
        print("Error: no reduction options specified. Use --platform, --without-jmc, or see --help.", file=sys.stderr)
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

    total = 0
    generated_jars: List[str] = []
    print(f"Generating platform JARs into {out_dir}/")

    with zipfile.ZipFile(args.input, "r") as zf:
        jmc_reduction = reduce_jmc(args.input, zf)
        for platform in selected:
            plat_reduction = reduce_platform(zf, platform, available)

            # Full variant (with JMC)
            out_path = os.path.join(out_dir, matrix_jar_name(base_stem, platform))
            reduce_jar(args.input, out_path, [plat_reduction])
            total += 1
            generated_jars.append(out_path)

            # Inflaterless variant (without JMC)
            out_path_no_jmc = os.path.join(
                out_dir, f"{base_stem}-{platform_slug(platform)}-inflaterless.jar"
            )
            reduce_jar(args.input, out_path_no_jmc, [plat_reduction, jmc_reduction])
            total += 1
            generated_jars.append(out_path_no_jmc)

    # Universal (unreduced) copy
    universal_path = os.path.join(out_dir, f"{base_stem}-universal.jar")
    reduce_jar(args.input, universal_path, [])
    total += 1
    generated_jars.append(universal_path)

    # Universal without JMC
    universal_no_jmc_path = os.path.join(out_dir, f"{base_stem}-universal-inflaterless.jar")
    with zipfile.ZipFile(args.input, "r") as zf:
        jmc_reduction = reduce_jmc(args.input, zf)
    reduce_jar(args.input, universal_no_jmc_path, [jmc_reduction])
    total += 1
    generated_jars.append(universal_no_jmc_path)

    if args.with_minimal:
        minimal_output_dir = args.minimal_output_dir or f"{out_dir.rstrip('/')}-minimal"
        os.makedirs(minimal_output_dir, exist_ok=True)
        cli_jar = _ensure_femtojar_cli()
        print(
            f"\nGenerating minimal variants (zopfli + proguard) into {minimal_output_dir}/"
        )
        failed: List[str] = []
        help_failed: List[str] = []
        minimal_generated = 0

        for input_jar in generated_jars:
            base_name = os.path.splitext(os.path.basename(input_jar))[0]
            output_jar = os.path.join(minimal_output_dir, f"{base_name}-minimal.jar")
            ok = _run_femtojar(
                cli_jar,
                input_jar,
                output_jar,
                "zopfli",
                True,
                CONDENSED_DATA_PROGUARD_OPTIONS,
                args.minimal_verbose,
            )
            if not ok:
                failed.append(output_jar)
                continue

            minimal_generated += 1
            total += 1

            if not args.minimal_no_smoke_test and not _test_jar_help(output_jar):
                help_failed.append(output_jar)

        if failed:
            print(f"\n[matrix] {len(failed)} minimal jar(s) failed to build:", file=sys.stderr)
            for f in failed:
                print(f"  {f}", file=sys.stderr)

        if help_failed:
            print(
                f"\n[matrix] {len(help_failed)} minimal jar(s) failed --help check:",
                file=sys.stderr,
            )
            for f in help_failed:
                print(f"  {f}", file=sys.stderr)

        if failed or help_failed:
            sys.exit(1)

        print(f"Generated {minimal_generated} minimal JARs")

    # ------ test against current-platform JARs ------
    if args.run_tests:
        current = _detect_current_platform()
        current_slug = platform_slug(current)
        print(f"\nRunning tests for current platform ({current}) …")

        test_failed: List[str] = []
        for jar in generated_jars:
            jar_base = os.path.basename(jar)
            # Only test JARs that match the current platform or are universal
            if current_slug not in jar_base and "universal" not in jar_base:
                continue
            inflaterless = "inflaterless" in jar_base
            if not _run_jar_tests(jar, inflaterless):
                test_failed.append(jar)

        if test_failed:
            print(
                f"\n[matrix] {len(test_failed)} jar(s) failed tests:",
                file=sys.stderr,
            )
            for f in test_failed:
                print(f"  {f}", file=sys.stderr)
            sys.exit(1)

    print(f"\nDone. {total} JARs written to {out_dir}/")


# ---------------------------------------------------------------------------
# femtojar subcommand
# ---------------------------------------------------------------------------

# Default ProGuard options for condensed-data (mirrors femtojar CI benchmark)
CONDENSED_DATA_PROGUARD_OPTIONS = [
    "-dontwarn",
    "-keep class **.cli.** { *; }",
]

# femtojar source dir relative to this script
FEMTOJAR_SOURCE_DIR = os.path.join(os.path.dirname(__file__), "femtojar")

# The assembled CLI jar produced by `mvn package` in FEMTOJAR_SOURCE_DIR
FEMTOJAR_CLI_JAR = os.path.join(FEMTOJAR_SOURCE_DIR, "target", "femtojar.jar")

# Cached downloaded CLI jar (from GitHub releases)
FEMTOJAR_CACHE_JAR = os.path.join(
    os.path.expanduser("~"),
    ".cache",
    "condensed-data",
    "femtojar",
    "femtojar-latest.jar",
)
FEMTOJAR_RELEASE_API = "https://api.github.com/repos/parttimenerd/femtojar/releases/latest"


def _download_latest_femtojar_release(target_jar: str) -> str:
    """Download latest femtojar release jar to *target_jar* and return the path."""
    req = Request(FEMTOJAR_RELEASE_API, headers={"Accept": "application/vnd.github+json"})
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        req.add_header("Authorization", f"Bearer {token}")

    with urlopen(req, timeout=30) as response:
        release = json.load(response)

    assets = release.get("assets", [])
    jar_assets = [
        a
        for a in assets
        if a.get("name", "").endswith(".jar")
        and "sources" not in a["name"]
        and "javadoc" not in a["name"]
    ]
    if not jar_assets:
        raise RuntimeError("No downloadable femtojar CLI jar found in latest release assets")

    preferred = next(
        (a for a in jar_assets if a["name"] == "femtojar.jar"),
        next((a for a in jar_assets if "femtojar" in a["name"]), jar_assets[0]),
    )

    os.makedirs(os.path.dirname(target_jar), exist_ok=True)
    download_url = preferred["browser_download_url"]
    print(f"Downloading femtojar CLI release asset {preferred['name']} from {download_url}")
    urlretrieve(download_url, target_jar)
    print(f"Cached femtojar CLI jar at {target_jar}")
    return target_jar


def _ensure_femtojar_cli() -> str:
    """Return path to the femtojar CLI jar from local build, cache, or latest release."""
    if os.path.exists(FEMTOJAR_CLI_JAR):
        return FEMTOJAR_CLI_JAR

    refresh_cache = os.environ.get("FEMTOJAR_REFRESH", "").lower() in {"1", "true", "yes"}
    if os.path.exists(FEMTOJAR_CACHE_JAR) and not refresh_cache:
        print(f"Using cached femtojar CLI jar: {FEMTOJAR_CACHE_JAR}")
        return FEMTOJAR_CACHE_JAR

    if os.path.isdir(FEMTOJAR_SOURCE_DIR):
        print(f"femtojar CLI jar not found, building from {FEMTOJAR_SOURCE_DIR} …")
        result = subprocess.run(
            ["mvn", "install", "-DskipTests", "-q"],
            cwd=FEMTOJAR_SOURCE_DIR,
        )
        if result.returncode != 0:
            print("Error: mvn install failed for femtojar", file=sys.stderr)
            sys.exit(result.returncode)

        result = subprocess.run(
            ["mvn", "package", "-DskipTests", "-q"],
            cwd=FEMTOJAR_SOURCE_DIR,
        )
        if result.returncode != 0:
            print("Error: mvn package failed for femtojar", file=sys.stderr)
            sys.exit(result.returncode)

        if not os.path.exists(FEMTOJAR_CLI_JAR):
            print(f"Error: expected CLI jar not found after build: {FEMTOJAR_CLI_JAR}", file=sys.stderr)
            sys.exit(1)

        print(f"femtojar CLI jar built: {FEMTOJAR_CLI_JAR}")
        return FEMTOJAR_CLI_JAR

    try:
        return _download_latest_femtojar_release(FEMTOJAR_CACHE_JAR)
    except Exception as exc:
        print(
            "Error: failed to download latest femtojar release and no local source build is available",
            file=sys.stderr,
        )
        print(f"Cause: {exc}", file=sys.stderr)
        sys.exit(1)


def _run_femtojar(
    cli_jar: str,
    input_jar: str,
    output_jar: str,
    compression: str,
    proguard: bool,
    proguard_options: List[str],
    verbose: bool,
) -> bool:
    """Run femtojar CLI. Returns True on success."""
    cmd = ["java", "-jar", cli_jar, input_jar, output_jar, "--compression", compression]
    if proguard:
        cmd.append("--proguard")
        for opt in proguard_options:
            cmd += ["--proguard-options", opt]
    if verbose:
        cmd.append("--verbose")

    label = os.path.basename(output_jar)
    print(f"  → {label}")
    result = subprocess.run(cmd, capture_output=not verbose, text=True)
    if result.returncode != 0:
        print(f"Error: femtojar failed for {label}", file=sys.stderr)
        if not verbose and result.stdout:
            print(result.stdout, file=sys.stderr)
        if not verbose and result.stderr:
            print(result.stderr, file=sys.stderr)
        return False
    return True


def _test_jar_help(jar_path: str) -> bool:
    """Run `java -jar <jar> --help` and return True if exit code is 0."""
    result = subprocess.run(
        ["java", "-jar", jar_path, "--help"],
        capture_output=True,
        text=True,
    )
    ok = result.returncode == 0
    if not ok:
        print(f"  FAIL --help check for {os.path.basename(jar_path)}", file=sys.stderr)
        if result.stdout:
            print(result.stdout[:500], file=sys.stderr)
        if result.stderr:
            print(result.stderr[:500], file=sys.stderr)
    return ok


def _detect_current_platform() -> str:
    """Detect the current OS/arch as a platform string like 'darwin/aarch64'."""
    os_name = platform_mod.system().lower()
    machine = platform_mod.machine().lower()
    # Map Python arch names to JVM-style names
    arch_map = {
        "x86_64": "amd64",
        "amd64": "amd64",
        "aarch64": "aarch64",
        "arm64": "aarch64",
    }
    arch = arch_map.get(machine, machine)
    return f"{os_name}/{arch}"


def _run_jar_tests(jar_path: str, inflaterless: bool) -> bool:
    """Run Maven tests against the given JAR.

    Uses system properties ``cjfr.test.jar`` and, when *inflaterless* is True,
    ``cjfr.test.inflaterless`` to configure the test harness.
    Returns True on success.
    """
    jar_abs = os.path.abspath(jar_path)
    label = os.path.basename(jar_path)
    print(f"  Running tests against {label} …")

    project_dir = os.path.dirname(os.path.abspath(__file__))
    sys_props = [f"-Dcjfr.test.jar={jar_abs}"]
    if inflaterless:
        sys_props.append("-Dcjfr.test.inflaterless=true")

    cmd = [
        "mvn", "test", "-pl", ".",
        *sys_props,
        "-q",
    ]
    result = subprocess.run(cmd, cwd=project_dir, capture_output=True, text=True)
    if result.returncode == 0:
        print(f"  {label}: tests PASSED")
        return True
    else:
        print(f"  {label}: tests FAILED", file=sys.stderr)
        if result.stdout:
            # Show last lines of output for diagnostics
            lines = result.stdout.strip().splitlines()
            for line in lines[-30:]:
                print(f"    {line}", file=sys.stderr)
        if result.stderr:
            lines = result.stderr.strip().splitlines()
            for line in lines[-15:]:
                print(f"    {line}", file=sys.stderr)
        return False


def cmd_femtojar(args: argparse.Namespace) -> None:
    """Handler for the 'femtojar' subcommand.

    Produces, for each compression mode (default + zopfli), two JARs:
      <stem>-<mode>.jar           – plain femtojar reencoding
      <stem>-<mode>-proguard.jar  – ProGuard + femtojar reencoding  (unless --skip-proguard)

    Then verifies each produced JAR by running `java -jar <jar> --help`.
    """
    cli_jar = _ensure_femtojar_cli()

    out_dir = args.output_dir
    os.makedirs(out_dir, exist_ok=True)

    base_stem = os.path.splitext(os.path.basename(args.input))[0]
    compression_modes = ["default", "zopfli"]
    proguard_options = CONDENSED_DATA_PROGUARD_OPTIONS

    generated: List[str] = []
    failed: List[str] = []

    for mode in compression_modes:
        # Plain reencoding
        out_plain = os.path.join(out_dir, f"{base_stem}-{mode}.jar")
        print(f"[femtojar] {mode} (no ProGuard):")
        ok = _run_femtojar(cli_jar, args.input, out_plain, mode, False, [], args.verbose)
        if ok:
            generated.append(out_plain)
        else:
            failed.append(out_plain)

        # ProGuard + reencoding
        if not args.skip_proguard:
            out_pg = os.path.join(out_dir, f"{base_stem}-{mode}-proguard.jar")
            print(f"[femtojar] {mode} + ProGuard:")
            ok = _run_femtojar(cli_jar, args.input, out_pg, mode, True, proguard_options, args.verbose)
            if ok:
                generated.append(out_pg)
            else:
                failed.append(out_pg)

    # ------ size table ------
    original_size = os.path.getsize(args.input)
    print(f"\nSize comparison (original: {original_size / 1024:.1f} KB):")
    print(f"  {'JAR':<55} {'size (KB)':>10}  {'%':>6}")
    print(f"  {'-'*55}  {'-'*10}  {'-'*6}")
    for jar in generated:
        size = os.path.getsize(jar)
        pct = 100.0 * size / original_size
        print(f"  {os.path.basename(jar):<55} {size / 1024:>10.1f}  {pct:>6.1f}%")

    # ------ smoke-test ------
    print("\nRunning --help smoke tests …")
    help_failed: List[str] = []
    for jar in generated:
        sys.stdout.write(f"  {os.path.basename(jar)} … ")
        sys.stdout.flush()
        if _test_jar_help(jar):
            print("OK")
        else:
            print("FAIL")
            help_failed.append(jar)

    if failed:
        print(f"\n[femtojar] {len(failed)} jar(s) failed to build:", file=sys.stderr)
        for f in failed:
            print(f"  {f}", file=sys.stderr)

    if help_failed:
        print(f"\n[femtojar] {len(help_failed)} jar(s) failed --help check:", file=sys.stderr)
        for f in help_failed:
            print(f"  {f}", file=sys.stderr)

    if failed or help_failed:
        sys.exit(1)

    print(f"\nDone. {len(generated)} JARs written to {out_dir}/")


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
    p_matrix.add_argument(
        "--with-minimal",
        action="store_true",
        help="Also generate -minimal variants via femtojar (zopfli + proguard)",
    )
    p_matrix.add_argument(
        "--minimal-output-dir",
        help="Output dir for minimal variants (default: <output_dir>-minimal)",
    )
    p_matrix.add_argument(
        "--minimal-no-smoke-test",
        action="store_true",
        help="Skip black-box '--help' smoke tests for generated minimal variants",
    )
    p_matrix.add_argument(
        "--minimal-verbose",
        action="store_true",
        help="Pass --verbose to femtojar while generating minimal variants",
    )
    p_matrix.add_argument(
        "--run-tests",
        action="store_true",
        help="Run Maven tests against JARs matching the current platform (and universal)",
    )
    add_common_options(p_matrix)
    p_matrix.set_defaults(func=cmd_matrix)

    # --- femtojar ---
    p_femtojar = subparsers.add_parser(
        "femtojar",
        help="Reencode with femtojar (default + zopfli, with and without ProGuard)",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p_femtojar.add_argument("input", help="Input JAR path")
    p_femtojar.add_argument("output_dir", help="Directory to write reencoded JARs into")
    p_femtojar.add_argument(
        "--skip-proguard",
        action="store_true",
        help="Skip ProGuard variants (only produce plain reencoded JARs)",
    )
    p_femtojar.add_argument(
        "--verbose",
        action="store_true",
        help="Pass --verbose to femtojar and show its output",
    )
    p_femtojar.set_defaults(func=cmd_femtojar)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
