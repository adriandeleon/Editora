# 0006 — Force `java.awt.headless=true` before anything else

**Status:** Accepted

## Context

The Markdown preview rasterizes SVG (shields.io / GitHub badges via JSVG) and LaTeX math (via
JLaTeXMath). Both touch `java.awt`/Java2D. On macOS the AWT/Java2D native pipeline (AppKit/Metal)
contends with JavaFX's Glass/Prism for the single AppKit run loop — an intermittent
deadlock/hang that grows likelier the more badges/formulas are rasterized.

## Decision

`App.main`'s **very first statement** is `System.setProperty("java.awt.headless", "true")`.
Headless Java2D rasterizes to a `BufferedImage` purely in software (no AppKit), so badges and math
still render while the conflict disappears.

## Consequences

- It must run **before any AWT class loads**, hence first in `main`. Keep it there.
- It covers every entry point — `javafx:run`, the fat-jar `Launcher`, and the jpackage module all
  route through `App.main`.
- A corollary: features that would otherwise use AWT must avoid it. Printing uses `javafx.print`,
  not `java.awt.print` (which would throw `HeadlessException` under this guard).
- SVG/math rasterization copies the `BufferedImage` into a JavaFX `WritableImage` via a
  `PixelWriter` — no `javafx.swing` is pulled in.

See [gotchas.md](../gotchas.md).
