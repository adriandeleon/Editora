#!/usr/bin/env python3
"""Run the evaluation probe against a local JDT LS, without changing production sources."""
import argparse
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--repo', action='store_true')
parser.add_argument('--build', action='store_true')
parser.add_argument('--generators', action='store_true')
args = parser.parse_args()
here = Path(__file__).resolve().parent
root = here.parents[2]
xml = root / 'target/surefire-reports/TEST-com.editora.lsp.LspManagerTest.xml'
if not xml.exists():
    raise SystemExit('Run mvn test in the evaluation worktree first.')
properties = ET.parse(xml).getroot().find('properties')
classpath = next(p.attrib['value'] for p in properties if p.attrib['name'] == 'java.class.path')
with tempfile.TemporaryDirectory(prefix='editora-evaluation-classes-') as classes:
    subprocess.run(['javac', '-cp', classpath, '-d', classes,
                    str(here / 'JavaLspEvaluationProbeTest.java'),
                    str(here / 'JavaLspEvaluationMain.java')], check=True, cwd=root)
    command = ['java', '-Dlsp.evaluation=true']
    if args.build:
        command.append('-Dlsp.evaluation.build=true')
    if args.generators:
        command.append('-Dlsp.evaluation.generators=true')
    command += ['-cp', classes + ':' + classpath, 'com.editora.lsp.JavaLspEvaluationMain']
    if args.repo:
        command.append('repo')
    result = subprocess.run(command, cwd=root)
    raise SystemExit(result.returncode)
