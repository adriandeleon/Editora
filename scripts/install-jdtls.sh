#!/usr/bin/env bash
#
# install-jdtls.sh — download the Eclipse JDT Language Server (jdtls) into Editora's config dir.
#
# jdtls is the Java language server that powers Editora's Java LSP (diagnostics, completion, hover,
# go-to-definition) — and the foundation the Java debugger layers on. Unlike a single jar it's a whole
# distribution (a .tar.gz with bin/, plugins/, config_*, features/); the launcher is bin/jdtls.
#
# Destination:  $EDITORA_CONFIG_DIR/plugins/lsp/java/   (default: ~/.editora/plugins/lsp/java/)
#   Editora adds this install's bin/ dir to the PATH it resolves tools against, so the default LSP "Java
#   server command" (jdtls) finds it automatically — no path to set. For a --dev instance:
#     EDITORA_CONFIG_DIR="$HOME/.editora-dev" scripts/install-jdtls.sh
#
# Requirements: curl, tar. jdtls itself needs a JDK 21+ on PATH to RUN (it can still analyze older code).
set -euo pipefail

CONFIG_DIR="${EDITORA_CONFIG_DIR:-$HOME/.editora}"
DEST="$CONFIG_DIR/plugins/lsp/java"
# The canonical "latest snapshot" tarball published by the Eclipse jdt.ls project.
URL="${JDTLS_URL:-https://download.eclipse.org/jdtls/snapshots/jdt-language-server-latest.tar.gz}"

for cmd in curl tar; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "error: '$cmd' is required but not installed." >&2; exit 1; }
done

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Downloading jdtls: $URL"
curl -fSL --progress-bar "$URL" -o "$TMP/jdtls.tar.gz"

echo "Extracting into $DEST ..."
# Replace any previous install so the launcher/plugins/config stay internally consistent.
rm -rf "$DEST"
mkdir -p "$DEST"
tar -xzf "$TMP/jdtls.tar.gz" -C "$DEST"

LAUNCHER="$DEST/bin/jdtls"
[ -f "$LAUNCHER" ] || { echo "error: jdtls launcher not found at $LAUNCHER after extraction." >&2; exit 1; }
chmod +x "$LAUNCHER" 2>/dev/null || true

echo
echo "Installed jdtls: $LAUNCHER"
echo "Editora finds it automatically (its bin/ dir is on the resolved PATH)."
echo "Enable Settings -> LSP -> 'Enable LSP' (Java server on). jdtls needs a JDK 21+ on PATH to run."
