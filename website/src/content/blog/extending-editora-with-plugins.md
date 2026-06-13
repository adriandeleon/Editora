---
title: Extending Editora with plugins
description: How Editora's plugin system works — and how to write your own in a few minutes.
date: 2026-06-16
author: Adrian De Leon
tags: [plugins, extensibility]
---

Editora just gained a plugin system, and it's built on the same idea that runs
the rest of the editor: **everything is a command.** A plugin doesn't get a
special back door — it registers the same kind of commands, keybindings, and
tool windows the core uses. That keeps the API small and the editor consistent.

## Two ways to write one

A plugin is a folder with a `plugin.json` manifest. From there you can go as
light or as deep as you like.

**Declarative** plugins need no code at all. The manifest can register commands
that run a shell command, bind keys, and point at `snippets/` and `templates/`
folders to merge into the built-in sets. That's enough for a surprising amount —
a formatter, a task runner, a code generator.

**Java** plugins implement the `Plugin` interface against the exported
`com.editora.plugin` API and get a `PluginContext`:

```java
public class HelloPlugin implements Plugin {
  public void start(PluginContext ctx) {
    ctx.registerCommand("hello", "Say Hello", () ->
        ctx.setStatus("Hello from a plugin!"));
    ctx.bindKey("C-c C-h", "hello");
  }
}
```

The context lets you register tool windows, add editor right-click items and
status-bar segments, and reach the active editor through a narrow facade
(`text()`, `selectedText()`, `replaceSelection()`, `setText()`, …) — never the
internals.

## A registry, not a marketplace

Plugins install from a curated registry —
[adriandeleon/editora-plugins](https://github.com/adriandeleon/editora-plugins) —
that ships **19 plugins** today: text transforms, encoders, hashers, a regex
tester, a scratchpad, a calculator, a color picker, "Open on GitHub," and more.
Editora fetches the index over HTTPS, verifies each download's sha-256, and
unpacks it with a zip-slip-hardened extractor. You can also install a `.zip`
straight from disk.

## A word on trust

Plugins are **not sandboxed** — a Java plugin runs with the same access as the
editor. That's deliberate (it's what makes them powerful), but it means you
should only install plugins you trust, the same way you would a VS Code or
IntelliJ extension. Plugins are off by default; you opt in per plugin.

---

Browse what's available on the [Plugins page](/plugins), or read the
[docs](/docs/plugins) and ship your own. I'd love to see what you build —
[Discussions](https://github.com/adriandeleon/Editora/discussions) is open.
