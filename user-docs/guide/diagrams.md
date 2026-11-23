---
title: Diagrams as code
description: A live 3-mode preview for Graphviz DOT and PlantUML files, with export to SVG, PNG, or PDF.
category: Editing
order: 8
---

Standalone **Graphviz DOT** (`.dot` / `.gv`) and **PlantUML** (`.puml` /
`.plantuml`) files get the same 3-mode preview (Editor / Split / Preview) as
Markdown and Mermaid.

Rendering runs off-thread via the external **`dot`** and **`plantuml`** CLIs.
Both rasterize to PNG natively, so there's no headless browser, and results are
cached by source hash so an unchanged diagram isn't re-rendered. Zoom resizes the
image.

![A Graphviz DOT file in Split mode: the source on the left and the rendered graph on the right, with a cluster labelled cache](/screenshots/features/diagrams.png "A DOT graph rendered beside its source.")

PlantUML runs with `PLANTUML_SECURITY_PROFILE=SANDBOX`, so a diagram cannot
`!include` local files or URLs. Export that variable yourself before starting
Editora to choose another profile. The renderers also start without
secret-looking environment variables.

## Export

Export a diagram to **SVG**, **PNG**, or **PDF** with `diagram.export` (also on
the preview's right-click menu). *File: Print Rendered Preview…*
(`preview.print`) prints it with a [print preview](/docs/print-pdf#print-preview).
The PDF is sized by Graphviz or PlantUML, so the PDF page settings do not apply
to it, and printed diagrams are always light.

## Enabling

It's **on by default**, self-gating on detection, so it stays inert until the
tool is found. Install with your package manager, for example
`brew install graphviz plantuml`. The toggle and the two tool paths live under
**Settings → Diagrams**. Under each path a **Detected** line says whether the
tool is there, for example *dot: found (/usr/bin/dot)* or *plantuml: not found*.
Editora has no installer for these tools, so a missing one shows a **How to
install** link to the [Diagrams feature page](/features/diagrams) instead of an
Install… button.
The paths are greyed while the preview is turned off.

For diagrams embedded in Markdown, see [Mermaid](/docs/markdown#mermaid-diagrams).
