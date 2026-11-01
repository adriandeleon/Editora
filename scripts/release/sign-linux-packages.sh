#!/usr/bin/env bash
# Signs the Linux downloads in each directory with the release's OpenPGP key, in place, and writes a
# detached signature beside every one. Run by the release's sign-linux job on the finished packages;
# a manual dry run without the key runs it with a throw-away one, so the step is tested before a tag.
# Shared with Termina and Torrenta, which sign with the same key; a fix here belongs in all three.
#
# Two kinds of signature, because each format's own tools check a different thing:
#   .rpm       embedded (rpmsign): `rpm -K`, and dnf or zypper once the key is imported.
#   .deb       embedded, as an `_gpgorigin` member, which debsig-verify checks. apt and dpkg
#              check nothing about a .deb that was downloaded rather than fetched from a repository.
#   all four   a detached, armoured `<file>.asc`: `gpg --verify <file>.asc <file>`. The only
#              signature the AppImage and the tarball carry.
#
# The AppImage has no embedded signature because appimagetool can sign only while it builds, which
# would put the key on the build runners — and those run whatever Maven resolves from a repository
# outside Central. Signing happens in a job of its own that runs nothing of ours but this.
#
# The key comes from the environment: EDITORA_GPG_KEY, an ASCII-armoured secret key, and
# EDITORA_GPG_PASSPHRASE if it has one. Its public half is written to <public-key-out>. The release
# sets EDITORA_GPG_EXPECTED_KEY to the committed packaging/linux/signing-key.asc, and the key must be
# that one: a release signed by any other key would verify against the key published beside it and
# against nothing else.
#
# The deb is signed here rather than by debsigs, which does the same thing but knows only gzip
# members: given dpkg-deb's control.tar.xz and data.tar.xz it found neither, signed debian-binary
# alone, and exited 0. The format is debsigs': a detached signature over the three members dpkg
# reads, concatenated in archive order, appended as a member named `_gpgorigin`.
#
# Needs gpg, ar and rpmsign.
#
# Usage: sign-linux-packages.sh <public-key-out> <dir>...
set -euo pipefail

public_out="${1:?public key output required}"
shift
[ $# -gt 0 ] || { echo "usage: $0 <public-key-out> <dir>..." >&2; exit 2; }
[ -n "${EDITORA_GPG_KEY:-}" ] || { echo "::error::EDITORA_GPG_KEY is not set"; exit 1; }

GNUPGHOME="$(mktemp -d)"
export GNUPGHOME
trap 'gpgconf --kill gpg-agent 2>/dev/null || true; rm -rf "$GNUPGHOME"' EXIT

# Every gpg below, including the one rpmsign starts, reads this. Loopback lets the
# passphrase come from the file rather than from a pinentry no runner can answer.
{
    echo "batch"
    echo "no-tty"
    echo "pinentry-mode loopback"
} > "$GNUPGHOME/gpg.conf"
if [ -n "${EDITORA_GPG_PASSPHRASE:-}" ]; then
    printf '%s' "$EDITORA_GPG_PASSPHRASE" > "$GNUPGHOME/passphrase"
    echo "passphrase-file $GNUPGHOME/passphrase" >> "$GNUPGHOME/gpg.conf"
fi

printf '%s\n' "$EDITORA_GPG_KEY" | gpg --quiet --import
fpr=$(gpg --with-colons --list-secret-keys | awk -F: '$1 == "fpr" { print $10; exit }')
[ -n "$fpr" ] || { echo "::error::EDITORA_GPG_KEY holds no secret key"; exit 1; }
echo "Signing with $fpr"

expected="${EDITORA_GPG_EXPECTED_KEY:-}"
if [ -n "$expected" ]; then
    [ -f "$expected" ] || { echo "::error::no public key at $expected to check EDITORA_GPG_KEY against"; exit 1; }
    want=$(gpg --with-colons --import-options show-only --import "$expected" |
        awk -F: '$1 == "fpr" { print $10; exit }')
    [ "$want" = "$fpr" ] || { echo "::error::EDITORA_GPG_KEY is $fpr, but $expected is $want"; exit 1; }
fi
gpg --armor --export "$fpr" > "$public_out"

sign_deb() {
    local deb="$1" members=() member
    rm -rf "$GNUPGHOME/deb" && mkdir "$GNUPGHOME/deb"
    (cd "$GNUPGHOME/deb" && ar x "$deb")
    while read -r member; do
        case "$member" in
            debian-binary|control.tar*|data.tar*) members+=("$GNUPGHOME/deb/$member") ;;
            _gpgorigin) echo "::error::$(basename "$deb") is already signed"; exit 1 ;;
        esac
    done < <(ar t "$deb")
    [ "${#members[@]}" = 3 ] || { echo "::error::$(basename "$deb") does not have the three members of a deb"; exit 1; }
    cat "${members[@]}" | gpg --detach-sign --local-user "$fpr" --output "$GNUPGHOME/deb/_gpgorigin"
    ar q "$deb" "$GNUPGHOME/deb/_gpgorigin"
}

shopt -s nullglob
signed=0
for dir in "$@"; do
    # Absolute: the deb is unpacked from inside a directory of its own.
    dir="$(cd "$dir" && pwd)"
    for rpm in "$dir"/*.rpm; do
        # rpm before 4.19 names the key _gpg_name, from 4.19 _openpgp_sign_id; both are given.
        rpmsign --addsign --define "_gpg_path $GNUPGHOME" --define "_gpg_name $fpr" \
            --define "_openpgp_sign_id $fpr" "$rpm"
    done
    for deb in "$dir"/*.deb; do
        sign_deb "$deb"
    done
    # After the embedded signatures, which change the files they are added to.
    for file in "$dir"/*.deb "$dir"/*.rpm "$dir"/*.AppImage "$dir"/*.tar.gz; do
        gpg --armor --detach-sign --local-user "$fpr" --output "$file.asc" "$file"
        echo "signed $(basename "$file")"
        signed=$((signed + 1))
    done
done
[ "$signed" -gt 0 ] || { echo "::error::nothing to sign in $*"; exit 1; }
