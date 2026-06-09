#!/usr/bin/env bash
#
# install-java-debug.sh — download the Microsoft java-debug plugin into Editora's config dir.
#
# The plugin (com.microsoft.java.debug.plugin-*.jar) is the jar that teaches jdtls how to debug. It
# isn't published as a standalone download; it ships inside the VS Code "Debugger for Java" extension
# (vscjava.vscode-java-debug), which is just a zip (.vsix). This script fetches the latest release from
# Open VSX and extracts only the plugin jar.
#
# Destination:  $EDITORA_CONFIG_DIR/plugins/dap/java/   (default: ~/.editora/plugins/dap/java/)
#   Editora auto-detects this location (and the ~/.editora-dev/... variant for --dev runs), so once the
#   jar is here you just enable Settings -> Debugging; no path needs to be set.
#   For a --dev instance, run:  EDITORA_CONFIG_DIR="$HOME/.editora-dev" scripts/install-java-debug.sh
#
# Requirements: curl, unzip.
set -euo pipefail

CONFIG_DIR="${EDITORA_CONFIG_DIR:-$HOME/.editora}"
DEST="$CONFIG_DIR/plugins/dap/java"
API="https://open-vsx.org/api/vscjava/vscode-java-debug/latest"

for cmd in curl unzip; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "error: '$cmd' is required but not installed." >&2; exit 1; }
done

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$DEST"

echo "Resolving the latest 'Debugger for Java' release from Open VSX..."
VSIX_URL="$(curl -fsSL "$API" | grep -oE 'https://[^"]+\.vsix' | head -n1)"
[ -n "$VSIX_URL" ] || { echo "error: could not find a .vsix download URL from $API" >&2; exit 1; }

echo "Downloading: $VSIX_URL"
curl -fSL --progress-bar "$VSIX_URL" -o "$TMP/java-debug.vsix"

echo "Extracting the plugin jar..."
unzip -o -q "$TMP/java-debug.vsix" 'extension/server/com.microsoft.java.debug.plugin-*.jar' -d "$TMP"
JAR="$(find "$TMP/extension/server" -name 'com.microsoft.java.debug.plugin-*.jar' 2>/dev/null | head -n1)"
[ -n "$JAR" ] || { echo "error: plugin jar not found inside the .vsix." >&2; exit 1; }

# Replace any previous copy so the newest version wins on auto-detect.
rm -f "$DEST"/com.microsoft.java.debug.plugin-*.jar
cp "$JAR" "$DEST/"

echo
echo "Installed: $DEST/$(basename "$JAR")"
echo "Enable it in Editora: Settings -> Debugging -> 'Enable Java debugging' (this path is auto-detected)."
echo "You also need jdtls + the Java LSP server enabled (Settings -> LSP)."
