#!/usr/bin/env python3
"""Run the Java typing study on disposable copies and an immutable compiled runtime.

Run `mvn test` first to compile the probe and produce Surefire's dependency classpath.
The source projects are read only. Output and disposable copies remain under --output.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--maven-project', type=Path, required=True)
parser.add_argument('--gradle-project', type=Path, required=True)
parser.add_argument('--jdtls', type=Path, required=True)
parser.add_argument('--java-home', type=Path, required=True)
parser.add_argument('--gradle-java-home', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--seconds', type=int, default=1800)
parser.add_argument('--projects', default='maven,gradle', choices=['maven,gradle', 'maven', 'gradle'])
parser.add_argument('--typing-mode', default='events', choices=['events', 'macro'])
args = parser.parse_args()
repo = Path(__file__).resolve().parents[2]
out = args.output.resolve()
out.mkdir(parents=True, exist_ok=False)

for kind, source in [('maven', args.maven_project), ('gradle', args.gradle_project)]:
    source = source.resolve()
    if out.is_relative_to(source):
        raise SystemExit('--output must be outside both source projects')
    def ignore(directory, names):
        relative = Path(directory).relative_to(source)
        ignored = {'.git', '.gradle', '.idea', '.settings', '__pycache__', '.codex', '.editora'}
        if 'src' not in relative.parts:
            ignored |= {'target', 'build', 'node_modules', 'artifacts', 'dist'}
        return [name for name in names if name in ignored or Path(directory, name).is_symlink()]
    shutil.copytree(source, out / kind, ignore=ignore)
    (out / kind / '.editora-disposable-probe').write_text('Disposable Java typing study copy.\n')

reports = sorted((repo / 'target/surefire-reports').glob('TEST-*.xml'))
if not reports:
    raise SystemExit('Run mvn test first (missing Surefire dependency classpath).')
properties = ET.parse(reports[0]).getroot().find('properties')
classpath = next(p.attrib['value'] for p in properties if p.attrib.get('name') == 'java.class.path')
for name in ('classes', 'test-classes'):
    old = repo / 'target' / name
    new = out / 'runtime' / name
    shutil.copytree(old, new)
    classpath = classpath.replace(str(old), str(new))
java_home = args.java_home.resolve()
command = [str(java_home / 'bin/java'), '--enable-native-access=ALL-UNNAMED', '-Xmx2g',
           '-Dglass.platform=Headless', '-Dprism.order=sw', '-Djava.awt.headless=true',
           '-Deditora.completion.trace=true', '-Dlsp.java.probe.command=' + str(args.jdtls.resolve()),
           '-Dlsp.java.soak.maven=' + str(out / 'maven'), '-Dlsp.java.soak.gradle=' + str(out / 'gradle'),
           '-Dlsp.java.soak.gradleJava=' + str(args.gradle_java_home.resolve()),
           '-Dlsp.java.soak.seconds=' + str(args.seconds), '-Dlsp.java.soak.projects=' + args.projects,
           '-Dlsp.java.soak.typingMode=' + args.typing_mode,
           '-cp', classpath, 'com.editora.ui.JavaTypingSoakProbeTest']
env = dict(os.environ, JAVA_HOME=str(java_home))
env['PATH'] = str(java_home / 'bin') + os.pathsep + env.get('PATH', '')
print('Study output:', out, flush=True)
with (out / 'study.log').open('w') as log:
    result = subprocess.run(command, cwd=repo, env=env, stdout=log, stderr=subprocess.STDOUT)
print('Study exit:', result.returncode, flush=True)
raise SystemExit(result.returncode)
