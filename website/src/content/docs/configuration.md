---
title: Configuration
description: The config folder, settings.toml, keybinding overrides, snippets, and dictionaries.
category: Guide
order: 1
---

Editora keeps preferences and session state in a config folder. Most settings
have a UI in the Settings window; everything is plain text you can edit by hand.

## Config directory

The config folder is chosen by this precedence:

| Source | Location |
| --- | --- |
| `--config-dir <path>` (CLI) | that path |
| `EDITORA_CONFIG_DIR` (env var) | that path |
| `--dev` flag | `~/.editora-dev/` |
| default | `~/.editora/` |

Use `--dev` to run a development instance that never touches your everyday
settings or session.

## Files

| File | Holds |
| --- | --- |
| `settings.toml` | Preferences: font, theme, keymap, tab size, view options, auto-save, keybinding overrides |
| `workspace-state.json` | Session: open files, collapsed folds, tool-window layout, window geometry |
| `recent-files.json` | Recent files list |
| `bookmarks.json` | Bookmarks (scoped per project) |
| `notes.json` | Personal notes (scoped per project) |
| `projects/<id>.json` | Each project's saved session |
| `dictionary.txt` | Your added spell-check words |
| `snippets/<lang>.json` | Your user snippets (override the bundled ones) |

## Preferences (settings.toml)

Edit in the Settings window, or directly. Common keys are written as TOML:

```toml
fontFamily = "JetBrains Mono"
fontSize = 14
theme = "Primer Dark"
tabSize = 4
autoSave = "afterDelay"
```

The live path to your settings file is shown in **About Editora** (it reflects
`--dev` / `--config-dir`).

## Keybindings

Editora ships an Emacs-style keymap (see [the reference](/keybindings)). User
overrides go in `settings.toml` and layer on top of the defaults, so you only
specify what you change.

## Snippets & dictionaries

- Add snippets in `snippets/<language>.json` (or `global.json`) — VS Code /
  TextMate syntax. "Snippet: Edit User Snippets…" opens the file; "Snippet:
  Reload Snippets" picks up changes.
- Spell-check: choose a dictionary per file with "Spell Check: Set Language…"
  (English, Spanish, French ship in); "Add to Dictionary" appends to
  `dictionary.txt`.

Full details live in the
[README](https://github.com/adriandeleon/Editora/blob/master/README.md).
