# Conventions

The rules a change must follow to be mergeable. Most PR review comments come back to one of
these. (See also [performance.md](performance.md), which is a convention important enough to
get its own page.)

## Every feature is a Command

Editora is command-driven. Every user-facing action is a registered
`command/Command` (id + title + runnable) in `MainController.registerCommands()`. The command
palette, the keybinding editor, and toolbar buttons all dispatch through the registry, so a
properly registered command is discoverable for free.

- **Never wire a UI handler straight to logic** when a command fits — register the command and
  have the button/menu run it by id.
- If it needs a key, add a binding to the bundled keymap(s) (see
  [extending.md](extending.md#add-a-command)). A keymap only changes accelerators; a command
  must work from the palette without one.

## Every setting also needs a palette command

A Settings-window control is not enough on its own. Add a command that flips/prompts the
**same** `Settings` field, persists (`requestSave()`), re-applies the feature (e.g.
`applyMarkdownLint()`), and keeps an open Settings window in step (`SettingsWindow.syncAll()`).

- Toggles → `view.toggle*` (or `appearance.`/`debug.` prefixes so the palette's feature-gating
  filter never hides the master toggle).
- Values → a `promptText` prompt or a `QuickOpen` picker; group many per-item settings under
  one picker command.
- Reuse the generic helpers: `toggleSetting` / `promptStringSetting` / `promptIntSetting` /
  `chooseSetting`.

## Localize every user-facing string

Never hand a raw English literal to a JavaFX control. Use
`import static com.editora.i18n.Messages.tr;` then `tr("some.key")` (or `tr("key", args…)` for
`MessageFormat` parameters like `Saved {0}`).

- **Add the key to all six** `resources/com/editora/i18n/messages[_<lang>].properties` files
  (English base + `it/es/fr/pt/de`) with a real translation in each. `MessagesTest`'s
  key-parity check fails the build if any locale is missing a key.
- A new `Command` gets its title for free from `command.<id>`, and its palette description from
  `command.<id>.desc` — **every** `command.<id>` must have a matching `.desc` (enforced by
  `MessagesTest`).
- The only deliberately-untranslated tokens are technical identifiers (`UTF-8`, `LF`/`CRLF`,
  `Ln`/`Col`, git verbs, language names, example URLs) and the pre-GUI `--help`/`--version`
  text.

## Config and schema

Preferences live in `settings.toml` (`Settings`, a Jackson POJO); session state in
`workspace-state.json` (`WorkspaceState`); other stores in their own JSON files. See
[architecture.md](architecture.md#multi-window-model) for the `SharedConfig`/`ConfigManager`
split.

**When you change a config schema** (add/rename/restructure a field in any versioned file):

1. Bump that POJO's `SCHEMA_VERSION` constant (`Settings`, `WorkspaceState`, `BookmarkStore`,
   `ProjectManager.Index`, `RecentFiles`, …).
2. Add a `v→v+1` entry to that file's `steps` map in
   [`config/migration/ConfigSchema.java`](../src/main/java/com/editora/config/migration/ConfigSchema.java).
   A purely additive field uses `ConfigMigrations::identity` (additive-identity); the new field
   just defaults.
3. A file newer than the running build is backed up to `<name>.v<n>.bak` and defaults are
   loaded — an older Editora never clobbers a newer config. The read path, version stamping,
   and downgrade backup are automatic once the step is registered.

If you add a Jackson-serialized type, remember the `opens com.editora.<pkg> to
com.fasterxml.jackson.databind;` in `module-info.java`.

## Code style: Palantir Java Format via Spotless

All Java is auto-formatted with [Palantir Java Format](https://github.com/palantir/palantir-java-format)
(a 120-col google-java-format fork) through the `spotless-maven-plugin`.

- **Run `mvn spotless:apply` before committing.** `spotless:check` runs at the `verify` phase,
  so `mvn verify`/`package`/CI fail on unformatted code. The `mvn javafx:run`/`compile` dev
  loop is left untouched.
- Import order: JDK (`java`/`javax`) → `javafx` → third-party + `com.editora` → static last.
  Longest-prefix wins, so `javafx` is not swallowed by `java`.
- Escape hatch for hand-aligned code: wrap it in `// spotless:off` … `// spotless:on`.

## Worktrees: one per task

This repo is worked on by multiple sessions in parallel. **Each task gets its own
`git worktree`** so sessions don't share a working tree:

```
scripts/worktree.sh new <branch>     # creates ../Editora-V2-worktrees/<slug> off origin/master
scripts/worktree.sh list
scripts/worktree.sh rm <branch>      # after merge
```

**Never `git checkout` a different branch in the main checkout** while other sessions may be
active — keep it on `master` and operate on a worktree by path. Spawn subagents with
`isolation: "worktree"` for the same reason.

## Tests

Prefer extracting a **pure decision helper** and unit-testing it (fast, no toolkit) over
testing through the UI. Real controller behavior is covered by the headless-FX harness. See
[testing.md](testing.md). `mvn verify` enforces JaCoCo per-package line-coverage floors on the
well-covered pure packages — when you raise a package's coverage, ratchet its floor up.

## Docs in the same PR

A feature PR updates `CHANGELOG.md`, `README.md`, and `TODO.md` in the same change, not as a
follow-up. User-facing documentation lives in the separate website repo; **this `docs/` folder
is for developers**. Keep `CLAUDE.md` (the dense, exhaustive reference) accurate too — but
prefer linking these docs from it over re-explaining.
