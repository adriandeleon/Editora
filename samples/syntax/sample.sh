#!/usr/bin/env bash
set -euo pipefail

greet() {
  local name="${1:-world}"
  echo "Hello, ${name}"
}

for n in alice bob; do
  greet "$n"
done
