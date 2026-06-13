---
title: "The black window: bounding GPU textures in JavaFX"
description: A bug that only showed up in the packaged build — open enough files and the window goes black. The cause was GPU texture exhaustion.
date: 2026-06-05
author: Adrian De Leon
tags: [performance, javafx]
---

This one only reproduced in the *packaged* build, which made it especially fun:
open enough files, and eventually the whole editor window would go **black**. No
exception in the echo area, no crash — just a black rectangle where the UI used
to be.

## The clue: it scaled with open files

The black window got likelier the more files you had open. That pointed away
from any single document and toward something that *accumulated*. Digging into
the render thread turned up NullPointerExceptions deep in JavaFX's Prism
pipeline — a null `RTTexture` / mask texture.

That's the tell. JavaFX's Prism renderer keeps a **texture pool with a fixed
ceiling** (512 MB by default). When you exhaust it, texture allocation starts
returning null, and the render thread NPEs trying to use it. The screen goes
black. It only showed up packaged because the dev run had different VRAM
headroom — the classic "works on my machine."

## The fix: don't let GPU resources grow with open files

The root problem was that GPU-backed resources scaled linearly with the number
of open buffers. Two big offenders, two fixes:

- **Minimaps.** Every open buffer kept a rendered minimap snapshot — a pinned
  GPU texture. Now a background (non-selected) tab drops its snapshot via
  `setRenderingActive(false)`, driven off the tab-selection listener, and
  regenerates it when shown again. Only the visible buffer holds a minimap
  texture.
- **Image caches.** The Markdown preview's image loader and the Mermaid renderer
  both cache `Image` objects — each a pinned texture. Those caches are now
  **LRU-bounded** instead of unbounded maps, so they can't grow without limit.

As a safety net, the packaged launcher (and `mvn javafx:run`, so dev matches
prod) also raises the caps: `-Dprism.maxvram=2G -Dprism.maxTextureSize=16384`.

## The rule it left behind

The lasting lesson is a checklist item now: **any per-buffer `Canvas` or
`Image` must be released or bounded.** GPU memory isn't garbage-collected the way
heap is — a cached `Image` is a texture you're holding until you let go. If a
resource's lifetime is "one per open file" and files can pile up, that's a leak
waiting to turn into a black window in someone's packaged build, not yours.
