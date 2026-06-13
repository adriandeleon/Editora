---
title: Plugins
description: Install plugins from the registry, and write your own to extend Editora.
category: Guide
order: 2
---

Editora can be extended with **plugins** — they add commands, keybindings, tool
windows, editor-menu items, and status-bar segments. Browse the
[plugin catalog](/plugins) for what's available, or write your own.

> Plugins are **off by default** and run with full access (no sandbox). Only
> install plugins you trust.

## Installing

1. Enable plugins in **Settings → Plugins**.
2. **Browse plugins…** (or the `plugins.browse` command) installs from the
   official registry; **Install from file…** installs a local `.zip`.
3. Restart Editora to load newly installed plugins.

Installed plugins live in your config folder under `plugins/<id>/`, and the
enabled set is tracked in `plugins.json`. (Plugins are loaded at startup, so
enabling/disabling takes effect on the next launch.)

## What a plugin can do

A plugin can:

- **Register commands** (they appear in the palette) and **bind keys** to them.
- **Add a tool window** (left / right / bottom) with custom UI.
- **Add editor right-click menu items** and **status-bar segments**.
- **Ship snippets and file templates** that merge into the built-in sets.
- **Run external tools** (formatters, task runners) via a subprocess.

## Writing a plugin

A plugin is a folder with a `plugin.json` manifest plus, optionally, a Java jar
and `snippets/` / `templates/` directories. The simplest plugins are purely
**declarative** (commands that run a shell command, keybindings, asset folders);
richer ones implement the `Plugin` interface against the exported
`com.editora.plugin` API.

Start from the **[Example Plugin](https://github.com/adriandeleon/editora-plugins/tree/main/plugins/example)**,
which exercises every extension point, and read the full authoring guide in
[`docs/plugins.md`](https://github.com/adriandeleon/Editora/blob/master/docs/plugins.md).

## Publishing

The official registry is
[adriandeleon/editora-plugins](https://github.com/adriandeleon/editora-plugins) —
a curated `index.json` plus each plugin's source and a downloadable `.zip`
(verified by sha-256 on install). Open a pull request there to add yours.
