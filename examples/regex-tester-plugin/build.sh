#!/usr/bin/env bash
# Builds <id>.jar + the distributable <id>.zip (+ its sha-256), compiling against Editora's exported API.
# Generic across the example plugins: the plugin id is read from plugin.json. The plugin loads via a child
# URLClassLoader, so a plain-classpath compile is correct.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"     # the Editora-V2 project root
ID="$(grep -o '"id"[^,}]*' "$HERE/plugin.json" | head -1 | sed -E 's/.*"id"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/')"

echo "==> Building plugin '$ID'"
( cd "$ROOT" && ./mvnw -q -o compile )

CP_FILE="$HERE/.classpath"
( cd "$ROOT" && ./mvnw -q -o dependency:build-classpath -Dmdep.outputFile="$CP_FILE" >/dev/null )
CP="$ROOT/target/classes:$(cat "$CP_FILE")"
rm -f "$CP_FILE"

OUT="$HERE/.build"; rm -rf "$OUT"; mkdir -p "$OUT"
find "$HERE/src" -name '*.java' > "$OUT/sources.txt"
javac --release 25 -cp "$CP" -d "$OUT" @"$OUT/sources.txt"
( cd "$OUT" && jar cf "$HERE/$ID.jar" com )
rm -rf "$OUT"

# The zip's top level is the plugin folder contents, so unzipping yields what lives under plugins/<id>/.
STAGE="$HERE/.zipstage"; rm -rf "$STAGE"; mkdir -p "$STAGE"
cp "$HERE/plugin.json" "$HERE/$ID.jar" "$STAGE/"
[ -d "$HERE/snippets" ] && cp -r "$HERE/snippets" "$STAGE/"
[ -d "$HERE/templates" ] && cp -r "$HERE/templates" "$STAGE/"
rm -f "$HERE/$ID.zip"
( cd "$STAGE" && zip -qr "$HERE/$ID.zip" . )
rm -rf "$STAGE"

if command -v shasum >/dev/null 2>&1; then
  SHA="$(shasum -a 256 "$HERE/$ID.zip" | cut -d' ' -f1)"
elif command -v sha256sum >/dev/null 2>&1; then
  SHA="$(sha256sum "$HERE/$ID.zip" | cut -d' ' -f1)"
else
  SHA="(install shasum/sha256sum to compute)"
fi

CFG="${EDITORA_CONFIG_DIR:-$HOME/.editora-dev}"
echo
echo "Built $HERE/$ID.jar and $HERE/$ID.zip"
echo "sha-256($ID.zip) = $SHA"
echo
echo "Install via the UI:  Settings -> Plugins -> Install from file… -> pick $ID.zip"
echo "Or hand-copy:        cp plugin.json $ID.jar  \"$CFG/plugins/$ID/\"   (then enable + restart)"
