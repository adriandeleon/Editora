#!/usr/bin/env python3
"""Minimal stdlib-only JDT LS same-file type/import reproduction (no Editora code).

Pass --jdtls /path/to/jdtls; its Java launcher must run on a supported JDK.
Only a temporary Maven project is created. No report is submitted to an external service.
"""
import argparse
import json
import os
from pathlib import Path
import queue
import re
import subprocess
import tempfile
import threading
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--jdtls', required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--supersede-before-resolve', action='store_true',
                    help='also check whether an unchanged second completion invalidates the first item')
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='jdt-import-conflict-') as temporary:
    root = Path(temporary) / 'project'
    folder = root / 'src/main/java/demo'
    folder.mkdir(parents=True)
    (root / 'pom.xml').write_text('<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId>'
                                '<artifactId>conflict</artifactId><version>1</version>'
                                '<properties><maven.compiler.release>25</maven.compiler.release></properties></project>\n')
    text = 'package demo;\nclass ArrayList {}\nclass Conflict { void run() { new ArrayLi } }\n'
    file = folder / 'Conflict.java'
    file.write_text(text)
    env = os.environ.copy()
    # Joining isolates the semantic conflict from pending didChange/lifecycle races.
    env['JDK_JAVA_OPTIONS'] = env.get('JDK_JAVA_OPTIONS', '') + ' -Djava.lsp.joinOnCompletion=true'
    stderr = (args.output / 'server-stderr.log').open('w')
    process = subprocess.Popen([args.jdtls, '-data', str(Path(temporary) / 'workspace')],
                               cwd=root, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr)
    messages = queue.Queue()
    def read_messages():
        try:
            while True:
                headers = {}
                while True:
                    line = process.stdout.readline()
                    if not line:
                        raise EOFError('server closed its output')
                    if line in (b'\r\n', b'\n'):
                        break
                    key, value = line.decode('ascii').split(':', 1)
                    headers[key.lower()] = value.strip()
                length = int(headers['content-length'])
                body = bytearray()
                while len(body) < length:
                    part = process.stdout.read(length - len(body))
                    if not part:
                        raise EOFError('truncated response')
                    body.extend(part)
                messages.put(json.loads(body))
        except Exception as error:
            messages.put(error)
    threading.Thread(target=read_messages, daemon=True).start()
    def send(message):
        data = json.dumps(dict(jsonrpc='2.0', **message)).encode()
        process.stdin.write(f'Content-Length: {len(data)}\r\n\r\n'.encode() + data)
        process.stdin.flush()
    sequence = 0
    def request(method, params, timeout=90):
        global sequence
        sequence += 1
        ident = sequence
        send(dict(id=ident, method=method, params=params))
        deadline = time.monotonic() + timeout
        while True:
            message = messages.get(timeout=max(.01, deadline - time.monotonic()))
            if isinstance(message, Exception):
                raise message
            if message.get('id') == ident and 'method' not in message:
                if 'error' in message:
                    raise RuntimeError(message['error'])
                return message.get('result')
            if 'method' in message and 'id' in message:
                result = None
                if message['method'] == 'workspace/configuration':
                    result = [{} for _ in message.get('params', {}).get('items', [])]
                send(dict(id=message['id'], result=result))
    def offset(position):
        lines = text.splitlines(keepends=True)
        return sum(len(line) for line in lines[:position['line']]) + position['character']
    try:
        initialize = dict(processId=os.getpid(), rootUri=root.as_uri(),
                          workspaceFolders=[dict(uri=root.as_uri(), name='conflict')],
                          capabilities=dict(textDocument=dict(completion=dict(completionItem=dict(
                              snippetSupport=True, insertReplaceSupport=True,
                              resolveSupport=dict(properties=['additionalTextEdits']))))),
                          initializationOptions=dict(settings=dict(java=dict(autobuild=dict(enabled=False)))))
        server = request('initialize', initialize)
        send(dict(method='initialized', params={}))
        send(dict(method='textDocument/didOpen', params=dict(textDocument=dict(uri=file.as_uri(), languageId='java', version=1, text=text))))
        position = dict(line=2, character=text.splitlines()[2].index('ArrayLi') + len('ArrayLi'))
        params = dict(textDocument=dict(uri=file.as_uri()), position=position, context=dict(triggerKind=1))
        deadline = time.monotonic() + 120
        candidate = None
        while time.monotonic() < deadline:
            result = request('textDocument/completion', params)
            items = result if isinstance(result, list) else (result or {}).get('items', [])
            candidate = next((item for item in items if item.get('label', '').startswith('ArrayList')
                              and 'java.util.ArrayList' in item.get('detail', '')), None)
            if candidate:
                break
            time.sleep(.15)
        if candidate is None:
            raise RuntimeError('java.util.ArrayList completion unavailable')
        if args.supersede_before_resolve:
            newer = request('textDocument/completion', params)
            lifetime = dict(source=text, completionRequest=params, firstItem=candidate,
                            interveningRequest='identical completion; no document change')
            try:
                lifetime['resolvedFirstItem'] = request('completionItem/resolve', candidate)
            except RuntimeError as error:
                lifetime['resolveError'] = str(error)
            (args.output / 'proposal-lifetime.json').write_text(
                json.dumps(lifetime, indent=2).replace(root.as_uri(), 'file:///fixture/project') + '\n')
            print(json.dumps(dict(oldItemInvalidated='resolveError' in lifetime)))
            items = newer if isinstance(newer, list) else (newer or {}).get('items', [])
            candidate = next(item for item in items if item.get('label', '').startswith('ArrayList')
                             and 'java.util.ArrayList' in item.get('detail', ''))
        resolved = request('completionItem/resolve', candidate)
        evidence = dict(source=text, initialize=initialize, serverInfo=server.get('serverInfo'),
                        completionRequest=params, completionItem=candidate, resolved=resolved)
        # Fixture paths are disposable; keep the transcript portable and free of machine-specific paths.
        transcript = json.dumps(evidence, indent=2).replace(root.as_uri(), 'file:///fixture/project')
        (args.output / 'protocol.json').write_text(transcript + '\n')
        edits = list(resolved.get('additionalTextEdits') or [])
        main = resolved.get('textEdit') or candidate.get('textEdit')
        if main:
            main = dict(main)
            main['range'] = main.get('range') or main.get('replace') or main.get('insert')
            main['newText'] = re.sub(r'\$\{\d+(?::([^}]*))?\}', lambda m: m.group(1) or '', main['newText'])
            main['newText'] = re.sub(r'\$\d+', '', main['newText'])
            edits.append(main)
        after = text
        for edit in sorted(edits, key=lambda e: offset(e['range']['start']), reverse=True):
            after = after[:offset(edit['range']['start'])] + edit['newText'] + after[offset(edit['range']['end']):]
        (args.output / 'Conflict.java').write_text(after)
        conflict = 'import java.util.ArrayList;' in after and 'class ArrayList {}' in after
        print(json.dumps(dict(sameFileImportConflict=conflict, serverInfo=server.get('serverInfo'))))
        if not conflict:
            raise SystemExit('Conflict did not reproduce; retain the protocol response for comparison.')
    finally:
        try:
            request('shutdown', None, timeout=10)
            send(dict(method='exit'))
            process.wait(timeout=10)
        except Exception:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        stderr.close()
