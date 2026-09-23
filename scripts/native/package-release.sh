#!/usr/bin/env bash
# Package the opt-in Linux x64 Native Image and every adjacent library emitted by native-image.
set -euo pipefail

if [[ $# -ne 3 ]]; then
    echo "Usage: $0 <native-executable> <version> <output-directory>" >&2
    exit 2
fi

binary="$(realpath -- "$1")"
version="$2"
output_dir="$(realpath -m -- "$3")"
if [[ ! -x "$binary" || ! "$version" =~ ^[0-9A-Za-z][0-9A-Za-z.+-]*$ ]]; then
    echo "Missing native executable or invalid version: $binary / $version" >&2
    exit 2
fi

name="Editora-${version}-linux-x64-native-experimental"
scratch="$(mktemp -d)"
trap 'rm -rf -- "$scratch"' EXIT
root="$scratch/$name"
mkdir -p -- "$root" "$output_dir"
cp -- "$binary" "$root/editora-native"

shopt -s nullglob
libraries=("$(dirname -- "$binary")"/*.so)
if (( ${#libraries[@]} == 0 )); then
    echo "Native Image emitted no adjacent shared libraries; refusing an incomplete bundle" >&2
    exit 1
fi
cp -- "${libraries[@]}" "$root/"

cat > "$root/run-editora-native" <<'LAUNCHER'
#!/usr/bin/env bash
set -euo pipefail
bundle="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
export EDITORA_CONFIG_DIR="${EDITORA_NATIVE_CONFIG_DIR:-${XDG_CONFIG_HOME:-$HOME/.config}/editora-native-experimental}"
exec "$bundle/editora-native" -XX:MissingRegistrationReportingMode=Exit -Xmx2g -Xms64m \
    "$@"
LAUNCHER
chmod +x "$root/run-editora-native"

cat > "$root/README.txt" <<'README'
Editora experimental Native Image (Linux x64)

From this extracted directory, run:
  ./run-editora-native [path/to/file]

The launcher keeps its settings in ~/.config/editora-native-experimental by
default. Set EDITORA_NATIVE_CONFIG_DIR to choose another directory. It passes
any remaining arguments to Editora. Keep all .so libraries next to the binary.
No Java installation is needed, but GTK3, X11, font, and GL system libraries
are required. This is a portable tarball, not an installer.

This build is experimental. Benchmarks found slower tokenization and input
latency than the JVM build. External Java plugins cannot load into this
closed-world image. LSP, debugging, SSH and other peripheral features have
not been qualified. Keep the ordinary Editora release available
for daily use.

Experiment and measured results:
https://github.com/adriandeleon/Editora/blob/master/docs/native-image-staticfx.md
README

tar -C "$scratch" -czf "$output_dir/$name.tar.gz" "$name"
echo "$output_dir/$name.tar.gz"
