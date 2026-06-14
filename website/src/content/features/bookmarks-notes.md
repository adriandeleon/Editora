---
title: "Bookmarks & notes"
group: "Workspace & files"
order: 2
beta: false
summary: "Line bookmarks (gutter markers, cross-file jump, per-project), plus Personal Notes attached to a word/line/range \u2014 stored outside the file, surviving renames, with Markdown bodies."
---

Two ways to mark up code.

**Bookmarks** toggle on a line (`C-c m`) with a gutter marker and an optional note; the Bookmarks tool window lists them across files, `C-c ]` / `C-c [` cycle within a file, and `M-g b` is a cross-file picker — reorderable and scoped per project.

**Personal Notes** attach an annotation to a word, line, or range, stored *outside* the file (great for read-only or generated code). They survive edits and renames via content-hash identity and text anchoring, render Markdown, and have their own tool window and `M-g n` picker. See the [deep-dive](/blog/personal-notes-that-survive-edits).
