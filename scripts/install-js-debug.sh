#!/usr/bin/env bash
#
# install-js-debug.sh — download Microsoft's vscode-js-debug (the Node DAP adapter) into Editora's config dir.
#
# Editora debugs JavaScript (Node) by running `node <dapDebugServer.js> <port>` and connecting to it over a
# socket. Microsoft publishes a standalone DAP bundle (js-debug-dap-v*.tar.gz) on the vscode-js-debug
# releases page; this script fetches the latest and extracts it. The DAP entry point ends up at
# <dest>/js-debug/src/dapDebugServer.js.
#
# Destination:  $EDITORA_CONFIG_DIR/plugins/dap/javascript/   (default: ~/.editora/plugins/dap/javascript/)
#   Editora auto-detects this location (and the ~/.editora-dev/... variant for --dev runs), so once it's
#   installed you just enable Settings -> Debugging -> JavaScript; no path needs to be set.
#   For a --dev instance, run:  EDITORA_CONFIG_DIR="$HOME/.editora-dev" scripts/install-js-debug.sh
#
# Requirements: curl, tar, node (node is needed at debug time, not to install).
set -euo pipefail

CONFIG_DIR="${EDITORA_CONFIG_DIR:-$HOME/.editora}"
DEST="$CONFIG_DIR/plugins/dap/javascript"
API="https://api.github.com/repos/microsoft/vscode-js-debug/releases/latest"

for cmd in curl tar; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "error: '$cmd' is required but not installed." >&2; exit 1; }
done

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$DEST"

echo "Resolving the latest vscode-js-debug DAP release from GitHub..."
TARBALL_URL="$(curl -fsSL "$API" | grep -oE 'https://[^"]+/js-debug-dap-v[^"]+\.tar\.gz' | head -n1)"
[ -n "$TARBALL_URL" ] || { echo "error: could not find a js-debug-dap-*.tar.gz asset from $API" >&2; exit 1; }

echo "Downloading: $TARBALL_URL"
curl -fSL --progress-bar "$TARBALL_URL" -o "$TMP/js-debug.tar.gz"

echo "Extracting into: $DEST"
# Replace any previous copy so the newest version wins on auto-detect.
rm -rf "$DEST/js-debug"
tar -xzf "$TMP/js-debug.tar.gz" -C "$DEST"

ENTRY="$(find "$DEST" -name 'dapDebugServer.js' 2>/dev/null | head -n1)"
[ -n "$ENTRY" ] || { echo "error: dapDebugServer.js not found after extraction." >&2; exit 1; }

echo
echo "Installed: $ENTRY"
echo "Enable it in Editora: Settings -> Debugging -> 'Enable JavaScript debugging' (this path is auto-detected)."
echo "Node must be on your PATH at debug time."
