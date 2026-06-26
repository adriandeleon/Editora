# 0007 — One window per project + a `SharedConfig`/`ConfigManager` split

**Status:** Accepted

## Context

Editora is project-aware and multi-window: a user can have several projects (and several
no-project "New Window"s) open at once. Early on, a single `ConfigManager` held everything,
which made "the active project" a global — switching a project in one place affected all windows,
and parallel windows clobbered each other's open-files/layout.

## Decision

Each project (and each New Window) opens in its **own top-level window** with its own
`MainController`, `Stage`, `CommandRegistry`, `KeyDispatcher`, and per-window services. Config is
split in two:

- **`SharedConfig`** — app-wide state held by reference across every window (`Settings`, the
  bookmark/note/breakpoint/connection/macro stores, the user dictionary, the `ProjectManager`
  index).
- **`ConfigManager`** — per-window, owning only that window's session (`WorkspaceState` + its
  `workspaceStateFile`) and delegating everything shared to `SharedConfig`.

## Consequences

- A `config.save()` from any window writes `settings.toml` + that window's session file without
  clobbering another window's in-memory copy. A settings change broadcasts via
  `WindowManager.broadcastSettingsApplied()`.
- `config.getBookmarks()/getNotes()/getBreakpoints()` return the bucket for *this* window's
  project, keyed off its session file (`currentBookmarkKey()`).
- The open-window set is tracked in `ProjectManager.Index.openProjectIds` and restored next launch
  (a debounced reconcile distinguishes a single-window close from a quit, so a quit reopens
  everything).
- Per-window services must all be per-instance (no static per-window state) and disposed on window
  close. See the [config subsystem deep-dive](../subsystems/config-and-migrations.md).
