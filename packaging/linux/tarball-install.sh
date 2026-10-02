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
#                                # (refused if DIR/editora exists and is not an Editora install)
#   ./install.sh --destdir DIR   # stage every file under DIR (for packagers; paths written into
#                                # the command and the menu entry stay un-prefixed)
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
DESTDIR=""       # staging root; empty for a real install
ACTION="install" # install | uninstall

die() { echo "editora-install: $*" >&2; exit 1; }

usage() {
    # The header comment above, from line 3 to the first line that is not a comment.
    awk 'NR >= 3 && /^#/ { sub(/^# ?/, ""); print; next } NR >= 3 { exit }' "$0"
    exit "${1:-0}"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --system)     MODE="system" ;;
        --user)       MODE="user" ;;
        --prefix)     shift; [ $# -gt 0 ] || die "--prefix needs a directory"; PREFIX="$1" ;;
        --prefix=*)   PREFIX="${1#--prefix=}" ;;
        --destdir)    shift; [ $# -gt 0 ] || die "--destdir needs a directory"; DESTDIR="$1" ;;
        --destdir=*)  DESTDIR="${1#--destdir=}" ;;
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
        update-desktop-database -q "$DESTDIR$DESKTOP_DIR" 2>/dev/null || true
}

# True when the directory $1 may be replaced or removed by this script: it does not exist, it is empty, or
# it is a previous Editora install (it has bin/Editora). Anything else is someone else's data — with
# `--prefix DIR`, DIR/editora can be ANY directory that happens to carry that name, and this script used to
# `rm -rf` it without looking.
is_ours_or_empty() {
    [ -e "$1" ] || [ -L "$1" ] || return 0     # nothing there
    [ -d "$1" ] || return 1                    # a file (or a dangling link), not an install
    [ -e "$1/bin/$PROG" ] && return 0          # a previous Editora install
    [ -z "$(ls -A "$1" 2>/dev/null)" ]         # or an empty directory
}

# Quote one argument for a .desktop Exec= line (Desktop Entry spec, "The Exec key"). An argument made only
# of unreserved characters is written as-is, so the usual /opt/editora/bin/Editora entry is unchanged.
# Otherwise it is wrapped in double quotes with  "  `  $  \  backslash-escaped, each of those backslashes
# doubled again for the string-value escaping layer the spec applies first (so a literal backslash becomes
# four), and a literal % written as %%. Without this a prefix containing a space split the command in two.
desktop_exec_arg() {
    case "$1" in
        *[!A-Za-z0-9_./:@+,=-]*)
            printf '"%s"' "$(printf '%s' "$1" | sed -e 's/\\/\\\\\\\\/g' \
                                                    -e 's/"/\\\\"/g' \
                                                    -e 's/`/\\\\`/g' \
                                                    -e 's/\$/\\\\$/g' \
                                                    -e 's/%/%%/g')"
            ;;
        *)  printf '%s' "$1" ;;
    esac
}

# A string value (Icon=) only needs its backslashes escaped; spaces are fine there.
desktop_string() { printf '%s' "$1" | sed -e 's/\\/\\\\/g'; }

# True when $1 is a symlink this installer made: one that points at some .../bin/Editora launcher.
is_our_link() {
    [ -L "$1" ] || return 1
    case "$(readlink "$1" 2>/dev/null || true)" in
        */bin/"$PROG") return 0 ;;
    esac
    return 1
}

# --- uninstall -----------------------------------------------------------------------------------------
if [ "$ACTION" = "uninstall" ]; then
    is_ours_or_empty "$DESTDIR$APPDIR" || die "refusing to remove $DESTDIR$APPDIR: it is not empty and \
has no bin/$PROG, so it does not look like an Editora install. Remove it by hand if you are sure."
    rm -rf "$DESTDIR$APPDIR"
    # Only remove the symlink if it points into our app dir (don't clobber an unrelated `editora`).
    if [ -L "$DESTDIR$BINLINK" ] && [ "$(readlink "$DESTDIR$BINLINK")" = "$LAUNCHER" ]; then
        rm -f "$DESTDIR$BINLINK"
    fi
    rm -f "$DESTDIR$DESKTOP"
    refresh_menu
    echo "editora-install: removed $DESTDIR$APPDIR (and its command + menu entry)."
    exit 0
