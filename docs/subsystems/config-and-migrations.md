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
| `settings.json` | JSON | `Settings` | App-wide preferences. |
| `workspace-state.json` | JSON | `WorkspaceState` | The no-project window's session. |
| `projects/<id>.json` | JSON | `WorkspaceState` | One project window's session. |
| `windows/<uuid>.json` | JSON | `WorkspaceState` | An untitled "New Window" session. |
| `recent-files.json` | JSON | `RecentFiles.Stored` | Was a bare array (v0). One shared instance; remote entries kept. |
| `bookmarks.json` | JSON | `BookmarkStore` | Per-project buckets. |
| `notes.json` | JSON | `NoteStore` | Per-project buckets. |
| `breakpoints.json` | JSON | `BreakpointStore` | Per-project buckets. |
| `history/index.json` + `history/blobs/` | JSON | `HistoryStore` | Local File History; blobs gzip'd. |
| `connections.json` | JSON | `ConnectionStore` | SFTP connection metadata, no secrets. |
| `macros.json` | JSON | `MacroStore` | App-global keyboard macros. |
| `plugins.json` | JSON | `PluginStore` | Plugin enable-state. |
| `projects.json` | JSON | `ProjectManager.Index` | Projects index + open-window set. |
| `search-history.json` | JSON | `SearchHistory` | Find-in-Files history. One shared instance. |
| `agent-sessions.json` | JSON | `AgentSessionHistory` | AI Agent chat sessions. One shared instance. |
| `dictionary.txt` | plain text | (in-memory `Set<String>`) | User spell-check words, one per line. |

On first launch after the format change, `SharedConfig.loadSettings()` converts a legacy
`settings.toml` when `settings.json` is absent. It reads the TOML through the ordinary versioned
migration pipeline, atomically writes the complete JSON replacement, and only then removes TOML.
If writing fails, the launch still uses the migrated in-memory values and leaves TOML for a retry;
if both files exist, JSON wins. Project-local `.editora/settings.toml` remains readable and is
converted by the explicit **Edit Project Settings** action.

## SharedConfig vs ConfigManager

The config is split in two so that multiple windows can run over the same preferences without clobbering each other:

- [`SharedConfig`](../../src/main/java/com/editora/config/SharedConfig.java) — the **app-wide** half: the `Settings` object, the bucketed stores (`BookmarkStore`/`NoteStore`/`BreakpointStore`/`HistoryStore`), `ConnectionStore`, `MacroStore`, `PluginStore`, the user spell dictionary, the three history lists (`recentFiles()`, `searchHistory()`, `agentSessions()`), and the `ProjectManager` index. A single instance is created once at startup and held **by reference** across every window. It owns the `ConfigWriter` (below) and the file-location getters (`getSettingsFile()`, `getBookmarksFile()`, …).
- [`ConfigManager`](../../src/main/java/com/editora/config/ConfigManager.java) — the **per-window** half: it owns only that window's `WorkspaceState` and the `workspaceStateFile` it lives in, and delegates everything shared to its `SharedConfig`. In single-window/test use a `ConfigManager` constructs its own `SharedConfig`.

So a `save()` from any window writes `settings.json` plus that window's session file without touching another window's in-memory copy.

### Changes reach every window

`Settings` is one object, but each window applies it to its own buffers and services. `SharedConfig.enqueueSettings(origin)` — the single point every save goes through — compares the serialized preferences with what all windows last applied (`markSettingsApplied()`, first called when a second window is built). When they differ it tells `WindowManager`, which re-applies them in every window except the one that saved (that one applied the change itself), after a short coalescing delay so a burst such as a Ctrl+wheel zoom costs the other windows one re-apply. A palette toggle, a key binding or any other command therefore needs no broadcast of its own; `lastUpdateCheckEpoch` and `dismissedUpdateVersion` are bookkeeping and never trigger one. The Settings window still calls `broadcastSettingsApplied()` directly, which applies everywhere at once and resets the baseline.

The recent-files list, search history and agent-session history are single instances too. Windows read and add to the same object, `WindowManager` refreshes every window's recent menu and query dropdown when one changes, and a window never binds a control to the shared list itself (it outlives the window) — `SearchCoordinator` keeps its own copy for the combo.

### Per-window session and per-project buckets

`ConfigManager.setWorkspaceStateFile(Path)` points a window at its session file (default `workspace-state.json` for the no-project window) and reloads it; `useDefaultWorkspaceStateFile()` returns to the default. The bucketed stores are keyed by a **project key** derived from the session file by the private `ConfigManager.currentBookmarkKey()`:

- `workspace-state.json` → `""` (the no-project / global bucket).
- `windows/<uuid>.json` (an untitled "New Window") → also `""` — every no-project window shares the global bucket.
- `projects/<id>.json` → `<id>`. **Only** a file directly under `projects/` gets its own bucket.

