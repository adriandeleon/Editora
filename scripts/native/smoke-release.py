#!/usr/bin/env python3
"""Extract and exercise the exact experimental archive before publishing it."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import zipfile


def smoke(archive, target, output):
    name = archive.name.removesuffix('.zip').removesuffix('.tar.gz')
    if not name.endswith(f'-{target}-native-experimental'):
        raise ValueError('archive name does not match target')
    extracted = output / 'bundle'
    extracted.mkdir(parents=True)
    if archive.suffix == '.zip':
        with zipfile.ZipFile(archive) as bundle:
            bundle.extractall(extracted)
    else:
        with tarfile.open(archive, 'r:gz') as bundle:
            bundle.extractall(extracted, filter='data')
    root = extracted / name
    windows = target.startswith('windows-')
    launcher = root / ('run-editora-native.cmd' if windows else 'run-editora-native')
    binary = root / ('editora-native.exe' if windows else 'editora-native')
    if windows:
        subprocess.run(subprocess.list2cmdline([str(launcher), '--version']),
                       shell=True, check=True, timeout=30)
        # The .cmd launcher is separately checked above. The GUI probe starts
        # the exact bundled executable so process teardown owns the app PID.
        application = [str(binary)]
    else:
        subprocess.run([str(launcher), '--version'], check=True, timeout=30)
        application = [str(launcher)]
    config = output / 'config.json'
    config.write_text(json.dumps({'modes': {'native': {'application': application}}}))
    command = [sys.executable, str(Path(__file__).with_name('app-probe.py')),
               str(config), '--output', str(output / 'probe'),
               '--runs', '1', '--warmups', '0', '--cycles', '3']
    try:
        subprocess.run(command, check=True, timeout=240)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        for log in sorted((output / 'probe').glob('*/app.log')):
            print(f'===== {log} (last 100 lines) =====', file=sys.stderr)
            print('\n'.join(log.read_text(errors='replace').splitlines()[-100:]), file=sys.stderr)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('target')
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    smoke(args.archive.resolve(strict=True), args.target, output)


if __name__ == '__main__':
    main()
