---
title: "Orphaned language servers and the symlink that ate my diagnostics"
description: Two LSP integration bugs — a process that wouldn't die and diagnostics that silently vanished — and what they taught me about subprocesses and paths.
date: 2026-06-08
author: Adrian De Leon
tags: [lsp, debugging]
---

Adding Language Server Protocol support to Editora meant managing external server
processes and reconciling their view of the filesystem with the editor's. Two
bugs from that work are worth telling, because both were silent and both had a
satisfying root cause.

## Bug 1: the language server that wouldn't die

Symptom: open a Java project, close it, open it again: and now there are no
diagnostics, no completion, just a server that seems to hang on startup.

The cause was a zombie. Disposing a session killed the process I launched, but
`jdtls` (and several other servers) isn't the real server; it's a **wrapper
script** (Homebrew's `jdtls` → python → java). Destroy only the wrapper and the
real server JVM is **orphaned**, still running, still holding its Eclipse
workspace `.lock`. The next session for that project can't acquire the lock and
hangs.

The fix is to kill the whole tree: snapshot `process.descendants()`, destroy the
descendants first, then the root. Dispose now takes the real server down with the
wrapper.

There was a sibling bug in the same area: the server's stderr was a PIPE that
nothing drained. A chatty server (jdtls logs *a lot*) fills the OS pipe buffer
(~64 KB) and then blocks mid-startup, waiting for someone to read it. The fix is
boring and important — `Redirect.DISCARD` the stderr; the LSP traffic is on
stdout anyway.

## Bug 2: diagnostics that silently vanished

Symptom: on some projects, squiggles and the Problems panel were simply empty.
No error. The server was clearly running and reporting.

The culprit was **symlinks**. A language server reports diagnostics under the
file's *real* URI. On macOS, `/tmp` is a symlink to `/private/tmp`, and plenty of
people keep projects under symlinked directories. So the server says "problem in
`/private/tmp/Foo.java`," while the open buffer remembers the path you opened,
`/tmp/Foo.java`. Editora matched them by `normalize()`, which doesn't resolve
symlinks, so **every diagnostic was dropped on the floor**.

The fix is to match by **canonical** (symlink-resolved) path: `toRealPath`, with
a normalize fallback. Everywhere a server-reported path is reconciled with an
open tab. It's a one-line idea with a unit test guarding it, and it's the
difference between "LSP is broken" and "LSP works."

## What they have in common

Both bugs were invisible: no stack trace, no error dialog, just a feature
quietly not working. And both came from a mismatch between what I *thought* I was
managing (a process, a path) and what the OS actually had (a process *tree*, a
*real* path). When you integrate external tools, those two gaps: descendant
processes and canonical path, are worth checking first.