`getBookmarks()`/`getNotes()`/`getBreakpoints()`/`getHistory()` each return the bucket for *this* window's key, so switching the session file automatically swaps which bookmarks/notes/breakpoints are visible. `SharedConfig.deleteBookmarksForProject(key)` (and the note/breakpoint/history twins) drop a whole project bucket when a project is deleted.

Bookmarks were deliberately moved out of `WorkspaceState` into their own `bookmarks.json`. `SharedConfig.loadBookmarks()` runs a one-time migration on first launch (`migrateLegacyBookmarks` / `extractAndStripBookmarks`): it pulls the legacy `bookmarks` node out of `workspace-state.json` (→ `""`) and each `projects/<id>.json` (→ that id), strips the node, and writes `bookmarks.json` so the migration never runs again. This is field-level back-compat that runs *before* the versioned read path below, not a registered schema migration.

## ConfigWriter: off-FX-thread atomic writes

[`ConfigWriter`](../../src/main/java/com/editora/config/ConfigWriter.java) performs all `settings.json` and session writes off the JavaFX thread on a single `config-writer` daemon thread.

The contract: callers serialize a **consistent snapshot to bytes on their own thread** (the FX thread is single-threaded, so reading the config POJOs needs no locking) and hand the immutable bytes to the writer. Each write is a **temp-file + atomic move** (`writeAtomic`), so a crash mid-write never leaves a half-written config.

Two paths:

- `enqueue(file, bytes)` — non-blocking and **coalesced per file** (latest bytes win), via `ConfigManager.saveAsync()` → `SharedConfig.enqueueSettings()`. This backs the frequent in-session save (`MainController.requestSave`).
- `flush()` — blocks until everything queued has landed, via `ConfigManager.save()` → `SharedConfig.flushWrites()`. This is the durable form used by quit (`persistSession`), one-off actions, and `exportConfig()`. `App.start` registers a JVM-shutdown flush.

`settings.json` and `workspace-state.json` have both async and sync save paths, and **both funnel through the one writer queue**. The three history lists (`recent-files.json`, `search-history.json`, `agent-sessions.json`) are queued on it as well, as an immutable snapshot serialized on the writer thread. Because a single thread keeps writes ordered, a stale async write can never land *after* and clobber a later durable one. Local History's `history/index.json` also uses that queue: it waits for the exact index snapshot to become durable before confirming a destructive file operation. Its blob GC is queued under the publication lock, before durable callbacks can start a newer snapshot; the history worker also queues GC under its publication lock so an older live set cannot overtake a new blob write. That GC is throttled (`HistoryService.gcIfDue`, at most once per ten minutes) so a save does not walk the whole blob store; a skipped pass deletes nothing and the next one uses the live set of its own moment, while a purge (`localHistory.purgeFile` / `localHistory.purgeProject`) requests an immediate pass. Retention is applied to the whole index once per start, off the FX thread (`HistoryRetention.sweep`), not only to the file being saved; a file's newest revision, labelled revisions and pre-delete copies are exempt from the ordinary limits but expire after a longer lease (six times the age limit, at least 180 days). Other stores (`bookmarks.json`, `notes.json`, …) keep direct synchronous writes in `SharedConfig`.

## More than one process on a config directory

`SharedConfig` shares the stores between the *windows* of one process. Two *processes* on the same
directory are a different matter, and an ordinary one: `App.shouldForwardLaunch` only forwards a plain
"open these files" launch to the running editor, so a launch with no file argument, `--project`,
`--new-file`, `--new-instance` or `--diff-ui` starts a second JVM on `~/.editora`.

`App.start` calls `SharedConfig.claimInstance()` before any window is built. The claim is an OS file lock
on `<configDir>/instance.lock` ([`InstanceLock`](../../src/main/java/com/editora/config/InstanceLock.java)),
released by the operating system when the holder dies, so a crash never leaves a stale claim:

- **byte 0, exclusive** — held for life by the first process, the *primary*. There is no promotion: a
  secondary that outlives the primary stays a secondary.
- **byte 1, shared** — held for life by every secondary, so the primary can ask "is anyone else here
  right now?" by trying to take it exclusively.

`SharedConfig.isPrimaryInstance()` is the single source of truth derived from it:

- `WindowManager` shows a one-time warning in a secondary (`status.config.secondaryInstance`).
- Local-history blob GC runs only in the primary, and only while no secondary is alive
  (`mayCollectHistoryBlobs`, asked on the history worker right before deleting). GC deletes every blob
  outside *this* process's index, and another process's revisions are not in it.

A config that was never claimed (tests, embedders) counts as its own sole user, and a filesystem that
refuses locks degrades to "primary, alone".

