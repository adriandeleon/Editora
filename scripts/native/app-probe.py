#!/usr/bin/env python3
"""Exercise the unchanged application via its existing loopback MCP interface.

Each run creates its own config and disposable Git project. No user files or settings are edited.
Commands come from config['modes'][name]['application'] (argv arrays, without file arguments).
The shipped AOT launcher can be measured without changing its main class or module graph.
HTTP/FX dispatch latency is end-to-end automation latency, not a keyboard/frame microbenchmark.
"""
import argparse
import collections
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time
import urllib.request

from benchmark import rss_bytes, summarize

ROOT = Path(__file__).resolve().parents[2]


def require(ok, message):
    if not ok:
        raise AssertionError(message)


def published_json(path):
    """Discovery files can exist briefly before their writer has finished publishing JSON."""
    try:
        return json.loads(path.read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return None


def run(command, env, folder, cycles=30):
    folder.mkdir()
    config = folder / 'config'
    project = folder / 'project'
    config.mkdir()
    project.mkdir()
    schema = int(re.search(r'SCHEMA_VERSION = (\d+)',
                          (ROOT / 'src/main/java/com/editora/config/Settings.java').read_text()).group(1))
    (config / 'settings.json').write_text(json.dumps({
        'schemaVersion': schema, 'mcpSupport': True, 'projectSupport': True,
        'updateCheck': False, 'lspSupport': False,
    }))
    initial = ('public class Example { int nativeProbeValue = 1; }\n' + '// filler\n' * 10240)[:102400]
    file = project / 'Example.java'
    file.write_text(initial)
    (project / 'pom.xml').write_text('<project><modelVersion>4.0.0</modelVersion><groupId>probe</groupId>'
                                   '<artifactId>probe</artifactId><version>1</version></project>\n')
    for args in (['init', '-q', '--initial-branch=probe'], ['add', '.'],
                 ['-c', 'user.name=Editora Probe', '-c', 'user.email=probe@example.invalid',
                  '-c', 'commit.gpgsign=false', 'commit', '-qm', 'fixture']):
        subprocess.run(['git', '-C', str(project)] + args, check=True, capture_output=True)
    command = command + ['--config-dir', str(config), '--no-session', '--project', str(project), str(file)]
    env = dict(os.environ, **env, EDITORA_CONFIG_DIR=str(config), EDITORA_PERF='1', EDITORA_PERF_EXIT='0',
               EDITORA_PERF_T0=str(time.time_ns() // 1_000_000))
    samples = collections.defaultdict(list)
    memory = {}
    milestones = {}
    log_path = folder / 'app.log'
    started = time.monotonic()
    with log_path.open('w') as log:
        proc = subprocess.Popen(command, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    deadline = started + 120 + cycles
    endpoint = None
    request_id = 0

    def alive():
        require(proc.poll() is None, f'application exited {proc.returncode}; see {log_path}')
        require(time.monotonic() < deadline, 'application workflow deadline exceeded')

    def rpc(method, params):
        nonlocal request_id
        alive()
        request_id += 1
        req = urllib.request.Request(endpoint['url'], json.dumps({
            'jsonrpc': '2.0', 'id': request_id, 'method': method, 'params': params,
        }).encode(), {'Authorization': 'Bearer ' + endpoint['token'], 'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=15) as response:
            result = json.load(response)
        require('error' not in result, 'JSON-RPC error: ' + str(result.get('error')))
        return result['result']

    def call(name, **arguments):
        result = rpc('tools/call', {'name': name, 'arguments': arguments})
        require(not result.get('isError'), 'MCP tool failed: ' + name + ': ' + str(result.get('content')))
        return json.loads(result['content'][0]['text'])

    def text():
        return call('read_buffer', path=str(file))['text']

    def timed(name, action):
        start = time.monotonic()
        result = action()
        samples[name].append((time.monotonic() - start) * 1000)
        return result

    def mark(name):
        milestones[name] = (time.monotonic() - started) * 1000
        memory[name] = rss_bytes(proc.pid)

    passed = False
    failure = None
    try:
        while endpoint is None:
            alive()
            endpoint = published_json(config / 'mcp-endpoint.json')
            if endpoint is None:
                time.sleep(.01)
        rpc('initialize', {'protocolVersion': '2024-11-05', 'capabilities': {},
                           'clientInfo': {'name': 'editora-native-probe', 'version': '1'}})
        # Wait for both actual file paint and a populated buffer. Stage.show alone is insufficient.
        while '[perf] first-paint' not in log_path.read_text():
            alive()
            time.sleep(.01)
        require(text() == initial, 'initial file oracle')
        mark('painted-file-and-project')
        call('edit_buffer', path=str(file), old_text='nativeProbeValue = 1', new_text='nativeProbeValue = 2')
        changed = initial.replace('nativeProbeValue = 1', 'nativeProbeValue = 2')
        require(text() == changed, 'first editable-file oracle')
        mark('first-file-editable')
        call('execute_command', id='edit.undo')
        require(text() == initial, 'first undo')
        call('execute_command', id='edit.redo')
        require(text() == changed, 'first redo')
        # MCP preserves ordinary adjacent-edit merging. Undo between cycles gives an explicit
        # boundary without changing production behavior or assuming one history step per request.
        cycle = initial.replace('nativeProbeValue = 1', 'nativeProbeValue = 3')
        for _ in range(cycles):
            timed('edit-via-mcp', lambda: call('edit_buffer', path=str(file),
                  old_text='nativeProbeValue = 2', new_text='nativeProbeValue = 3'))
            require(text() == cycle, 'cycle edit oracle')
            timed('undo-via-mcp', lambda: call('execute_command', id='edit.undo'))
            require(text() == changed, 'cycle undo oracle')
            timed('redo-via-mcp', lambda: call('execute_command', id='edit.redo'))
            require(text() == cycle, 'cycle redo oracle')
            call('execute_command', id='edit.undo')
        call('edit_buffer', path=str(file), old_text='nativeProbeValue = 2', new_text='nativeProbeValue = 202')
        final = initial.replace('nativeProbeValue = 1', 'nativeProbeValue = 202')
        require(text() == final, 'final edit oracle')
        def save_and_wait():
            call('save_buffer', path=str(file))
            while file.read_text() != final:
                alive()
                time.sleep(.005)
        timed('save-settled-via-mcp', save_and_wait)
        mark('after-editing-and-save')
        hits = timed('project-search-via-mcp', lambda: call('find_in_files', query='nativeProbeValue'))
        require(any(h['file'] == str(file) for h in hits), 'project search contains fixture')
        mark('project-search-responsive')
        status = call('git_status')
        require(status['repo'] and any(f['path'] == 'Example.java' for f in status['files']),
                'Git sees the saved modification')
        require(text() == final, 'final buffer oracle')
        history = config / 'history/index.json'
        expected_hash = hashlib.sha256(final.encode()).hexdigest()
        stored = None
        while stored is None or expected_hash not in json.dumps(stored):
            alive()
            stored = published_json(history)
            time.sleep(.005)
        require(any(revision['sha256'] == expected_hash
                    for files in stored['byProject'].values()
                    for revisions in files.values() for revision in revisions), 'local history persisted')
        alive()
        passed = True
    except (AssertionError, OSError, ValueError, KeyError) as error:
        failure = str(error)
    finally:
        # A harness-owned disposable process; no app quit dialog can leave it running after failure.
        if proc.poll() is not None and proc.returncode != 0:
            passed = False
            failure = f'application exited {proc.returncode}; see {log_path}'
        if proc.poll() is None:
            os.killpg(proc.pid, signal.SIGTERM)
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(proc.pid, signal.SIGKILL)
                proc.wait()
    marks = dict((name, int(ms)) for name, ms in re.findall(r'\[perf\]\s+([\w-]+)\s+(\d+)\s+\(', log_path.read_text()))
    return {'ok': passed, 'failure': failure, 'command': command, 'milestones_ms': milestones,
            'rss_bytes': memory, 'startup_marks_ms': marks, 'samples_ms': dict(samples),
            'final_sha256': hashlib.sha256(file.read_bytes()).hexdigest(), 'log': str(log_path)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('config', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--runs', type=int, default=5)
    parser.add_argument('--warmups', type=int, default=1)
    parser.add_argument('--cycles', type=int, default=30)
    args = parser.parse_args()
    require(args.runs > 0 and args.warmups >= 0 and 1 <= args.cycles <= 2000, 'invalid run/cycle count')
    config = json.loads(args.config.read_text())
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=False)
    (args.output / 'config.json').write_text(json.dumps(config, indent=2) + '\n')
    modes = {name: mode for name, mode in config['modes'].items() if 'application' in mode}
    require(bool(modes), 'no application commands configured')
    names = list(modes)
    results = []
    for iteration in range(-args.warmups, args.runs):
        shift = iteration % len(names)
        for name in names[shift:] + names[:shift]:
            mode = modes[name]
            result = run(mode['application'], mode.get('env', {}), args.output / f'{name}-{iteration}', args.cycles)
            result.update(mode=name, iteration=iteration)
            results.append(result)
            (args.output / 'raw.json').write_text(json.dumps(results, indent=2) + '\n')
            print(f"{name}-{iteration}: {'PASS' if result['ok'] else 'FAIL: ' + str(result['failure'])}", flush=True)
    buckets = collections.defaultdict(list)
    for result in results:
        if not result['ok'] or result['iteration'] < 0:
            continue
        for kind in ('milestones_ms', 'rss_bytes', 'startup_marks_ms', 'samples_ms'):
            for name, value in result[kind].items():
                if value is not None:
                    buckets[f"{result['mode']}/{kind}/{name}"].extend(value if isinstance(value, list) else [value])
    failures = [{k: r[k] for k in ('mode', 'iteration', 'failure', 'log')} for r in results if not r['ok']]
    hashes = {r['final_sha256'] for r in results if r['ok']}
    report = {'runs': args.runs, 'warmups': args.warmups, 'cycles': args.cycles, 'failures': failures,
              'final_files_equal': len(hashes) == 1, 'metrics': {k: summarize(v) for k, v in sorted(buckets.items())},
              'limitations': ['MCP requests add HTTP serialization, polling and FX queue delay.',
                              'Project search response is not proof that every background subsystem is ready.',
                              'RSS is the application process, excluding Git/LSP children; no heap metric here.',
                              'MCP enabled, update checks/LSP disabled in a fresh disposable config.',
                              'Short edit/undo/redo cycles are not hours of steady-state use.']}
    (args.output / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
    return 1 if failures or len(hashes) != 1 else 0


if __name__ == '__main__':
    raise SystemExit(main())
