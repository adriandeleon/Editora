# Editora agent guide

Editora is a keyboard-driven, cross-platform programmer's text editor built with JDK 25,
JavaFX 26, Maven, and JPMS module `com.editora`.

This file is the operational guide for coding agents. Durable architecture, rationale, and
subsystem history belong in [`docs/`](docs/README.md); do not grow this file into a changelog.
When documentation and code disagree, verify the code and update the documentation in the same
change.

## Essential commands

Run Maven from the repository root.

- Run the app: `mvn javafx:run`
- Run tests: `mvn test`
- Format: `mvn spotless:apply`
- Full verification: `mvn verify`
- Build a runnable host-platform fat jar: `mvn -Pfatjar package`
- Build an app image or installer: **`mvn clean -Pdist package`**
- Build a quick unpackaged app image:
  `mvn clean -Pdist -DskipTests -Djpackage.type=APP_IMAGE package`

The `clean` in every `-Pdist` build is mandatory. Incremental compilation can leave synthetic
enum-switch classes out of `target/classes`; jlink can then package an app whose keyboard fails on
the first keypress. See [building and packaging](docs/building-and-packaging.md) and the
[detailed command reference](docs/reference/commands.md).

## Worktrees — one per task

Multiple Codex sessions may work on this repository concurrently. Every task gets its own Git
worktree so branch switches and commits cannot interfere.

- Create one with `scripts/worktree.sh new <branch>`; the base defaults to `origin/master`.
- Work in `../Editora-V2-worktrees/<slug>`. `scripts/worktree.sh list`, `rm <branch>`, and `prune`
  inspect or clean worktrees.
- Never switch the main checkout away from `master`. A session anchored there must operate on a
  worktree by explicit path.
- A created worktree does not move the current terminal into it. Open a session there or pass its
  path explicitly to every edit, build, and Git command.
- Worktrees share Git objects and branches but have independent working directories. Preserve
  unrelated changes and never remove another task's worktree.
- If subagents are requested, isolate each one in its own worktree.

## Change checklist

- Inspect nearby code and tests before editing; follow existing package and naming patterns.
- Keep command behavior in `CommandRegistry`; palette actions and key bindings should resolve to
  registered command ids rather than parallel implementations.
- Keep JavaFX scene-graph work on the FX application thread. Keep per-keystroke, layout, scroll,
  gutter, highlighting, and overlay paths allocation-light and non-blocking.
- Prefer a pure decision helper plus unit tests. Use the headless JavaFX harness when controller or
  toolkit behavior is what matters.
- For settings changes, update defaults, persistence, UI synchronization, schema version, and a
  migration. Migrations must preserve existing users' choices.
- Add or update strings in all six message catalogs. Do not bake key chords into text that can be
  derived from the active keymap.
- Check `module-info.java` when reflection, FXML, service loading, or a new dependency crosses JPMS
  boundaries.
- Run `mvn spotless:apply` after Java edits, then the narrow tests and `mvn verify` when practical.
- Update `CHANGELOG.md`, `README.md`, `TODO.md`, and contributor docs when the change affects them.
- Do not commit generated build output, editor state, credentials, or unrelated user changes.

## Architecture orientation

- [`docs/architecture.md`](docs/architecture.md) — boot path, multi-window ownership, module map,
  `MainController`, `EditorBuffer`, and recurring patterns.
- [`docs/conventions.md`](docs/conventions.md) — coding, configuration, i18n, testing, formatting,
  and documentation conventions.
- [`docs/performance.md`](docs/performance.md) — hot paths and responsiveness constraints.
- [`docs/gotchas.md`](docs/gotchas.md) — JPMS, JavaFX, platform, and packaging traps.
- [`docs/extending.md`](docs/extending.md) — recipes for commands, settings, languages, servers,
  adapters, tool windows, overlays, and coordinators.
- [`docs/subsystems/`](docs/subsystems/) — focused subsystem deep-dives.
- [`docs/decisions/`](docs/decisions/) — architecture decision records and rationale.

The detailed historical catalogs formerly embedded in this file are preserved under
[`docs/reference/`](docs/reference/README.md). Consult them when changing an established subsystem,
then prefer updating the focused guide or decision record that owns the topic.

## Release and packaging

The Maven version is the release source of truth. Normal development on `master` uses a
`-SNAPSHOT` version. Release builds are platform-specific and the native pipeline has deliberate
macOS, Linux, AOT, jpackage, and installer workarounds; do not simplify them without reproducing the
relevant platform test.

Read [`docs/release.md`](docs/release.md),
[`docs/building-and-packaging.md`](docs/building-and-packaging.md), and the preserved
[`release pipeline reference`](docs/reference/release-pipeline.md) before changing release CI,
installers, launchers, icons, file associations, or AOT training.

## Documentation map

[`docs/README.md`](docs/README.md) is the contributor documentation index. Keep operational rules
here concise; put stable explanations in a focused guide, unusual choices in an ADR, and exhaustive
implementation history in the reference catalog.
