# Contributing to Editora

Thanks for hacking on Editora. This is the short version; the full developer documentation is
in [`docs/`](docs/README.md).

## Setup

- **JDK 25** and Maven (the bundled `./mvnw` wrapper works).
- Run the app: `mvn javafx:run`
- Run tests: `mvn test`
- Format + verify before pushing: `mvn spotless:apply && mvn verify`

See [`docs/building-and-packaging.md`](docs/building-and-packaging.md) for the dist build,
fat jar, and packaging.

## Workflow

1. **Branch in a worktree** — `scripts/worktree.sh new feat/my-thing` (off `origin/master`),
   then work in `../Editora-V2-worktrees/<slug>`. Don't `git checkout` another branch in the
   main checkout. ([why](docs/conventions.md#worktrees-one-per-task))
2. **Make the change**, following the conventions below.
3. **`mvn spotless:apply`**, then **`mvn verify`** (runs all tests + the Spotless check + the
   JaCoCo coverage floors).
4. **Update docs in the same PR**: `CHANGELOG.md`, `README.md`, `TODO.md`.
5. Open a PR against `master`.

## The conventions that get PRs bounced

Read [`docs/conventions.md`](docs/conventions.md) in full once. The high points:

- **Every feature is a `Command`**, registered and palette-discoverable.
- **Every setting also needs a palette command** that flips the same `Settings` field.
- **Localize every user-facing string** — add the key to **all six** `messages*.properties`
  catalogs (key-parity is enforced by `MessagesTest`); every `command.<id>` needs a `.desc`.
- **Bump `SCHEMA_VERSION` + add a migration step** for any config-schema change.
- **Performance is a first-class constraint** — never block the FX thread; debounce and
  coalesce; only do work on what's visible. See [`docs/performance.md`](docs/performance.md),
  and state the hot-path cost of your change in the PR.

## Finding your way

- New here? Start with [`docs/architecture.md`](docs/architecture.md).
- Adding a command / LSP server / grammar / tool window / overlay? The recipes are in
  [`docs/extending.md`](docs/extending.md).
- Writing tests (incl. the headless-FX harness): [`docs/testing.md`](docs/testing.md).
- Building a plugin (the public extension API): [`docs/plugins.md`](docs/plugins.md).

The exhaustive subsystem reference is [`CLAUDE.md`](CLAUDE.md); the `docs/` guides are the
readable distillation of it.
