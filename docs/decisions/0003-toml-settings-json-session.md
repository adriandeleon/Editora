# 0003 — TOML for settings, JSON for session + stores

**Status:** Accepted

## Context

Editora persists several kinds of state: user **preferences** (font, theme, keymap, toggles,
keybindings), per-window **session** state (open files, folds, tool-window layout), and a set of
**stores** (bookmarks, notes, breakpoints, connections, macros, recent files, projects).
Preferences are meant to be hand-editable; session/stores are machine-managed.

## Decision

- **Preferences** live in `settings.toml`, read/written with a Jackson `TomlMapper`
  (jackson-dataformat-toml). TOML is comfortable to hand-edit, with comments and clear sections.
- **Session state** stays JSON in `workspace-state.json`, and the stores stay JSON in their own
  files. These are machine-managed, so editability doesn't matter and JSON is the lighter choice.

## Consequences

- The switch to TOML for settings was a clean cut — a pre-existing `settings.json` is **not**
  migrated.
- Both POJOs (`Settings`, `WorkspaceState`) are `@JsonIgnoreProperties(ignoreUnknown = true)`, so
  a save rewrites with only modeled fields.
- Every structured file carries a `schemaVersion` and goes through the same migration path
  (`ConfigMigrations.readVersioned`), which is mapper-agnostic — it works for TOML and JSON alike
  because `TomlMapper` yields ordinary Jackson nodes. See
  [config-and-migrations.md](../subsystems/config-and-migrations.md).
- Bundled keymaps and TextMate grammars stay JSON — they are app resources, not user config.
