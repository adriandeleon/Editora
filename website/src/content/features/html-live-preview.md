---
title: "HTML live preview"
group: "Docs & diagrams"
order: 4
beta: true
summary: "Click the globe on any HTML file to open it in a detected browser (Safari, Chrome, Firefox, Edge\u2026), served over a loopback web server with live-as-you-type reload \u2014 sibling CSS, JS, and images included."
---

A floating browser-globe button on any `.html`/`.htm`/`.xhtml` file opens it in a **detected browser** — Safari, Chrome, Firefox, Edge, or the system default.

The file is served over a tiny embedded web server bound to **loopback only**, so its sibling CSS, JS, and images load, and a small injected script **reloads the page live as you type** (it serves the buffer's in-memory text, so you don't have to save). No external tool — it uses the JDK's built-in HTTP server.

Off by default — enable it under Settings → HTML Preview. Read the [deep-dive](/blog/html-live-preview).
