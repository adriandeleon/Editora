# Config and schema migrations

How Editora finds, reads, writes, and version-migrates its on-disk configuration. Back to [the docs index](../README.md).

The `config/` package (plus `config/migration/`) owns everything the editor persists between launches: preferences, per-window session state, recent files, and a handful of standalone stores (bookmarks, notes, breakpoints, SFTP connections, macros, plugin enable-state, projects). It is multi-window-aware, writes off the FX thread, and carries a per-file schema version so an upgrade or downgrade never loses or corrupts data.

## Config directory

Everything lives under one config directory. It is resolved by precedence, with the CLI flag winning over the environment, the environment over `--dev`, and `--dev` over the production default:

1. `--config-dir <path>` — the CLI arg, parsed by `App.configDirArg` (pure, unit-tested).
2. `EDITORA_CONFIG_DIR` — used verbatim as the config folder when set and non-blank.
3. `--dev` → `~/.editora-dev/` (the `App.devFlag`), so a development instance can't disturb production config.
4. `~/.editora/` (the default).

The env/default fallback is the pure resolver [`ConfigManager.resolveConfigDir(editoraHome, userHome, dev)`](../../src/main/java/com/editora/config/ConfigManager.java) — `editoraHome` (trimmed) when non-blank, else `userHome/.editora` or `userHome/.editora-dev`. `user.home` is the user profile on every OS, so this works on macOS, Linux, and Windows. The `--config-dir` precedence step is applied above this by the caller in `App`. Malformed input falls back to `.` (the current directory).

## File formats and layout

Two serialization formats, chosen per file:

| File | Format | POJO / store | Notes |
| --- | --- | --- | --- |
| `settings.toml` | TOML (Jackson `TomlMapper`) | `Settings` | App-wide preferences. |
| `workspace-state.json` | JSON | `WorkspaceState` | The no-project window's session. |
| `projects/<id>.json` | JSON | `WorkspaceState` | One project window's session. |
| `windows/<uuid>.json` | JSON | `WorkspaceState` | An untitled "New Window" session. |
| `recent-files.json` | JSON | `RecentFiles.Stored` | Was a bare array (v0). |
| `bookmarks.json` | JSON | `BookmarkStore` | Per-project buckets. |
| `notes.json` | JSON | `NoteStore` | Per-project buckets. |
| `breakpoints.json` | JSON | `BreakpointStore` | Per-project buckets. |
| `history/index.json` + `history/blobs/` | JSON | `HistoryStore` | Local File History; blobs gzip'd. |
| `connections.json` | JSON | `ConnectionStore` | SFTP connection metadata, no secrets. |
| `macros.json` | JSON | `MacroStore` | App-global keyboard macros. |
| `plugins.json` | JSON | `PluginStore` | Plugin enable-state. |
| `projects.json` | JSON | `ProjectManager.Index` | Projects index + open-window set. |
| `search-history.json` | JSON | `SearchHistory` | Find-in-Files history. |
| `dictionary.txt` | plain text | (in-memory `Set<String>`) | User spell-check words, one per line. |

`settings.toml` was a clean cut from a pre-existing `settings.json` — there is no JSON→TOML migration. The bundled keymap and TextMate grammars stay JSON but are app *resources*, not user config, so they are out of scope here.

## SharedConfig vs ConfigManager

The config is split in two so that multiple windows can run over the same preferences without clobbering each other:

- [`SharedConfig`](../../src/main/java/com/editora/config/SharedConfig.java) — the **app-wide** half: the `Settings` object, the bucketed stores (`BookmarkStore`/`NoteStore`/`BreakpointStore`/`HistoryStore`), `ConnectionStore`, `MacroStore`, `PluginStore`, the user spell dictionary, and the `ProjectManager` index. A single instance is created once at startup and held **by reference** across every window. It owns the `ConfigWriter` (below) and the file-location getters (`getSettingsFile()`, `getBookmarksFile()`, …).
- [`ConfigManager`](../../src/main/java/com/editora/config/ConfigManager.java) — the **per-window** half: it owns only that window's `WorkspaceState` and the `workspaceStateFile` it lives in, and delegates everything shared to its `SharedConfig`. In single-window/test use a `ConfigManager` constructs its own `SharedConfig`.

