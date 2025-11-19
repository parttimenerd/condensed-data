#!/usr/bin/env python3
"""
Visualize compression benchmark results.
Creates bar plots showing duration and size relative to a baseline (default: XZ).
"""

import os
import sys
import subprocess
from pathlib import Path

# Configuration
VENV_DIR = Path(__file__).parent / ".venv_visualize"
REQUIRED_PACKAGES = ["matplotlib", "seaborn", "pandas", "numpy"]

def setup_venv():
    """Create virtual environment and install dependencies if needed."""
    if not VENV_DIR.exists():
        print(f"Creating virtual environment in {VENV_DIR}...")
        # Create venv with real-time output
        process = subprocess.Popen(
            [sys.executable, "-m", "venv", str(VENV_DIR)],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1
        )
        for line in process.stdout:
            print(line, end='', flush=True)
        process.wait()
        if process.returncode != 0:
            raise subprocess.CalledProcessError(process.returncode, [sys.executable, "-m", "venv", str(VENV_DIR)])
        print("Virtual environment created.")

        # Determine pip path
        if os.name == 'nt':  # Windows
            pip_path = VENV_DIR / "Scripts" / "pip"
        else:  # Unix-like
            pip_path = VENV_DIR / "bin" / "pip"

        # Install dependencies with real-time output
        print("Installing dependencies...")
        process = subprocess.Popen(
            [str(pip_path), "install"] + REQUIRED_PACKAGES,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1
        )
        for line in process.stdout:
            print(line, end='', flush=True)
        process.wait()
        if process.returncode != 0:
            raise subprocess.CalledProcessError(process.returncode, [str(pip_path), "install"] + REQUIRED_PACKAGES)
        print("Dependencies installed.")
        print()

def run_in_venv():
    """Re-run this script in the virtual environment."""
    if os.name == 'nt':  # Windows
        python_path = VENV_DIR / "Scripts" / "python"
    else:  # Unix-like
        python_path = VENV_DIR / "bin" / "python"

    # Re-execute this script with the venv Python
    os.execv(str(python_path), [str(python_path)] + sys.argv)

# Check if we're running in the venv
if not hasattr(sys, 'real_prefix') and not (hasattr(sys, 'base_prefix') and sys.base_prefix != sys.prefix):
    # Not in a virtual environment
    setup_venv()
    run_in_venv()

# Now we're in the venv, import the required packages
import json
import argparse
from collections import defaultdict
import matplotlib.pyplot as plt
import seaborn as sns
import pandas as pd
import numpy as np

def load_results(filename):
    """Load benchmark results from JSON file."""
    with open(filename, 'r') as f:
        return json.load(f)

def aggregate_results(data):
    """Aggregate results across runs and files."""
    aggregated = {}
    num_runs = data.get('runs', 1)
    show_stddev = num_runs > 1

    for compression, runs in data['compressions'].items():
        if not runs:
            continue

        # Collect all results across runs
        all_durations = []
        all_sizes = []

        # Track individual benchmark files
        benchmark_files = {}

        for run in runs:
            for result in run['results']:
                all_durations.append(result['duration_seconds'])
                all_sizes.append(result['size_bytes'])

                # Track per-file results for grouped charts
                file_name = result['file']
                if file_name not in benchmark_files:
                    benchmark_files[file_name] = {'durations': [], 'sizes': []}
                benchmark_files[file_name]['durations'].append(result['duration_seconds'])
                benchmark_files[file_name]['sizes'].append(result['size_bytes'])

        # Calculate overall statistics
        aggregated[compression] = {
            'avg_duration': np.mean(all_durations),
            'std_duration': np.std(all_durations) if show_stddev and len(all_durations) > 1 else 0.0,
            'avg_size': np.mean(all_sizes),
            'std_size': np.std(all_sizes) if show_stddev and len(all_sizes) > 1 else 0.0,
            'total_duration': np.sum(all_durations),
            'total_size': np.sum(all_sizes),
            'benchmark_files': benchmark_files,
            'num_runs': num_runs,
            'show_stddev': show_stddev
        }

    return aggregated

