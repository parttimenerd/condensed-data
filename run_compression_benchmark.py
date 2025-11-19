#!/usr/bin/env python3
"""
Run compression benchmarks for all available compression algorithms.
Runs each compression 3 times and stores results in JSON format.
"""

import subprocess
import re
import json
import sys
import argparse
import statistics
from collections import defaultdict
from datetime import datetime

# All available compression algorithms from the Compression enum
COMPRESSIONS = [
    "NONE",
    "GZIP",
    "LZ4FRAMED",
    "ZLIB",
    "XZ",
    "BZIP2",
    "LZMA",
    "ZSTD",
    "SNAPPY"
]

CONFIGURATION = "reasonable-default"

def parse_benchmark_line(line):
    """Parse a single benchmark output line and extract file result."""
    # Pattern: Benchmarked <filename> with <config> in <time>s, size: <bytes> bytes
    pattern = r'Benchmarked ([\w\-_.]+\.jfr) with [\w\-]+ in ([\d.]+)s, size: ([\d]+) bytes'
    match = re.search(pattern, line)

    if match:
        filename = match.group(1)
        duration = float(match.group(2))
        size = int(match.group(3))
        return {
            'file': filename,
            'duration_seconds': duration,
            'size_bytes': size
        }
    return None

def run_benchmark(compression, filter_regex=None, all_results=None, output_file=None, current_run=1):
    """Run benchmark for a specific compression algorithm."""
    print(f"\n>>> Running benchmark with compression: {compression}")
    cmd = [
        './cjfr', 'benchmark',
        '--configuration', CONFIGURATION,
        '--compression', compression
    ]

    if filter_regex:
        cmd.extend(['--regexp', filter_regex])

    print(f">>> Command: {' '.join(cmd)}")
    print("\n--- Real-time Output ---")
    sys.stdout.flush()

    try:
        # Use Popen to get real-time output
        process = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
            universal_newlines=True
        )

        # Track results for this run
        run_results = []

        # Process output line by line
        for line in process.stdout:
            print(line, end='', flush=True)  # Print immediately

            # Try to parse each line as a benchmark result
            result = parse_benchmark_line(line)
            if result:
                run_results.append(result)
                print(f">>> Parsed: {result['file']} - {result['duration_seconds']:.2f}s, {result['size_bytes']:,} bytes")

                # Update the results structure and save immediately
                if all_results and output_file:
                    # Initialize compression entry if needed
                    if compression not in all_results['compressions']:
                        all_results['compressions'][compression] = []

                    # Find or create the current run entry
                    current_run_data = None
                    for run_data in all_results['compressions'][compression]:
                        if run_data['run'] == current_run:
                            current_run_data = run_data
                            break

                    if current_run_data is None:
                        current_run_data = {'run': current_run, 'results': []}
                        all_results['compressions'][compression].append(current_run_data)

                    # Add the new result
                    current_run_data['results'].append(result)

                    # Save to file immediately
                    save_results(all_results, output_file)

        # Wait for process to complete
        process.wait()

        if process.returncode != 0:
            raise subprocess.CalledProcessError(process.returncode, cmd)

        print("--- End Output ---\n")
        print(f">>> Parsed {len(run_results)} benchmark results")
        return run_results

    except subprocess.CalledProcessError as e:
        print(f"\n!!! Error running benchmark for {compression}: {e}")
        print(f"!!! Command failed with return code: {e.returncode}")
        return None
    except Exception as e:
        print(f"\n!!! Unexpected error running benchmark for {compression}: {e}")
        return None

def save_results(all_results, output_file='compression_benchmark_results.json'):
    """Save results to JSON file."""
    try:
        with open(output_file, 'w') as f:
            json.dump(all_results, f, indent=2)
    except Exception as e:
        print(f"!!! Error saving results: {e}")

