#!/usr/bin/env python3
"""Release gates run by .github/workflows/release.yml.

  check-release.py version <ref-type> <ref-name> <pom.xml>
      Preflight, before any build leg starts: on a tag build the tag must be a version
      (vX.Y.Z or vX.Y.Z-rcN) and, unless it is an -rc tag, equal the pom's <version>.

  check-release.py assets <artifacts-dir> <version> --pom <pom.xml> [--mode enforce|report]
      Before JReleaser publishes: every installer the build matrix is supposed to produce
      must be present and non-empty. GitHub releases are immutable once published, and
      JReleaser's file globs happily match nothing, so a release that lost its .deb to a
      failed wrap would otherwise publish without it and could never be completed.

Both exit 0 when the release may proceed and 1 with a list of what is wrong otherwise.
"""
import argparse
from pathlib import Path
import re
import sys


# What each build leg stages (the "Stage artifacts" step of release.yml), by target. Linux arm64
# deliberately ships only the tarball and the fat jar; macOS ships only the .dmg. Keep this in step
# with that step and with docs/release.md — test_check_release.py pins the full list.
EXPECTED = {
    'linux-x64': ('deb', 'rpm', 'AppImage', 'tar.gz', 'jar'),
    'linux-arm64': ('tar.gz', 'jar'),
    'macos-x64': ('dmg',),
    'macos-arm64': ('dmg',),
    'windows-x64': ('msi', 'jar'),
}

# Best-effort archives from the native-experimental matrix: reported, never required.
OPTIONAL = {
    'linux-x64': 'tar.gz',
    'macos-x64': 'tar.gz',
    'macos-arm64': 'tar.gz',
    'windows-x64': 'zip',
}

TAG = re.compile(r'v(\d+\.\d+\.\d+)(-rc[0-9A-Za-z.]*)?')


def pom_version(pom):
    """The project's own <version>: the first one in the file, as the workflow's grep -m1 reads it."""
    match = re.search(r'<version>\s*([^<\s]+)\s*</version>', Path(pom).read_text(encoding='utf-8'))
    if not match:
        raise ValueError(f'no <version> found in {pom}')
    return match.group(1)


def check_version(ref_type, ref_name, pom):
    """Returns a list of problems (empty when the build may proceed)."""
    if ref_type != 'tag':
        return []  # a manual dry run from a branch: nothing to compare
    match = TAG.fullmatch(ref_name)
    if not match:
        return [f"tag '{ref_name}' is not a release tag: expected vX.Y.Z or vX.Y.Z-rcN"]
    if match.group(2):
        return []  # an -rc is cut from the -SNAPSHOT line; the pom is not expected to match it
    tagged, declared = match.group(1), pom_version(pom)
    if declared != tagged:
        hint = (' — drop the -SNAPSHOT suffix before tagging (docs/release.md, step 1)'
                if declared.endswith('-SNAPSHOT') else '')
        return [f"tag '{ref_name}' does not match the pom version '{declared}'{hint}"]
    return []


def expected_assets(version, jar_version):
    """Every required file name. Fat jars carry the POM version, everything else the release version."""
    names = []
    for target, extensions in EXPECTED.items():
        for extension in extensions:
            stem = jar_version if extension == 'jar' else version
            names.append(f'Editora-{stem}-{target}.{extension}')
    return names


def optional_assets(version):
    return [f'Editora-{version}-{target}-native-experimental.{extension}'
            for target, extension in OPTIONAL.items()]


def present(root):
    """Names of the non-empty files anywhere under the downloaded-artifacts directory."""
    root = Path(root)
    if not root.is_dir():
        return set()
    return {path.name for path in root.rglob('*') if path.is_file() and path.stat().st_size > 0}


def missing_assets(root, version, jar_version):
    found = present(root)
    return [name for name in expected_assets(version, jar_version) if name not in found]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    version = commands.add_parser('version')
    version.add_argument('ref_type')
    version.add_argument('ref_name')
    version.add_argument('pom', type=Path)
    assets = commands.add_parser('assets')
    assets.add_argument('artifacts', type=Path)
    assets.add_argument('version')
    assets.add_argument('--pom', type=Path, required=True)
    assets.add_argument('--mode', choices=('enforce', 'report'), default='enforce')
    args = parser.parse_args(argv)

    if args.command == 'version':
        problems = check_version(args.ref_type, args.ref_name, args.pom)
        for problem in problems:
            print(f'::error title=Release preflight::{problem}')
        if not problems:
            print(f"preflight ok: {args.ref_type} '{args.ref_name}', pom {pom_version(args.pom)}")
        return 1 if problems else 0

    found = present(args.artifacts)
    missing = missing_assets(args.artifacts, args.version, pom_version(args.pom))
    for name in expected_assets(args.version, pom_version(args.pom)):
        print(f"  {'MISSING' if name in missing else 'ok     '}  {name}")
    for name in optional_assets(args.version):
        print(f"  {'ok     ' if name in found else 'absent '}  {name} (experimental, optional)")
    if not missing:
        print('all expected release assets are present')
        return 0
    level = 'error' if args.mode == 'enforce' else 'warning'
    print(f"::{level} title=Release assets missing::{len(missing)} expected asset(s) missing: {', '.join(missing)}")
    if args.mode == 'enforce':
        print('refusing to publish: a GitHub release is immutable, so these could never be added later.',
              file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
