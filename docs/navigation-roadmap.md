# Code-navigation roadmap

The plan for making Editora a first-class code-navigation tool. This is a *roadmap*, not a
subsystem reference — it records where navigation stands today, which gaps actually matter, and
the order to close them in. When a phase ships, its content moves into
[`../CLAUDE.md`](../CLAUDE.md) and (if it warrants one) a `subsystems/` deep-dive, and its section
here shrinks to a line saying so.

## Status

| Phase | State | Where |
| --- | --- | --- |
| 1 — ranked matcher | **done** | `feat/fuzzy-match` |
| 2 — symbol index | **done** (lazy build, incremental on save) | `feat/navigation-flow` |
| 3 — Search Everywhere | **done** | `feat/navigation-flow` |
| 4 — flow | **done** | `feat/navigation-flow` |
| 5 — polish | done bar code lens; symbol breadcrumb dropped as redundant | `feat/navigation-flow` |

`feat/navigation-flow` is stacked on `feat/fuzzy-match`. Neither is pushed or merged.

## The thesis

A navigation tool is judged on two axes:

- **Reach** — can the thing be found at all? (Does go-to-definition work here? Is the symbol
  indexed? Does the picker surface it?)
- **Flow** — can you jump without losing your place? (Peek instead of replace, preview instead of
  commit, a trail back to where you were.)

Editora's *plumbing* is in good shape on both. What is weak is the fundamentals underneath them:
results are unranked, and symbol navigation exists only when a language server does. Reach comes
first — it is what makes the tool feel intelligent. Flow second — it is what makes it feel fast.

## Where things stand

Already shipped, and genuinely good:

- **LSP navigation** — definition / declaration / type-definition / implementation / references,
  call and type hierarchy (`ui/HierarchyPanel`), workspace symbols (`ui/WorkspaceSymbolPopup`),
  document symbols into the Structure tool window with sort/filter/doc-tooltips, semantic tokens,
  inlay hints, server-provided folding and selection ranges.
- **Non-LSP navigation** — folds (`editor/FoldRegions` + `FoldTree`), AceJump char- and line-mode,
  the Emacs mark ring, per-project bookmarks that re-anchor through external edits, back/forward
  (`ui/NavigationHistory`), `M-s o` occur, ripgrep-backed Find in Files, the minimap and its
  diagnostic/TODO stripes, the IntelliJ-style Switcher, occurrence highlighting.

So the gaps below are not "add more features". They are four fundamentals that everything above
sits on.

### Gap 1 — Nothing is ranked

Every picker in the app filters through
[`CommandPalette.isSubsequence`](../src/main/java/com/editora/ui/CommandPalette.java): a boolean
subsequence test that answers *whether* something matched but not *how well*.

Only the command palette ranks the survivors at all, via `CommandPalette.byRelevance` — a coarse
five-bucket score (exact > whole-word > word-start > substring anywhere > scattered subsequence)
tie-broken by title length. It has no notion of contiguity, camelCase boundaries, or acronyms, so
everything that matched only as a scattered subsequence lands in one undifferentiated bucket
ordered by length. `QuickOpen` — recent files, open buffers, bookmarks, snippets, projects,
themes, LSP references — applies no ranking whatever and shows matches in source order.
[`ProjectPanel.search`](../src/main/java/com/editora/ui/ProjectPanel.java) is a lowercased
`contains` sorted alphabetically. [`FileFinder`](../src/main/java/com/editora/ui/FileFinder.java)
is a *directory browser* on `startsWith` — so there is no project-wide fuzzy "go to file" anywhere
in the product.

Concretely: typing `mcon` cannot find `MainController` in a file picker, and in the pickers where a
subsequence *does* match, the best answer is frequently not first.
`completion/MatchHighlighter` already does camelCase-aware subsequence matching for the completion
popup — it simply never reached the pickers, and it answers "which characters matched", not "how
good is this match". Its match is also greedy left-to-right, so it takes the first alignment rather
than the best one.

This is the most-felt gap in the product and the cheapest to close.

### Gap 2 — Symbol navigation requires a language server

LSP is off by default and needs a user-installed server. For a first-run user — or for any of the
20+ languages that ship a grammar but have no configured server — go-to-definition and
go-to-symbol do not exist. The Structure window falls back to a per-file fold/TextMate heuristic;
there is no project-wide equivalent, and no persistent index of any kind (confirmed: no
symbol-index, ctags, or tree-sitter machinery in the tree).

That is the line between an editor that has navigation and a navigation tool.

### Gap 3 — Finding is fragmented

Files, symbols, text, commands, and recent files are five pickers behind five keystrokes. The
modern baseline is one entry point that ranks across all of them, with scoping prefixes to narrow
(IntelliJ's double-Shift, VS Code's `Ctrl-P` with `@`/`#`/`>`).

### Gap 4 — Every jump is destructive

