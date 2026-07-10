#!/usr/bin/env bash
#
# Build a portable Linux install tarball from the jpackage app-image (Linux release leg only).
#
# The tarball is the extract-and-install counterpart to the .AppImage (see build-appimage.sh): it bundles
# the same already-trained, AOT-cached, relocatable app-image that -Pdist produced at
# target/aot-image/Editora — a jlink'd runtime + the native bin/Editora launcher — next to a self-contained
# install.sh (packaging/linux/tarball-install.sh). Extracting + running install.sh drops the image into
# /opt/editora (as root) or ~/.local/editora (as a user) and wires up an `editora` command + menu entry,
# with no package manager and no jpackage installer. Editora spawns host tools (git, LSP/DAP servers,
# python/node, ripgrep, ...) via the user's real PATH, so nothing here sandboxes.
#
# Usage:  scripts/build-tarball.sh <app-image-dir> <out-dir>
#   e.g.  scripts/build-tarball.sh target/aot-image/Editora target/dist
#
# Best-effort in release.yml (continue-on-error), so a hiccup here never sinks the .deb/.rpm/.jar.
set -euo pipefail

APPIMG_DIR="${1:?app-image dir required}"   # the jpackage Linux app-image (has bin/Editora + lib/)
OUTDIR="${2:?out dir required}"

if [ ! -x "$APPIMG_DIR/bin/Editora" ]; then
  echo "[tarball] launcher not found at $APPIMG_DIR/bin/Editora — skipping" >&2
  exit 1
fi

ARCH="$(uname -m)"   # x86_64 on linux-x64, aarch64 on linux-arm64
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
INSTALLER="$SCRIPT_DIR/../packaging/linux/tarball-install.sh"

if [ ! -f "$INSTALLER" ]; then
  echo "[tarball] installer template not found at $INSTALLER — skipping" >&2
  exit 1
fi

WORK="$(mktemp -d)"
STAGE="$WORK/editora-$ARCH"      # tarball's single top-level dir (avoids a tarbomb)
mkdir -p "$STAGE"

# The whole app-image (jlink runtime + lib/app/editora.aot ride along; bin/Editora stays executable).
cp -a "$APPIMG_DIR" "$STAGE/Editora"
cp "$INSTALLER" "$STAGE/install.sh"
chmod +x "$STAGE/install.sh"

cat > "$STAGE/README.txt" <<'EOF'
Editora — portable Linux install
=================================

This archive contains a self-contained Editora app image (its own bundled Java
runtime) plus an installer. No package manager, no root required.

  Install for your user  (-> ~/.local/editora):
      ./install.sh

  Install system-wide    (-> /opt/editora):
      sudo ./install.sh

  Other options:
      ./install.sh --prefix /some/dir     # install into /some/dir/editora
      ./install.sh --uninstall            # remove a previous install
      ./install.sh --help

After installing, run 'editora' from a terminal or launch it from your
applications menu. (A per-user install may need ~/.local/bin on your PATH —
install.sh prints a hint if it isn't.)

You can also run Editora in place without installing:
      ./Editora/bin/Editora
EOF

mkdir -p "$OUTDIR"
OUT="$OUTDIR/Editora-$ARCH.tar.gz"
# GNU tar on the Linux runners; --owner/--group=0 keeps a clean root-owned tree regardless of the
# building user, and a sorted listing makes the archive reproducible.
tar --numeric-owner --owner=0 --group=0 -C "$WORK" -czf "$OUT" "editora-$ARCH"

echo "[tarball] built $OUT"
ls -la "$OUT"
rm -rf "$WORK"
