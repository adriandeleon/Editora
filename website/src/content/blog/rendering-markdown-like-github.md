---
title: "Rendering Markdown like GitHub — without a WebView"
description: How Editora's Markdown preview renders natively to JavaFX nodes — task lists, code pills, images, and SVG badges — with no embedded browser.
date: 2026-06-04
author: Adrian De Leon
tags: [markdown, rendering]
---

The easy way to add a Markdown preview is to embed a WebView, render to HTML, and
call it a day. I didn't want to ship a browser engine inside the editor, it's
heavy, it doesn't match the app's theme without fighting it, and it's a second
rendering model to reason about. So Editora's preview renders **natively to
JavaFX nodes**, and it aims to look like GitHub.

## The pipeline

Parsing and rendering are split across threads. Editora parses CommonMark + GFM
with [commonmark-java](https://github.com/commonmark/commonmark-java) **off the
UI thread**, then builds the JavaFX node tree **on** the FX thread under a
stale-generation guard — the same idiom the syntax highlighter uses. The whole
thing is debounced (~250 ms) off `multiPlainChanges`, and only runs while the
preview is actually visible. Big files render once.

The result tracks the app theme for free, because the preview's colors are
plain AtlantaFX semantic CSS variables, no per-theme overrides, no WebView
stylesheet to sync.

## Matching GitHub is in the details

Getting close to GitHub's look came down to a pile of small decisions:

- **Task lists** render as real checkboxes. The trick is that the
  `TaskListItemMarker` has to be the list item's *first child*, not nested
  inside its paragraph.
- **Inline `code`** is a `Label` styled as a rounded gray "pill": a raw `Text`
  node can't carry a background, so a pill needs a real control behind it.
- **`#`/`##` headings** get an underline rule; **links** aren't permanently
  underlined (only on hover), like GitHub.
- Content sits in a **centered, width-capped column** with GitHub-like margins
  and vertical rhythm.

## Images, badges, and diagrams

Images go through a dedicated off-thread loader (with caching and a
failure-TTL so a dead host isn't retried on every re-render). The interesting
case is **SVG**: JavaFX can't decode it, so badges, which shields.io serves as
`image/svg+xml`, would be invisible. Editora rasterizes them with JSVG into an
image, which is what makes GitHub/CI badges actually show up in a README
preview.

Fenced ` ```mermaid ` blocks are special-cased too: they render to a PNG via the
`mmdc` CLI and embed as an image, so diagrams appear inline alongside the prose.

## Why bother going native

A WebView would have been less code up front. But the native path means: the
preview inherits the editor's fonts and all six themes with zero extra work,
there's no second engine to ship or sandbox, and rendering happens on the same
threading model as everything else in the app, so the same performance rules
apply. For an editor that cares about startup time and footprint, not bundling a
browser was worth the extra effort.
