---
title: "Notes that survive edits and renames"
description: How Editora's Personal Notes stay attached to the right text — even after you edit the file in another program or rename it.
date: 2026-06-10
author: Adrian De Leon
tags: [editor, design]
---

Personal Notes let you attach an annotation to a word, line, or range — stored
*outside* the file, which is what makes them useful for read-only, generated, or
shared code you don't want to touch. But "stored outside the file" raises an
obvious question: when the file changes, how does a note stay attached to the
right place? That anchoring problem is the whole feature.

## Two kinds of "the file moved"

There are really two problems hiding here:

1. **The file moved or was renamed** — the note has to find its file again.
2. **The text inside the file changed** — the note has to find its *spot* again.

Editora handles them separately.

## Finding the file: identity, not just a path

Each note records a `FileIdentity`: the path, the canonical path, size, last
modified time, and a content hash (sha-256, capped for large files). Matching a
note to a file tries these in priority order: **canonical path → content hash →
similar path**. So if you rename `draft.md` to `final.md` but the contents are
the same, the content hash still matches and the notes follow. Identity is more
than a filename.

## Finding the spot: anchors with context

Within a file, a note stores a `TextAnchor` — not just a line and column, but the
selected text plus a `prefix` and `suffix` of surrounding context. Relocating a
note (the pure, unit-tested `NoteAnchors.relocate`) works in levels:

- First, check the saved offset — if the text there still matches, nothing moved.
- Otherwise, score the occurrences of the anchored text across the document,
  with a bonus for matching prefix/suffix context and for proximity to the
  original position, and move the note to the best one.
- If nothing matches, the note is marked **orphaned** rather than silently
  jumping somewhere wrong.

While you're editing *inside* Editora, notes also track their position live
through `plainTextChanges`, shifting as you insert and delete above them. The
relocation logic is for the harder case: the file changed while Editora wasn't
watching.

## Self-healing on open

When a file opens, Editora relocates every note against the current text and, if
anything moved, **persists the corrected positions** — so the next open hits the
exact-match fast path. The notes file quietly heals itself instead of drifting
further each session.

## Why this shape

The model carries tags, a status (active/resolved/orphaned), and the full
`FileIdentity`, so the obvious next steps — fuzzy matching beyond nearest-text,
team sync — are additive rather than rewrites. But the core idea is simple and
worth stealing for any "metadata about code that lives outside the code": don't
anchor to a position, anchor to *content plus context*, and re-find it every time
you open.
