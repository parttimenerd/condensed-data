#!/usr/bin/env python3
"""
Reduce a fat JAR by removing JMC-dependent classes, or reencode with femtojar.

Usage examples:
    # Remove JMC classes
    ./reduce-jar.py reduce input.jar output.jar --without-jmc

    # Recompress with femtojar (ProGuard + zopfli), output jars into a directory
    # Builds femtojar automatically if the CLI jar is not yet present.
    ./reduce-jar.py femtojar target/condensed-data.jar out-dir/
    ./reduce-jar.py femtojar target/condensed-data.jar out-dir/ --skip-proguard
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass, field
from pathlib import PurePosixPath
from typing import List, Optional

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

REDUCTION_INFO_PATH = "jar-reduction-info.json"

# Prefixes removed in inflaterless (without-JMC) builds
JMC_PREFIX = "org/openjdk/jmc/"
INFLATERLESS_EXTRA_PREFIXES = [
    "org/owasp/",
    "META-INF/maven/",
    "org/jetbrains/",
    "org/intellij/",
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
    if args.output is None:
        print("Error: output path is required", file=sys.stderr)
        sys.exit(1)

    # Collect applicable reductions
    reductions: List[ReductionResult] = []

    with zipfile.ZipFile(args.input, "r") as zf:
        if args.without_jmc:
            reductions.append(reduce_jmc(args.input, zf))

    if not reductions:
        print("Error: no reduction options specified. Use --without-jmc or see --help.", file=sys.stderr)
        sys.exit(1)

    reduce_jar(args.input, args.output, reductions)

    # Apply femtojar: on by default when --without-jmc, opt-out with --no-femtojar
    use_femtojar = (args.without_jmc and not args.no_femtojar) or (args.femtojar and not args.no_femtojar)
    use_proguard = args.femtojar_proguard and not args.no_femtojar_proguard
    if use_femtojar:
        print(f"\nApplying femtojar compression to {os.path.basename(args.output)} …")
        cli_jar = _ensure_femtojar_cli()
        ok = _run_femtojar(
            cli_jar,
            args.output,
            args.output,
            args.femtojar_compression,
            use_proguard,
            CONDENSED_DATA_PROGUARD_OPTIONS if use_proguard else [],
            args.femtojar_verbose,
            source_jar=args.input,
        )
        if not ok:
            print(f"Error: femtojar compression failed", file=sys.stderr)
            sys.exit(1)


# ---------------------------------------------------------------------------
# femtojar subcommand
# ---------------------------------------------------------------------------

# Default ProGuard options for condensed-data (mirrors femtojar CI benchmark)
CONDENSED_DATA_PROGUARD_OPTIONS = [
    "-dontwarn",
    "-keep class **.cli.** { *; }",
    # Preserve Launcher-Agent-Class and Agent-Class entry points (only referenced
    # from META-INF/MANIFEST.MF, not from code, so ProGuard cannot see them).
    "-keep class me.bechberger.jfr.cli.agent.ModuleOpenerAgent { *; }",
    "-keep class me.bechberger.jfr.cli.agent.Agent { *; }",
    # Preserve the Record attribute so Class.getRecordComponents() works at runtime.
    # Configuration uses it to copy itself with one field changed (withFieldValue).
    "-keepattributes Record,RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,Signature,InnerClasses,EnclosingMethod",
    # Targeted full-keeps for packages reflected on by field/record-component name.
    # ReadStructUtil walks declared fields by name; StructType holds them as records;
    # the Compression enum loads codec factories via Class.forName(FQCN).
    "-keep class me.bechberger.condensed.types.** { *; }",
    "-keep class me.bechberger.condensed.codec.** { *; }",
    "-keep class me.bechberger.condensed.Universe* { *; }",
    "-keep class me.bechberger.condensed.ReadStruct { *; }",
    "-keep class me.bechberger.condensed.ReadList { *; }",
    "-keep class me.bechberger.condensed.Message$* { *; }",
    "-keep class me.bechberger.condensed.CJFRFooter* { *; }",
    # JFR-side reflective targets. CombinerSpec/JFREventCombiner/JFRReduction read
    # record component names; ReducedJFRTypes maps fields by name; JFRHashConfig
    # wrappers are constructed reflectively from raw RecordedObjects.
    "-keep class me.bechberger.jfr.Configuration { *; }",
    "-keep class me.bechberger.jfr.CombinerSpec* { *; }",
    "-keep class me.bechberger.jfr.JFREventCombiner* { *; }",
    "-keep class me.bechberger.jfr.JFREventTypedValueCombiner* { *; }",
    "-keep class me.bechberger.jfr.JFRReduction* { *; }",
    "-keep class me.bechberger.jfr.JFRHashConfig* { *; }",
    "-keep class me.bechberger.jfr.ReducedJFRTypes* { *; }",
    "-keep class me.bechberger.jfr.UnsafeRecordedObjectAccessor* { *; }",
    # For everything else in condensed/jfr, keep names so reflection-by-classname
    # still works, but allow ProGuard to shrink unreachable methods/classes.
    "-keep,allowshrinking,allowoptimization class me.bechberger.condensed.** { *; }",
    "-keep,allowshrinking,allowoptimization class me.bechberger.jfr.** { *; }",
    # femtolz4 loads its compressor implementations via Class.forName.
    "-keep class me.bechberger.femtolz4.** { *; }",
]

# femtojar source dir relative to this script
FEMTOJAR_SOURCE_DIR = os.path.join(os.path.dirname(__file__), "femtojar")

# The assembled CLI jar produced by `mvn package` in FEMTOJAR_SOURCE_DIR
FEMTOJAR_CLI_JAR = os.path.join(FEMTOJAR_SOURCE_DIR, "target", "femtojar.jar")

# Cached built CLI jar
FEMTOJAR_CACHE_DIR = os.path.join(
    os.path.expanduser("~"),
    ".cache",
    "condensed-data",
    "femtojar",
)
FEMTOJAR_CACHE_JAR = os.path.join(FEMTOJAR_CACHE_DIR, "femtojar-latest.jar")
FEMTOJAR_REPO_URL = "https://github.com/parttimenerd/femtojar.git"


def _clone_and_build_femtojar(target_jar: str) -> str:
    """Clone the femtojar repo, build the CLI fat jar, and cache it at *target_jar*."""
    cache_dir = os.path.dirname(target_jar)
    clone_dir = os.path.join(cache_dir, "femtojar-src")

    # Shallow-clone (or pull latest) the repo
    if os.path.isdir(os.path.join(clone_dir, ".git")):
        print(f"Updating femtojar source in {clone_dir} …")
        subprocess.run(["git", "pull", "--ff-only", "-q"], cwd=clone_dir, check=True)
    else:
        os.makedirs(cache_dir, exist_ok=True)
        if os.path.isdir(clone_dir):
            shutil.rmtree(clone_dir)
        print(f"Cloning femtojar from {FEMTOJAR_REPO_URL} …")
        subprocess.run(
            ["git", "clone", "--depth", "1", FEMTOJAR_REPO_URL, clone_dir],
            check=True,
        )

    print("Building femtojar CLI jar …")
    result = subprocess.run(
        ["mvn", "package", "-DskipTests", "-q"],
        cwd=clone_dir,
    )
    if result.returncode != 0:
        raise RuntimeError("mvn package failed for femtojar")

    built_jar = os.path.join(clone_dir, "target", "femtojar.jar")
    if not os.path.exists(built_jar):
        raise RuntimeError(f"Expected CLI jar not found after build: {built_jar}")

    shutil.copy2(built_jar, target_jar)
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
        return _clone_and_build_femtojar(FEMTOJAR_CACHE_JAR)
    except Exception as exc:
        print(
            "Error: failed to build femtojar from source and no local build is available",
            file=sys.stderr,
        )
        print(f"Cause: {exc}", file=sys.stderr)
        sys.exit(1)


# ---------------------------------------------------------------------------
# femtocli-minimal classifier swap
# ---------------------------------------------------------------------------

FEMTOCLI_GROUP_PATH = "me/bechberger/util/femtocli"
FEMTOCLI_SOURCE_DIR = os.path.join(os.path.dirname(__file__), "femtocli")


def _femtocli_version() -> str:
    """Read the femtocli dependency version from pom.xml so this script never
    drifts from the actual dependency when it is bumped."""
    pom = os.path.join(os.path.dirname(__file__), "pom.xml")
    try:
        with open(pom, encoding="utf-8") as f:
            content = f.read()
    except OSError:
        return "0.4.1"
    m = re.search(
        r"<artifactId>femtocli</artifactId>\s*<version>([^<]+)</version>", content
    )
    return m.group(1).strip() if m else "0.4.1"


FEMTOCLI_VERSION = _femtocli_version()
FEMTOCLI_MINIMAL_M2_JAR = os.path.join(
    os.path.expanduser("~"),
    ".m2",
    "repository",
    FEMTOCLI_GROUP_PATH,
    FEMTOCLI_VERSION,
    f"femtocli-{FEMTOCLI_VERSION}-minimal.jar",
)
# Class-file prefix that identifies femtocli runtime classes inside a fat jar
FEMTOCLI_CLASS_PREFIX = "me/bechberger/femtocli/"


def _ensure_femtocli_minimal_jar() -> str:
    """Return the path to femtocli-<version>-minimal.jar, building locally if needed."""
    if os.path.exists(FEMTOCLI_MINIMAL_M2_JAR):
        return FEMTOCLI_MINIMAL_M2_JAR

    if not os.path.isdir(FEMTOCLI_SOURCE_DIR):
        print(
            f"Error: femtocli minimal jar not found at {FEMTOCLI_MINIMAL_M2_JAR} "
            f"and no local source at {FEMTOCLI_SOURCE_DIR}",
            file=sys.stderr,
        )
        sys.exit(1)

    print(f"femtocli minimal jar not in ~/.m2; building from {FEMTOCLI_SOURCE_DIR} …")
    result = subprocess.run(
        ["mvn", "install", "-Pminimal", "-DskipTests", "-q"],
        cwd=FEMTOCLI_SOURCE_DIR,
    )
    if result.returncode != 0:
        print("Error: mvn install -Pminimal failed for femtocli", file=sys.stderr)
        sys.exit(result.returncode)

    if not os.path.exists(FEMTOCLI_MINIMAL_M2_JAR):
        print(
            f"Error: expected femtocli minimal jar not found after build: "
            f"{FEMTOCLI_MINIMAL_M2_JAR}",
            file=sys.stderr,
        )
        sys.exit(1)
    return FEMTOCLI_MINIMAL_M2_JAR


def _swap_femtocli(input_jar: str, output_jar: str, minimal_jar: str) -> None:
    """Replace all me/bechberger/femtocli/** entries in *input_jar* with the
    contents of *minimal_jar*, writing the result to *output_jar*."""
    with zipfile.ZipFile(minimal_jar, "r") as mz:
        minimal_entries = {
            name: mz.read(name)
            for name in mz.namelist()
            if name.startswith(FEMTOCLI_CLASS_PREFIX) and not name.endswith("/")
        }

    with zipfile.ZipFile(input_jar, "r") as zf_in:
        with zipfile.ZipFile(output_jar, "w", compression=zipfile.ZIP_DEFLATED) as zf_out:
            written: set = set()
            for item in zf_in.infolist():
                if item.filename.startswith(FEMTOCLI_CLASS_PREFIX):
                    continue  # drop original femtocli classes; replace below
                zf_out.writestr(item, zf_in.read(item.filename))
                written.add(item.filename)
            for name, data in minimal_entries.items():
                if name in written:
                    continue
                zf_out.writestr(name, data)


# Classes that must remain as real (unbundled) zip entries in femtojar output so
# the JVM instrumentation loader can find them before the femtojar blob loader
# starts up (Launcher-Agent-Class is loaded before main() runs).
_FEMTOJAR_UNBUNDLED_CLASSES = [
    "me/bechberger/jfr/cli/agent/ModuleOpenerAgent.class",
]


def _inject_unbundled_classes(source_jar: str, output_jar: str) -> None:
    """Inject classes from *source_jar* as real zip entries in *output_jar*.

    Femtojar bundles all .class files into a single compressed blob that is
    inaccessible until main() runs.  Launcher-Agent-Class entries in
    MANIFEST.MF are loaded by the JVM instrumentation system *before* main(),
    so they must exist as normal zip entries.  This function extracts each
    class listed in _FEMTOJAR_UNBUNDLED_CLASSES from the pre-femtojar JAR and
    adds it to the femtojar output without disturbing anything else.
    """
    classes_to_inject: dict[str, bytes] = {}
    with zipfile.ZipFile(source_jar, "r") as zf_src:
        for cls_path in _FEMTOJAR_UNBUNDLED_CLASSES:
            if cls_path in zf_src.namelist():
                classes_to_inject[cls_path] = zf_src.read(cls_path)

    if not classes_to_inject:
        return

    # Rewrite output_jar in-place: copy all existing entries then append new ones.
    tmp = output_jar + ".tmp"
    with zipfile.ZipFile(output_jar, "r") as zf_in, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zf_out:
        existing = set(zf_in.namelist())
        for info in zf_in.infolist():
            zf_out.writestr(info, zf_in.read(info.filename))
        for cls_path, data in classes_to_inject.items():
            if cls_path not in existing:
                zf_out.writestr(cls_path, data)
    os.replace(tmp, output_jar)


def _run_femtojar(
    cli_jar: str,
    input_jar: str,
    output_jar: str,
    compression: str,
    proguard: bool,
    proguard_options: List[str],
    verbose: bool,
    source_jar: Optional[str] = None,
) -> bool:
    """Run femtojar CLI. Returns True on success.

    *source_jar* is the pre-femtojar JAR from which Launcher-Agent-Class
    entries are extracted and re-injected as real zip entries.  Defaults to
    *input_jar*; pass explicitly when input_jar == output_jar (in-place mode).
    """
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
    _inject_unbundled_classes(source_jar or input_jar, output_jar)
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
        description="Reduce a fat JAR by stripping JMC classes, or reencode with femtojar.",
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
        "--femtojar",
        action="store_true",
        default=None,
        help="Apply femtojar compression to the reduced JAR (default: on when --without-jmc is used)",
    )
    p_reduce.add_argument(
        "--no-femtojar",
        action="store_true",
        help="Disable femtojar compression even when --without-jmc is used",
    )
    p_reduce.add_argument(
        "--femtojar-compression",
        choices=["default", "zopfli"],
        default="zopfli",
        help="Compression algorithm for femtojar (default: zopfli)",
    )
    p_reduce.add_argument(
        "--femtojar-proguard",
        action="store_true",
        default=True,
        help="Apply ProGuard optimization with femtojar (default: on)",
    )
    p_reduce.add_argument(
        "--no-femtojar-proguard",
        action="store_true",
        help="Disable ProGuard when running femtojar",
    )
    p_reduce.add_argument(
        "--femtojar-verbose",
        action="store_true",
        help="Show femtojar verbose output",
    )
    add_common_options(p_reduce)
    p_reduce.set_defaults(func=cmd_reduce)

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
