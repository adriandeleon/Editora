#!/usr/bin/env bash
# A shell script for the Run gutter: the ▶ sits on the first line and runs the whole file with bash.
set -euo pipefail

name="${1:-Editora}"
echo "Hello from a shell script, ${name}!"
for i in 1 2 3; do
  echo "line $i"
done
