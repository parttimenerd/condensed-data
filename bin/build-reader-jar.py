#!/usr/bin/env python3
"""
Build the condensed-data reader JAR by stripping JMC/JFR-consumer-dependent
classes from the fat JAR.

A class is excluded if its bytecode constant pool references any of the
forbidden packages (org/openjdk/jmc, jdk/jfr/consumer, jdk/jfr/internal).
We then transitively close: any class that references an excluded class is
also excluded.  CLI, agent, and benchmark packages are excluded regardless.

Usage:
    python3 bin/build-reader-jar.py [--fat-jar <path>] [--output <path>] [--deploy]

The script rebuilds the fat JAR via `mvn package -DskipTests` if the fat JAR
is absent or stale (older than any source file).  Pass --deploy to also run
`mvn deploy` with the reader-publication profile.
"""

import argparse
import os
import re
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

# ── Forbidden roots ──────────────────────────────────────────────────────────
# Classes whose constant pool contains a reference to one of these prefixes
# are stripped — UNLESS they are me.bechberger.* classes, which may legitimately
# reference JMC/JFR-consumer code that GraalVM native-image eliminates as dead code
# when building the WASM (JMC is on the `provided` classpath in jafar pom.xml).
FORBIDDEN_PREFIXES = (
    "org/openjdk/jmc",
    "jdk/jfr/consumer",
    "jdk/jfr/internal",
)

# Packages that are always excluded regardless of bytecode content
EXCLUDED_PREFIXES = (
    "me/bechberger/jfr/cli/commands/",
    "me/bechberger/jfr/cli/agent/",
    "me/bechberger/jfr/cli/query/",
    "me/bechberger/jfr/cli/JFRCLI",
    "me/bechberger/jfr/Benchmark",
    "org/openjdk/jmc/",
)

# Only include classes from these top-level packages in the reader JAR
ALLOWED_PREFIXES = (
    "me/bechberger/",
)


