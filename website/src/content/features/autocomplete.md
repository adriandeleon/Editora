---
title: "Autocomplete"
group: "Code intelligence"
order: 3
beta: false
summary: "As-you-type completion \u2014 a popup for code (LSP + snippets) and inline ghost text for prose. Trigger with <kbd>C-M-i</kbd> / <kbd>M-/</kbd>."
---

Completion appears as you type, debounced and off the hot path.

**Code** buffers get a popup that merges LSP results and snippets — Enter or Tab to accept. Accepting a snippet starts a full tab-stop session; accepting an LSP item can auto-add its import.

**Prose** buffers get inline **ghost text** — a muted suffix you accept with Tab.

Trigger manually with `C-M-i` or `M-/`. Per-source toggles (words, snippets) live in Settings → Editor.