So a `save()` from any window writes `settings.toml` plus that window's session file without touching another window's in-memory copy.

### Per-window session and per-project buckets

`ConfigManager.setWorkspaceStateFile(Path)` points a window at its session file (default `workspace-state.json` for the no-project window) and reloads it; `useDefaultWorkspaceStateFile()` returns to the default. The bucketed stores are keyed by a **project key** derived from the session file by the private `ConfigManager.currentBookmarkKey()`:

- `workspace-state.json` → `""` (the no-project / global bucket).
- `windows/<uuid>.json` (an untitled "New Window") → also `""` — every no-project window shares the global bucket.
- `projects/<id>.json` → `<id>`. **Only** a file directly under `projects/` gets its own bucket.

`getBookmarks()`/`getNotes()`/`getBreakpoints()`/`getHistory()` each return the bucket for *this* window's key, so switching the session file automatically swaps which bookmarks/notes/breakpoints are visible. `SharedConfig.deleteBookmarksForProject(key)` (and the note/breakpoint/history twins) drop a whole project bucket when a project is deleted.

Bookmarks were deliberately moved out of `WorkspaceState` into their own `bookmarks.json`. `SharedConfig.loadBookmarks()` runs a one-time migration on first launch (`migrateLegacyBookmarks` / `extractAndStripBookmarks`): it pulls the legacy `bookmarks` node out of `workspace-state.json` (→ `""`) and each `projects/<id>.json` (→ that id), strips the node, and writes `bookmarks.json` so the migration never runs again. This is field-level back-compat that runs *before* the versioned read path below, not a registered schema migration.

## ConfigWriter: off-FX-thread atomic writes

[`ConfigWriter`](../../src/main/java/com/editora/config/ConfigWriter.java) performs all `settings.toml` and session writes off the JavaFX thread on a single `config-writer` daemon thread.

The contract: callers serialize a **consistent snapshot to bytes on their own thread** (the FX thread is single-threaded, so reading the config POJOs needs no locking) and hand the immutable bytes to the writer. Each write is a **temp-file + atomic move** (`writeAtomic`), so a crash mid-write never leaves a half-written config.

Two paths:

- `enqueue(file, bytes)` — non-blocking and **coalesced per file** (latest bytes win), via `ConfigManager.saveAsync()` → `SharedConfig.enqueueSettings()`. This backs the frequent in-session save (`MainController.requestSave`).
- `flush()` — blocks until everything queued has landed, via `ConfigManager.save()` → `SharedConfig.flushWrites()`. This is the durable form used by quit (`persistSession`), one-off actions, and `exportConfig()`. `App.start` registers a JVM-shutdown flush.

`settings.toml` and `workspace-state.json` are the only files with both an async and a sync writer, and **both funnel through the one writer queue**. Because a single thread keeps writes ordered, a stale async write can never land *after* and clobber a later durable one. The other stores (`bookmarks.json`, `notes.json`, …) keep their own direct synchronous `json.writeValue` calls in `SharedConfig` and don't go through the queue.

## Schema versioning and migrations

Every structured config file carries an integer `schemaVersion` field, and its owning POJO declares a `SCHEMA_VERSION` constant (the baseline is **1**). The [`config/migration/`](../../src/main/java/com/editora/config/migration) package drives reads through one engine.

### The registry: `ConfigSchema`

[`ConfigSchema`](../../src/main/java/com/editora/config/migration/ConfigSchema.java) is an enum, one constant per versioned file. Each carries three things:

