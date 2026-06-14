---
title: "Going headless to stay alive: an AWT/JavaFX deadlock"
description: Why the very first line of Editora's main() sets java.awt.headless=true — and the intermittent macOS hang that forced it.
date: 2026-06-03
author: Adrian De Leon
tags: [javafx, debugging]
---

Open the very first line of Editora's `main()` and you'll find something that
looks out of place for a GUI app:

```java
public static void main(String[] args) {
    System.setProperty("java.awt.headless", "true");
    // ...
}
```

A JavaFX app declaring itself *headless*? That's the fix for one of the more
maddening bugs I've chased: an intermittent macOS hang that got likelier the
more Markdown previews you had open.

## The setup: SVG badges in the Markdown preview

Editora renders Markdown to native JavaFX nodes (no WebView), and that includes
images. README files are full of [shields.io](https://shields.io) badges, which
are served as `image/svg+xml`. JavaFX's image decoder can't read SVG, so I
rasterize them with [JSVG](https://github.com/weisJ/jsvg), a small pure-Java SVG
library, into a `BufferedImage` and copy that into a JavaFX image.

That `BufferedImage` is the catch. Rasterizing through Java2D pulls in
`java.awt`, and on macOS the AWT/Java2D **native** pipeline (AppKit/Metal) and
JavaFX's own Glass/Prism pipeline both want the single AppKit run loop. Put them
in the same process, ask them to render at the same time, and occasionally they
deadlock. The more badges being rasterized — i.e., the more previews open — the
likelier the hang.

## The fix: rasterize in software

`java.awt.headless=true` tells Java2D to use its **software** rasterizer
instead of the native AppKit pipeline. The badge still renders to a
`BufferedImage` exactly as before: it just never touches AppKit, so the
contention disappears. JavaFX keeps the screen to itself.

Two details matter:

- **It has to be the first thing `main` does**, before any AWT class loads —
  once Java2D initializes its pipeline, the property is read too late.
- **It has to cover every entry point.** `mvn javafx:run`, the fat-jar
  `Launcher`, and the jpackage native launcher all route through `App.main`, so
  one line covers all three.

## A free security win

There's a nice side effect. JSVG loads with
`LoaderContext.createDefault()`, which **denies external resource loading** —
an SVG can't be coaxed into fetching a remote URL while it rasterizes. So a
malicious badge in some README can't turn the preview into an SSRF vector. The
headless software path keeps the whole thing self-contained.

## The lesson

Mixing two UI toolkits in one process is asking for trouble, and "I'm only using
AWT to make a `BufferedImage`, not a window" isn't the escape hatch it looks
like: on macOS, *initializing* Java2D is enough to wake AppKit. If you ever need
Java2D purely for offscreen rendering inside a JavaFX (or SWT, or any
non-AWT) app, set `java.awt.headless=true` up front and save yourself the
intermittent-hang bug report that's almost impossible to reproduce on demand.
