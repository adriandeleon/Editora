#!/usr/bin/env bash
# Builds lorem-ipsum.jar + the distributable lorem-ipsum.zip (+ its sha-256), compiling against Editora's
# exported API. The plugin loads via a child URLClassLoader, so a plain-classpath compile is correct.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"     # the Editora-V2 project root

echo "==> Compiling Editora (provides the plugin API)"
( cd "$ROOT" && ./mvnw -q -o compile )

echo "==> Resolving the dependency classpath"
CP_FILE="$HERE/.classpath"
( cd "$ROOT" && ./mvnw -q -o dependency:build-classpath -Dmdep.outputFile="$CP_FILE" >/dev/null )
CP="$ROOT/target/classes:$(cat "$CP_FILE")"
rm -f "$CP_FILE"

echo "==> Compiling the plugin"
OUT="$HERE/.build"
rm -rf "$OUT"; mkdir -p "$OUT"
find "$HERE/src" -name '*.java' > "$OUT/sources.txt"
javac --release 25 -cp "$CP" -d "$OUT" @"$OUT/sources.txt"

echo "==> Packaging lorem-ipsum.jar"
( cd "$OUT" && jar cf "$HERE/lorem-ipsum.jar" com )
rm -rf "$OUT"

echo "==> Packaging the distributable lorem-ipsum.zip"
# The zip's top level is the plugin folder contents, so unzipping it yields what lives under
# <configDir>/plugins/lorem-ipsum/.
STAGE="$HERE/.zipstage"
rm -rf "$STAGE"; mkdir -p "$STAGE"
cp "$HERE/plugin.json" "$STAGE/"
cp "$HERE/lorem-ipsum.jar" "$STAGE/"
rm -f "$HERE/lorem-ipsum.zip"
( cd "$STAGE" && zip -qr "$HERE/lorem-ipsum.zip" . )
rm -rf "$STAGE"

if command -v shasum >/dev/null 2>&1; then
  SHA="$(shasum -a 256 "$HERE/lorem-ipsum.zip" | cut -d' ' -f1)"
elif command -v sha256sum >/dev/null 2>&1; then
  SHA="$(sha256sum "$HERE/lorem-ipsum.zip" | cut -d' ' -f1)"
else
  SHA="(install shasum/sha256sum to compute)"
fi

CFG="${EDITORA_CONFIG_DIR:-$HOME/.editora-dev}"
echo
echo "Built:"
echo "  $HERE/lorem-ipsum.jar"
echo "  $HERE/lorem-ipsum.zip"
echo "  sha-256(lorem-ipsum.zip) = $SHA"
echo
echo "Install via the UI:  Settings -> Plugins -> Install from file… -> pick lorem-ipsum.zip"
echo
echo "Or hand-copy into your dev config:"
echo "  mkdir -p \"$CFG/plugins/lorem-ipsum\""
echo "  cp \"$HERE/plugin.json\"        \"$CFG/plugins/lorem-ipsum/\""
echo "  cp \"$HERE/lorem-ipsum.jar\"    \"$CFG/plugins/lorem-ipsum/\""
echo
echo "Enable it in Settings -> Plugins and restart, then use the palette:"
echo "  \"Lorem Ipsum: Insert Paragraph\"  /  \"Lorem Ipsum: Replace Selection\""