**What is still not safe across processes:** every store is written whole from its process's in-memory
copy, so `settings.json`, `notes.json`, `bookmarks.json`, `breakpoints.json`, `projects.json`,
`recent-files.json`, `history/index.json` and the other stores remain *last-writer-wins* between two
processes. There is no merge-on-write and no cross-process change notification; the warning exists
because of that. (The spawned-process ledger is per process — see
[LSP and DAP](lsp-and-dap.md#processregistry--processrunner).)

## Schema versioning and migrations

Every structured config file carries an integer `schemaVersion` field, and its owning POJO declares a `SCHEMA_VERSION` constant (the baseline is **1**). The [`config/migration/`](../../src/main/java/com/editora/config/migration) package drives reads through one engine.

### The registry: `ConfigSchema`

[`ConfigSchema`](../../src/main/java/com/editora/config/migration/ConfigSchema.java) is an enum, one constant per versioned file. Each carries three things:

1. The **current** version (the POJO's `SCHEMA_VERSION`).
2. The version to **assume when the file has no `schemaVersion` marker** — `1`, the pre-versioning baseline (a bare JSON array is detected as `0` instead, by `ConfigMigrations.versionOf`). `SETTINGS` also carries a small *evidence* table (`versionWithoutMarker`): a key that first appeared in version N proves the file is at least N, so a current-shape file that merely lost its marker resumes after the steps that are not safe to repeat instead of replaying all of them from 1.
3. An ordered map of **step `Migration`s** keyed by the version they upgrade *from* (`v → v+1`).

For example `SETTINGS` is currently at `Settings.SCHEMA_VERSION` (105), with an additive identity step for
the Default JDK at `102 → 103` (also used by standalone Java files) and `104 → 105` as
`retireUnusedSettingsKeys`; `WORKSPACE` uses `11 → 12` for the per-run-configuration JDK
override; `PROJECTS` registers `1 → 2` as `seedOpenProjectIds`; and `RECENT` registers `0 → 1` as
`wrapRecentFilesArray`.

### The engine: `ConfigMigrations.readVersioned`

[`ConfigMigrations.readVersioned(file, mapper, defaults, schema)`](../../src/main/java/com/editora/config/migration/ConfigMigrations.java) is the single read path. It is mapper-agnostic, so the same migrations also process the legacy TOML file before conversion because `TomlMapper` produces ordinary Jackson nodes:

1. Missing/unreadable/empty → return `defaults`.
2. Parse to a Jackson tree.
3. `upgrade(schema, tree, mapper)` — read the stored version (`versionOf`), then `applySteps` runs the `from → to` chain in order (one registered step per version, throwing `IllegalStateException` if a step is missing), and stamps `schemaVersion` to the current version.
4. Merge the migrated object **onto `defaults`** via `mapper.readerForUpdating(defaults)`. So a purely additive new field just defaults when an old file is read.
5. A value of the wrong type (a hand edit such as `"showMinimap": "yes"`) costs only that top-level property: the merge is retried one property at a time, the bad one keeps its default, and every other property is still read.
6. Unparseable content or a misconfigured migration → fall back to `defaults` rather than crash.

Whenever content was not read as written, the original file is copied to `<name>.corrupt.bak` (a counter is appended when the name is taken) and the caller is told through the optional `Consumer<ConfigLoadProblem>`. `SharedConfig` collects these for its own files; the first window built shows each one once as a status-bar error (`ConfigLoadMessages`), so it stays flagged in the message log.

Numeric setters that feed arithmetic clamp (`Settings.setTabSize`/`setFontSize`/`setFontZoom`), so an out-of-range value in the file loads as the nearest legal one rather than failing later.

A [`Migration`](../../src/main/java/com/editora/config/migration/Migration.java) is a `@FunctionalInterface` over the in-memory tree (`JsonNode apply(JsonNode)`). Keep steps pure and total: never throw on unexpected-but-harmless input, return the best tree you can. Make a step **safe to repeat** by checking for the shape it produces (`splitKeybindings` skips a file that already has `keybindingsMac`); when the target shape cannot be told apart from the source, add the next version's new key to the schema's evidence table.

A getter that *resolves* a blank value (`getAuthorName()` → the OS user, `getPluginRegistryUrl()` → the built-in registry) must not be what Jackson serializes, or the first save freezes the resolved value into the file: mark it `@JsonIgnore` and put `@JsonProperty` on the raw getter and the setter.

### Downgrade safety

If a file's stored `schemaVersion` is **newer** than this build supports (the user downgraded the app), `upgrade` throws [`NewerThanSupportedException`](../../src/main/java/com/editora/config/migration/NewerThanSupportedException.java). `readVersioned` then backs the file up to `<name>.v<n>.bak` (`ConfigMigrations.backup`, preserving any existing backup) and returns `defaults`. An older Editora never overwrites — and silently drops fields from — a newer config.

That guarantee holds when the backup itself fails (a read-only directory, or every backup name already taken): the problem is reported with no backup path, `ConfigLoadProblem.mustNotOverwrite()` is true, and `SharedConfig` then refuses to write that file for the rest of the session (`isWriteProtected`). The same applies to an unparseable file that could not be copied aside. The Local File History index is the one exception — it is reported but still written, because its publication protocol must keep running.

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
