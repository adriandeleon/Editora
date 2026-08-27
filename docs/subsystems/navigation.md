# Code navigation

Ranking, the server-free symbol index, Search Everywhere, and the "don't lose your place"
features. Back to [the docs index](../README.md).

A navigation tool is judged on two things. **Reach** — can the thing be found at all? **Flow** —
can you jump without losing your place? Editora's plumbing was in decent shape on both long before
this subsystem existed; what was missing underneath was that nothing was *ranked*, and symbol
navigation existed only where a language server did. The pieces below close those two, and
everything else here sits on top of them.

The [navigation roadmap](../navigation-roadmap.md) records the sequencing and the decisions that
were considered and rejected. This document describes what is in the tree.

## The pieces

- [`search/FuzzyMatch.java`](../../src/main/java/com/editora/search/FuzzyMatch.java) — the ranked
  matcher every picker filters through.
- [`index/`](../../src/main/java/com/editora/index/) — the server-free symbol index
  (`DeclarationScanner`, `DeclarationRules`, `SourceBlanker`, `SymbolIndex`, `Symbol`, `SymbolKind`).
- [`ui/IndexCoordinator.java`](../../src/main/java/com/editora/ui/IndexCoordinator.java) — the walk,
  the incremental updates, and the `index.gotoSymbol` picker.
- [`search/SearchEverywhere.java`](../../src/main/java/com/editora/search/SearchEverywhere.java) +
  [`ui/SearchEverywherePopup.java`](../../src/main/java/com/editora/ui/SearchEverywherePopup.java) —
  one picker over commands, files and symbols.
- [`ui/NavigationHistory.java`](../../src/main/java/com/editora/ui/NavigationHistory.java) —
  back/forward and the recent-locations trail.
- [`editor/StickyScroll.java`](../../src/main/java/com/editora/editor/StickyScroll.java) +
  `StickyScrollBar` — the enclosing scope pinned above the viewport.
- [`search/RelatedFiles.java`](../../src/main/java/com/editora/search/RelatedFiles.java) — the
  counterpart-file rules behind `nav.relatedFile`.

## Ranking — `FuzzyMatch`

`FuzzyMatch.of(candidate, query)` returns a `Match(score, ranges)` or `null`. The ranges are the
matched character spans, so the picker that scored a row also knows which characters to embolden —
before this, scoring and highlighting came from two different matchers and could disagree about the
same row. `ui/MatchText` turns a `Match` into styled `Text` runs; every picker uses it.

`ofPath(path, query)` is the path-aware variant: a hit in the basename outranks one in the
directory part, because that is what a person means when they type a file name.

Two constraints on this class are not negotiable.

**Index the candidate directly and fold case per character.** Never match against a lowercased
*copy*. `String.toLowerCase` is not length-preserving — `İ` (U+0130) becomes two characters — so a
copy's indices drift, and the ranges then overrun the string the cell factory substrings. This is
the same rule `completion/MatchHighlighter`, `index/SourceBlanker` and `editor/CompactSource`
follow.

**It runs on the FX thread, on every keystroke, over the whole candidate list.** The command
registry alone is ~600 entries. It has to be allocation-light and bail out early on a non-match. A
candidate longer than `MAX_SCAN` (400) is matched on its *tail*, because for the two things that get
long — a path and a qualified symbol name — the distinguishing part is at the end.

A blank query returns `null` rather than a neutral match: "show everything" is a decision for the
caller, and each one makes it differently (the palette lists the whole registry, a file picker lists
nothing).

## The symbol index

LSP is off by default and needs a user-installed server, and 20+ languages ship a grammar with no
server available at all. Without an index, go-to-symbol simply does not exist for those users. The
index is the **floor**: wherever a language server is running it is better in every respect and
takes precedence. Nothing here competes with LSP.

`DeclarationScanner` runs per-language regexes from `DeclarationRules` over source that
`SourceBlanker` has pre-processed, covering 16 language ids (c, cpp, csharp, go, java, javascript,
javascriptreact, kotlin, lua, php, python, ruby, rust, shell, typescript, typescriptreact).

Three decisions in here are load-bearing:

**It is deliberately not built on the TextMate grammars**, despite their `entity.name.*` scopes
being the obvious source. tm4e grammars are not thread-safe and the registry is shared with the
editor's background highlighters, so a project walk would race them — the hazard `pdf/CodeHtml`
already documents, where tokenizing on the FX thread deadlocked the suite. Serialising the index
behind the highlighters would make it as slow as opening every file. Regexes hold no state and run
on any thread.

