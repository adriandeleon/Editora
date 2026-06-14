---
title: "HTML live preview"
description: Open any HTML file in a real browser, served over a loopback web server, with live-as-you-type reload.
date: 2026-06-18
beta: true
---

Editing HTML in Editora just got a lot nicer. A floating **browser-globe button**
now appears at the top-right of any `.html`/`.htm`/`.xhtml` editor (mirroring the
Markdown preview toggle). Click it to open the current file in a **detected
browser** — Safari, Chrome, Firefox, Edge, or your system default.

The file is served over a tiny embedded web server bound to **loopback only**, so
its sibling CSS, JS, and images load normally — and a small injected script
**reloads the page live as you type** (it serves the buffer's in-memory text, so
you don't even have to save).

It's **off by default**; enable it under **Settings → HTML Preview**, then use
the globe or the *Open in Browser* / *Open in Browser…* palette commands. No new
dependency — it uses the JDK's built-in HTTP server.

Read the [deep-dive on the blog](/blog/html-live-preview) for how it works.
