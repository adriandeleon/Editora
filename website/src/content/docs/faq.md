---
title: FAQ
description: Frequently asked questions — licensing, telemetry, platforms, signing, and customization.
category: Help
order: 1
---

## Is Editora free and open source?

Yes — it's free and released under the MIT License. Source is on
[GitHub](https://github.com/adriandeleon/Editora).

## Does it phone home or collect telemetry?

No. There are no accounts, no telemetry, and no network calls for analytics —
it's a local editor.

## Which platforms are supported?

macOS (Intel & Apple Silicon), Windows (x64), and Linux (x64 & arm64).
Windows-on-ARM runs the x64 installer under emulation.

## Do I need Java installed?

No for the native installers — they bundle their own Java runtime. The portable
fat jar does need JDK 25 (`java -jar`).

## The installer says it's from an unidentified developer.

Installers are currently unsigned. On macOS, right-click the app and choose
*Open* the first time; on Windows, click "More info → Run anyway" in SmartScreen.
Signing is on the roadmap.

## How do I add a language, snippet, or dictionary word?

Snippets: drop a `snippets/<language>.json` in your config folder (they override
the bundled ones). Spell-check: "Add to Dictionary" saves to `dictionary.txt`;
pick a language per file with "Spell Check: Set Language…". See
[Configuration](/docs/configuration).

## Can I rebind keys or use a different keymap?

Yes — Editora is keyboard-first and every action is a command. In **Settings →
Keymaps** you can switch between five built-in keymaps — **Emacs** (default),
**CUA**, **Sublime Text**, **VS Code**, and **IntelliJ IDEA** — and rebind any
command with the built-in keybinding editor (recording multi-key chords like
`C-x C-s`). Changes apply live and your overrides are saved in
`settings.toml`, layered on top of the active keymap. See the
[keybindings reference](/keybindings).

## How do I run a separate, throwaway config?

Launch with `--dev` to use an isolated `~/.editora-dev/` config, or
`--config-dir <path>` / the `EDITORA_CONFIG_DIR` env var to point anywhere.

## Why JavaFX?

It gives one native-feeling, cross-platform codebase with a fast text surface
(RichTextFX), and lets Editora ship a single app per platform without a browser
engine.

Didn't find your answer?
[Open an issue](https://github.com/adriandeleon/Editora/issues).
