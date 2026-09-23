#!/usr/bin/env python3
"""Bundle one host-built experimental Native Image and its adjacent shared libraries."""
import argparse
import os
from pathlib import Path
import re
import shutil
import tarfile
import tempfile
import zipfile


SHELL_LAUNCHER = '''#!/usr/bin/env bash
set -euo pipefail
bundle="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
export EDITORA_CONFIG_DIR="${EDITORA_NATIVE_CONFIG_DIR:-%s}"
exec "$bundle/editora-native" -XX:MissingRegistrationReportingMode=Exit -Xmx2g -Xms64m "$@"
'''
WINDOWS_LAUNCHER = '''@echo off
setlocal
if not defined EDITORA_NATIVE_CONFIG_DIR set "EDITORA_NATIVE_CONFIG_DIR=%APPDATA%\\EditoraNativeExperimental"
set "EDITORA_CONFIG_DIR=%EDITORA_NATIVE_CONFIG_DIR%"
"%~dp0editora-native.exe" -XX:MissingRegistrationReportingMode=Exit -Xmx2g -Xms64m %*
exit /b %errorlevel%
'''
README = '''Editora experimental Native Image (%s)

From this extracted directory, run:
  %s [path/to/file]

The launcher uses separate settings. Set EDITORA_NATIVE_CONFIG_DIR to choose
another directory. Keep all adjacent shared libraries beside the binary.
No Java installation is needed. This is a portable archive, not an installer.
The build is unsigned; macOS may require removing quarantine after inspection.

This build is experimental. Benchmarks found slower tokenization and input
latency than the JVM build. External Java plugins cannot load into this
closed-world image. LSP, debugging, SSH and other peripheral features have
not been qualified. Keep the ordinary Editora release available for daily use.

Experiment and measured results:
https://github.com/adriandeleon/Editora/blob/master/docs/native-image-staticfx.md
'''


def package(binary, version, target, output):
    if not re.fullmatch(r'[0-9A-Za-z][0-9A-Za-z.+-]*', version):
        raise ValueError('invalid version')
    if target not in ('linux-x64', 'macos-x64', 'macos-arm64', 'windows-x64'):
        raise ValueError('unsupported native target')
    binary = binary.resolve(strict=True)
    if not binary.is_file():
        raise ValueError('native executable is not a file')
    windows = target.startswith('windows-')
    if windows != (binary.suffix.lower() == '.exe'):
        raise ValueError('native executable extension does not match target')
    extension = '.dll' if windows else '.dylib' if target.startswith('macos-') else '.so'
    libraries = sorted(p for p in binary.parent.iterdir() if p.is_file() and p.suffix == extension)
    # Native Image can produce a stand-alone Windows/macOS executable. Linux's AWT
    # path is known to emit adjacent .so files and needs them in the archive.
    if target == 'linux-x64' and not libraries:
        raise ValueError('Native Image emitted no adjacent .so libraries')
    name = f'Editora-{version}-{target}-native-experimental'
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as scratch:
        root = Path(scratch) / name
        root.mkdir()
        shutil.copy2(binary, root / ('editora-native.exe' if windows else 'editora-native'))
        for library in libraries:
            shutil.copy2(library, root / library.name)
        if windows:
            launcher = root / 'run-editora-native.cmd'
            launcher.write_text(WINDOWS_LAUNCHER, newline='\r\n')
        else:
            config = ('${XDG_CONFIG_HOME:-$HOME/.config}/editora-native-experimental'
                      if target == 'linux-x64' else
                      '${HOME}/Library/Application Support/EditoraNativeExperimental')
            launcher = root / 'run-editora-native'
            launcher.write_text(SHELL_LAUNCHER % config)
            launcher.chmod(0o755)
        (root / 'README.txt').write_text(README %
            (target, 'run-editora-native.cmd' if windows else './run-editora-native'))
        archive = output / (name + ('.zip' if windows else '.tar.gz'))
        if windows:
            with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as bundle:
                for path in sorted(root.iterdir()):
                    bundle.write(path, f'{name}/{path.name}')
        else:
            with tarfile.open(archive, 'w:gz') as bundle:
                bundle.add(root, arcname=name)
    return archive


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('binary', type=Path)
    parser.add_argument('version')
    parser.add_argument('target')
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    print(package(args.binary, args.version, args.target, args.output))


if __name__ == '__main__':
    main()
