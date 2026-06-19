# Architecture

A map of how Editora is put together, for contributors. This is the orientation doc;
the deeper conventions live in [conventions.md](conventions.md) and
[performance.md](performance.md), and the step-by-step "add an X" recipes in
[extending.md](extending.md).

> The authoritative, exhaustive description of every subsystem is in the root
> [`CLAUDE.md`](../CLAUDE.md). These docs are the human-readable distillation; when they
> disagree with the code, the code wins.

## What it is

A keyboard-driven, cross-platform programmer's text editor: **JDK 25 + JavaFX 25**, Maven,
a single JPMS module `com.editora` (see [`src/main/java/module-info.java`](../src/main/java/module-info.java)).
The editor surface is a [RichTextFX](dependencies.md) `CodeArea`; standard controls are
themed by AtlantaFX.

## Boot path

1. **`App.main`** ([`App.java`](../src/main/java/com/editora/App.java)) sets
   `java.awt.headless=true` as its very first statement (the SVG-rasterization guard — see
   the note below), installs the debug-log handler + uncaught-exception handler, and handles
   `--version`/`--help` before the toolkit starts.
2. **`App.start`** loads the shared config, initializes i18n (`Messages.init`), pins a
   classloader (the macOS FXML null-context-classloader fix), reaps orphaned LSP/DAP servers
   from a previous run, then hands off to **`WindowManager`**.
3. **`WindowManager`** ([`ui/WindowManager.java`](../src/main/java/com/editora/ui/WindowManager.java))
   owns the set of open windows and restores the previously-open window set.
   `buildWindow(...)` is the per-window construction: it loads config, builds the command +
   keymap system, installs the `KeyDispatcher` on the scene, applies the theme, and loads
   `ui/main.fxml` into a **`MainController`**.
4. **`MainController`** ([`ui/MainController.java`](../src/main/java/com/editora/ui/MainController.java))
   is the per-window hub: toolbar, the tabbed `EditorBuffer`s, the status bar, tool windows,
   and the wiring for every feature. It is large by design — most features are wired here and
   delegate into their own packages.

### The headless-AWT guard (don't move it)

`App.main`'s first line is `System.setProperty("java.awt.headless", "true")`. SVG
rasterization (badges in the Markdown preview, math via JLaTeXMath) touches Java2D; on macOS
the AWT/Java2D native pipeline contends with JavaFX's Glass/Prism for the single AppKit run
loop, an intermittent deadlock. Headless Java2D rasterizes in software, so it must be set
before any AWT class loads. Keep it first in `main`.

## Multi-window model

Each project (and each "New Window") opens in its own top-level window with its own
`MainController`, `Stage`, `CommandRegistry`, `KeyDispatcher`, and per-window services.
Config is split:

- **`SharedConfig`** ([`config/SharedConfig.java`](../src/main/java/com/editora/config/SharedConfig.java))
  holds app-wide state by reference across every window: the `Settings` object, the
  bookmark/note/breakpoint/connection stores, the user dictionary, the macro store, and the
  `ProjectManager` index.
- **`ConfigManager`** ([`config/ConfigManager.java`](../src/main/java/com/editora/config/ConfigManager.java))
  is per-window and owns only that window's session (`WorkspaceState` + its
  `workspaceStateFile`), delegating everything shared to `SharedConfig`.