fi

# --- install -------------------------------------------------------------------------------------------
[ -x "$SRC/bin/$PROG" ] || die "app image not found next to this script (expected $SRC/bin/$PROG)."
is_ours_or_empty "$DESTDIR$APPDIR" || die "refusing to replace $DESTDIR$APPDIR: it is not empty and has \
no bin/$PROG, so it does not look like a previous Editora install. Choose another --prefix, or remove it."

echo "editora-install: installing to $DESTDIR$APPDIR ($MODE) ..."
rm -rf "$DESTDIR$APPDIR"               # clean reinstall (idempotent; also drops a stale AOT cache)
mkdir -p "$(dirname "$DESTDIR$APPDIR")"
cp -a "$SRC" "$DESTDIR$APPDIR"         # SRC is .../Editora -> APPDIR becomes its copy (bin/, lib/, ...)
for doc in LICENSE NOTICE; do          # the licence texts ride beside this script in the tarball
    if [ -f "$SCRIPT_DIR/$doc" ]; then cp "$SCRIPT_DIR/$doc" "$DESTDIR$APPDIR/$doc"; fi
done

# A system-wide install must not stay writable by whoever unpacked the tarball. `cp -a` preserves the
# extracting user's uid/gid (and their umask's group-write bit), so after `sudo ./install.sh` the launcher
# behind /usr/local/bin/editora — a command every user on the machine runs — belonged to that one user, who
# (or anything running as them) could replace it. Hand the tree to root and drop group/other write.
# -h: change a symlink itself, never what it points at (the jlink runtime's legal/ files are symlinks).
if [ "$MODE" = "system" ]; then
    chown -R -h 0:0 "$DESTDIR$APPDIR"
    chmod -R go-w "$DESTDIR$APPDIR"
fi

mkdir -p "$(dirname "$DESTDIR$BINLINK")"
if [ ! -e "$DESTDIR$BINLINK" ] && [ ! -L "$DESTDIR$BINLINK" ] || is_our_link "$DESTDIR$BINLINK"; then
    ln -sf "$LAUNCHER" "$DESTDIR$BINLINK"
    LINKED=1
else
    echo "editora-install: $DESTDIR$BINLINK already exists and is not an Editora link — leaving it alone." >&2
    LINKED=0
fi

mkdir -p "$DESTDIR$DESKTOP_DIR"
{
    echo "[Desktop Entry]"
    echo "Type=Application"
    echo "Name=Editora"
    echo "GenericName=Text Editor"
    echo "Comment=A keyboard-driven, cross-platform programmer's text editor"
    # printf, not echo: dash's echo would swallow half of the backslashes the two helpers just wrote.
    printf 'Exec=%s %%F\n' "$(desktop_exec_arg "$LAUNCHER")"
    [ -f "$DESTDIR$ICON" ] && printf 'Icon=%s\n' "$(desktop_string "$ICON")"
    echo "Categories=Development;Utility;TextEditor;IDE;"
    echo "Terminal=false"
    echo "StartupNotify=true"
    echo "StartupWMClass=$WMCLASS"
    echo "MimeType=text/plain;"
} > "$DESTDIR$DESKTOP"
chmod 0644 "$DESTDIR$DESKTOP"
refresh_menu

echo "editora-install: installed."
echo "  app image : $DESTDIR$APPDIR"
if [ "$LINKED" = 1 ]; then
    echo "  command   : $DESTDIR$BINLINK  ->  $LAUNCHER"
else
    echo "  command   : (not linked) run $LAUNCHER"
fi
echo "  menu entry: $DESTDIR$DESKTOP"

# PATH hint (the per-user symlink dir isn't always on PATH).
case ":${PATH}:" in
    *":$(dirname "$BINLINK"):"*) : ;;
    *) echo ""; echo "  NOTE: $(dirname "$BINLINK") is not on your PATH — add it, e.g.:"
       echo "        echo 'export PATH=\"$(dirname "$BINLINK"):\$PATH\"' >> ~/.profile" ;;
esac

echo ""
echo "Run 'editora' (or launch it from your applications menu). Uninstall with: $0 --uninstall"
