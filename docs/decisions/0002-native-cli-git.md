# 0002 — Native-CLI git, not JGit

**Status:** Accepted

## Context

Editora has a full git integration: status, diff, gutter change bars, branch switching, log,
blame, stash, commit, clone. The two ways to do this in a JVM app are an embedded library (JGit)
or shelling out to the user's installed `git`.

## Decision

Shell out to the user's `git` binary. There is no JGit dependency. All git work goes through one
subprocess chokepoint, `process/ProcessRunner`, behind the `git/GitService` facade (a single
daemon executor + a generation guard, posting results to the FX thread).

## Consequences

- **No new dependency, no `module-info` change** — the integration is pure CLI.
- We get exactly the user's git: their version, config, credential helpers, hooks, and any
  `includeIf`/conditional config. `LC_ALL=C` + `GIT_OPTIONAL_LOCKS=0` are set for stable parsing
  and lock-free status.
- Parsing is our responsibility: pure, unit-tested parsers (`StatusParser` for porcelain-v2,
  `DiffParser`, `BlameParser`, `StashParser`, …) turn git output into model records.
- A Finder-launched `.app` inherits a stripped `PATH` without Homebrew's git; `ProcessRunner`'s
  augmented-PATH / login-shell-PATH resolution handles that (see [gotchas.md](../gotchas.md)).
- Git is **self-gating**: inert until `git` is on `PATH`; remote (SFTP) buffers report no repo.

The diff/merge viewer reuses the same facade (`GitService.show`/`log`/`diff`).