1. The **current** version (the POJO's `SCHEMA_VERSION`).
2. The version to **assume when the file has no `schemaVersion` marker** — `1`, the pre-versioning baseline (a bare JSON array is detected as `0` instead, by `ConfigMigrations.versionOf`).
3. An ordered map of **step `Migration`s** keyed by the version they upgrade *from* (`v → v+1`).

For example `SETTINGS` is currently at `Settings.SCHEMA_VERSION` (45) with identity steps for every bump from 1 through 44; `PROJECTS` registers `1 → 2` as `seedOpenProjectIds`; `RECENT` registers `0 → 1` as `wrapRecentFilesArray`.

### The engine: `ConfigMigrations.readVersioned`

[`ConfigMigrations.readVersioned(file, mapper, defaults, schema)`](../../src/main/java/com/editora/config/migration/ConfigMigrations.java) is the single read path (mapper-agnostic, so it works for both `TomlMapper` and the JSON `ObjectMapper` — `TomlMapper` produces ordinary Jackson nodes):

1. Missing/unreadable/empty → return `defaults`.
2. Parse to a Jackson tree.
3. `upgrade(schema, tree, mapper)` — read the stored version (`versionOf`), then `applySteps` runs the `from → to` chain in order (one registered step per version, throwing `IllegalStateException` if a step is missing), and stamps `schemaVersion` to the current version.
4. Merge the migrated object **onto `defaults`** via `mapper.readerForUpdating(defaults)`. So a purely additive new field just defaults when an old file is read.
5. Malformed content or a misconfigured migration → fall back to `defaults` rather than crash.

A [`Migration`](../../src/main/java/com/editora/config/migration/Migration.java) is a `@FunctionalInterface` over the in-memory tree (`JsonNode apply(JsonNode)`). Keep steps pure and total: never throw on unexpected-but-harmless input, return the best tree you can.

### Downgrade safety

If a file's stored `schemaVersion` is **newer** than this build supports (the user downgraded the app), `upgrade` throws [`NewerThanSupportedException`](../../src/main/java/com/editora/config/migration/NewerThanSupportedException.java). `readVersioned` then backs the file up to `<name>.v<n>.bak` (`ConfigMigrations.backup`, preserving any existing backup) and returns `defaults`. An older Editora never overwrites — and silently drops fields from — a newer config.

### Worked examples

- **RECENT 0 → 1** — `recent-files.json` was a bare JSON array. `versionOf` reports a bare array as `0`; `wrapRecentFilesArray` wraps it into `{ "files": [ … ] }` (the `RecentFiles.Stored` shape), and `readVersioned` stamps `schemaVersion: 1`.
- **PROJECTS 1 → 2** — `seedOpenProjectIds` adds the multi-window `openProjectIds` set (when absent), seeding it from the single `activeProjectId` a pre-multi-window install tracked, so the previously-active project reopens as its own window. No active project → an empty set (the global window opens by default).

## How to add a migration

The short version is in [conventions.md → Config and schema](../conventions.md#config-and-schema). The full steps:

1. **Bump the POJO's `SCHEMA_VERSION`** (`Settings`, `WorkspaceState`, `BookmarkStore`, `ProjectManager.Index`, `RecentFiles`, …).
2. **Add one `v → v+1` entry** to that file's `steps` map in `ConfigSchema`. For a purely additive field (new field with a default), use `ConfigMigrations::identity` — the read path merges onto defaults, so the old file needs no transform; it just gets re-stamped. For a structural change, write a small pure `Migration` and register it (see `wrapRecentFilesArray` / `seedOpenProjectIds`).
3. Done. The read path, version stamping, and downgrade backup are automatic once the step is registered.

If the change adds a **new Jackson-serialized type**, also add `opens com.editora.<pkg> to com.fasterxml.jackson.databind;` in `module-info.java`. The config package already has `opens com.editora.config to com.fasterxml.jackson.databind` (and `com.editora.vfs`/`macro`/`todo`/`externaltool` for the types those packages serialize through this engine).

## Projects

[`ProjectManager`](../../src/main/java/com/editora/config/ProjectManager.java) holds the projects index, persisted as JSON in `projects.json` (the inner `ProjectManager.Index`, schema **2**). A project is a named single folder; each project's session is a separate `WorkspaceState` JSON under `projects/<id>.json` (`ProjectManager.stateFile(project)`).

The index tracks the **open-window set** in `openProjectIds` (`""` = the global no-project window; an untitled window's `untitled:<uuid>` key also collapses to `""` for bucketing but is tracked here by its key) plus `activeProjectId` as the last-focused window. `markOpen`/`markClosed`/`setOpenWindows` mutate the set; it's restored on the next launch by `WindowManager.launch`. The `1 → 2` migration (`seedOpenProjectIds`) is what bridges pre-multi-window installs into this model.