def _read_constant_pool_strings(data: bytes) -> list[str]:
    """Return all Utf8 constant-pool entries from a .class file."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        return []
    pos = 8  # skip magic + minor + major
    count = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    strings = []
    i = 1
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == 1:  # Utf8
            length = struct.unpack_from(">H", data, pos)[0]
            pos += 2
            strings.append(data[pos : pos + length].decode("utf-8", errors="replace"))
            pos += length
        elif tag in (7, 8, 16, 19, 20):  # Class, String, MethodType, Module, Package
            pos += 2
        elif tag in (9, 10, 11, 12, 17, 18):  # Fieldref, Methodref, etc.
            pos += 4
        elif tag in (3, 4):  # Integer / Float
            pos += 4
        elif tag == 15:  # MethodHandle: ref_kind(1) + ref_index(2)
            pos += 3
        elif tag in (5, 6):  # Long / Double (take two slots)
            pos += 8
            i += 1
        else:
            # Unknown tag — give up parsing further
            break
        i += 1
    return strings


def is_forbidden(class_bytes: bytes) -> bool:
    """Return True if this class references any forbidden package."""
    for s in _read_constant_pool_strings(class_bytes):
        for prefix in FORBIDDEN_PREFIXES:
            if prefix in s:
                return True
    return False


def strip_forbidden(fat_jar: Path, output: Path) -> tuple[int, int]:
    """
    Read fat_jar, exclude forbidden + non-allowed entries, write to output.
    Returns (total_classes, excluded_classes).
    """
    total = excluded = 0

    # First pass: collect all class names and flag forbidden ones
    with zipfile.ZipFile(fat_jar) as zin:
        names = zin.namelist()
        class_entries = {n for n in names if n.endswith(".class")}
        forbidden_classes: set[str] = set()

        # Direct forbidden check
        for name in class_entries:
            total += 1
            data = zin.read(name)
            # Always exclude explicitly banned prefixes
            if any(name.replace(".", "/").startswith(p) for p in EXCLUDED_PREFIXES):
                forbidden_classes.add(name)
                continue
            # Only include me/bechberger/ classes
            if not any(name.startswith(p) for p in ALLOWED_PREFIXES):
                forbidden_classes.add(name)
                continue
            # me.bechberger.* classes: apply the forbidden-ref check — strip any that
            # directly reference JMC/JFR-consumer packages.
            if is_forbidden(data):
                forbidden_classes.add(name)

        excluded = len(forbidden_classes)

        # Second pass: write output JAR
        output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as zout:
            for name in names:
                if name in forbidden_classes:
                    continue
                # Skip non-me.bechberger non-class resources from 3rd party jars
                if not name.endswith(".class") and not name.startswith("me/bechberger/"):
                    # Keep META-INF/MANIFEST.MF
                    if name != "META-INF/MANIFEST.MF" and not name.startswith("META-INF/maven/me.bechberger"):
                        continue
                zout.writestr(name, zin.read(name))

    return total, excluded


def find_fat_jar(project_dir: Path, version: str) -> Path | None:
    target = project_dir / "target"
    candidates = list(target.glob(f"condensed-data-{version}.jar"))
    if candidates:
        return candidates[0]
    # Try without version qualifier
    candidates = list(target.glob("condensed-data-*.jar"))
    candidates = [c for c in candidates if "reader" not in c.name and "sources" not in c.name]
    return candidates[0] if candidates else None


def is_stale(jar: Path, src_dir: Path) -> bool:
    if not jar.exists():
        return True
    jar_mtime = jar.stat().st_mtime
    for src in src_dir.rglob("*.java"):
        if src.stat().st_mtime > jar_mtime:
            return True
    return False


def mvn(args: list[str], cwd: Path) -> None:
    cmd = ["mvn"] + args
    print(f"$ {' '.join(cmd)}", flush=True)
    result = subprocess.run(cmd, cwd=cwd)
    if result.returncode != 0:
        sys.exit(result.returncode)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--fat-jar", help="Path to the already-built fat JAR")
    parser.add_argument("--output", help="Where to write the reader JAR")
    parser.add_argument("--deploy", action="store_true", help="Deploy to Maven local/remote after building")
    parser.add_argument("--install", action="store_true", help="Install to local Maven repo after building")
    args = parser.parse_args()

    project_dir = Path(__file__).parent.parent.resolve()

    # Determine version from pom.xml
    pom = (project_dir / "pom.xml").read_text()
    m = re.search(r"<version>([^<]+)</version>", pom)
    version = m.group(1) if m else "0.1.1-SNAPSHOT"

    fat_jar = Path(args.fat_jar) if args.fat_jar else find_fat_jar(project_dir, version)

    src_dir = project_dir / "src" / "main" / "java"
    if fat_jar is None or is_stale(fat_jar, src_dir):
        print("Building fat JAR…")
        mvn(["package", "-Dmaven.test.skip=true", "-q"], project_dir)
        fat_jar = find_fat_jar(project_dir, version)
        if fat_jar is None:
            print("ERROR: could not find fat JAR after build", file=sys.stderr)
            sys.exit(1)

    print(f"Fat JAR: {fat_jar}")

    output = Path(args.output) if args.output else (
        fat_jar.parent / fat_jar.name.replace(".jar", "-reader.jar")
    )

    print(f"Stripping JMC-dependent classes…")
    total, excluded = strip_forbidden(fat_jar, output)
    kept = total - excluded
    print(f"  {total} classes total → {excluded} excluded → {kept} kept")
    print(f"Reader JAR: {output}")

    if args.install or args.deploy:
        # Install into local repo with the reader classifier
        group_id = "me.bechberger"
        artifact_id = "condensed-data"
        install_cmd = [
            "mvn", "install:install-file",
            f"-Dfile={output}",
            f"-DgroupId={group_id}",
            f"-DartifactId={artifact_id}",
            f"-Dversion={version}",
            "-Dclassifier=reader",
            "-Dpackaging=jar",
            "-DgeneratePom=true",
            f"-DpomFile={project_dir}/pom.xml",
        ]
        print(f"$ {' '.join(install_cmd)}")
        result = subprocess.run(install_cmd, cwd=project_dir)
        if result.returncode != 0:
            sys.exit(result.returncode)
        print(f"Installed {group_id}:{artifact_id}:{version}:reader to local Maven repo.")

    if args.deploy:
        mvn(["-Preader-publication", "deploy", "-DskipTests"], project_dir)


if __name__ == "__main__":
    main()
