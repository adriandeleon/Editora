# 0008 — Decompose `MainController` into feature coordinators

**Status:** Accepted (ongoing)

## Context

`MainController` is the per-window hub and grew to ~12k lines as features accreted. A class that
large is hard to hold in your head, hard to test (it needs a real window), and a magnet for more
code. But the features genuinely need access to window-level state (the active buffer, tool
windows, the status bar, settings), so they can't just move to their own packages wholesale.

## Decision

Pull each feature's logic into a **coordinator** in `ui` that reaches the window only through a
narrow shared interface, `ui/CoordinatorHost` (plus a small per-feature `Ops`/`WindowOps`
extension for the few extra capabilities it needs). `MainController` constructs the coordinator
with an anonymous `Host` adapter and keeps **one-line delegations** at each call site.

## Consequences

- A coordinator is **unit-testable** with a fake `Host`, without a real window (see the
  `*CoordinatorFxTest`s).
- New features should be their own coordinator (or pure package) from the start, **never** bolted
  onto `MainController`.
- The decomposition is incremental — External Tools, Macros, TODO, Search, Run, Log Viewer,
  Mermaid, HTML Preview, LSP, Debug, Git, Diff, History, Notes, Bookmarks, Remote, Plugins, and
  others now live in coordinators; `MainController` keeps the tool-window registrations, the
  shared helpers, and the command registrations that delegate in.
- Give a coordinator only the capabilities it needs on `CoordinatorHost`; don't hand it the whole
  `MainController`.

See [extending.md](../extending.md#extract-a-feature-coordinator).
