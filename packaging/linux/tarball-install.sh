#!/bin/sh
#
# Editora portable-tarball installer.
#
# This script ships INSIDE Editora-<version>-linux-<arch>.tar.gz, next to the self-contained `Editora/`
# app-image (a jlink'd runtime + the native `bin/Editora` launcher + the AOT cache — the same relocatable
# image the .deb/.rpm/.AppImage are built from). It installs that image and wires up an `editora` command
# on PATH plus an application-menu entry, WITHOUT needing a package manager.
#
#   Run as root      -> installs system-wide to /opt/editora        (+ /usr/local/bin/editora)
#   Run as a user    -> installs to ~/.local/editora                (+ ~/.local/bin/editora)
#
# Usage:
#   ./install.sh                 # auto: system if root, else per-user
#   sudo ./install.sh            # force system-wide (/opt/editora)
#   ./install.sh --user          # force per-user (~/.local/editora)
#   ./install.sh --system        # force system-wide (requires root)
#   ./install.sh --prefix DIR    # install the app image into DIR/editora instead
#   ./install.sh --uninstall     # remove a previous install (same mode/prefix rules)
#   ./install.sh --help
#
# POSIX sh (no bashisms) so it runs under dash/busybox on minimal systems.
set -eu

PROG="Editora"
WMCLASS="com.editora.App"   # JavaFX derives WM_CLASS from the module main; the menu entry must match it
                            # exactly or the running window won't inherit the launcher icon (wmctrl -lx).

# --- resolve the app-image that ships beside this script -----------------------------------------------
SCRIPT_DIR=$(cd -- "$(dirname -- "$0")" >/dev/null 2>&1 && pwd -P)
SRC="$SCRIPT_DIR/$PROG"

MODE=""          # system | user
PREFIX=""        # explicit install parent (overrides MODE's default location)
ACTION="install" # install | uninstall

die() { echo "editora-install: $*" >&2; exit 1; }

usage() {
    sed -n '3,26p' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --system)     MODE="system" ;;
        --user)       MODE="user" ;;
        --prefix)     shift; [ $# -gt 0 ] || die "--prefix needs a directory"; PREFIX="$1" ;;
        --prefix=*)   PREFIX="${1#--prefix=}" ;;
        --uninstall|--remove) ACTION="uninstall" ;;
        -h|--help)    usage 0 ;;
        *)            die "unknown option: $1 (try --help)" ;;
    esac
    shift
done

# Default mode: root -> system, otherwise per-user.
if [ -z "$MODE" ]; then
    if [ "$(id -u)" = "0" ]; then MODE="system"; else MODE="user"; fi
fi
[ "$MODE" = "system" ] && [ "$(id -u)" != "0" ] && \
    die "--system needs root; re-run with sudo (or use --user for a per-user install)."

# --- resolve install locations from the mode/prefix ----------------------------------------------------
if [ -n "$PREFIX" ]; then
    APPDIR="$PREFIX/editora"
elif [ "$MODE" = "system" ]; then
    APPDIR="/opt/editora"
else
    APPDIR="$HOME/.local/editora"   # self-contained app payload (kept out of ~/.local/share so it's
                                    # easy to find/remove); the launcher resolves its runtime relatively.
fi

if [ "$MODE" = "system" ]; then
    BINLINK="/usr/local/bin/editora"
    DESKTOP_DIR="/usr/share/applications"
else
    BINLINK="$HOME/.local/bin/editora"
    DESKTOP_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
fi
DESKTOP="$DESKTOP_DIR/editora.desktop"
LAUNCHER="$APPDIR/bin/$PROG"
ICON="$APPDIR/lib/$PROG.png"

refresh_menu() {
    command -v update-desktop-database >/dev/null 2>&1 && \
        update-desktop-database -q "$DESKTOP_DIR" 2>/dev/null || true
}

# --- uninstall -----------------------------------------------------------------------------------------
if [ "$ACTION" = "uninstall" ]; then
    rm -rf "$APPDIR"
    # Only remove the symlink if it points into our app dir (don't clobber an unrelated `editora`).
    if [ -L "$BINLINK" ] && [ "$(readlink "$BINLINK")" = "$LAUNCHER" ]; then rm -f "$BINLINK"; fi
    rm -f "$DESKTOP"
    refresh_menu
    echo "editora-install: removed $APPDIR (and its command + menu entry)."
    exit 0
fi

# --- install -------------------------------------------------------------------------------------------
[ -x "$SRC/bin/$PROG" ] || die "app image not found next to this script (expected $SRC/bin/$PROG)."

echo "editora-install: installing to $APPDIR ($MODE) ..."
rm -rf "$APPDIR"                       # clean reinstall (idempotent; also drops a stale AOT cache)
mkdir -p "$(dirname "$APPDIR")"
cp -a "$SRC" "$APPDIR"                 # SRC is .../Editora -> APPDIR becomes its copy (bin/, lib/, ...)

mkdir -p "$(dirname "$BINLINK")"
ln -sf "$LAUNCHER" "$BINLINK"

mkdir -p "$DESKTOP_DIR"
{
    echo "[Desktop Entry]"
    echo "Type=Application"
    echo "Name=Editora"
    echo "GenericName=Text Editor"
    echo "Comment=A keyboard-driven, cross-platform programmer's text editor"
    echo "Exec=$LAUNCHER %F"
    [ -f "$ICON" ] && echo "Icon=$ICON"
    echo "Categories=Development;Utility;TextEditor;IDE;"
    echo "Terminal=false"
    echo "StartupNotify=true"
    echo "StartupWMClass=$WMCLASS"
    echo "MimeType=text/plain;"
} > "$DESKTOP"
chmod 0644 "$DESKTOP"
refresh_menu

echo "editora-install: installed."
echo "  app image : $APPDIR"
echo "  command   : $BINLINK  ->  $LAUNCHER"
echo "  menu entry: $DESKTOP"

# PATH hint (the per-user symlink dir isn't always on PATH).
case ":${PATH}:" in
    *":$(dirname "$BINLINK"):"*) : ;;
    *) echo ""; echo "  NOTE: $(dirname "$BINLINK") is not on your PATH — add it, e.g.:"
       echo "        echo 'export PATH=\"$(dirname "$BINLINK"):\$PATH\"' >> ~/.profile" ;;
esac

echo ""
echo "Run 'editora' (or launch it from your applications menu). Uninstall with: $0 --uninstall"
