#!/usr/bin/env bash
# Builds example-plugin.jar by compiling against Editora's exported API + JavaFX, then prints the install
# steps. The plugin is loaded by a child URLClassLoader (parent = the app loader), so a plain classpath
# compile is exactly right — no module path needed.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"     # the Editora-V2 project root

echo "==> Compiling Editora (provides the plugin API + JavaFX deps)"
( cd "$ROOT" && ./mvnw -q -o compile )

echo "==> Resolving the dependency classpath (JavaFX, etc.)"
CP_FILE="$HERE/.classpath"
( cd "$ROOT" && ./mvnw -q -o dependency:build-classpath -Dmdep.outputFile="$CP_FILE" >/dev/null )
CP="$ROOT/target/classes:$(cat "$CP_FILE")"
rm -f "$CP_FILE"

echo "==> Compiling the plugin"
OUT="$HERE/.build"
rm -rf "$OUT"; mkdir -p "$OUT"
find "$HERE/src" -name '*.java' > "$OUT/sources.txt"
javac --release 25 -cp "$CP" -d "$OUT" @"$OUT/sources.txt"

echo "==> Packaging example-plugin.jar"
( cd "$OUT" && jar cf "$HERE/example-plugin.jar" com )
rm -rf "$OUT"

echo "==> Packaging the distributable example.zip (for Install-from-file / a registry entry)"
# The zip's top level is the plugin folder contents (plugin.json + jar + snippets/), so unzipping it
# yields exactly what lives under <configDir>/plugins/example/.
STAGE="$HERE/.zipstage"
rm -rf "$STAGE"; mkdir -p "$STAGE/snippets"
cp "$HERE/plugin.json" "$STAGE/"
cp "$HERE/example-plugin.jar" "$STAGE/"
cp "$HERE/snippets/"* "$STAGE/snippets/"
rm -f "$HERE/example.zip"
( cd "$STAGE" && zip -qr "$HERE/example.zip" . )
rm -rf "$STAGE"

# Compute the sha-256 (for a registry index.json entry).
if command -v shasum >/dev/null 2>&1; then
  SHA="$(shasum -a 256 "$HERE/example.zip" | cut -d' ' -f1)"
elif command -v sha256sum >/dev/null 2>&1; then
  SHA="$(sha256sum "$HERE/example.zip" | cut -d' ' -f1)"
else
  SHA="(install shasum/sha256sum to compute)"
fi

CFG="${EDITORA_CONFIG_DIR:-$HOME/.editora-dev}"
echo
echo "Built:"
echo "  $HERE/example-plugin.jar   (the compiled plugin)"
echo "  $HERE/example.zip          (the distributable archive)"
echo "  sha-256(example.zip) = $SHA"
echo
echo "Install via the UI:  Settings -> Plugins -> Install from file… -> pick example.zip"
echo
echo "Or hand-copy into your dev config:"
echo "  mkdir -p \"$CFG/plugins/example/snippets\""
echo "  cp \"$HERE/plugin.json\"          \"$CFG/plugins/example/\""
echo "  cp \"$HERE/example-plugin.jar\"   \"$CFG/plugins/example/\""
echo "  cp \"$HERE/snippets/\"*           \"$CFG/plugins/example/snippets/\""
echo
echo "To host it in a registry: upload example.zip as a GitHub release asset and add an entry to your"
echo "index.json with its URL + the sha-256 above (see docs/plugins.md, 'Publishing & installing')."
echo
echo "Then launch Editora (--dev), Settings -> Plugins, tick \"Enable plugins\" + \"Example Plugin\","
echo "and restart. Try the palette (\"Example: Say Hello\"), C-c C-h, the Example tool window, the editor"
echo "right-click \"Uppercase selection (example)\", the status-bar segment, and the 'callout' snippet."
