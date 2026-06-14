---
title: "Keymaps your way"
group: "Keyboard & commands"
order: 2
beta: false
summary: "Pick <strong>Emacs</strong>, <strong>CUA</strong>, <strong>Sublime Text</strong>, <strong>VS Code</strong>, or <strong>IntelliJ IDEA</strong> \u2014 switch live, no restart. Or rebind any command yourself in the built-in keybinding editor (multi-key chords like <kbd>C-x C-s</kbd> supported)."
---

Editora ships five complete keymaps — **Emacs** (default), **CUA**, **Sublime Text**, **VS Code**, and **IntelliJ IDEA** — selectable in **Settings → Keymaps** and switchable **live, with no restart**. Each is a chord→command map over the same command ids, so switching changes accelerators without stranding any functionality.

Prefer your own bindings? The built-in keybinding editor records multi-key chords (like `C-x C-s`), rebinds any command, and resets to defaults. Overrides are saved in `settings.toml`, layered on top of the active keymap, so you only specify what you change.

On macOS the non-Emacs keymaps use ⌘ wherever the [keybindings reference](/keybindings) shows Ctrl.
