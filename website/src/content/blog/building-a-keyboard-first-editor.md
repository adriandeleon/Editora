---
title: Building a keyboard-first text editor
description: Why Editora treats every action as a command — and what that design buys you.
date: 2026-06-09
author: Adrian De Leon
tags: [design, keyboard]
---

Most editors grow features as menus. A new capability shows up as a menu item,
maybe a toolbar button, and, if you're lucky, a shortcut buried in a
preferences screen. Editora is built the other way around: **every action is a
command first.**

## Commands are the foundation

In Editora, there's no such thing as an action that isn't a command. Saving,
toggling a bookmark, starting the debugger, switching a theme, each is a
registered `Command` with an id and a title. That single decision pays off
everywhere:

- The **command palette** (`M-x`) lists all 200+ of them with fuzzy search, so
  you can do anything without remembering where it lives.
- **Keybindings** are just a map from a chord to a command id — so any command
  can be bound, and you can override the defaults.
- **Toolbar buttons** dispatch the same commands, so the UI and the keyboard can
  never drift apart.

If you've ever hunted through nested menus for something you do ten times a day,
this is the antidote: type a few letters, hit Enter, move on.

## Keyboard-first, not keyboard-only

Keyboard-first doesn't mean mouse-hostile. The gutter still has clickable run
and breakpoint markers, tabs drag to reorder, and the Markdown preview has
buttons. But the fast path is always the keyboard, and you never *have* to reach
for the mouse to get something done.

## Fast is a feature

A keyboard-driven editor has to keep up with you. Editora tokenizes,
parses, and searches off the UI thread, re-highlights incrementally from the
changed line, and only repaints what's visible, so typing stays smooth even on
large files. Performance isn't an afterthought; it's a constraint the whole
codebase is held to.

---

That's the philosophy. If it resonates, [give it a try](/#download) — and let me
know what you think on [GitHub](https://github.com/adriandeleon/Editora).
