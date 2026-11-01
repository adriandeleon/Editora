#!/usr/bin/env bash
# Checks what sign-linux-packages.sh produced against the public key alone, the way a user would:
# every download has a detached signature by that key, the rpm's embedded signature passes `rpm -K`,
# and the deb carries an origin signature over its own contents. Nothing here trusts the keyring
# that did the signing — a check run there would pass on a signature by any key it holds.
# Shared with Termina and Torrenta; a fix here belongs in all three.
#
# Needs gpgv, rpm, rpmkeys and ar; debsig-verify if installed.
#
# Usage: check-linux-signatures.sh <public-key> <dir>...
set -euo pipefail

key="${1:?public key required}"
shift
[ $# -gt 0 ] || { echo "usage: $0 <public-key> <dir>..." >&2; exit 2; }
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fail() { echo "::error::$*"; exit 1; }

# gpgv trusts exactly the keys in the keyring it is given, and wants it unarmoured.
gpg --batch --quiet --homedir "$work" --dearmor --output "$work/key.gpg" "$key"
fpr=$(gpg --batch --homedir "$work" --with-colons --import-options show-only --import "$key" |
    awk -F: '$1 == "fpr" { print $10; exit }')
[ -n "$fpr" ] || fail "$key holds no key"
keyid="${fpr: -16}"
echo "Checking against $fpr"

# rpm keeps its keys in its own database; this one holds only ours.
mkdir -p "$work/rpmdb"
rpm --define "_dbpath $work/rpmdb" --initdb
rpmkeys --define "_dbpath $work/rpmdb" --import "$key"

# debsig-verify reads a policy naming the key, under the key's id, from directories it is pointed at.
if command -v debsig-verify >/dev/null 2>&1; then
    mkdir -p "$work/debsig/policies/$keyid" "$work/debsig/keyrings/$keyid"
    cp "$work/key.gpg" "$work/debsig/keyrings/$keyid/editora.gpg"
    cat > "$work/debsig/policies/$keyid/editora.pol" <<EOF
<?xml version="1.0"?>
<!DOCTYPE Policy SYSTEM "https://www.debian.org/debsig/1.0/policy.dtd">
<Policy xmlns="https://www.debian.org/debsig/1.0/">
  <Origin Name="Editora" id="$keyid" Description="Editora releases"/>
  <Selection>
    <Required Type="origin" File="editora.gpg" id="$keyid"/>
  </Selection>
  <Verification MinOptional="0">
    <Required Type="origin" File="editora.gpg" id="$keyid"/>
  </Verification>
</Policy>
EOF
fi

shopt -s nullglob
checked=0
for dir in "$@"; do
    # Absolute: the deb is unpacked from inside a directory of its own.
    dir="$(cd "$dir" && pwd)"
    for file in "$dir"/*.deb "$dir"/*.rpm "$dir"/*.AppImage "$dir"/*.tar.gz; do
        name=$(basename "$file")
        [ -f "$file.asc" ] || fail "$name has no detached signature"
        gpgv --keyring "$work/key.gpg" "$file.asc" "$file" 2> "$work/gpgv.txt" ||
            { cat "$work/gpgv.txt"; fail "$name.asc does not verify against $fpr"; }
        echo "$name.asc: good signature"
        checked=$((checked + 1))
    done

    for rpm in "$dir"/*.rpm; do
        # Unsigned, this says "digests OK"; a signature by an unknown key, "NOKEY".
        out=$(rpmkeys --define "_dbpath $work/rpmdb" --checksig "$rpm") || { echo "$out"; fail "$(basename "$rpm") fails rpm -K"; }
        echo "$out"
        case "$out" in *"signatures OK"*) ;; *) fail "$(basename "$rpm") has no embedded signature" ;; esac
    done

    for deb in "$dir"/*.deb; do
        name=$(basename "$deb")
        ar t "$deb" > "$work/members.txt"
        grep -qx _gpgorigin "$work/members.txt" || fail "$name has no embedded origin signature"
        # The origin signature covers the three members dpkg reads, concatenated in archive order.
        rm -rf "$work/deb" && mkdir "$work/deb"
        (cd "$work/deb" && ar x "$deb")
        members=()
        while read -r member; do
            case "$member" in debian-binary|control.tar*|data.tar*) members+=("$work/deb/$member") ;; esac
        done < "$work/members.txt"
        cat "${members[@]}" > "$work/deb-signed"
        gpgv --keyring "$work/key.gpg" "$work/deb/_gpgorigin" "$work/deb-signed" 2> "$work/gpgv.txt" ||
            { cat "$work/gpgv.txt"; fail "$name's embedded signature does not verify against $fpr"; }
        dpkg-deb --info "$deb" > /dev/null || fail "$name no longer reads as a deb after signing"
        if command -v debsig-verify >/dev/null 2>&1; then
            debsig-verify --policies-dir "$work/debsig/policies" --keyrings-dir "$work/debsig/keyrings" "$deb" ||
                fail "debsig-verify rejects $name"
        fi
        echo "$name: embedded signature good"
    done
done
[ "$checked" -gt 0 ] || fail "no signed downloads in $*"
echo "signatures: OK"
