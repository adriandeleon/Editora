#!/usr/bin/env python3
"""Replay an unqualified Windows archive under alternative JavaFX startup modes."""
import json
from pathlib import Path
import subprocess
import sys
import zipfile


def main(archive):
    output = Path('native-candidate-diagnostics').resolve()
    output.mkdir(exist_ok=False)
    with zipfile.ZipFile(archive) as bundle:
        bundle.extractall(output / 'bundle')
    binaries = list((output / 'bundle').glob('*/editora-native.exe'))
    if len(binaries) != 1:
        raise ValueError(f'expected one native executable, found {len(binaries)}')
    binary = binaries[0]
    options = ('-Xmx2g', '-Xms64m', '-Dprism.order=sw', '-Dprism.verbose=true',
               '-Deditora.debug.stderr=true')
    cases = (
        ('headless-strict', '-XX:MissingRegistrationReportingMode=Exit', '-Dglass.platform=Headless'),
        ('headless-warn', '-XX:MissingRegistrationReportingMode=Warn', '-Dglass.platform=Headless'),
        ('desktop-strict', '-XX:MissingRegistrationReportingMode=Exit'),
    )
    for name, *extra in cases:
        application = [str(binary), *extra, *options]
        config = output / f'{name}.json'
        config.write_text(json.dumps({'modes': {name: {'application': application}}}))
        print(f'===== {name}: {application} =====', flush=True)
        try:
            result = subprocess.run(
                [sys.executable, str(Path(__file__).with_name('app-probe.py')), str(config),
                 '--output', str(output / name), '--runs', '1', '--warmups', '0', '--cycles', '3'],
                check=False, timeout=180)
            print(f'{name} probe exit: {result.returncode}', flush=True)
        except subprocess.TimeoutExpired:
            print(f'{name} probe timed out', flush=True)
        for log in sorted((output / name).glob('*/app.log')):
            print(f'===== {log} =====', flush=True)
            print('\n'.join(log.read_text(errors='replace').splitlines()[-100:]), flush=True)
        for log in sorted((output / name).glob('*/config/editora-session.log')):
            print(f'===== {log} =====', flush=True)
            print('\n'.join(log.read_text(errors='replace').splitlines()[-100:]), flush=True)


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('usage: diagnose-windows-candidate.py ARCHIVE.candidate')
    main(Path(sys.argv[1]).resolve(strict=True))
