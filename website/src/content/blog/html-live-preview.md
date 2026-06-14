---
title: "HTML live preview, with no browser engine"
description: How Editora previews HTML in a real browser: a loopback HTTP server, live-as-you-type reload, and a long-poll trick — without bundling a WebView.
date: 2026-06-18
author: Adrian De Leon
tags: [web, feature]
beta: true
---

Editora's Markdown preview renders natively, on purpose. I didn't want to ship a
browser engine. But HTML is different: the *point* of previewing HTML is to see
it in a **real browser**, with real CSS, real JS, and real layout. So HTML live
preview takes the opposite approach from Markdown: it hands the page to a browser
you already have, and just makes that fast and live.

## A tiny server, on loop-back

When you click the globe on an HTML file, Editora starts a small web server using
the JDK's built-in `HttpServer`, bound to `127.0.0.1:0`: **loop-back only**, a
random port, never the LAN. The file's parent directory becomes the document
root, so its sibling CSS, JS, and images load exactly as they would when
deployed.

The previewed file itself is served from the editor's **in-memory text**, not
from disk. That's the key to "live as you type": you don't have to save to see
changes.

A couple of safety details matter even for a localhost server: paths are checked
against a traversal guard (you can't escape the document root), and serving live
buffer text means the preview always reflects exactly what's on screen.

## Live reload without WebSockets

How does the page know to refresh? The trick is an old, dependency-free one:
**long polling.**

Editora splices a small `<script>` into the served HTML. On load, that script
hits a `/__editora_livereload` endpoint, and the server **holds the request open**
until a `volatile long` version counter changes (or ~25 seconds pass, then it
retries). Every debounced edit bumps that counter; the held request returns
`200`, and the script reloads the page.

No WebSocket library, no SSE plumbing, just a held HTTP request and a counter. A
cached daemon thread pool backs it so those parked requests don't starve normal
asset serving.

## The User's browsers

"Open in a detected browser" means actually detecting them, per OS: macOS app
bundles (`open -a`), Linux binaries on `PATH`, Windows Program Files executables,
plus a **System Default** entry routed through JavaFX's `HostServices`. The picker
even gives each browser its own icon. The detection and launch-argument logic is
pure and unit-tested, with the OS and filesystem probes injected, so it's
testable without actually launching Chrome in CI.

## Staying off the hot path

The whole feature is careful not to tax the editor. Live reload is *one*
`volatile` bump on the **already-debounced** 250 ms edit pulse,  no new
per-keystroke work. The server runs on its own daemon threads. The browser is
launched detached (fire-and-forget), so a slow-to-start browser can't stall the
UI. And it's all **off by default**, behind a Settings toggle, with the commands
filtered out of the palette when disabled.

## Why not a WebView?

Because the browser you test in is the one that matters. A bundled WebView would
be *a* renderer, not *your* renderer, and it would mean shipping a browser engine
inside a text editor, which is exactly what Editora avoids. Serving to your real,
installed browsers gets you accurate rendering, your devtools, and zero extra
download. The only thing Editora adds is the live-reload glue.

---

It's a Beta feature today, give it a try (enable it under Settings → HTML
Preview) and let me know how it holds up in
[Discussions](https://github.com/adriandeleon/Editora/discussions).