def main():
    parser = argparse.ArgumentParser(description='Run compression benchmarks for all available compression algorithms')
    parser.add_argument('--runs', '-r', type=int, default=3,
                        help='Number of runs per compression algorithm (default: 3)')
    parser.add_argument('--output', '-o', default='compression_benchmark_results.json',
                        help='Output JSON file (default: compression_benchmark_results.json)')
    parser.add_argument('--filter-regex', '-f', type=str,
                        help='Regex pattern to filter benchmark files (e.g., "dotty.*" for smaller files)')
    parser.add_argument('--small', '-s', action='store_true',
                        help='Shortcut for --filter-regex "dotty.*" (smaller/faster files)')

    args = parser.parse_args()

    runs = args.runs
    output_file = args.output

    # Handle --small as a shortcut for a common regex pattern
    if args.small:
        filter_regex = "(.*dotty.*|.*all.*).*G1.*"
    else:
        filter_regex = args.filter_regex

    print(f"Starting benchmark with {runs} runs per compression algorithm")
    if filter_regex:
        print(f">>> Using file filter regex: {filter_regex}")
    print(f"Results will be saved to: {output_file}")

    all_results = {
        'timestamp': datetime.now().isoformat(),
        'configuration': CONFIGURATION,
        'runs': runs,
        'regexp': filter_regex,
        'compressions': {}
    }

    for compression in COMPRESSIONS:
        print(f"\n{'='*60}")
        print(f"Benchmarking {compression}")
        if filter_regex:
            print(f"(Filter: {filter_regex})")
        print(f"{'='*60}")

        for run_idx in range(runs):
            print(f"\n>>> Run {run_idx + 1}/{runs} for {compression}...")
            results = run_benchmark(
                compression,
                filter_regex=filter_regex,
                all_results=all_results,
                output_file=output_file,
                current_run=run_idx + 1
            )

            if results is None:
                print(f"!!! Skipping {compression} due to error")
                break

            # Print summary for this run
            total_size = sum(r['size_bytes'] for r in results)
            total_duration = sum(r['duration_seconds'] for r in results)
            print(f">>> Run {run_idx + 1} summary: {len(results)} files, "
                  f"total size: {total_size:,} bytes, total duration: {total_duration:.2f}s")

        # Print summary for this compression if it has results
        if compression in all_results['compressions'] and all_results['compressions'][compression]:
            runs_data = all_results['compressions'][compression]
            total_size = sum(r['size_bytes'] for run in runs_data for r in run['results'])
            total_duration = sum(r['duration_seconds'] for run in runs_data for r in run['results'])
            if runs_data and runs_data[0]['results']:
                avg_size = total_size / (len(runs_data) * len(runs_data[0]['results']))
                avg_duration = total_duration / (len(runs_data) * len(runs_data[0]['results']))
                print(f"\n>>> {compression} complete - Avg size: {avg_size:,.0f} bytes, Avg duration: {avg_duration:.2f}s")

    # Save final results
    print(f"\n{'='*60}")
    save_results(all_results, output_file)
    print(f">>> Final results saved to {output_file}")
    print(f"{'='*60}")

def print_benchmark_table(compression, runs_data, baseline_stats=None):
    """Print a formatted table for a single compression algorithm."""
    print(f"\n{compression} Results:")
    print("-" * 80)
    print(f"{'File':<40} {'Duration (s)':>12} {'Size (bytes)':>15} {'Run':>8}")
    print("-" * 80)

    for run in runs_data:
        for result in run['results']:
            print(f"{result['file']:<40} {result['duration_seconds']:>12.2f} {result['size_bytes']:>15,} {run['run']:>8}")

def calculate_stats(runs_data):
    """Calculate statistics for a compression algorithm."""
    all_durations = []
    all_sizes = []

    for run in runs_data:
        for result in run['results']:
            all_durations.append(result['duration_seconds'])
            all_sizes.append(result['size_bytes'])

    if not all_durations:
        return None

    # Only calculate standard deviation if we have multiple runs
    num_runs = len(runs_data)

    return {
        'avg_duration': statistics.mean(all_durations),
        'std_duration': statistics.stdev(all_durations) if num_runs > 1 and len(all_durations) > 1 else 0.0,
        'avg_size': statistics.mean(all_sizes),
        'std_size': statistics.stdev(all_sizes) if num_runs > 1 and len(all_sizes) > 1 else 0.0,
        'count': len(all_durations),
        'num_runs': num_runs
    }

