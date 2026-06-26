# 0001 — Fork RichTextFX for multiple cursors, vendor it in `m2-repo/`

**Status:** Accepted

## Context

The editor surface is a RichTextFX `CodeArea`. RichTextFX has no built-in support for VS
Code–style multiple cursors / Alt+drag column selection, and upstream has not added it. We want
that feature, and we want it without rewriting the editor surface on a different toolkit.

## Decision

Maintain a personal fork, `io.github.adriandeleon:richtextfx`, **vendored in the in-project
`m2-repo/`** (source at github.com/adriandeleon/RichTextFX). The fork adds a self-contained
`org.fxmisc.richtext.multi.MultiCaretController.install(area)` add-on — a layered, well-behaved
`InputMap` gated on `MultiCaretManager.hasExtras()`, so it is transparent when there is one caret.

## Consequences

- `EditorBuffer` installs it via `setMultiCaretEnabled(...)`; the area-level KEY filters
  early-return (no consume) via `multiCaretActiveOn(a)` when an area has extra carets, so edits
  fan out to all carets.
- Updating the fork is a manual ritual: rebuild it, copy `richtextfx-<version>.{jar,pom}` into
  `m2-repo/`, bump `<richtextfx.version>`. Use 0.11.7+ (earlier breaks on JavaFX 25+).
- RichTextFX and its transitive deps (`reactfx`, `flowless`, `undofx`, `wellbehavedfx`) are
  automatic modules, so the dist build's moditect step injects `module-info` descriptors for the
  jlink image — bumping the fork may require adjusting their `requires`.
- **Known limitation:** Emacs movement chords are resolved by the scene-level `KeyDispatcher` on
  the primary caret and don't fan out; arrows do.

See [dependencies.md](../dependencies.md#richtextfx--a-personal-fork-m2-repo).