**`SourceBlanker` is length-preserving.** It replaces comment and string contents with spaces so a
scanner cannot match the word "class" in a Javadoc paragraph or `def` in a docstring, and every
offset in the blanked text is still the offset in the original — so the scanner reports positions
directly with no offset map. Newlines survive as newlines, including inside block comments and
multi-line strings, so line numbering holds.

**The rules under-report on purpose.** A missing declaration costs one fallback to search; an
invented one sends the user somewhere that does not exist and teaches them not to trust the feature.

### `IndexCoordinator` — when the work happens

**Built lazily, on first use.** The index does *not* build when a project opens. An index that walks
every file the moment you open a folder spends real work for a user who may never ask it anything,
and this codebase's position is that background work has to justify itself. Asking for a symbol is
the justification. The cost is that the first query pays for the walk — measured at 654 files /
15,858 symbols / 826 ms over this repo's own `src/main/java` — announced with a status message so it
does not read as a hang.

After that it is incremental: a save rescans exactly the file that changed. **There is no filesystem
watcher here on purpose** — `ProjectPanel` already runs one, and a second walker competing with it
is the duplicated background cost the lazy build exists to avoid. External changes are handled by
`index.rebuild`.

The usual shape applies: a single `symbol-index` daemon thread, an `AtomicLong` generation guard so
a project switch discards a superseded walk, results marshalled back with `Platform.runLater`, and
`search/GitignoreFilter` honoured so `target/` and `node_modules/` are skipped. Bounds:
`MAX_FILE_BYTES` (2 MB — a generated bundle is not worth the scan) and `MAX_VISIT` (50,000 files).

Gated by `Settings.symbolIndex` (on by default) and off in Simple UI mode; disabling it clears the
in-memory index rather than merely hiding it.

`SymbolIndex.search` is a linear scan over every symbol with a `limit`, on the FX thread. That is
fine for a deliberately-opened picker at current corpus sizes, and it is the first thing to revisit
if the index ever grows to hold more than a project's own sources.

## Search Everywhere

One picker over commands, project files and symbols, so you can type the *name* of the thing instead
of first choosing which finder it lives in. `>` scopes to commands, `#` to files, `@` to symbols —
VS Code's sigils rather than invented ones, because the muscle memory already exists.

**Results stay grouped by source rather than interleaved on raw score.** This is the whole point of
the `SearchEverywhere` class. The sources differ in size by orders of magnitude — tens of thousands
of symbols, thousands of files, about six hundred commands — so a flat merge hands the entire list
to whichever source happens to be biggest and the other two effectively disappear. Grouping with a
per-source cap (`DEFAULT_PER_GROUP` = 8, `DEFAULT_TOTAL` = 24) gives each source a guaranteed share,
makes the *groups* compete on their best result rather than their bulk, and trims a group's tail
rather than dropping a source outright.

**When only one source is in play, nothing is capped** (`UNCAPPED`). A `>` search, or the empty
query, has nothing to drown, and trimming there would make the scoped mode strictly worse than the
single-purpose picker it stands in for.

**An empty query lists every command** and touches no corpus, so opening the picker shows a
browsable list rather than a blank box, and no project walk is provoked by merely opening it. A bare
sigil with nothing typed after it is a *scope*, not an empty query — it names what it will search
and walks nothing.

