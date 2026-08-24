# Code-navigation roadmap

The plan for making Editora a first-class code-navigation tool. This is a *roadmap*, not a
subsystem reference: it records what shipped, what was decided against, and what is left.

**Phases 1–5 have shipped.** What they built is documented in
[subsystems/navigation.md](subsystems/navigation.md) and, terser, in the
`search/FuzzyMatch` + `index/` + `search/SearchEverywhere` bullet of [`../CLAUDE.md`](../CLAUDE.md).
This file keeps only the sequencing, the rejected alternatives, and the items still open.

## Status

| Phase | State | Where it lives now |
| --- | --- | --- |
| 1 — ranked matcher | **shipped** | [subsystems/navigation.md § Ranking](subsystems/navigation.md#ranking--fuzzymatch) |
| 2 — symbol index | **shipped** (lazy build, incremental on save) | [§ The symbol index](subsystems/navigation.md#the-symbol-index) |
| 3 — Search Everywhere | **shipped** | [§ Search Everywhere](subsystems/navigation.md#search-everywhere) |
| 4 — flow | **shipped** | [§ Flow](subsystems/navigation.md#flow--not-losing-your-place) |
| 5 — polish | **shipped**; symbol breadcrumb dropped as redundant | below |

## The thesis

A navigation tool is judged on two axes:

- **Reach** — can the thing be found at all? (Does go-to-definition work here? Is the symbol
  indexed? Does the picker surface it?)
- **Flow** — can you jump without losing your place? (Peek instead of replace, preview instead of
  commit, a trail back to where you were.)

Editora's *plumbing* was in good shape on both before any of this. What was weak were the
fundamentals underneath: results were unranked, and symbol navigation existed only when a language
server did. Reach came first — it is what makes the tool feel intelligent. Flow second — it is what
makes it feel fast.

## What the four gaps were

Recorded because each one explains why a piece is shaped the way it is.

1. **Nothing was ranked.** Every picker filtered through a boolean subsequence test that answered
   *whether* something matched but not *how well*; `QuickOpen` applied no ranking at all and showed
   matches in source order. Typing `mcon` could not find `MainController` anywhere in the product.
   Closed by `FuzzyMatch`.
2. **Symbol navigation required a language server.** LSP is off by default and needs a user-installed
   server, so for a first-run user, or any of the 20+ languages with a grammar and no server,
   go-to-definition and go-to-symbol did not exist. Closed by `com.editora.index`.
3. **Finding was fragmented.** Files, symbols, text, commands and recent files were five pickers
   behind five keystrokes, which asks you to know which kind of thing you want before you can start
   typing its name. Closed by Search Everywhere.
4. **Every jump was destructive.** No peek, no preview-on-select, no recent-locations list, no sticky
   scroll, no preview tabs — so browsing shredded the tab strip and losing your place was the norm.
   Closed by the flow features.

## Decisions worth not relitigating

- **The index is not built on the TextMate grammars**, despite their `entity.name.*` scopes being the
  obvious source. tm4e grammars are not thread-safe and the registry is shared with the editor's
  background highlighters. Detail in the subsystem doc.
- **The index is on by default but built lazily**, rather than off by default. Off-by-default has the
  same defect as LSP being off by default: the first-run user gets nothing. Lazy building means an
  install that never asks pays nothing, which earns trust more honestly than a checkbox does.
- **No persistence and no watcher for the index.** A rebuild is under a second, so a cache buys very
  little; and `ProjectPanel` already runs a filesystem watcher, so a second walker competing with it
  is exactly the duplicated background cost being avoided.
- **Search Everywhere excludes text search and recent locations.** Text search wants a scope, globs
  and a replace box, all of which `SearchPanel` already provides; folding it in would make the popup
  a worse version of two things. Recent locations is inherently recency-ordered, which a relevance
  merge would destroy.
- **Ranking across sources resolved as grouping with a per-source cap**, not a flat merge — the
  sources differ in size by orders of magnitude.
- **Search Everywhere does not replace the command palette.** It can take the palette's chord
  (`Settings.paletteUsesSearchEverywhere`, off by default), but both pickers stay: which chord opens
  which picker is muscle memory, and the substitution is only safe because Search Everywhere was made
  a strict superset first — an empty query lists every command, gated commands are listed grayed with
  an explanation, and the description line and `C-h` docs are both present.
- **The code lens needed a fork change, as predicted.** The spike measured both existing mechanisms
  and ruled them out: `Inlay` is a string at a column, and a 44px gutter graphic leaves a 19px row at
  19px, because it is resized down to the line rather than growing it. richtextfx `0.11.7-lens.2`
  adds `lensFactory`, the vertical counterpart of `paragraphGraphicFactory` — the same 44px node as a
  lens takes the row to 63px. Off by default: every lens costs the language server a project-wide
  search to resolve.
- **The symbol breadcrumb was dropped.** It answers the same question as sticky scroll, which answers
  it with the actual source lines. The one thing a breadcrumb adds is clickable sibling dropdowns,
  and `structure.jump` already covers that. Worth revisiting only if that dropdown turns out to be
  wanted as a *mouse* affordance. (`ui/FileBreadcrumb` is a **path** breadcrumb — a different thing,
  and it stays.)

## Still open

- **One "go to symbol".** `lsp.gotoSymbol` and `index.gotoSymbol` are still separate commands rather
  than a single prefer-server-then-fall-back-to-index dispatch.

## Sequencing, and why

Kept as a record of a judgement that held up.

**Phase 1 before Phase 2, even though 2 is the differentiator.** Ranking was days of work and
improved every surface already shipped; the index was weeks and its payoff was gated on good ranking
anyway. An unranked symbol index is a *worse* experience than no index — it buries the answer in
noise and teaches the user not to trust the picker.

**Phases 3–5 were strictly downstream of 1 and 2.** Search Everywhere over an unranked, LSP-only
corpus would have been a worse product than the five separate pickers it replaces.

## What any further work here must follow

The standing rules in [conventions.md](conventions.md) apply — no exceptions for this work:

- Every user-facing action is a registered `Command`, discoverable in the palette.
- Every setting gets a palette command as well as a Settings control.
- Every string is localized in all six catalogs.
- Pure logic goes in its own toolkit-free class with unit tests; anything window-scoped goes in a
  feature coordinator, not `MainController`.
- Any new config field is an additive schema bump with a migration.
- Assess and report the hot-path cost of each change — the pickers and the index both sit on paths
  this codebase guards closely.
