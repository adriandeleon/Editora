# Developer documentation

Guides for working **on** Editora. User-facing documentation lives in the separate website
repository; this folder is for contributors.

The exhaustive, terse subsystem reference is the root [`CLAUDE.md`](../CLAUDE.md). These guides
are the readable distillation of it — when they disagree with the code, the code wins.

## Start here

- [architecture.md](architecture.md) — module map, boot path, the multi-window model, the
  `EditorBuffer`/`TabContent` abstraction, and the recurring patterns.
- [conventions.md](conventions.md) — the rules a change must follow (commands, settings, i18n,
  schema, formatting, worktrees). See also [`../CONTRIBUTING.md`](../CONTRIBUTING.md).
- [performance.md](performance.md) — the hot paths and the discipline that keeps the editor
  responsive. Read before touching highlighting, overlays, the gutter, or scrolling.

## How-to

- [extending.md](extending.md) — recipes: add a command, a setting, an LSP server, a DAP
  adapter, a language/grammar, a tool window, a Canvas overlay, a feature coordinator.
- [plugins.md](plugins.md) — the public plugin API (SPI + declarative manifest), building,
  packaging, and the registry. The full plugin catalog lives in the
  [editora-plugins](https://github.com/adriandeleon/editora-plugins) repo.

## Build, test, ship

- [building-and-packaging.md](building-and-packaging.md) — run, dist/jpackage, the AOT cache,
  fat jar, the per-OS Prism pipeline.
- [dependencies.md](dependencies.md) — the vendored/forked/repackaged deps (RichTextFX fork,
  Monocle, tm4e) and the moditect story.
- [testing.md](testing.md) — pure tests, the headless-FX Monocle harness, the JaCoCo floors.
- [release.md](release.md) — cutting a release and the CI matrix.
