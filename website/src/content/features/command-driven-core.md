---
title: "Command-driven core"
group: "Keyboard & commands"
order: 1
beta: false
summary: "Hunting through menus? Every action is a registered <code>Command</code> \u2014 bound to a chord or one <kbd>M-x</kbd> search away. 200+ commands, nothing buried."
---

Editora has no hidden actions. Every capability — save, toggle a bookmark, start the debugger, switch a theme — is a registered `Command` with an id and a title. That one decision powers three things at once:

- The **command palette** (`M-x`) fuzzy-searches all 200+ commands, each with a one-line description.
- **Keybindings** are just a map from a chord to a command id, so anything can be bound — or rebound.
- **Toolbar buttons** dispatch the same commands, so the UI and the keyboard never drift apart.

If you can describe it, you can find it by typing a few letters. Browse the full [command list](/commands).
