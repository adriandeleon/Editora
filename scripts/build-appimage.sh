#!/usr/bin/env bash
#
# Build a portable Linux AppImage from the jpackage app-image (Linux release leg only).
#
# Unlike Flatpak, an AppImage does NOT sandbox: it bundles Editora's jlink'd runtime and runs with
# the user's real host PATH, so Editora's host-tool spawning (git, the LSP servers, debug adapters,
# python/node, ripgrep, mmdc, browsers — all via ProcessRunner) works with zero code changes. We just
# wrap the already-trained, AOT-cached app-image that -Pdist produced at target/aot-image/Editora.
#
# Usage:  scripts/build-appimage.sh <app-image-dir> <icon> <out-dir>
#   e.g.  scripts/build-appimage.sh target/aot-image/Editora branding/editora.png target/dist
#
# On a manual dry run the release.yml step is continue-on-error; on a tag it is not, and the release job's
# asset check requires the .AppImage — a release is immutable, so it cannot be added afterwards.
#
# The two downloads below are PINNED to versioned upstream releases and verified by SHA-256 before anything
# is executed (fail closed). They used to come from the rolling `continuous` release with no checksum, and
# appimagetool then fetched the type2 runtime — the ELF stub that becomes the first bytes of the shipped
# .AppImage — from a second `continuous` tag on its own. To move to a newer release: change the version,
# download the two files for BOTH architectures, and replace the four hashes (`sha256sum <file>`; they
# should equal the digests GitHub shows on the release page). scripts/test_build_scripts.py checks that a
# file whose hash does not match is never run.
set -euo pipefail

APPIMAGETOOL_VERSION="1.9.1"
TYPE2_RUNTIME_VERSION="20251108"

APPIMG_DIR="${1:?app-image dir required}"   # the jpackage Linux app-image (has bin/Editora + lib/)
ICON="${2:?icon path required}"
OUTDIR="${3:?out dir required}"

if [ ! -x "$APPIMG_DIR/bin/Editora" ]; then
  echo "[appimage] launcher not found at $APPIMG_DIR/bin/Editora — skipping" >&2
  exit 1
fi

ARCH="$(uname -m)"   # x86_64 on linux-x64, aarch64 on linux-arm64 — matches appimagetool's asset names
case "$ARCH" in
  x86_64)
    APPIMAGETOOL_SHA256="ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0"
    TYPE2_RUNTIME_SHA256="2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d"
    ;;
  aarch64)
    APPIMAGETOOL_SHA256="f0837e7448a0c1e4e650a93bb3e85802546e60654ef287576f46c71c126a9158"
    TYPE2_RUNTIME_SHA256="00cbdfcf917cc6c0ff6d3347d59e0ca1f7f45a6df1a428a0d6d8a78664d87444"
    ;;
  *)
    echo "[appimage] no pinned appimagetool/runtime checksums for architecture '$ARCH' — refusing to build" >&2
    exit 1
    ;;
esac

# fetch_verified <url> <sha256> <dest>: download, then refuse the file unless its SHA-256 is the pinned one.
fetch_verified() {
  local url="$1" want="$2" dest="$3" got
  curl -fsSL --retry 3 -o "$dest" "$url"
  got="$(sha256sum "$dest" | cut -d' ' -f1)"
  if [ "$got" != "$want" ]; then
    echo "[appimage] CHECKSUM MISMATCH for $url" >&2
    echo "[appimage]   expected $want" >&2
    echo "[appimage]   got      $got" >&2
    rm -f "$dest"
    exit 1
  fi
  echo "[appimage] verified $(basename "$dest") ($want)"
}

WORK="$(mktemp -d)"
APPDIR="$WORK/Editora.AppDir"
mkdir -p "$APPDIR"

# Bundle the whole app-image (jlink runtime + lib/app/editora.aot ride along).
cp -a "$APPIMG_DIR" "$APPDIR/Editora"

# AppRun: resolve the mount dir and exec the jpackage launcher. The launcher computes its own
# runtime/app paths relative to itself, and jpackage's $APPDIR token (in the .cfg, for -XX:AOTCache)
# is expanded by the launcher to lib/app — independent of AppImage's own APPDIR env var.
cat > "$APPDIR/AppRun" <<'EOF'
#!/usr/bin/env bash
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/Editora/bin/Editora" "$@"
EOF
chmod +x "$APPDIR/AppRun"

# Desktop entry (Icon must match the icon basename below).
cat > "$APPDIR/editora.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=Editora
GenericName=Text Editor
Comment=A keyboard-driven, cross-platform programmer's text editor
Exec=Editora %F
Icon=editora
Categories=Development;Utility;TextEditor;IDE;
MimeType=text/plain;
StartupWMClass=com.editora.App
Terminal=false
EOF

cp "$ICON" "$APPDIR/editora.png"
cp "$ICON" "$APPDIR/.DirIcon"

# The licence texts as plain files inside the image (they are also inside the application jar).
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
mkdir -p "$APPDIR/usr/share/licenses/editora"
for doc in LICENSE NOTICE; do
  if [ -f "$SCRIPT_DIR/../$doc" ]; then
    cp "$SCRIPT_DIR/../$doc" "$APPDIR/usr/share/licenses/editora/$doc"
  else
    echo "[appimage] $doc not found beside the scripts directory — the AppImage will not carry it" >&2
  fi
done

# Fetch appimagetool for this arch (a single self-contained AppImage) and the type2 runtime it embeds,
# both pinned and verified (see the header). EXTRACT_AND_RUN avoids needing FUSE on the build runner.
TOOL="$WORK/appimagetool-$ARCH.AppImage"
RUNTIME="$WORK/runtime-$ARCH"
fetch_verified \
  "https://github.com/AppImage/appimagetool/releases/download/$APPIMAGETOOL_VERSION/appimagetool-$ARCH.AppImage" \
  "$APPIMAGETOOL_SHA256" "$TOOL"
fetch_verified \
  "https://github.com/AppImage/type2-runtime/releases/download/$TYPE2_RUNTIME_VERSION/runtime-$ARCH" \
  "$TYPE2_RUNTIME_SHA256" "$RUNTIME"
chmod +x "$TOOL"

mkdir -p "$OUTDIR"
OUT="$OUTDIR/Editora-$ARCH.AppImage"
# --runtime-file: embed the runtime verified above instead of letting appimagetool download one itself.
# --no-appstream: skip AppStream validation. A metainfo file does exist (packaging/linux/
# com.editora.Editora.metainfo.xml, installed by the .deb's postinst), but this AppDir does not place it
# under usr/share/metainfo, and validating one would make appstreamcli a build dependency of the runner.
ARCH="$ARCH" APPIMAGE_EXTRACT_AND_RUN=1 "$TOOL" --no-appstream --runtime-file "$RUNTIME" "$APPDIR" "$OUT"

echo "[appimage] built $OUT"
ls -la "$OUT"
rm -rf "$WORK"