**A command whose feature is switched off is listed, grayed, with an explanation** naming the
setting that would enable it, exactly as the command palette does (#532). Hiding it is tidier in a
mixed list, and that is how it originally shipped, but it means the command can never be discovered
— and a picker you cannot learn the product from is a worse palette than the one it replaces.
Grayed rows sort after everything runnable (`Item.enabled` drives both the within-group sort and the
group's competing score), and the cursor and the mouse both step over them. The reason text is
derived per *visible* cell through `Ops.disabledReason`, never for the whole list.

The highlighted row's description appears under the list and `C-h` opens its docs, both matching the
palette. The docs URL comes from `CommandPalette.docsUrl` rather than a second copy — the version in
the path is the subtlety worth having in one place.

**`Settings.paletteUsesSearchEverywhere`** (default **off**) makes the command palette's chord open
Search Everywhere instead. Off by default because which picker that chord opens is muscle memory,
not because the substitution loses anything: the empty query lists every command, and typing reaches
files and symbols too.

Deliberately **not** included: **text search** (Find in Files wants a scope, globs and a replace box,
all of which `SearchPanel` already provides, and folding it in would make the popup a worse version
of two things) and **recent locations** (already one keystroke away, and inherently ordered by
recency, which a relevance merge would destroy).

Still open: `lsp.gotoSymbol` and `index.gotoSymbol` remain separate commands rather than one
prefer-server-then-fall-back dispatch.

## Flow — not losing your place

- **Back / forward** (`nav.back`, `nav.forward`) over `NavigationHistory`: a browser-style jump list
  of `Location(path, line, column, snippet)`, capped at 100.
- **Recent locations** (`nav.recentLocations`) reads `NavigationHistory.recent()` — the same trail
  Back walks, not a separate log, so what the picker offers and what Back reaches can never diverge.
  Each entry carries the **line text captured when the location was recorded**, because by the time
  the list is shown the file may be closed, edited or gone — and `Foo.java:214` says nothing about
  why you were there while the line usually says it at a glance. Session-only. Dedupe keeps the
  *newest* visit of a line, so repeatedly passing through a spot does not push everything else off.
- **Preview on select** — an opt-in `QuickOpen.setPreview(preview, onCancelled)`, wired for recent
  locations. Other pickers can opt in a line at a time.
- **Peek definition** (`lsp.peekDefinition`) — shown centred through the shared `OverlayHost` rather
  than inline at the caret, because the overlay host is the Windows-focus-safe route the rest of the
  app uses. Placing it at the caret is a refinement that can follow.
- **Sticky scroll** — `StickyScroll` (pure) decides what to pin from `FoldRegions`: a block whose
  header has scrolled above the viewport while its body is still inside it is exactly a scope you are
  in but cannot see the name of. Capped at `DEFAULT_MAX` (5) rows, beyond which the pin eats the
  viewport it exists to explain. `StickyScrollBar` renders it **from the area's already-applied style
  spans**, which sidesteps re-tokenizing on the shared, non-thread-safe grammar.
- **Preview tabs** — italic, single-slot, promoted to a real tab by editing or by an explicit open,
  so browsing does not shred the tab strip.
- **Related file** (`nav.relatedFile`) — a test and its subject, a C header and its implementation, a
  component and its stylesheet. Pure `RelatedFiles` proposes *candidate names* in preference order
  and never touches the filesystem; the controller resolves which exist, sibling directory first and
  the project index after. The conventions being wrong costs nothing, because a name that does not
  exist is simply not offered.
- **Open definition in a split** (`lsp.gotoDefinitionInSplit`).
- **Bookmark mnemonics** — `bookmarks.setMnemonic` plus ten `bookmarks.gotoMnemonic<N>` chords,
  unique per project and shown on the panel row.

Most of these are **palette-only**; `search.everywhere` is the exception, bound to `M-S-x` (Emacs)
and `C-S-e` / `Cmd-S-e` in the four GUI keymaps. Everything else is bindable from Settings ▸ Keymaps.

## Two things deliberately absent

**A symbol breadcrumb** (`Class → method`) was dropped. It answers the same question as sticky
scroll, which answers it with the actual source lines — strictly more informative. The one thing a
breadcrumb adds is clickable sibling dropdowns, and `structure.jump` already covers that as a
picker. Worth revisiting only if the sibling dropdown is wanted as a *mouse* affordance.
(`ui/FileBreadcrumb` is a **path** breadcrumb — a different thing, and it stays.)

**A reference-count code lens** is open, and is a rendering capability rather than a feature. It
needs an editor decoration *above* a line: every existing overlay (whitespace, spell, search,
diagnostics, TODO, inline values) draws over the text or beside it in the gutter, and none of them
insert vertical space. The RichTextFX fork's `Inlay` displaces glyphs *horizontally*; a lens needs
the vertical equivalent. Scope that before building on it.

## When adding to this subsystem

- Rank through `FuzzyMatch` and highlight through `MatchText`. A picker that filters with its own
  `contains` is the thing this subsystem was built to remove.
- **A ranking test must pin which candidate wins, not merely that both matched.** The ordering *is*
  the product here; a test that only asserts non-null passes against a matcher that ranks backwards.
- A new Search Everywhere source is a `Kind` plus an `Ops` method; the merge does not need to know
  what the payload is.
- Anything that walks the project goes off the FX thread with a generation guard, honours
  `GitignoreFilter`, and bounds both file size and file count.
- The index is the floor. If a language server can answer, it answers.
