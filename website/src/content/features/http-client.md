---
title: "HTTP client"
group: "Run & debug"
order: 3
beta: true
summary: "Run <code>.http</code> / <code>.rest</code> requests from a gutter \u25b6 \u2014 environments, variables, and a formatted response view. Built on the JDK HTTP client."
---

Open a `.http` or `.rest` file and click the green ▶ next to a request to send it — no external tool, it uses the JDK's built-in HTTP client.

Define multiple requests separated by `###`, use `{{variables}}` and dynamic built-ins (`{{$uuid}}`, `{{$timestamp}}`…), and switch **environments** from `http-client.env.json`. The response shows status, headers, timing, and a pretty-printed JSON body in a tool window, with a Save-response action. Run one request or the whole file. Off by default — enable it under Settings → HTTP Client.
