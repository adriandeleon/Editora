#!/usr/bin/env bash
#
# install-debugpy.sh — install debugpy (the Python DAP adapter) into Editora's config dir.
#
# Editora debugs Python by running `python -m debugpy.adapter` (DAP over stdio). debugpy is a normal
# PyPI package; this script pip-installs it into a self-contained target dir that Editora adds to
# PYTHONPATH, so it never touches your system/global site-packages. (If debugpy is already importable by
# your `python3`, you don't even need this — Editora will use it directly.)
#
# Destination:  $EDITORA_CONFIG_DIR/plugins/dap/python/   (default: ~/.editora/plugins/dap/python/)
#   Editora auto-detects this location (and the ~/.editora-dev/... variant for --dev runs), so once it's
#   installed you just enable Settings -> Debugging -> Python; no path needs to be set.
#   For a --dev instance, run:  EDITORA_CONFIG_DIR="$HOME/.editora-dev" scripts/install-debugpy.sh
#
# Requirements: python3 (with pip).
set -euo pipefail

CONFIG_DIR="${EDITORA_CONFIG_DIR:-$HOME/.editora}"
DEST="$CONFIG_DIR/plugins/dap/python"
PYTHON="${PYTHON:-python3}"

command -v "$PYTHON" >/dev/null 2>&1 || { echo "error: '$PYTHON' is required but not installed." >&2; exit 1; }
"$PYTHON" -m pip --version >/dev/null 2>&1 || { echo "error: pip is not available for '$PYTHON'." >&2; exit 1; }

mkdir -p "$DEST"

echo "Installing debugpy into: $DEST"
"$PYTHON" -m pip install --upgrade --target "$DEST" debugpy

echo
echo "Installed debugpy into $DEST"
echo "Enable it in Editora: Settings -> Debugging -> 'Enable Python debugging' (this path is auto-detected)."
echo "Set a specific interpreter under 'Python interpreter' if you don't use 'python3' on PATH."
