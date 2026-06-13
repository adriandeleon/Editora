---
title: "Installers start ~28% faster"
description: A GUI-trained JDK 25 AOT cache cuts time-to-first-frame in the native installers.
date: 2026-06-13
---

The native installers now launch noticeably quicker — about **28% faster to the
first frame (~300–480 ms on macOS)** — thanks to a JDK 25 **AOT cache** trained
against a real GUI run and baked into each installer. It degrades gracefully, so
a launch can never be slower than before.

Grab a [fresh installer](/#download) to feel it, and read the
[deep-dive on the blog](/blog/faster-cold-start-with-an-aot-cache) for how it
works.
