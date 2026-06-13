---
title: "Signing the plugin registry: consent, integrity, authenticity"
description: Plugins run unsandboxed, so the registry leans on three defenses — informed consent, download integrity, and a signed index. Here's how each works.
date: 2026-06-14
author: Adrian De Leon
tags: [plugins, security]
---

Editora's plugins are powerful precisely because they're **not sandboxed** — a
Java plugin runs with the same access as the editor, like a VS Code or IntelliJ
extension. That's a deliberate v1 trade-off, but it raises the stakes on
*installing* one. The registry's security rests on three layers: consent,
integrity, and authenticity.

## Consent: tell the user what they're arming

Before a plugin is *enabled* — at every arming point: install from the registry,
install from a file, or flipping the per-plugin checkbox in Settings — Editora
shows a **capability disclosure**. It answers, in plain terms: does this plugin
ship a jar (i.e. run arbitrary code)? Does it declare external commands, and with
what arguments? Does it remap any keybindings? You opt in per plugin, with that
information in front of you, rather than trusting a name.

## Integrity: the bytes you asked for, and nothing malicious

Installing pulls a `.zip` over the network, which is two risks: getting the wrong
bytes, and a hostile archive.

- **The right bytes.** The registry entry carries a sha-256, and install
  **verifies it** — a mismatch aborts. Fetches are HTTPS-only with no
  `https → http` downgrade, and reads are size-capped so a malicious server can't
  OOM the app with an enormous index or download.
- **A safe unpack.** The extractor is a hardened `ZipInputStream` with a
  **zip-slip guard** (every entry's resolved path must stay under the target
  dir) plus per-entry, total-size, and entry-count caps. A `.zip` can't write
  outside its plugin folder or zip-bomb you.

## Authenticity: who published this index?

The strongest layer is a signature. Editora verifies a detached **Ed25519**
signature of the registry's `index.json` (fetched from `<url>.sig`) against a
**public key bundled in the app**. A setting, on by default, makes the browser
**refuse an unsigned or unverified registry**. The registry owner signs with a
small keygen/sign/verify tool; the private key is never committed, only the
public key ships.

This proves *who* published the index — it does not make plugins safe to run.
That distinction matters, so the UI is honest about it: signing is authenticity,
not a sandbox.

## Why bother, for a hobby-scale registry?

Because "small and trusted today" is exactly when the habits are cheap to build,
and a plugin system is the one feature that turns "a bug in my code" into "a bug
in code I downloaded." None of these layers makes unsandboxed plugins *safe* —
the honest answer is to only install ones you trust — but together they make the
common attacks (tampered downloads, a spoofed registry, a malicious archive,
an uninformed click) meaningfully harder. Real sandboxing is the deferred,
harder follow-up.
