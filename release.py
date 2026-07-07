#!/usr/bin/env python3
"""
Release orchestration script for condensed-data / cjfr.

Steps:
  1. Validates the version argument and working tree state.
  2. Bumps pom.xml to the release version via mvn versions:set.
  3. Commits and tags the release.
  4. Builds the universal JAR with Maven (mvn package -DskipTests).
  5. Builds the platform JAR matrix via reduce-jar.py.
  6. Pushes the tag to origin (triggers CI).
  7. Creates a GitHub Release via `gh` and uploads all JARs.
  8. Triggers the GitHub Pages deploy workflow.
  9. Bumps pom.xml to the next SNAPSHOT version and commits.

Usage:
    python3 release.py <version>               # e.g. 0.2.0
    python3 release.py <version> --dry-run     # print what would happen, no changes
    python3 release.py <version> --skip-tests  # skip mvn test during build
    python3 release.py <version> --no-minimal  # skip femtojar/ProGuard minimal variants
"""

import argparse
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).parent.resolve()
UNIVERSAL_JAR = REPO_ROOT / "target" / "condensed-data.jar"
PLATFORM_JARS_DIR = REPO_ROOT / "target" / "platform-jars"
MINIMAL_JARS_DIR = REPO_ROOT / "target" / "platform-jars-minimal"
POM_XML = REPO_ROOT / "pom.xml"
REDUCE_JAR = REPO_ROOT / "reduce-jar.py"


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def run(cmd: list[str], *, dry_run: bool = False, check: bool = True, **kwargs) -> subprocess.CompletedProcess:
    print(f"  $ {' '.join(str(c) for c in cmd)}")
    if dry_run:
        return subprocess.CompletedProcess(cmd, 0, stdout="", stderr="")
    return subprocess.run(cmd, check=check, **kwargs)


def get_current_version() -> str:
    result = subprocess.run(
        ["mvn", "help:evaluate", "-Dexpression=project.version", "-q", "-DforceStdout"],
        capture_output=True, text=True, check=True, cwd=REPO_ROOT,
    )
    return result.stdout.strip()


def next_snapshot(version: str) -> str:
    """Increment the patch segment and append -SNAPSHOT. e.g. 0.2.0 -> 0.2.1-SNAPSHOT."""
    parts = version.split(".")
    parts[-1] = str(int(parts[-1]) + 1)
    return ".".join(parts) + "-SNAPSHOT"


def check_git_clean(dry_run: bool) -> None:
    result = subprocess.run(
        ["git", "status", "--porcelain"], capture_output=True, text=True, check=True, cwd=REPO_ROOT,
    )
    if result.stdout.strip() and not dry_run:
        print("ERROR: Working tree has uncommitted changes. Commit or stash them first.", file=sys.stderr)
        print(result.stdout, file=sys.stderr)
        sys.exit(1)


def check_gh_auth() -> None:
    result = subprocess.run(["gh", "auth", "status"], capture_output=True, cwd=REPO_ROOT)
    if result.returncode != 0:
        print("ERROR: `gh` CLI is not authenticated. Run `gh auth login` first.", file=sys.stderr)
        sys.exit(1)


def set_pom_version(version: str, dry_run: bool) -> None:
    run(
        ["mvn", "-B", "versions:set", f"-DnewVersion={version}", "versions:commit"],
        dry_run=dry_run, cwd=REPO_ROOT,
    )


def git_commit_and_tag(version: str, dry_run: bool) -> None:
    run(["git", "add", "pom.xml"], dry_run=dry_run, cwd=REPO_ROOT)
    run(
        ["git", "commit", "-m", f"Release version {version}"],
        dry_run=dry_run, cwd=REPO_ROOT,
    )
    run(
        ["git", "tag", "-a", f"v{version}", "-m", f"Release v{version}"],
        dry_run=dry_run, cwd=REPO_ROOT,
    )


def build_universal_jar(skip_tests: bool, dry_run: bool) -> None:
    cmd = ["mvn", "-ntp", "-B", "package"]
    if skip_tests:
        cmd.append("-DskipTests")
    run(cmd, dry_run=dry_run, cwd=REPO_ROOT)


def build_platform_jars(with_minimal: bool, dry_run: bool) -> None:
    PLATFORM_JARS_DIR.mkdir(parents=True, exist_ok=True)
    cmd = [
        sys.executable, str(REDUCE_JAR), "matrix",
        str(UNIVERSAL_JAR), str(PLATFORM_JARS_DIR),
    ]
    if with_minimal:
        cmd += ["--with-minimal", "--minimal-output-dir", str(MINIMAL_JARS_DIR)]
    run(cmd, dry_run=dry_run, cwd=REPO_ROOT)


def collect_release_jars(with_minimal: bool) -> list[Path]:
    """Collect all JARs to attach to the GitHub Release."""
    jars = [UNIVERSAL_JAR]
    if PLATFORM_JARS_DIR.exists():
        jars += sorted(PLATFORM_JARS_DIR.glob("*.jar"))
    if with_minimal and MINIMAL_JARS_DIR.exists():
        jars += sorted(MINIMAL_JARS_DIR.glob("*.jar"))
    return jars


