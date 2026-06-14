---
title: "Read-only / View mode"
group: "Workspace & files"
order: 5
beta: false
summary: "Toggle a buffer read-only to browse without editing; pager-style <kbd>Space</kbd>/<kbd>Backspace</kbd> paging and a Word-style View Mode banner."
---

Toggle a buffer read-only with `C-x C-q` so it can't be edited by accident — typing and edit commands are blocked while highlighting, folding, search, and copy keep working.

A file that isn't writable on disk opens read-only automatically, and the per-file state is remembered. A Word-style **View Mode banner** docks above the editor with an *Enable Editing* button (when the file is writable), and while read-only, **Space pages down / Backspace pages up** like a pager.
