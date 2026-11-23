---
title: Code navigation
description: The project symbol index, sticky scroll, Peek Definition, preview tabs, related files, and jumping without losing your place.
category: Navigation
order: 2
---

This page covers two parts of moving around code. **Reach** is whether the
thing can be found at all, which the server-free symbol index handles. **Flow**
is whether you can jump without losing your place, which the peek, preview and
related-file commands handle.

For pickers, find, and project-wide search, see
[Navigation & search](/docs/navigation).

## Go to Symbol in Project

**Go to Symbol in Project** (`index.gotoSymbol`) finds a class, method or
function anywhere in the project **with no language server installed**. Editora
carries its own declaration scanner, so the command works on a first run and in
every language that ships a grammar but has no server. A project in a
[remote folder](/docs/remote) has no symbols.

![The Go to Symbol in Project picker listing classes and methods whose names match Keymap, each with its file and line](/screenshots/features/code-navigation.png "Go to Symbol in Project, with no language server running.")

Nineteen language ids are covered: Java, Kotlin, Groovy, Scala, Clojure, C#,
Python, JavaScript, JSX, TypeScript, TSX, Go, Rust, C, C++, Ruby, PHP, shell and
Lua. It records
the kinds of symbol a pattern can identify reliably: type, interface, enum,
method, function, field, variable and module. LSP has twenty-six kinds, and most
of them need real type resolution to tell apart.

In Clojure it records the namespace and the definitions written in the first
column (`def`, `defonce`, `defn`, `defn-`, `defmacro`, `defmulti`, `deftest`,
`defprotocol`, `definterface`, `defrecord`, `deftype`), so a form inside a
`(comment …)` block is left out. In Groovy, `task foo(…)` and the other calls of
a Gradle build are not taken for methods.

In shell scripts it records functions in every common form (`name()`,
`function name`, `name ()` with the brace on the next line), including names
such as `mylib::helper`, `lib.fn` and `my-fn`. It also records script-level
variables declared at the start of a line with `readonly`, `declare`, `typeset`
or `export NAME=`. A function's `local` variables and plain `x=1` assignments
are left out. Text inside a here-document or a quoted string that spans lines is
skipped, so a generated script or a usage message adds nothing. A script with
no extension, such as `bin/release`, is indexed by its `#!` line.

In Python it records classes, functions and methods (`async def` too),
constants written in capitals at the start of a line (`MAX_SIZE = 10`), and type
aliases (`type Vector = list[float]`, `Json: TypeAlias = dict[str, object]`). A
method shows the class it is indented under, nested classes included, so
`Point.norm` and another class's `norm` are told apart. A `def` or `class` inside
a string or docstring is skipped.

