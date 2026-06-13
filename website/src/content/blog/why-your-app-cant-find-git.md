---
title: "Why your .app can't find git or node"
description: A GUI-launched app inherits a stripped PATH. Here's how Editora recovers the real one so it can find git, node, and version-managed tools.
date: 2026-06-07
author: Adrian De Leon
tags: [packaging, java]
---

Here's a bug that bites almost every desktop app that shells out to a CLI: it
works perfectly when you run it from a terminal, then a user double-clicks the
packaged app and suddenly `git`, `node`, `mmdc`, or a language server "isn't
installed." It is installed. The app just can't see it.

## The cause: a stripped PATH

When you launch an app from Finder (or a `.desktop` file on Linux), it does
**not** inherit your shell's environment. It gets a minimal PATH —
`/usr/bin:/bin:/usr/sbin:/sbin` — with none of the directories your tools
actually live in: Homebrew (`/opt/homebrew/bin`), npm globals, and especially
anything under a version manager.

Editora shells out a lot — Git, the Mermaid CLI, every LSP server, the debug
adapters, the run feature — so "can't find the binary" would have been a
constant support burden.

## The fix: rebuild the PATH the app should have had

`ProcessRunner` is the one place Editora launches a subprocess, and it builds an
**augmented PATH** = the inherited PATH **+ the user's login-shell PATH + the
usual install dirs**, then rewrites a bare command name (`git`) to its absolute
path against that PATH before launching. (Java's Unix `ProcessBuilder` resolves
the executable against the JVM's own stripped PATH, not the child env, so both
the augmentation *and* the rewrite are needed.)

The crucial piece is the **login-shell PATH**. Editora runs your shell once —
`$SHELL -l -i -c 'printf …$PATH…'`, with stdin from `/dev/null`, stderr
discarded, a 5-second timeout, and marker strings fencing the PATH off from any
`.zshrc` banner output (the pure marker-parse is unit-tested). This is the same
approach VS Code uses (`resolveShellEnv`).

Why go to that trouble instead of hardcoding directories? Because the important
ones are **version-specific and can't be hardcoded** — nvm's
`~/.nvm/versions/node/<version>/bin` (where npm-global tools like
`typescript-language-server` and `pyright-langserver` live) changes with every
Node version, and fnm/asdf/volta are similar. Only your shell knows the current
one. A list of hardcoded dirs (`/opt/homebrew/bin`, `~/.cargo/bin`, `~/go/bin`,
…) remains as a fallback.

## Making it cheap

Spawning a login shell isn't free, so the result is **cached** after the first
call, and the LSP manager **warms it off-thread** at startup — so neither tool
detection nor the first subprocess launch blocks the UI. (Windows has no
POSIX login-shell convention, so it skips this and relies on the hardcoded
dirs plus any Settings override.)

## The takeaway

If your app shells out and "works from the terminal but not when double-clicked,"
this is almost certainly why. Don't trust the PATH a GUI launch hands you —
reconstruct the one the user actually has, and resolve binaries to absolute paths
yourself.
