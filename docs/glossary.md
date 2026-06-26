# Glossary

The recurring Editora-specific terms a new contributor will hit, with one place each to go read
more. Back to [the docs index](README.md). When a definition here disagrees with the code, the
code wins — see the root [`CLAUDE.md`](../CLAUDE.md) for the exhaustive reference.

---

### automatic module

a jar with no `module-info` (a `Automatic-Module-Name`, or a name derived
from the filename). `jlink` cannot link one, so the `dist` profile's [moditect](#moditect) step
injects a generated descriptor. See [dependencies.md](dependencies.md#automatic-modules--moditect-general).

### Canvas overlay

the discipline every document overlay follows (whitespace, spell-check,
search-highlight, TODO, Markdown-lint, LSP diagnostics, …): a mouse-transparent `Canvas` sized to
the viewport, [coalesced](#debounce--coalesce) redraw, drawing only the [visible paragraphs](#hot-path),
clamped via [`CanvasGuards`](../src/main/java/com/editora/editor/CanvasGuards.java) and released to
a 1×1 backing texture when inactive. References:
[`SpellCheckOverlay`](../src/main/java/com/editora/editor/SpellCheckOverlay.java),
[`MarkdownLintOverlay`](../src/main/java/com/editora/editor/MarkdownLintOverlay.java); recipe in
[extending.md](extending.md#add-a-canvas-overlay).

### chord

a single key combination token (e.g. `C-x`, `Cmd-S`) built by
[`KeyDispatcher.chord(KeyEvent)`](../src/main/java/com/editora/command/KeyDispatcher.java), with
modifiers in canonical order `C- M- Cmd- S-`. A multi-key Emacs binding (`C-x C-s`) is a
space-joined sequence of chords. The recorder in the keybinding editor captures the same tokens.

### ConfigManager

the per-window config object
([`config/ConfigManager.java`](../src/main/java/com/editora/config/ConfigManager.java)). It owns
only that window's session ([`WorkspaceState`](#workspacestate) + its `workspaceStateFile`) and
delegates everything app-wide to [`SharedConfig`](#sharedconfig). Contrast with `SharedConfig`.

### CoordinatorHost

the narrow interface
([`ui/CoordinatorHost.java`](../src/main/java/com/editora/ui/CoordinatorHost.java)) a
[feature coordinator](#feature-coordinator) reaches the window through (settings, active/all
buffers, status, prompts, the overlay host, …). A fake `Host` in a test makes the coordinator
unit-testable without a real window. Recipe in
[extending.md](extending.md#extract-a-feature-coordinator).

### debounce / coalesce

the two ways work is kept off the [hot path](#hot-path). *Debounce*: wait
for a quiet interval before doing expensive text-driven work
(`multiPlainChanges().successionEnds(Duration.ofMillis(250))`). *Coalesce*: collapse many redraw
requests in a pulse to one via a `pending` flag + `Platform.runLater`. See
[performance.md](performance.md#2-debounce-and-coalesce).

### EditorBuffer

the per-file hub
([`editor/EditorBuffer.java`](../src/main/java/com/editora/editor/EditorBuffer.java)): it wraps the
RichTextFX `CodeArea` and owns highlighting, the gutter/fold chevrons, the minimap, every overlay,
bookmarks, notes, and the per-feature [injected hooks](#injected-hooks-setxxxprovider). It is a
[`TabContent`](#tabcontent), so a tab's buffer is reached via `MainController.bufferOf(Tab)`, never
a raw cast.

### `editora.ownsKeys`

a node property
(`getProperties().put("editora.ownsKeys", Boolean.TRUE)`) that tells the scene-level
[`KeyDispatcher`](#keydispatcher) to leave a focused node's own navigation keys (`C-n`/`C-p`/arrows)
alone instead of resolving them as commands. Set on in-scene overlay cards and the completion popup
so their list navigation works. See `ui/ProjectPanel`, `ui/OverlayHost`.

### feature coordinator

a `final class …Coordinator` in `ui` that pulls a feature's logic out of
the large `MainController` and owns its service(s) + state, reaching the window only through
[`CoordinatorHost`](#coordinatorhost). `MainController` keeps one-line delegations. References:
`LogViewerCoordinator`, `MermaidCoordinator`, `HtmlPreviewCoordinator`. New features should be their
own coordinator, not bolted onto `MainController`.

### generation guard

the `AtomicLong` counter that drops a stale [off-thread](#off-thread-service-idiom)
result: a request bumps the counter, captures its value, and the result is applied only if the
counter is still equal when it returns. Prevents an old highlight/search/diagnostic from landing
after a newer one. See [performance.md](performance.md#1-never-block-the-javafx-application-thread).

### highlightExecutor idiom

the canonical [off-thread service](#off-thread-service-idiom) shape,
named for the `editor-highlighter` daemon executor in `EditorBuffer`: tokenize/parse off the FX
thread, then apply on the FX thread under a [generation guard](#generation-guard). Reused by every
service and overlay.

### hot path

code that runs on every keystroke or scroll pulse: typing/editing, scrolling, syntax
highlighting, the document overlays, the line-number gutter. The FX thread is sacred on these paths;
work must be [off-thread](#off-thread-service-idiom), [debounced/coalesced](#debounce--coalesce),
incremental, and visible-only. See [performance.md](performance.md#the-hot-paths).

### huge / large / heavy file tiers

three size guards in `EditorBuffer`. *huge* (≥ 50 MB): the
file is read-only with a capped load. *large* (≥ 5 MB): highlighting + minimap are disabled. *heavy*
(`setHeavyFile`, line-count ≥ `Settings.largeFileThreshold`, default 10000): minimap + LSP are
disabled but highlighting + editing stay. Overlays check these flags and no-op. See
[performance.md](performance.md#5-preserve-the-largehuge-file-guards).

### injected hooks (`setXxxProvider`)

the pattern that keeps the `editor` and `completion`
packages free of `ui`/`config`/feature packages. Instead of importing a feature, `EditorBuffer`
exposes setters (`setSnippetProvider`, `setCompletionProvider`, `setMarkdownLintValidator`,
`setLspCompletionProvider`, …) and `MainController` injects a `Supplier`/`Consumer`/small interface.
Add an editor feature this way; don't add a `ui` import to `editor`. See
[architecture.md](architecture.md#the-editor-buffer).

### KeyDispatcher

the scene-level `KEY_PRESSED` filter
([`command/KeyDispatcher.java`](../src/main/java/com/editora/command/KeyDispatcher.java)) that builds
[chord](#chord) tokens, holds a pending-prefix buffer for multi-key Emacs chords, and resolves them
to command ids via the [keymap](#keymap). A lone unbound key is not consumed, so normal typing works.

### keymap

the [chord](#chord)→command-id map managed by
[`KeymapManager`](../src/main/java/com/editora/command/KeymapManager.java). Five are bundled (the
ordered `KeymapManager.AVAILABLE`): **emacs** (default), **cua**, **sublime**, **vscode**,
**intellij**. The four non-Emacs maps are non-modal (different accelerators over the same command
ids). Each GUI keymap ships a base `<name>.json` (Ctrl) and a `<name>.mac.json` (Cmd); Emacs is
single-file. User overrides layer on top via `applyOverrides`.

### moditect

the `moditect-maven-plugin` (`dist` profile) that injects a generated `module-info`
into an [automatic module](#automatic-module) so `jlink` can link it (RichTextFX & friends, tm4e
core, PDFBox, lsp4j, java-diff-utils, …). A few need hand-written descriptors. See
[dependencies.md](dependencies.md#automatic-modules--moditect-general).

### off-thread service idiom

a feature's impure engine: a single daemon `ExecutorService` + a
[generation guard](#generation-guard), running heavy work off the FX thread and posting results back
via `Platform.runLater`. References: `GitService`, `SearchService`, `MarkdownLintService`,
`MermaidService`. See [performance.md](performance.md#1-never-block-the-javafx-application-thread).

### OverlayHost (in-scene overlays)

the single shared host
([`ui/OverlayHost.java`](../src/main/java/com/editora/ui/OverlayHost.java)) that renders the command
palette and every keyboard picker/popup as one "card" over a dim, click-to-dismiss backdrop inside
the scene — **not** as a `javafx.stage.Popup`. A Popup is a separate native window that on Windows
doesn't reliably take OS keyboard focus, which orphaned focus and killed typing app-wide; the
in-scene host owns focus capture/restore and `Esc`/`C-g` dismissal. `OverlayInput` builds form
cards over the same host.

### the palette

the command palette (`M-x`), populated from `CommandRegistry.all()`. Every
user-facing action is a registered `command/Command`, so it is discoverable for free; a setting also
needs a palette command, not just a Settings-window control. See
[conventions.md](conventions.md#every-feature-is-a-command).

### schema version / migration

every structured config file carries an integer `schemaVersion`
(the owning POJO's `SCHEMA_VERSION`), enumerated in
[`config/migration/ConfigSchema.java`](../src/main/java/com/editora/config/migration/ConfigSchema.java)
with an ordered map of `v→v+1` step migrations. Reads go through
`ConfigMigrations.readVersioned(...)`, which applies the chain; a file newer than the build is
backed up and defaults loaded (`NewerThanSupportedException`). See
[conventions.md](conventions.md#config-and-schema).

### SharedConfig

the app-wide config
([`config/SharedConfig.java`](../src/main/java/com/editora/config/SharedConfig.java)) held by
reference across every window: the `Settings` object, the bookmark/note/breakpoint/connection
stores, the user dictionary, the macro store, and the `ProjectManager` index. Contrast with the
per-window [`ConfigManager`](#configmanager). See
[architecture.md](architecture.md#multi-window-model).

### TabContent

the tab-content interface
([`editor/TabContent.java`](../src/main/java/com/editora/editor/TabContent.java)):
`node()`/`title()`/`icon()`/`closeable()`. A tab's `userData` is a `TabContent`, not always an
[`EditorBuffer`](#editorbuffer) — `EditorBuffer` and `WelcomePane` both implement it. Read it via
`MainController.bufferOf(Tab)` (returns the buffer or `null`); a raw cast `ClassCastException`s on
the Welcome tab. See [architecture.md](architecture.md#tabs-are-tabcontent-not-always-buffers).

### tool window

a dockable side/bottom panel (Project, Search, Problems, Git, Debug, …) managed by
`ToolWindowManager` and built from a `ToolWindowContent`. `register(ToolWindow)` adds it to a stripe
+ toggle command; `setAvailable(tw, condition)` is a *transient* hide (e.g. "only in a git repo"),
distinct from the persisted `setVisible` preference. Recipe in
[extending.md](extending.md#add-a-tool-window).

### `Vfs.isLocal` (local-only gating)

the single predicate
([`vfs/Vfs.java`](../src/main/java/com/editora/vfs/Vfs.java)) that decides whether a buffer's `Path`
is on the default filesystem versus a remote (SFTP) `SftpFileSystem`. Every local-process feature
(LSP, DAP, run, git, external-change polling) gates on it, because those reach the file via
`path.toFile()`, which a remote path can't honor. A null/untitled path counts as local.

### WorkspaceState

the per-window session POJO
([`config/WorkspaceState.java`](../src/main/java/com/editora/config/WorkspaceState.java)), stored as
JSON in `workspace-state.json` (or `projects/<id>.json`): collapsed fold regions, tool-window
layout/visibility, per-file Markdown/read-only modes, program args, debug watches, etc. Owned by the
per-window [`ConfigManager`](#configmanager). Bookmarks/notes are deliberately *not* here — they live
in their own stores.