So a `config.save()` from any window writes `settings.toml` + that window's session file
without clobbering another window's in-memory copy. A settings change in one window
broadcasts to all via `WindowManager.broadcastSettingsApplied()`. See
[config-and-schema](conventions.md#config-and-schema) for the storage details.

## The editor buffer

**`EditorBuffer`** ([`editor/EditorBuffer.java`](../src/main/java/com/editora/editor/EditorBuffer.java))
wraps the `CodeArea` and owns everything per-file: highlighting, the line-number gutter and
fold chevrons (`FoldManager`), the minimap, the overlays (whitespace, spell-check, search,
TODO, lint, diagnostics, …), bookmarks, notes, and the per-feature hooks (LSP, DAP, Mermaid,
completion, snippets). Features are injected rather than imported so the `editor` package
stays free of `ui`, `config`, and the feature packages — e.g. `setSnippetProvider`,
`setCompletionProvider`, `setMarkdownLintValidator`, `setLspCompletionProvider`. When you add
an editor feature, follow that injection pattern.

### Tabs are `TabContent`, not always buffers

A tab's `userData` is a **`editor/TabContent`** (`node()`/`title()`/`icon()`/`closeable()`),
not necessarily an `EditorBuffer`. `EditorBuffer` and `WelcomePane` both implement it, so the
Welcome page is a real tab (and future diff/image/help views can be too). **Every**
`tab.getUserData()` read must go through `MainController.bufferOf(Tab)`, which returns the
`EditorBuffer` or `null` for a non-buffer tab — a raw cast `ClassCastException`s on the
Welcome tab. Tab-switch consumers (status bar, Structure, git, …) must be null-safe.

## Package map

| Package | Responsibility |
| --- | --- |
| `command/` | The keyboard core: `Command`/`CommandRegistry`, `KeymapManager`, `KeyDispatcher`. Every action is a registered command. |
| `editor/` | `EditorBuffer` + the editor surface: highlighting, gutter, minimap, overlays, indentation, brackets, snippets/completion, the pure editing helpers (`Indenter`, `Commenter`, `Transposer`, `MarkdownLint`, …). |
| `ui/` | `MainController`, `WindowManager`, `SettingsWindow`, tool-window panels, the in-scene overlays (`OverlayHost`), status bar. |
| `config/` | `ConfigManager`/`SharedConfig`, `Settings` (TOML), `WorkspaceState` (JSON), the stores, and `config/migration/` (schema versioning). |
| `i18n/` | `Messages` — the localized catalog (six languages). |
| `lsp/` `dap/` | Language Server / Debug Adapter Protocol integration (lsp4j). |
| `git/` `diff/` | Native-CLI git, the diff/merge viewer. |
| `search/` `todo/` `snippet/` `template/` `completion/` `pdf/` `print/` `mermaid/` `http/` `web/` `macro/` `externaltool/` `logviewer/` `editorconfig/` `vfs/` `plugin/` `run/` | Feature packages, each largely self-contained and wired in `MainController`. |
| `process/` | `ProcessRunner` (the only place a subprocess is spawned) + `ProcessRegistry` (lifecycle of spawned servers). |

## Recurring patterns

You will see these everywhere; learn them once:

- **The off-thread service idiom** (`GitService`/`SearchService`/`MarkdownLintService`/…): a
  single daemon executor + a generation guard, posting results back via `Platform.runLater`.
  See [performance.md](performance.md).
- **The Canvas overlay idiom** (`SpellCheckOverlay`/`MarkdownLintOverlay`/…): a
  mouse-transparent `Canvas`, coalesced redraw, visible-paragraphs only, released to 1×1 when
  inactive. Recipe in [extending.md](extending.md#add-a-canvas-overlay).
- **The feature-coordinator pattern** (`LogViewerCoordinator`/`MermaidCoordinator`/…): pull a
  feature's logic out of `MainController` behind a `CoordinatorHost` interface so it is
  unit-testable. Recipe in [extending.md](extending.md#extract-a-feature-coordinator).
- **Injected hooks** keep `editor`/`completion` free of `ui`. Don't add a `ui` import to
  `editor`; inject a `Supplier`/`Consumer`/small interface instead.

## Where to start reading

- A feature end-to-end: pick one in `MainController` (e.g. `applyMarkdownLint`), follow it
  into its package's pure core + its `EditorBuffer` hooks + its overlay/panel.
- The command system: `command/Command`, `CommandRegistry`, `KeymapManager`, `KeyDispatcher`.
- The build: [building-and-packaging.md](building-and-packaging.md).