def push_tag(version: str, dry_run: bool) -> None:
    run(["git", "push", "origin", "main"], dry_run=dry_run, cwd=REPO_ROOT)
    run(["git", "push", "origin", f"v{version}"], dry_run=dry_run, cwd=REPO_ROOT)


def create_github_release(version: str, jars: list[Path], dry_run: bool) -> None:
    notes = (
        f"## cjfr v{version}\n\n"
        "See [GETTING_STARTED.md](https://parttimenerd.github.io/condensed-data/getting-started/) "
        "for installation instructions.\n\n"
        "### JAR variants\n\n"
        "See [JAR Release Selection](https://parttimenerd.github.io/condensed-data/jar-releases/) "
        "for guidance on which JAR to download.\n\n"
        "| File | Description |\n"
        "|---|---|\n"
        "| `condensed-data.jar` | Universal JAR (all platforms, ~8.7 MB) |\n"
        "| `condensed-data-<platform>.jar` | Platform-specific JAR (~2.1 MB) |\n"
        "| `condensed-data-<platform>-inflaterless.jar` | No `inflate` support (~1.5 MB) |\n"
        "| `condensed-data-<platform>-minimal.jar` | LZ4-only, smallest (~500 KB) |\n"
        "| `condensed-data-<platform>-inflaterless-minimal.jar` | Smallest agent JAR (~440 KB) |\n"
    )

    with tempfile.NamedTemporaryFile(mode="w", suffix=".md", delete=False) as f:
        f.write(notes)
        notes_file = f.name

    try:
        cmd = [
            "gh", "release", "create", f"v{version}",
            "--title", f"v{version}",
            "--notes-file", notes_file,
        ] + [str(j) for j in jars]
        run(cmd, dry_run=dry_run, cwd=REPO_ROOT)
    finally:
        os.unlink(notes_file)


def trigger_pages_deploy(dry_run: bool) -> None:
    run(
        ["gh", "workflow", "run", "pages.yml", "--ref", "main"],
        dry_run=dry_run, cwd=REPO_ROOT,
    )


def bump_to_snapshot(version: str, dry_run: bool) -> None:
    snap = next_snapshot(version)
    print(f"\nBumping to next development version: {snap}")
    set_pom_version(snap, dry_run)
    run(["git", "add", "pom.xml"], dry_run=dry_run, cwd=REPO_ROOT)
    run(
        ["git", "commit", "-m", f"chore: bump to {snap}"],
        dry_run=dry_run, cwd=REPO_ROOT,
    )
    run(["git", "push", "origin", "main"], dry_run=dry_run, cwd=REPO_ROOT)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Release orchestration for condensed-data / cjfr.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("version", help="Release version, e.g. 0.2.0")
    parser.add_argument(
        "--dry-run", action="store_true",
        help="Print all commands without executing them",
    )
    parser.add_argument(
        "--skip-tests", action="store_true",
        help="Pass -DskipTests to Maven build",
    )
    parser.add_argument(
        "--no-minimal", action="store_true",
        help="Skip femtojar/ProGuard minimal JAR variants (faster, but no *-minimal.jar files)",
    )
    args = parser.parse_args()

    version = args.version
    dry_run = args.dry_run
    with_minimal = not args.no_minimal

    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        print(f"ERROR: version must be X.Y.Z, got '{version}'", file=sys.stderr)
        sys.exit(1)

    if dry_run:
        print("=== DRY RUN — no changes will be made ===\n")

    print(f"Releasing cjfr v{version}\n")

    print("Step 1: Checking prerequisites")
    check_git_clean(dry_run)
    check_gh_auth()

    current = get_current_version()
    print(f"  Current version: {current}")

    print(f"\nStep 2: Set release version in pom.xml → {version}")
    set_pom_version(version, dry_run)

    print(f"\nStep 3: Commit and tag v{version}")
    git_commit_and_tag(version, dry_run)

    print(f"\nStep 4: Build universal JAR (skip_tests={args.skip_tests})")
    build_universal_jar(args.skip_tests, dry_run)

    print(f"\nStep 5: Build platform JAR matrix (with_minimal={with_minimal})")
    build_platform_jars(with_minimal, dry_run)

    print(f"\nStep 6: Push tag v{version} to origin")
    push_tag(version, dry_run)

    print(f"\nStep 7: Create GitHub Release v{version}")
    jars = collect_release_jars(with_minimal)
    print(f"  Attaching {len(jars)} JAR(s):")
    for j in jars:
        size = j.stat().st_size // 1024 if j.exists() else 0
        print(f"    {j.name} ({size} KB)")
    create_github_release(version, jars, dry_run)

    print(f"\nStep 8: Trigger GitHub Pages deploy")
    trigger_pages_deploy(dry_run)

    print(f"\nStep 9: Bump to next SNAPSHOT version")
    bump_to_snapshot(version, dry_run)

    print(f"\n✓ Release v{version} complete.")
    print(f"  GitHub Release: https://github.com/parttimenerd/condensed-data/releases/tag/v{version}")
    print(f"  Docs site:      https://parttimenerd.github.io/condensed-data/")


if __name__ == "__main__":
    main()
