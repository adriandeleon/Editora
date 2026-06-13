---
title: "One DAP client, three debuggers"
description: How Editora debugs Java, Python, and JavaScript through a single Debug Adapter Protocol client — three very different transports behind one UI.
date: 2026-06-11
author: Adrian De Leon
tags: [debugging, dap]
---

Editora can debug Java, Python, and JavaScript, and it does it with **one**
client. The Debug Adapter Protocol (DAP) is the same idea as LSP but for
debuggers: a common wire protocol so an editor doesn't need bespoke code per
language. The trick is that the three adapters are reached three completely
different ways.

## Three transports, one protocol

- **Java** is layered on the existing jdtls language-server session. jdtls is
  started with Microsoft's java-debug plugin, which registers
  `vscode.java.*` commands; at debug time, asking it to
  `vscode.java.startDebugSession` returns a **TCP port**, and the client
  connects a socket to it.
- **Python** is **debugpy over stdio** — Editora spawns
  `python -m debugpy.adapter` and speaks DAP over the process's stdin/stdout.
- **JavaScript** is **vscode-js-debug over a socket** — spawn
  `node dapDebugServer.js <port>` and connect.

All three then speak the same protocol (via `org.eclipse.lsp4j.debug`), so
breakpoints, stepping, watches, and the variables tree are written once. Above
the client, everything is exposed as neutral records, so the UI and editor
layers never import the debug library at all.

## The gotchas were all about processes and threads

Getting three adapters working surfaced the same two traps as the language-server
work, plus a threading one:

- **Undrained stderr deadlocks.** Same as the LSP servers: an adapter's stderr
  PIPE that nobody reads fills up and blocks it mid-handshake. `Redirect.DISCARD`
  it.
- **Orphaned adapters.** Kill the whole descendant process tree on dispose, not
  just the launcher — a wrapper script otherwise leaves the real adapter running.
- **Don't block the reader thread.** When the `initialized` event arrives, you
  fire `setBreakpoints` / `setExceptionBreakpoints` / `configurationDone`. That
  callback runs on the DAP **reader thread** — so if you `.join()` on those
  requests, you deadlock, because the responses can't be read while you're
  blocking the reader. Fire and don't wait.

## Why one client was worth it

It would have been faster, short-term, to write a Java-only debugger and stop
there. But DAP meant Python and JavaScript were mostly a matter of "spawn the
adapter differently and shape the launch arguments" — the breakpoints, stepping,
call stack, watches, inline values, and the console came along for free. The same
bet LSP makes for language smarts, DAP makes for debugging: do the protocol once,
get the languages cheaply.
