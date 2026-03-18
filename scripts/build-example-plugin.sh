#!/usr/bin/env zsh
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "$0")/.." && pwd)"
PLUGIN_NAME="timestamp-plugin"
EXAMPLE_DIR="$ROOT_DIR/examples/$PLUGIN_NAME"
BUILD_DIR="$EXAMPLE_DIR/build"
CLASSES_DIR="$BUILD_DIR/classes"
OUTPUT_JAR="$ROOT_DIR/plugins/$PLUGIN_NAME.jar"

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 25)}"
export PATH="$JAVA_HOME/bin:$PATH"

cd "$ROOT_DIR"
./mvnw -q -DskipTests compile

rm -rf "$BUILD_DIR"
mkdir -p "$CLASSES_DIR"

javac \
  --release 25 \
  -cp "$ROOT_DIR/target/classes" \
  -d "$CLASSES_DIR" \
  $(find "$EXAMPLE_DIR/src/main/java" -name '*.java' | sort)

if [ -d "$EXAMPLE_DIR/src/main/resources" ]; then
  cp -R "$EXAMPLE_DIR/src/main/resources/." "$CLASSES_DIR/"
fi

mkdir -p "$ROOT_DIR/plugins"
jar --create --file "$OUTPUT_JAR" -C "$CLASSES_DIR" .

echo "Built $OUTPUT_JAR"

