# 0009 — Plugins via a child `URLClassLoader`, no `ModuleLayer`

**Status:** Accepted

## Context

Editora supports third-party plugins (a `plugin.json` manifest + optional Java jar). The modern
JPMS way to load isolated code would be a `ModuleLayer` with its own module path. But the
shipped app is a **sealed jlink image with no module path at runtime**, and there is no
`ServiceLoader` infrastructure baked in.

## Decision

Discover plugins by **manifest**, and load a Java plugin's classes via a child
`URLClassLoader(jarUrls, appClassLoader)` — no `ModuleLayer`, no `ServiceLoader`. The parent is
the app module's loader, so a plugin can use Editora's **exported** packages
(`com.editora.plugin`/`command`/`ui`/`editor`/`config`) and the baked JDK/JavaFX modules via
normal parent delegation.

## Consequences

- A plugin is compiled on a plain **classpath**, not the module path.
- The API surface is the exported `com.editora.plugin` package: `Plugin`/`PluginContext`/
  `ActiveEditor` — a narrow editor facade that never exposes `MainController`.
- Classes load **once** (a shared `PluginManager` owned by `WindowManager`), but a `Plugin`
  instance + its tool-window content node are built **per window** (a node can't live in two
  scenes).
- Plugins load at **startup only**; enable/disable takes effect next launch (no hot
  classloader/UI unload). Per-window `stop()` on close is the only teardown.
- Security is consent + integrity + authenticity (capability disclosure, HTTPS-only capped
  downloads, an Ed25519-signed registry index), **not** a sandbox — a plugin runs with full
  trust, like VS Code / IntelliJ.

See [plugins.md](../plugins.md).