def main():
    parser = argparse.ArgumentParser(description='Run compression benchmarks for all available compression algorithms')
    parser.add_argument('--runs', '-r', type=int, default=3,
                        help='Number of runs per compression algorithm (default: 3)')
    parser.add_argument('--output', '-o', default='compression_benchmark_results.json',
                        help='Output JSON file (default: compression_benchmark_results.json)')
    parser.add_argument('--filter-regex', '-f', type=str,
                        help='Regex pattern to filter benchmark files (e.g., "dotty.*" for smaller files)')
    parser.add_argument('--small', '-s', action='store_true',
                        help='Shortcut for --filter-regex "dotty.*" (smaller/faster files)')
    parser.add_argument('--baseline', '-b', default='LZ4FRAMED',
                        help='Baseline compression for relative comparison (default: LZ4FRAMED)')

    args = parser.parse_args()

    runs = args.runs
    output_file = args.output
    baseline = args.baseline

    # Handle --small as a shortcut for a common regex pattern
    if args.small:
        filter_regex = "(.*dotty.*|.*all.*).*G1.*"
    else:
        filter_regex = args.filter_regex

    print(f"Starting benchmark with {runs} runs per compression algorithm")
    if filter_regex:
        print(f">>> Using file filter regex: {filter_regex}")
    print(f"Results will be saved to: {output_file}")
    print(f"Baseline for relative comparison: {baseline}")

    all_results = {
        'timestamp': datetime.now().isoformat(),
        'configuration': CONFIGURATION,
        'runs': runs,
        'regexp': filter_regex,
        'baseline': baseline,
        'compressions': {}
    }

    # ...existing code for running benchmarks...
    for compression in COMPRESSIONS:
        print(f"\n{'='*60}")
        print(f"Benchmarking {compression}")
        if filter_regex:
            print(f"(Filter: {filter_regex})")
        print(f"{'='*60}")

        for run_idx in range(runs):
            print(f"\n>>> Run {run_idx + 1}/{runs} for {compression}...")
            results = run_benchmark(
                compression,
                filter_regex=filter_regex,
                all_results=all_results,
                output_file=output_file,
                current_run=run_idx + 1
            )

            if results is None:
                print(f"!!! Skipping {compression} due to error")
                break

            # Print summary for this run
            total_size = sum(r['size_bytes'] for r in results)
            total_duration = sum(r['duration_seconds'] for r in results)
            print(f">>> Run {run_idx + 1} summary: {len(results)} files, "
                  f"total size: {total_size:,} bytes, total duration: {total_duration:.2f}s")

        # Print summary for this compression if it has results
        if compression in all_results['compressions'] and all_results['compressions'][compression]:
            runs_data = all_results['compressions'][compression]
            total_size = sum(r['size_bytes'] for run in runs_data for r in run['results'])
            total_duration = sum(r['duration_seconds'] for run in runs_data for r in run['results'])
            if runs_data and runs_data[0]['results']:
                avg_size = total_size / (len(runs_data) * len(runs_data[0]['results']))
                avg_duration = total_duration / (len(runs_data) * len(runs_data[0]['results']))
                print(f"\n>>> {compression} complete - Avg size: {avg_size:,.0f} bytes, Avg duration: {avg_duration:.2f}s")

    # Save final results
    print(f"\n{'='*60}")
    save_results(all_results, output_file)
    print(f">>> Final results saved to {output_file}")
    print(f"{'='*60}")

    # Calculate statistics for all compressions
    compression_stats = {}
    for compression, runs_data in all_results['compressions'].items():
        if runs_data:
            stats = calculate_stats(runs_data)
            if stats:
                compression_stats[compression] = stats

    # Get baseline statistics
    baseline_stats = compression_stats.get(baseline)

    # Print individual benchmark tables
    print("\n" + "="*80)
    print("INDIVIDUAL BENCHMARK RESULTS")
    print("="*80)
    for compression, runs_data in all_results['compressions'].items():
        if runs_data:
            print_benchmark_table(compression, runs_data)

    # Print comprehensive summary
    print("\n" + "="*100)
    print("COMPREHENSIVE SUMMARY")
    if filter_regex:
        print(f"(Filter: {filter_regex})")
    print(f"Baseline: {baseline}")
    print("="*100)

    # Determine if we should show standard deviation (only when runs > 1)
    show_stddev = runs > 1

    # Header - conditional based on whether we show stddev
    if show_stddev:
        header = f"{'Algorithm':<12} {'Avg Duration':>12} {'±StdDev':>10} {'Rel%':>8} {'Avg Size':>15} {'±StdDev':>12} {'Rel%':>8} {'Files':>8}"
    else:
        header = f"{'Algorithm':<12} {'Avg Duration':>12} {'Rel%':>8} {'Avg Size':>15} {'Rel%':>8} {'Files':>8}"

    print(header)
    print("-" * (100 if show_stddev else 75))

    for compression in COMPRESSIONS:
        if compression in compression_stats:
            stats = compression_stats[compression]

            # Calculate relative values
            rel_duration = (stats['avg_duration'] / baseline_stats['avg_duration'] * 100) if baseline_stats else 100.0
            rel_size = (stats['avg_size'] / baseline_stats['avg_size'] * 100) if baseline_stats else 100.0

            # Format the row - conditional based on whether we show stddev
            if show_stddev:
                row = (f"{compression:<12} "
                       f"{stats['avg_duration']:>11.2f}s "
                       f"±{stats['std_duration']:>8.2f}s "
                       f"{rel_duration:>7.1f}% "
                       f"{stats['avg_size']:>14,.0f} "
                       f"±{stats['std_size']:>10,.0f} "
                       f"{rel_size:>7.1f}% "
                       f"{stats['count']:>8}")
            else:
                row = (f"{compression:<12} "
                       f"{stats['avg_duration']:>11.2f}s "
                       f"{rel_duration:>7.1f}% "
                       f"{stats['avg_size']:>14,.0f} "
                       f"{rel_size:>7.1f}% "
                       f"{stats['count']:>8}")

            # Highlight baseline
            if compression == baseline:
                print(f"→ {row}")
            else:
                print(f"  {row}")
        else:
            print(f"  {compression:<12} {'No results':<70}")

    # Print notes
    print("\n" + "="*(100 if show_stddev else 75))
    print("NOTES:")
    print(f"- Baseline ({baseline}) is marked with →")
    print("- Rel% = Relative percentage compared to baseline (lower is better)")
    print("- Duration: Time to compress all files")
    print("- Size: Total compressed file size")
    print("- Files: Total number of benchmark files processed")
    if show_stddev:
        print("- StdDev: Standard deviation across multiple runs")
    else:
        print("- Standard deviation not shown (single run mode)")

if __name__ == '__main__':
    main()