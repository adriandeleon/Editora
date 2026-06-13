---
title: "Keeping the UI thread sacred"
description: The performance doctrine behind Editora — off-thread work, incremental updates, coalesced redraws, and degrading gracefully on huge files.
date: 2026-06-06
author: Adrian De Leon
tags: [performance]
---

Editora has one rule it's held to above all others: **never block the JavaFX
Application Thread.** An editor lives or dies on whether typing, scrolling, and
highlighting feel instant, and all of those happen on that one thread. Here's the
doctrine that keeps them fast.

## Do the heavy work somewhere else

Tokenizing, parsing, and search all run **off** the UI thread on a dedicated
executor, and apply their results back **on** the FX thread behind a
generation/stale-result guard — if a newer edit has landed by the time the work
finishes, the stale result is dropped. So a slow highlight pass on a big file
never freezes the cursor; it just gets superseded.

## Debounce and coalesce

Re-highlighting is debounced. The document overlays — whitespace markers, the
80-column ruler, the minimap, search highlights, the gutter — **coalesce to one
redraw per pulse** with a simple `pending` flag plus `Platform.runLater`. The
rule for new code is strict: don't add per-keystroke or per-scroll work that
isn't coalesced. A handful of listeners each doing "just a little" work per pulse
is how an editor dies by a thousand cuts.

## Only touch what changed, and what's visible

Two more multipliers:

- **Incremental.** An edit re-tokenizes from the *changed line* to the end,
  reusing stored per-line grammar state for the unchanged prefix — not the whole
  document.
- **Visible-only.** Overlays iterate just the visible paragraphs
  (`firstVisibleParToAllParIndex … last`) and skip folded lines. Nothing does
  O(document) work on an edit or a scroll.

There's also a subtle one: don't defeat JavaFX's per-node CSS style cache. Syntax
tokens use a compound `.text.<class>` selector and adjacent same-style spans are
merged before being applied, so the style system isn't asked to re-resolve more
than it must.

## Know when to give up gracefully

Some files are too big to treat normally, and pretending otherwise just makes the
editor janky. So there are explicit modes: at **5 MB** Editora skips syntax
highlighting and the minimap; at **50 MB** it opens read-only with a capped load
(at most the first 50 MB is read, so a multi-GB log can't exhaust memory), and
undo history is bounded. The status bar says what happened. Degrading on purpose
beats degrading by accident.

## The cultural part

The technical rules only stick because of a habit: **every change is assessed for
its cost on the hot paths** — allocation per keystroke, added FX-thread work,
extra layout/CSS passes — and that cost gets stated, even when it's "negligible."
When something risks a regression, I measure (temporary `System.nanoTime`
instrumentation) rather than guess. Performance isn't a milestone you hit once;
it's a constraint you keep paying attention to.