The same index backs the file and symbol halves of
[Search Everywhere](/docs/navigation#search-everywhere),
[Go to Related File](#go-to-related-file), and
[Go to Definition without a language server](#go-to-definition-without-a-language-server).

### It is built lazily

The index is **not** built when a project opens, because walking every file is
wasted work if you never ask for a symbol. The first query does the walk. It
takes under a second on a 650-file project and is announced in the status bar so
it doesn't look like a hang. After that the index is incremental: saving a file
rescans only that file, from the text already in memory.

There is no second filesystem watcher; the [project tree](/docs/workspace)
already runs one, and a file it reports as changed outside the editor is
rescanned on its own. When the tree cannot account for a change (many files at
once, for example), the index is marked stale and the next query walks the
project again. **Rebuild Symbol Index** (`index.rebuild`) forces that walk.

It honours your `.gitignore` the same way Find in Files and the project tree do,
skips `node_modules` even without a `.gitignore` (see
[Find in files](/docs/navigation#find-in-files)),
skips files over 2 MB, and is bounded at 20,000 files and 400,000 symbols so
that it cannot grow without limit in the background. With **Exclude .gitignore
files and folders from search** off, `node_modules` is walked too, but only
after the project's own files, so a large `node_modules` cannot fill the index
and leave your own code out.

### It under-reports on purpose

When the scanner isn't sure, it records nothing. A missing declaration costs
you one fallback to search, while an invented one sends you somewhere that
doesn't exist.

The index is a **fallback** for when no language server is running. Where a
[language server](/docs/lsp) is running it is better at this in every respect,
so use **LSP: Go to Symbol in Workspace** (`lsp.gotoSymbol`) there.

Turn it off in **Settings → Editor → Editing** ("Project symbol index"), or with
`view.toggleSymbolIndex`. Disabling it releases the project's symbols.

## Go to Definition without a language server

**LSP: Go to Definition** (`lsp.gotoDefinition`) asks the
[language server](/docs/lsp) first. When LSP is off, when no
server handles the file, or when the server finds nothing, Editora looks up the
name at the caret itself: first in the file's own declarations, then in the
project's symbol index. One match jumps there; several open the symbol picker.
The status bar says when the index answered, or that no declaration was found.

This works for the languages the index reads (see the list above). In
JavaScript and TypeScript the file's own lookup goes by scope: a parameter, a
local variable or a `catch` binding goes to its declaration in the enclosing
function or block, and an imported name to its `import` line, rather than to a
top-level name that happens to match (see
[Languages](/docs/languages#javascript-and-typescript)). In other
languages the command still needs a server. Ctrl+click on a name needs a
running server too.

In an HTML page, Go to Definition and Ctrl/Cmd-click on an `href` or `src`
open the file the link names, at the element its `#fragment` names, with or
without a server; see [Following links](/docs/html#following-links).

## Sticky scroll

**Sticky scroll** pins the enclosing scope headers above the viewport, so deep
in a long method you can still see the class, the method and the block it
belongs to. Clicking a pinned row jumps to that header. The headers are the
blocks that fold, so in Python they come from indentation and need no language
server.

Two rules make it stay out of the way:

- **A header already on screen is not pinned.** Pinning it would cover the real
  line with a copy of itself.
- **When the chain is deeper than the cap (5 rows), the *outermost* entries are
  kept.** The innermost scope is the one you can infer from the code in front of
  you; the file-level type has been off screen longest.

Rows are rebuilt from the highlighting the editor has already applied, so they
match what's on screen and cost no extra tokenizing. A pinned header starts at
the same column as the code, past the gutter, so it lines up with the lines
under it. It's on by default, and off in
[large-file mode](/docs/troubleshooting#performance) along with the other per-viewport work.
Toggle it in **Settings → Editor → Display** or with `view.toggleStickyScroll`.

## Peek Definition

**LSP: Peek Definition** (`lsp.peekDefinition`) shows a definition *over* the
editor and leaves your place, scroll position and tab count where they were.

Often all you need from a definition is its signature and a few lines of body,
and that isn't worth leaving your place for. The peek shows a couple of lines of
context and a dozen of the definition, syntax-highlighted. **Enter** makes the
real jump and **Esc** dismisses the peek.

A library target with no file to read degrades to the full go-to-definition
rather than reporting a failure.

Needs a [language server](/docs/lsp) for the file.

## Go to Definition in a Split

**LSP: Go to Definition in Split** (`lsp.gotoDefinitionInSplit`) puts the
definition in a new [editor group](/docs/workspace#editor-groups-two-files-at-once) and leaves the
origin where it was, so the call and the thing called are on screen together.

A definition in the *same file* jumps without splitting: there's only one tab, so
the split would move it and leave the group it came from empty.

## Preview tabs

Browsing uses one tab however many files you glance at. Arrowing through a
picker, or following a definition to see what something is, reuses a single slot
whose title is **italic** to show that it is not permanent yet, the same idea as
the unsaved marker.

It becomes a permanent tab when you commit to it:

- **editing the file** promotes it, and
- **opening it explicitly** (from the file finder, the project tree, anywhere
  that isn't a glance) promotes it.

An already-open file is **never demoted** into the slot. You opened it on
purpose, and in the slot it would be replaced the next time you glanced at
something else. A file with unsaved changes is never reused either.

## Go to Related File

**Go: Related File** (`nav.relatedFile`) pairs a file with its counterpart: a
test and the class it tests, a header and its implementation, a component and its
stylesheet. It works in both directions, and from a test the class under test is
offered first. It is in the **Navigate** menu, on `C-c S-t` in the Emacs keymap
and Ctrl+Shift+T in the IntelliJ keymap.

| From | Offers |
| --- | --- |
| `Foo.java` | `FooTest.java`, `FooTests.java`, `FooSpec.java`, `FooIT.java` |
| `FooTest.java` | `Foo.java` |
| `test_foo.py` | `foo.py` |
| `foo.c` | `foo.h` |
| `foo.cpp` | `foo.hpp`, `foo.hxx`, `foo.h` |
| `util.ts` | `util.d.ts`, `util.js` |
| `util.d.ts` | `util.ts`, `util.js` |
| `util.mts`, `util.cts` | `util.d.mts` and `util.mjs`, `util.d.cts` and `util.cjs` |
| `Button.tsx` | `Button.jsx`, `Button.css`, `Button.scss` |
| `Button.jsx` | `Button.tsx`, `Button.css`, `Button.scss` |
| `app.js` | `app.ts`, `app.d.ts`, `app.css`, `app.scss`, `app.html` |
| `index.html` | the stylesheets and scripts it loads (`<link href>`, `<script src>`) |
| `site.css`, `main.js`, `main.mjs` | the project's pages that load it, first |
| `x_test.go`, `x.test.ts`, `x.spec.ts` | `x.go`, `x.ts` |

The two web-page rows come from the pages' links rather than from names. From a
stylesheet or script, Editora reads up to 500 of the project's indexed pages to
find the ones that load it, so that direction needs the
[symbol index](#go-to-symbol-in-project).

These are conventions, so Editora proposes candidate names in preference order
and offers only the ones that **exist**. A convention that doesn't hold in your
project costs nothing, which is why the list can be generous. A
counterpart rarely sits beside its partner (a test lives under `src/test`, a
header under `include`), so the sibling directory is only the first place looked;
the [symbol index](#go-to-symbol-in-project) knows every file in the project and
finds the rest.

One match opens. Several offer a picker, since `Foo.css` and `Foo.scss` can both
exist and only you know which was meant.

A Java class under `src/main/java` with no test is offered one: Editora asks to
create a JUnit 5 test in the same package under `src/test/java`, the folder
Maven and Gradle look in, and opens it.

## Keeping your place

A jump (Go to Definition, a Structure click, a search result) scrolls sideways
to the target's column as well as to its line, so the line is never shown with
its start cut off and a target far along a long line is on screen.

Three things work together here, and each is documented where it lives:

- [**Back and forward**](/docs/navigation#back-and-forward) steps through the
  jump list.
- [**Recent Locations**](/docs/navigation#recent-locations) lists the session's
  trail with the line you were on, and previews as you arrow through it.
- [**Bookmarks**](/docs/bookmarks-notes) mark places you want to return to
  deliberately, and a **mnemonic** turns one into a single chord.

Every keymap binds Back and Forward; bookmarks have default chords in the
Emacs keymap only. Peek Definition is bound in the
[IntelliJ keymap](/docs/keymaps#java-chords) (`Ctrl+Shift+I`) and the VS Code
keymap (`Alt+F12`), and Related File in IntelliJ (`Ctrl+Shift+T`) and Emacs
(`C-c S-t`). Go to Symbol in Project, Recent Locations and Go to Definition in
Split have no default chord. Bind the ones you use from **Settings → Keymaps**.
