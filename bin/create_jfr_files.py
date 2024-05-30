#! /usr/bin/env python3

"""
This script runs the renaissance benchmark suite and creates JFR files
for each GC algorithm and JFR config file.
"""

import os
import shutil
import subprocess
import time
from pathlib import Path
from typing import List

BENCHMARK_FOLDER = Path(__file__).parent.parent / "benchmark"
RENAISSANCE_JAR = BENCHMARK_FOLDER / "renaissance-gpl-0.15.0.jar"

JFC_FILES = list(BENCHMARK_FOLDER.glob("*.jfc"))
TIMING_FILE = BENCHMARK_FOLDER / "benchmark_times.txt"

SUPPORTED_GC_ALGORITHMS = ["G1", "SerialGC", "ParallelGC", "ZGC"]

# these benchmarks cause to many exceptions to be thrown
# or aren't usable with one of the GCs (SerialGC doesn't like akka-uct)
EXCLUDED_BENCHMARKS = ["finagle-chirper", "finagle-http", "akka-uct"]


def get_all_benchmarks() -> List[str]:
    return subprocess.check_output(["java", "-jar", str(RENAISSANCE_JAR), "--raw-list"], cwd=BENCHMARK_FOLDER).decode().splitlines()


def get_supported_benchmarks() -> List[str]:
    return [b for b in get_all_benchmarks() if b not in EXCLUDED_BENCHMARKS]


def get_compressed_size(jfr_file: Path) -> int:
    # compress with zip and high compression level, return size
    # throw away resulting file
    zip_file = jfr_file.with_suffix(".zip")
    subprocess.run(["zip", "-9", zip_file, jfr_file], check=True)
    compressed_size = zip_file.stat().st_size
    zip_file.unlink()
    return compressed_size


def record_timing(jfr_file: Path, jfc_file: Path, gc_algorithm: str, time: float):
    # timing file has format: file,time
    # we first read it into a dictionary
    # then add the new time
    # and write the dictionary back to the file
    timings = {}
    if TIMING_FILE.exists():
        try:
            with open(TIMING_FILE, "r") as f:
                for line in f:
                    file, *parts = line.strip().split(",")
                    assert len(parts) == 4
                    timings[file] = parts
        except Exception as e:
            print("Error reading timing file: " + str(e))
            os.remove(TIMING_FILE)
            timings = {}

    timings[jfr_file.name] = (gc_algorithm, jfc_file.stem, time, get_compressed_size(jfr_file))

    with open(TIMING_FILE, "w") as f:
        for file, parts in timings.items():
            f.write(f"{file},{','.join(map(str, parts))}\n")



def name_jfr_file(benchmark_name: str, jfc_file: Path, gc_algorithm: str):
    return f"{benchmark_name}_{jfc_file.stem}_{gc_algorithm}.jfr"


def jvm_gc_option(gc_algorithm: str) -> str:
    return "-XX:+Use" + gc_algorithm + ("GC" if not gc_algorithm.endswith("GC") else "")


def run_benchmark(benchmark: str, benchmark_name: str, jfc_file: Path, gc_algorithm: str, force_gc: bool = True):
    jfr_file = name_jfr_file(benchmark_name, jfc_file, gc_algorithm)
    jfr_file_path = BENCHMARK_FOLDER / jfr_file

    print(f"Running benchmark {benchmark_name} for {gc_algorithm} with {jfc_file.name}")
    start_time = time.time()
    jfr_flag = f"-XX:StartFlightRecording=filename={jfr_file_path},settings={jfc_file}"
    args = ["java", jvm_gc_option(gc_algorithm), "-XX:+UnlockDiagnosticVMOptions",
            jfr_flag, "-XX:+DebugNonSafepoints", "-jar", str(RENAISSANCE_JAR)]
    if benchmark != "all":
        args.append(benchmark)
    else:
        args.extend(get_supported_benchmarks())
    if not force_gc:
        args.append("--no-forced-gc")
    subprocess.run(args,
                   cwd=BENCHMARK_FOLDER, check=True,
                  )
    end_time = time.time()
    print(f"Finished benchmark for {gc_algorithm} with {jfc_file.name} in {end_time - start_time:.2f} seconds")
    # delete launcher-* folders
    for folder in BENCHMARK_FOLDER.glob("launcher-*"):
        shutil.rmtree(folder)

    record_timing(jfr_file_path, jfc_file, gc_algorithm, end_time - start_time)


def main():
    if not RENAISSANCE_JAR.exists():
        # download renaissance jar to renaissance file
        print("Downloading renaissance jar")
        url = "https://github.com/renaissance-benchmarks/renaissance/releases/download/v0.15.0/renaissance-gpl-0.15.0.jar"
        subprocess.run(["wget", url, "-O", str(RENAISSANCE_JAR)], check=True)
        print("Done")
    for jfc_file in JFC_FILES:
        for gc_algorithm in SUPPORTED_GC_ALGORITHMS:
            for benchmark in ["dotty", "all"]:
                if benchmark == "all" and jfc_file.stem == "gc_details" and gc_algorithm == "G1":
                    continue
                run_benchmark(benchmark, "renaissance-" + benchmark, jfc_file, gc_algorithm, force_gc=False)

    for benchmark in ["dotty", "all"]:
        run_benchmark(benchmark, "renaissance-" + benchmark, Path("default.jfc"), "G1", force_gc=False)

if __name__ == "__main__":
    main()