def create_grouped_benchmark_plots(aggregated, baseline, output_prefix, show_stddev):
    """Create grouped bar charts showing results for each benchmark file."""

    # Get all unique benchmark files
    all_benchmark_files = set()
    for compression, stats in aggregated.items():
        all_benchmark_files.update(stats['benchmark_files'].keys())

    all_benchmark_files = sorted(all_benchmark_files)

    if not all_benchmark_files:
        return

    # Get baseline stats for each benchmark file
    baseline_files = aggregated[baseline]['benchmark_files']

    # Prepare data for grouped charts
    compressions = sorted(aggregated.keys())

    # Create duration grouped chart
    fig, ax = plt.subplots(figsize=(max(12, len(all_benchmark_files) * 2), 8))

    x = np.arange(len(all_benchmark_files))
    width = 0.8 / len(compressions)

    for i, compression in enumerate(compressions):
        durations = []
        duration_stds = []

        for benchmark_file in all_benchmark_files:
            if (benchmark_file in aggregated[compression]['benchmark_files'] and
                benchmark_file in baseline_files):

                file_durations = aggregated[compression]['benchmark_files'][benchmark_file]['durations']
                baseline_duration = np.mean(baseline_files[benchmark_file]['durations'])

                # Calculate relative duration
                avg_duration = np.mean(file_durations)
                rel_duration = (avg_duration / baseline_duration) * 100
                durations.append(rel_duration)

                # Calculate standard deviation if applicable
                if show_stddev and len(file_durations) > 1:
                    std_duration = np.std(file_durations)
                    rel_std = (std_duration / baseline_duration) * 100
                    duration_stds.append(rel_std)
                else:
                    duration_stds.append(0)
            else:
                durations.append(0)
                duration_stds.append(0)

        # Create bars
        if show_stddev:
            bars = ax.bar(x + i * width, durations, width,
                         yerr=duration_stds, capsize=3,
                         label=compression, alpha=0.8)
        else:
            bars = ax.bar(x + i * width, durations, width,
                         label=compression, alpha=0.8)

        # Highlight baseline
        if compression == baseline:
            for bar in bars:
                bar.set_edgecolor('red')
                bar.set_linewidth(2)

    ax.axhline(y=100, color='r', linestyle='--', alpha=0.5, label=f'{baseline} baseline')
    ax.set_ylabel('Duration (% of baseline)', fontsize=12)
    ax.set_xlabel('Benchmark Files', fontsize=12)
    title = f'Duration by Benchmark File (Relative to {baseline})'
    if not show_stddev:
        title += ' - Single Run'
    ax.set_title(title, fontsize=14, fontweight='bold')
    ax.set_xticks(x + width * (len(compressions) - 1) / 2)
    ax.set_xticklabels([f.replace('.jfr', '') for f in all_benchmark_files], rotation=45, ha='right')
    ax.legend(bbox_to_anchor=(1.05, 1), loc='upper left')
    plt.tight_layout()
    plt.savefig(f'{output_prefix}_duration_by_file.png', dpi=300, bbox_inches='tight')
    print(f"Saved duration by file plot to {output_prefix}_duration_by_file.png")

    # Create size grouped chart
    fig, ax = plt.subplots(figsize=(max(12, len(all_benchmark_files) * 2), 8))

    for i, compression in enumerate(compressions):
        sizes = []
        size_stds = []

        for benchmark_file in all_benchmark_files:
            if (benchmark_file in aggregated[compression]['benchmark_files'] and
                benchmark_file in baseline_files):

                file_sizes = aggregated[compression]['benchmark_files'][benchmark_file]['sizes']
                baseline_size = np.mean(baseline_files[benchmark_file]['sizes'])

                # Calculate relative size
                avg_size = np.mean(file_sizes)
                rel_size = (avg_size / baseline_size) * 100
                sizes.append(rel_size)

                # Calculate standard deviation if applicable
                if show_stddev and len(file_sizes) > 1:
                    std_size = np.std(file_sizes)
                    rel_std = (std_size / baseline_size) * 100
                    size_stds.append(rel_std)
                else:
                    size_stds.append(0)
            else:
                sizes.append(0)
                size_stds.append(0)

        # Create bars
        if show_stddev:
            bars = ax.bar(x + i * width, sizes, width,
                         yerr=size_stds, capsize=3,
                         label=compression, alpha=0.8)
        else:
            bars = ax.bar(x + i * width, sizes, width,
                         label=compression, alpha=0.8)

        # Highlight baseline
        if compression == baseline:
            for bar in bars:
                bar.set_edgecolor('red')
                bar.set_linewidth(2)

    ax.axhline(y=100, color='r', linestyle='--', alpha=0.5, label=f'{baseline} baseline')
    ax.set_ylabel('Compressed Size (% of baseline)', fontsize=12)
    ax.set_xlabel('Benchmark Files', fontsize=12)
    title = f'Compressed Size by Benchmark File (Relative to {baseline})'
    if not show_stddev:
        title += ' - Single Run'
    ax.set_title(title, fontsize=14, fontweight='bold')
    ax.set_xticks(x + width * (len(compressions) - 1) / 2)
    ax.set_xticklabels([f.replace('.jfr', '') for f in all_benchmark_files], rotation=45, ha='right')
    ax.legend(bbox_to_anchor=(1.05, 1), loc='upper left')
    plt.tight_layout()
    plt.savefig(f'{output_prefix}_size_by_file.png', dpi=300, bbox_inches='tight')
    print(f"Saved size by file plot to {output_prefix}_size_by_file.png")

