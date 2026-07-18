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
# Best-effort: the release.yml step is continue-on-error, so a failure here never sinks the .deb/.rpm.
set -euo pipefail

APPIMG_DIR="${1:?app-image dir required}"   # the jpackage Linux app-image (has bin/Editora + lib/)
ICON="${2:?icon path required}"
OUTDIR="${3:?out dir required}"

if [ ! -x "$APPIMG_DIR/bin/Editora" ]; then
  echo "[appimage] launcher not found at $APPIMG_DIR/bin/Editora — skipping" >&2
  exit 1
fi

ARCH="$(uname -m)"   # x86_64 on linux-x64, aarch64 on linux-arm64 — matches appimagetool's asset names

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

# Fetch appimagetool for this arch (a single self-contained AppImage). EXTRACT_AND_RUN avoids needing
# FUSE on the build runner.
TOOL="$WORK/appimagetool-$ARCH.AppImage"
curl -fsSL -o "$TOOL" \
  "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-$ARCH.AppImage"
chmod +x "$TOOL"

mkdir -p "$OUTDIR"
OUT="$OUTDIR/Editora-$ARCH.AppImage"
# ARCH guides appimagetool's runtime selection; --no-appstream avoids a hard dependency on a
# metainfo.xml we don't ship yet.
ARCH="$ARCH" APPIMAGE_EXTRACT_AND_RUN=1 "$TOOL" --no-appstream "$APPDIR" "$OUT"

echo "[appimage] built $OUT"
ls -la "$OUT"
rm -rf "$WORK"