There is no peek (inline definition popup), and pickers do not preview on selection — `SearchPanel`
already gets this right, opening the match on single-click while keeping focus in the results, but
the `QuickOpen` pickers do not. `NavigationHistory.Location` is line + column only, session-only,
with no snippet and no list UI, so there is no "recent locations" to scan. There is no sticky
scroll, and no preview-tab semantics, so browsing shreds the tab strip.

Separately: `ui/FileBreadcrumb` is a **path** breadcrumb (folders and files). The navigational one
in an IDE is a **symbol** breadcrumb — `Class → method` — which is a different thing.

## The phases

Sizes are relative effort, not calendar. Each phase is independently shippable.

### Phase 1 — A ranked matcher (small)

A pure `com.editora.search.FuzzyMatch`: given a query and a candidate, return a score and the
matched character ranges, or "no match".

Scoring signals, in rough order of weight: contiguity of the matched run; hits on word and
camelCase boundaries; match in the basename rather than the directory part of a path; a full
acronym hit (`mc` → `MainController`); an exact-case tiebreak; and a caller-supplied recency or
frecency bias.

Then wire it into `QuickOpen`, `CommandPalette`, `ProjectPanel`, and `WorkspaceSymbolPopup`, and
render the returned ranges bold through the existing `MatchHighlighter` styling path. Three
existing pieces collapse into it: `CommandPalette.isSubsequence` (the filter), `byRelevance` /
`relevance` (the coarse bucket ranking), and eventually `MatchHighlighter` itself — a matcher that
scores already knows which characters matched.

Two implementation constraints, both inherited from `MatchHighlighter` and non-negotiable:

- **Index the candidate directly and fold case per character.** Never match against a lowercased
  *copy* — `String.toLowerCase` is not length-preserving (`İ` U+0130 becomes two characters), so a
  copy's indices drift and the returned ranges overrun the string the picker then substrings.
- **The matcher runs on the FX thread, on every keystroke, over the whole candidate list** — the
  command registry alone is ~550 entries. It must be allocation-light and bail out early on a
  non-match.

Ships as: one pure class, one unit test with a fixture corpus asserting *ordering* (the ranking is
the product, so the test must pin which candidate wins, not merely that both matched).

### Phase 2 — A local symbol index (large)

**Done.** `com.editora.index` (`DeclarationScanner`, `DeclarationRules`, `SourceBlanker`,
`SymbolIndex`, `Symbol`, `SymbolKind`) covers 16 language ids, measured at 654 files / 15,858
symbols / 826 ms over this repo's own `src/main/java`. `ui/IndexCoordinator` drives it behind
`index.gotoSymbol` and `index.rebuild`.

Two deviations from what this document originally proposed, both deliberate:

- **On by default, but built lazily on first use** rather than off by default. Off-by-default has
  the same defect as LSP being off by default — the first-run user gets nothing — while lazy
  building means an install that never asks pays nothing, which earns trust more honestly than a
  checkbox does.
- **No persistence under the config dir, and no watcher.** A rebuild is under a second, so a cache
  would be complexity buying very little; and `ProjectPanel` already runs a filesystem watcher, so
  a second walker competing with it is exactly the duplicated background cost this is avoiding.
  External changes are handled by `index.rebuild`.

Still open: merging it with the LSP `workspace/symbol` path so there is one "go to symbol" rather
than two commands. That is Phase 3's job.

One decision worth not relitigating: it is **not** built on the TextMate grammars, despite their
`entity.name.*` scopes being the obvious source. tm4e grammars are not thread-safe and the registry
is shared with the editor's background highlighters, so a project walk would race them — the hazard
`pdf/CodeHtml` already documents. Serialising behind the highlighters would make indexing as slow as
opening every file. Regexes hold no state and run anywhere.

It gives go-to-definition, go-to-symbol-in-workspace, and a project-wide outline for every
language, offline, with no server to install. **LSP stays authoritative** and overrides the index
whenever a server is live for that file — the index is the floor, not a competitor.

Follows the established shape: an off-FX-thread daemon executor with a generation guard, results
marshalled back with `Platform.runLater`, invalidated by the filesystem watcher `ProjectPanel`
already runs, gitignore-aware through the existing `search/GitignoreFilter`.

This is the phase that can go wrong. It is a background process touching every file in a project,
in an app whose performance discipline treats the FX thread as sacred. Budget it explicitly:
bounded memory, bounded scan, bounded per-file work, and off by default until it earns trust. If
it hitches, it will be blamed for every unrelated stutter in the editor.

### Phase 3 — Search Everywhere (medium)

**Done.** `search/SearchEverywhere` (pure merge) + `ui/SearchEverywherePopup`, behind
`search.everywhere`. Sources: commands, project files, symbols. `>`/`#`/`@` scope to one.

