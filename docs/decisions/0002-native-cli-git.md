# 0002 — Native-CLI git, not JGit

**Status:** Accepted

## Context

Editora has a full git integration: status, diff, gutter change bars, branch switching, log,
blame, stash, commit, clone. The two ways to do this in a JVM app are an embedded library (JGit)
or shelling out to the user's installed `git`.

## Decision

Shell out to the user's `git` binary. There is no JGit dependency. All git work goes through one
subprocess chokepoint, `process/ProcessRunner`, behind the `git/GitService` facade (daemon
executors + a generation guard, posting results to the FX thread). Reads and local mutations share
one serial lane; clone/fetch/pull/push run on a second, so a slow remote never queues status and
gutter work behind it. Working-tree mutations are serialised across both.

## Consequences

- **No new dependency, no `module-info` change** — the integration is pure CLI.
- **User-initiated commands** (commit, checkout, reset, stash, fetch/pull/push, …) are exactly the
  user's git: their version, config, credential helpers, hooks, and any `includeIf`/conditional
  config. `LC_ALL=C` + `GIT_OPTIONAL_LOCKS=0` are set for stable parsing and lock-free status, and
  `GIT_TERMINAL_PROMPT=0` so a missing credential fails at once instead of waiting on a terminal
  nobody can see (askpass and credential helpers are unaffected).
- **Background commands are not the user's git verbatim.** Status and the gutter diff run on every
  tab activation, so with repository config honoured, opening a file from an extracted archive or a
  shared folder ran whatever program its `.git/config` named. Everything Editora runs on its own
  initiative (status, diff, show, log, blame, ref and file listings) therefore carries the
  `-c` overrides in `git/GitSafety` — `core.fsmonitor=false`, `core.hooksPath=<null device>`,
  `core.pager=cat`, `log.showSignature=false`, `protocol.ext.allow=never` — plus `--no-ext-diff`
  and `--no-textconv` on diff-producing commands. `credential.helper` is left alone. Not covered:
  `filter.<name>.clean`/`process` drivers selected through `.gitattributes`, which cannot be
  disabled without breaking Git LFS and end-of-line conversion; that needs a trust decision, not an
  override. The cost is that a `core.fsmonitor` daemon is not used for background status.
- Ref names are repository data. A revision that starts with `-` is refused before it reaches git,
  and `--end-of-options` is added where the installed git (2.24+) understands it.
- Kill timers differ by kind: 10 s for background reads, 15 min for working-tree mutations and
  commit (hooks, a GPG pinentry and large checkouts legitimately take long, and a mutation killed
  mid-update leaves a half-switched tree), 30 min for network commands. Closing a window drops
  queued work but lets a running user command finish.
- Parsing is our responsibility: pure, unit-tested parsers (`StatusParser` for porcelain-v2,
  `DiffParser`, `BlameParser`, `StashParser`, …) turn git output into model records.
- A Finder-launched `.app` inherits a stripped `PATH` without Homebrew's git; `ProcessRunner`'s
  augmented-PATH / login-shell-PATH resolution handles that (see [gotchas.md](../gotchas.md)).
- Git is **self-gating**: inert until `git` is on `PATH`; remote (SFTP) buffers report no repo.

The diff/merge viewer reuses the same facade (`GitService.show`/`log`/`diff`).
