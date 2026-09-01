# 0011 — JSON for global and project settings

**Status:** Accepted

## Context

Editora used TOML for global preferences and committed per-project toolchain overrides while all
other persistent stores and session files used JSON. TOML's intended advantage was hand-editability,
but Editora rewrites the complete modeled settings object on save, so comments and unknown fields
were not preserved. Maintaining a second serialization format therefore added complexity without
providing a durable user benefit.

## Decision

- Global preferences live in `settings.json` and use the same pretty-printing Jackson
  `ObjectMapper` as the other configuration stores.
- Committed project overrides live in `.editora/settings.json`.
- An existing global `settings.toml` is migrated automatically when `settings.json` is absent. The
  current schema migration pipeline reads it, an atomic write creates the JSON replacement, and the
  TOML file is removed only after that write succeeds. Existing JSON always wins.
- Legacy project TOML remains readable so existing repositories keep working. The explicit
  **Edit Project Settings** action converts it atomically to JSON; malformed TOML is never replaced
  by an empty file.
- TOML support remains an application feature for editing TOML documents, parsing `Cargo.toml`, and
  reading legacy settings. This decision removes TOML only as Editora's active config format.

## Consequences

- Preferences, sessions, project overrides, and stores now share one serialization format and one
  set of JSON tooling.
- JSON does not support comments. Project settings seed an ignored `_description` and `_examples`
  block to keep the hand-edited file discoverable without activating example overrides.
- The settings schema version does not change solely for this format conversion: the serialized
  model is unchanged, and existing field migrations run before the JSON replacement is written.
