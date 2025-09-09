#!/usr/bin/python3

"""
A script to update the help messages in the README file.
It runs commands that start with ">" in code blocks in the README
and replaces the rest of the code block with the command's output.

Options -y (not ask user), else ask user with
diff README.md README.new.md --side-by-side --suppress-common-lines
whether to accept the changes.

Commands like
> java -javaagent:target/condensed-data.jar=("[a-zA-Z-]+")|"[a-zA-Z- ]+"
should be appended with " h" when running and ignore the error output

run "mvn package -Dmaven.test.skip=true" before the running of commands,
but don't do it if the option --no-build is given.
"""

import argparse
import re
import subprocess
import sys
import os
from pathlib import Path

def run_command(cmd, ignore_stderr=False):
    """Run a command and return its output"""
    try:
        if ignore_stderr:
            result = subprocess.run(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        else:
            result = subprocess.run(cmd, shell=True, capture_output=True, text=True)
        return result.stdout.rstrip()
    except Exception as e:
        return f"Error running command: {e}"

def should_append_h_and_ignore_stderr(cmd):
    """Check if command should have 'h' appended and stderr ignored"""
    # Pattern for javaagent commands with quoted arguments
    pattern = r'java -javaagent:target/condensed-data\.jar=("[a-zA-Z-]+"|"[a-zA-Z- ]+")'
    return bool(re.search(pattern, cmd))

def process_command(cmd):
    """Process a command, potentially modifying it based on patterns"""
    cmd = cmd.strip()

    # Remove leading ">" if present
    if cmd.startswith(">"):
        cmd = cmd[1:].strip()

    ignore_stderr = False

    # Check if we need to append "h" and ignore stderr
    if should_append_h_and_ignore_stderr(cmd):
        # Append " h" to the command
        cmd += " h"
        ignore_stderr = True

    return cmd, ignore_stderr

def update_readme():
    """Update the README.md file with fresh command outputs"""
    readme_path = Path("README.md")
    if not readme_path.exists():
        print("README.md not found in current directory")
        return False

    with open(readme_path, 'r') as f:
        content = f.read()

    # Pattern to match code blocks
    code_block_pattern = r'```(?:shell)?\n(.*?)```'

    def replace_code_block(match):
        block_content = match.group(1)
        lines = block_content.split('\n')

        # Find the first line that starts with ">"
        command_line = None
        command_line_idx = -1
        for i, line in enumerate(lines):
            if line.strip().startswith('>'):
                command_line = line.strip()
                command_line_idx = i
                break

        if command_line is None:
            # No command found, return original block
            return match.group(0)

        # Process the command
        cmd, ignore_stderr = process_command(command_line)

        print(f"Running: {cmd}")
        output = run_command(cmd, ignore_stderr)

        # Build new block content
        new_lines = [command_line]
        if output:
            new_lines.append(output)

        new_block = '```shell\n' + '\n'.join(new_lines) + '\n```'
        return new_block

    # Replace all code blocks
    new_content = re.sub(code_block_pattern, replace_code_block, content, flags=re.DOTALL)

    return new_content

def show_diff_and_confirm(original_file, new_content):
    """Show diff and ask for confirmation"""
    # Write new content to temporary file
    with open("README.new.md", 'w') as f:
        f.write(new_content)

    # Show diff
    diff_cmd = "diff -u README.md README.new.md"
    print("Showing differences:")
    subprocess.run(diff_cmd, shell=True)

    # Ask for confirmation
    response = input("\nAccept these changes? (y/N): ").strip().lower()
    return response in ['y', 'yes']

def main():
    parser = argparse.ArgumentParser(description="Update help messages in README.md")
    parser.add_argument('-y', '--yes', action='store_true',
                       help="Don't ask for confirmation, apply changes automatically")
    parser.add_argument('--no-build', action='store_true',
                       help="Don't run maven build before executing commands")

    args = parser.parse_args()

    # Change to the script's directory
    script_dir = Path(__file__).parent.parent
    os.chdir(script_dir)

    # Build the project unless --no-build is specified
    if not args.no_build:
        print("Building project...")
        build_result = subprocess.run("mvn package -Dmaven.test.skip=true",
                                    shell=True, capture_output=True, text=True)
        if build_result.returncode != 0:
            print("Build failed:")
            print(build_result.stderr)
            return 1
        print("Build completed successfully.")

    # Update README
    try:
        new_content = update_readme()
        if new_content is False:
            return 1

        # Read original content for comparison
        with open("README.md", 'r') as f:
            original_content = f.read()

        if new_content == original_content:
            print("No changes needed.")
            return 0

        # Show diff and get confirmation
        if args.yes or show_diff_and_confirm("README.md", new_content):
            with open("README.md", 'w') as f:
                f.write(new_content)
            print("README.md updated successfully.")

            # Clean up temporary file
            if os.path.exists("README.new.md"):
                os.remove("README.new.md")
        else:
            print("Changes not applied.")
            # Clean up temporary file
            if os.path.exists("README.new.md"):
                os.remove("README.new.md")

        return 0

    except Exception as e:
        print(f"Error: {e}")
        return 1

if __name__ == "__main__":
    sys.exit(main())