def create_relative_plots(aggregated, baseline='XZ', output_prefix='compression_benchmark'):
    """Create bar plots showing relative performance to baseline."""

    if baseline not in aggregated:
        print(f"Error: Baseline {baseline} not found in results")
        return

    baseline_duration = aggregated[baseline]['avg_duration']
    baseline_size = aggregated[baseline]['avg_size']
    show_stddev = aggregated[baseline]['show_stddev']

    # Prepare data for plotting
    compressions = []
    relative_durations = []
    relative_sizes = []
    duration_stds = []
    size_stds = []

    for compression, stats in sorted(aggregated.items()):
        compressions.append(compression)

        # Calculate relative values (as percentage of baseline)
        rel_duration = (stats['avg_duration'] / baseline_duration) * 100
        rel_size = (stats['avg_size'] / baseline_size) * 100

        relative_durations.append(rel_duration)
        relative_sizes.append(rel_size)

        # Calculate relative standard deviations (only if show_stddev)
        if show_stddev:
            duration_stds.append((stats['std_duration'] / baseline_duration) * 100)
            size_stds.append((stats['std_size'] / baseline_size) * 100)
        else:
            duration_stds.append(0)
            size_stds.append(0)

    # Set seaborn style
    sns.set_style("whitegrid")
    sns.set_palette("husl")

    # Create overall duration plot
    fig, ax = plt.subplots(figsize=(12, 6))
    if show_stddev:
        bars = ax.bar(compressions, relative_durations, yerr=duration_stds, capsize=5)
    else:
        bars = ax.bar(compressions, relative_durations)

    # Color the baseline bar differently
    for i, compression in enumerate(compressions):
        if compression == baseline:
            bars[i].set_color('red')
            bars[i].set_alpha(0.7)

    ax.axhline(y=100, color='r', linestyle='--', alpha=0.5, label=f'{baseline} baseline')
    ax.set_ylabel('Duration (% of baseline)', fontsize=12)
    ax.set_xlabel('Compression Algorithm', fontsize=12)
    title = f'Compression Duration Relative to {baseline}\n(Lower is better)'
    if not show_stddev:
        title += ' - Single Run'
    ax.set_title(title, fontsize=14, fontweight='bold')
    ax.legend()
    plt.xticks(rotation=45, ha='right')
    plt.tight_layout()
    plt.savefig(f'{output_prefix}_duration.png', dpi=300)
    print(f"Saved duration plot to {output_prefix}_duration.png")

    # Create overall size plot
    fig, ax = plt.subplots(figsize=(12, 6))
    if show_stddev:
        bars = ax.bar(compressions, relative_sizes, yerr=size_stds, capsize=5)
    else:
        bars = ax.bar(compressions, relative_sizes)

    # Color the baseline bar differently
    for i, compression in enumerate(compressions):
        if compression == baseline:
            bars[i].set_color('red')
            bars[i].set_alpha(0.7)

    ax.axhline(y=100, color='r', linestyle='--', alpha=0.5, label=f'{baseline} baseline')
    ax.set_ylabel('Compressed Size (% of baseline)', fontsize=12)
    ax.set_xlabel('Compression Algorithm', fontsize=12)
    title = f'Compressed Size Relative to {baseline}\n(Lower is better)'
    if not show_stddev:
        title += ' - Single Run'
    ax.set_title(title, fontsize=14, fontweight='bold')
    ax.legend()
    plt.xticks(rotation=45, ha='right')
    plt.tight_layout()
    plt.savefig(f'{output_prefix}_size.png', dpi=300)
    print(f"Saved size plot to {output_prefix}_size.png")

    # Create grouped bar chart per benchmark file
    create_grouped_benchmark_plots(aggregated, baseline, output_prefix, show_stddev)

    # Create combined plot
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(16, 6))

    # Duration subplot
    if show_stddev:
        bars1 = ax1.bar(compressions, relative_durations, yerr=duration_stds, capsize=5)
    else:
        bars1 = ax1.bar(compressions, relative_durations)
    for i, compression in enumerate(compressions):
        if compression == baseline:
            bars1[i].set_color('red')
            bars1[i].set_alpha(0.7)
    ax1.axhline(y=100, color='r', linestyle='--', alpha=0.5, label=f'{baseline} baseline')
    ax1.set_ylabel('Duration (% of baseline)', fontsize=12)
    ax1.set_xlabel('Compression Algorithm', fontsize=12)
    ax1.set_title('Compression Duration', fontsize=12, fontweight='bold')
    ax1.legend()
    ax1.tick_params(axis='x', rotation=45)
    plt.setp(ax1.xaxis.get_majorticklabels(), rotation=45, ha='right')

    # Size subplot
    if show_stddev:
        bars2 = ax2.bar(compressions, relative_sizes, yerr=size_stds, capsize=5)
    else:
        bars2 = ax2.bar(compressions, relative_sizes)
    for i, compression in enumerate(compressions):
        if compression == baseline:
            bars2[i].set_color('red')
            bars2[i].set_alpha(0.7)
    ax2.axhline(y=100, color='r', linestyle='--', alpha=0.5, label=f'{baseline} baseline')
    ax2.set_ylabel('Compressed Size (% of baseline)', fontsize=12)
    ax2.set_xlabel('Compression Algorithm', fontsize=12)
    ax2.set_title('Compressed Size', fontsize=12, fontweight='bold')
    ax2.legend()
    ax2.tick_params(axis='x', rotation=45)
    plt.setp(ax2.xaxis.get_majorticklabels(), rotation=45, ha='right')

    title = f'Compression Benchmark Results Relative to {baseline}\n(Lower is better)'
    if not show_stddev:
        title += ' - Single Run'
    fig.suptitle(title, fontsize=14, fontweight='bold')
    plt.tight_layout()
    plt.savefig(f'{output_prefix}_combined.png', dpi=300)
    print(f"Saved combined plot to {output_prefix}_combined.png")

    # Print table
    print("\nRelative Performance Table:")
    if show_stddev:
        print(f"{'Compression':<15} {'Duration %':>12} {'±StdDev':>10} {'Size %':>12} {'±StdDev':>10}")
        print("-" * 65)
        for i, compression in enumerate(compressions):
            print(f"{compression:<15} {relative_durations[i]:>11.1f}% {duration_stds[i]:>9.1f}% {relative_sizes[i]:>11.1f}% {size_stds[i]:>9.1f}%")
    else:
        print(f"{'Compression':<15} {'Duration %':>12} {'Size %':>12}")
        print("-" * 40)
        for i, compression in enumerate(compressions):
            print(f"{compression:<15} {relative_durations[i]:>11.1f}% {relative_sizes[i]:>11.1f}%")

def main():
    parser = argparse.ArgumentParser(description='Visualize compression benchmark results')
    parser.add_argument('--input', '-i', default='compression_benchmark_results.json',
                        help='Input JSON file with benchmark results')
    parser.add_argument('--baseline', '-b', default='XZ',
                        help='Baseline compression algorithm for comparison')
    parser.add_argument('--output', '-o', default='compression_benchmark',
                        help='Output file prefix for plots')

    args = parser.parse_args()

    try:
        data = load_results(args.input)
    except FileNotFoundError:
        print(f"Error: Could not find input file {args.input}")
        sys.exit(1)

    aggregated = aggregate_results(data)

    if not aggregated:
        print("Error: No results found in input file")
        sys.exit(1)

    create_relative_plots(aggregated, baseline=args.baseline, output_prefix=args.output)

if __name__ == '__main__':
    main()