The ranking-across-sources problem resolved as **grouping with a per-source cap** rather than a flat
merge: the sources differ in size by orders of magnitude, so a flat ranking hands the list to
whichever is biggest. Groups compete on their best result, not their bulk, and the total cap trims a
group's tail rather than dropping a source.

Not included, and deliberately: **text** (Find in Files) and **recent locations**. Text search is a
different interaction — it wants a scope, globs and a replace box, all of which `SearchPanel`
already provides — and folding it in would make the popup a worse version of two things. Recent
locations is already one keystroke away and is inherently ordered by recency, which a relevance
merge would destroy.

Still open: `lsp.gotoSymbol` and `index.gotoSymbol` remain separate commands. Search Everywhere is
now the unified entry point, but the two direct commands have not been merged behind a
prefer-server-then-fall-back-to-index dispatch.

### Phase 4 — Flow (medium, and separable)

Each of these stands alone and can ship on its own:

- ~~**Recent locations**~~ — **done**. `NavigationHistory.recent()` plus a picker, with the line text
  captured at record time. Session-only: persisting it needs a `WorkspaceState` field and a schema
  bump, and the value of the list is overwhelmingly within a session.
- ~~**Preview on select in pickers**~~ — **done**, as an opt-in `QuickOpen.setPreview(preview,
  onCancelled)`, wired for recent locations. Other pickers can opt in a line at a time.
- ~~**Peek definition**~~ — **done** as `lsp.peekDefinition`. Shown centred through the shared
  `OverlayHost` rather than inline at the caret: the overlay host is the Windows-focus-safe route the
  rest of the app already uses, and placing a popup at the caret is a refinement that can follow.
- ~~**Sticky scroll**~~ — **done**. `StickyScroll` (pure) decides what to pin, `StickyScrollBar`
  renders it from the area's already-applied style spans — which also sidesteps re-tokenizing on a
  shared, non-thread-safe grammar.
- ~~**Preview tabs**~~ — **done**. Italic, single-slot, promoted by editing or by an explicit open.
  This lifted the picker-preview restriction: a recent location whose file is closed now previews into
  the slot instead of being skipped.

### Phase 5 — Polish

- ~~**Related-file jump**~~ — **done** (`nav.relatedFile`). Pure `search/RelatedFiles` proposes
  candidate names; the controller resolves which exist, sibling directory first and the project index
  after.
- ~~**Open definition in a split**~~ — **done** (`lsp.gotoDefinitionInSplit`).
- ~~**Bookmark mnemonics**~~ — **done** (`bookmarks.setMnemonic` + ten `bookmarks.gotoMnemonic<N>`
  chords). Unique per project, shown on the panel row.
- **Symbol breadcrumb** — **dropped**, deliberately. It was proposed before sticky scroll existed, and
  the two answer the same question: what am I inside? Sticky scroll answers it with the actual source
  lines, which is strictly more informative than `Class → method`. The one thing a breadcrumb adds is
  clickable sibling dropdowns — jump to another method in this class — and `structure.jump` already
  covers that as a picker. Building it would mean a third way to see the enclosing scope and a second
  way to jump within a file, which is redundancy rather than polish. Worth revisiting only if the
  sibling dropdown turns out to be wanted as a *mouse* affordance, which is the one gap left.
- **Reference-count code lens** — **open, and the only item left.** It needs an editor decoration
  layer *above* a line, which nothing in the codebase has: every existing overlay (whitespace, spell,
  search, diagnostics, TODO, inline values) draws *over* the text or beside it in the gutter, and none
  of them insert vertical space. The RichTextFX fork gained `Inlay` for inline hints, which displaces
  glyphs horizontally — a lens needs the vertical equivalent. That is a rendering capability, not a
  feature, and it should be scoped and looked at before much is built on it.
- **Bookmark mnemonics** (IntelliJ's `Ctrl+Shift+0-9`) — open, and independent of everything else.

## Sequencing, and why

**Phase 1 before Phase 2, even though 2 is the differentiator.** Ranking is days of work and
improves every surface already shipped; the index is weeks and its payoff is gated on good ranking
anyway. An unranked symbol index is a *worse* experience than no index — it buries the answer in
noise and teaches the user not to trust the picker.

**Phases 3–5 are strictly downstream of 1 and 2.** Search Everywhere over an unranked, LSP-only
corpus would be a worse product than the five separate pickers it replaces.

## What every phase must still follow

The standing rules in [conventions.md](conventions.md) apply — no exceptions for this work:

- Every user-facing action is a registered `Command`, discoverable in the palette.
- Every setting gets a palette command as well as a Settings control.
- Every string is localized in all six catalogs.
- Pure logic goes in its own toolkit-free class with unit tests; anything window-scoped goes in a
  feature coordinator, not `MainController`.
- Any new config field is an additive schema bump with a migration.
- Assess and report the hot-path cost of each change — the pickers and the index both sit on paths
  this codebase guards closely.
