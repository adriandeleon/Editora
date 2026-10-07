# Editora sample corpus

A curated, feature-organized set of text samples for **manual** smoke-testing and demoing Editora.
Open the relevant file and exercise the feature; this is a human dev aid, not part of the automated
test suite (the FX/unit tests in `src/test` cover behavior). One lightweight guard test —
`com.editora.SamplesCorpusTest` — keeps this manifest honest: every committed sample must be listed
here, and every path listed here must exist.

> Tip: open the whole `samples/` folder as a project (`mvn javafx:run -- --project samples` or
> File → Open Folder) to browse it with the Project tool window.

This corpus contains **deliberately broken / unusual** files (a bad Mermaid diagram, merge-conflict
markers, misspellings, non-UTF-8 encodings). That is intentional — see *Conventions* at the bottom.

## syntax/ — one file per language (highlighting + folding)

Open and confirm TextMate highlighting and fold chevrons. Every programming-language sample is a
complete program of roughly 40–80 lines built around the same small "shelf of items" example, and
each one opens with a comment listing the constructs it covers (comments of every style, strings
with escapes, numbers, types, control flow), so a missing color is easy to spot and the same idea
can be compared across languages. They are long enough for comment toggling (`M-;`), spell check in
comments, Go to Symbol and the Structure outline. `SamplesCorpusTest` fails if a bundled grammar
has no sample anywhere in the corpus.

`samples/syntax/Sample.java`, `samples/syntax/sample.py`, `samples/syntax/sample.ts`,
`samples/syntax/sample.tsx`, `samples/syntax/sample.go`, `samples/syntax/sample.rs`,
`samples/syntax/sample.c`, `samples/syntax/sample.cpp`, `samples/syntax/Sample.cs`,
`samples/syntax/Sample.kt`, `samples/syntax/sample.php`, `samples/syntax/sample.rb`,
`samples/syntax/sample.lua`, `samples/syntax/sample.html`, `samples/syntax/sample.css`,
`samples/syntax/sample.json`, `samples/syntax/sample.yaml`, `samples/syntax/sample.xml`,
`samples/syntax/sample.toml`, `samples/syntax/sample.sql`, `samples/syntax/sample.sh`,
`samples/syntax/sample.ps1`, `samples/syntax/sample.bat`, `samples/syntax/sample.groovy`,
`samples/syntax/sample.ini`, `samples/syntax/sample.js` (JavaScript, via the TypeScript grammar),
`samples/syntax/sample.jsx` (JSX, via the TSX grammar), `samples/syntax/sample.astro` (Astro: a
frontmatter script fence, a template, and style/script blocks), `samples/syntax/sample.tf`
(Terraform/HCL), `samples/syntax/Dockerfile` (matched by filename)

`samples/syntax/sample.tsx` declares its own minimal JSX types instead of importing React, so a
language server reports no missing-module error when the corpus is opened without `node_modules`.

Plain-text developer formats: `samples/syntax/sample.patch` (unified diff — added/removed lines
tint green/red), `samples/syntax/Makefile` (recipe lines are real tabs), `samples/syntax/justfile`,
`samples/syntax/sample.proto`, `samples/syntax/sample.graphql`, `samples/syntax/sample.properties`
(both `=`/`:` separators, escapes, backslash continuations), `samples/syntax/sample.mw` (a
**Markwhen** timeline — dates/ranges/`#tags`/`#`-header sections + `//` comments; also has an
Editor/Split/Preview toggle that renders the timeline). There is deliberately no
`.gitattributes` sample — a real one would change Git's behavior for this folder (see
*Conventions*); open any repo's `.gitattributes` to see that grammar. The ignore-file grammar
(`.gitignore` and friends) is covered by `samples/dockerfile/.dockerignore` for the same reason.

## folding/ — nested fold regions

- `samples/folding/deeply-nested.json` — collapse/expand several nested levels; the gutter chevrons
  and the Structure outline should mirror the nesting.

## navigation/ — sticky scroll, fold levels, outline, related files

- `samples/navigation/Warehouse.java` — a deliberately long (about 240 lines), deeply nested class:
  the one file in the corpus with enough height and depth for **sticky scroll** (scroll into
  `Aisle.Shelf.Bin.take` and four scopes stay pinned), **Fold Level 1–5**, **Fold / Unfold
  Recursively**, **Go to Parent Fold**, the **Structure** outline, **Narrow to Defun**, the
  **minimap**, and **bracket pair colorization** (the `nested` method has six depths on one line). It
  compiles and runs (`java samples/navigation/Warehouse.java`).
- `samples/navigation/geometry.h` + `samples/navigation/geometry.c` — a header/implementation pair
  for **Go: Related File**. The other related-file pairs live where they are also useful for
  something else: the web trio under `web/`, and each build-tool project's source + test file.

## indent/ — auto-indent, smart backspace, closer re-align

- `samples/indent/braces.c` — Enter after `{` indents a level; typing `}` re-aligns to the opener.
- `samples/indent/blocks.py` — Enter after `:` indents; smart backspace on a blank indented line
  jumps back to the end of the previous line.

## editing/ — Emacs-style editing commands (content-dependent)

Fixtures for the editing commands where the *content* is the test. Set the keymap to **Emacs**.

- `samples/editing/tabs.txt` — a block of tab-indented lines and a block of space-indented lines. Select
  one, then run *Edit: Untabify Region* (tabs → spaces) or *Edit: Tabify Region* (spaces → tabs). Turn on
  *Toggle Whitespace* to see which is which; the mixed block at the bottom checks the tab-then-spaces rule.
- `samples/editing/align.txt` — assignment / key-value / comment blocks. Select a block and run
  *Edit: Align Regexp* with `=`, `:` or `//`. The already-uneven block shows that it pads to the widest
  match rather than collapsing existing whitespace (so it's idempotent).
- `samples/editing/fill.txt` — long paragraphs for **auto-fill**. Turn on *View: Toggle Auto-Fill*, set a
  small fill column (`C-x f`, e.g. 40), then keep typing past the margin and watch lines wrap at a word
  boundary; the indented paragraph confirms the wrap keeps the indent. `M-q` reflows a whole paragraph.

The other Emacs commands are keystroke-driven and need no special fixture — open any buffer and exercise
them: kill ring (`C-k`/`C-y`/`M-y`), rectangles (`C-x r …`), narrowing (`C-x n n`/`C-x n w`), query-replace
(`M-%`), the mark ring (`C-SPC`/`C-x C-SPC`), occur (`M-s o`), and the `C-u` prefix argument
(`C-u 5 C-n`). Abbreviations need a dictionary — define one with `C-x a g`, then expand with `C-x a e`.

## markdown/ — preview, GFM, math, embedded Mermaid

- `samples/markdown/gfm.md` — toggle preview (Editor/Split/Preview): bold/italic/code, a task list
  with real checkboxes, a column-aligned table, a fenced code block, and a shields.io SVG **badge**
  (exercises the SVG rasterizer).
- `samples/markdown/math.md` — inline `$…$` and block `$$…$$` math (needs `mathSupport`).
- `samples/markdown/mermaid-in-markdown.md` — a fenced `mermaid` block renders inline (needs `mmdc`).
- `samples/markdown/extras.md` — everything `gfm.md` leaves out: YAML front matter, footnotes (reference
  and inline), strikethrough, inserted text, an autolink, nested and task lists, nested block quotes,
  an indented code block, and **local** images (`../images/…`, so the preview needs no network).
  Five heading levels make it the file for the outline and *Markdown: Insert/Update Table of Contents*.
- `samples/markdown/lint.md` — **Markdown lint**: trips each of the 16 rules exactly where a comment
  says so (MD001 … MD052), shows a `markdownlint-disable` directive suppressing one, and has a long
  line for the optional MD013. Try the quick fixes, then undo. It is deliberately malformed — the
  repo `.editorconfig` exempts it from the final-newline rule so saving it does not "repair" MD047.

## mermaid/ — standalone diagrams + lint

- `samples/mermaid/flowchart.mmd` — a valid diagram; preview renders it (needs `mmdc`).
- `samples/mermaid/invalid.mmd` — intentionally broken; `maid` should draw lint squiggles.

## markwhen/ — timeline + calendar + JSON export

`samples/syntax/sample.mw` already covers the basic timeline; this one is day-level so the alternate
views are worth exercising.

- `samples/markwhen/roadmap.mw` — a dated roadmap with `#tag` colors and `#`/`##`-header sections. The
  3-mode preview renders the **timeline**; **Switch to Calendar View** (`markwhen.toggleView`) shows the
  month-grid with tag-colored chips on each covered day; **Export to JSON** (`markwhen.exportJson`) writes
  the parsed tree. Right-click the preview for Export-to-PDF / Print too.

## diagrams/ — Graphviz DOT + PlantUML preview

- `samples/diagrams/graph.dot` — a Graphviz digraph; the 3-mode preview renders it (needs the `dot` CLI).
- `samples/diagrams/sequence.puml` — a PlantUML sequence diagram; preview renders it (needs `plantuml`).

## typst/ — Typst document preview (multi-page)

All need the `typst` CLI; the 3-mode preview renders one image per page, stacked. Export to PDF is native
single-file; print paginates the pages. Every sample is **package-free** (no `@preview` imports), so it
compiles offline. They cover Typst's common document types:

- `samples/typst/report.typ` — a general two-page document (prose, headings, a list, inline + block math,
  a small table) — good for the list-continuation and format-bar editing too.
- `samples/typst/math.typ` — **mathematics**: inline + display equations, matrices, systems, calculus, symbols.
- `samples/typst/tables.typ` — **tables**: a styled header/footer table + a zebra-striped one (`#table`).
- `samples/typst/code.typ` — **code**: syntax-highlighted fenced blocks (Rust/Python/Typst) + inline raw.
- `samples/typst/bibliography.typ` — **bibliographies**: `@cite` references + an auto-generated list from
  `samples/typst/refs.bib` (resolves because the preview compiles with `--root` = the file's folder).
- `samples/typst/slides.typ` — **slides**: a native 16:9 multi-page deck (colored pages, big text) — one
  `#pagebreak()` per slide, so the preview shows several stacked page images.
- `samples/typst/shapes.typ` — **visualizations**: native drawing primitives (rect/circle/polygon/curve) +
  a hand-drawn bar chart (rich charts/diagrams use packages like cetz, kept out to stay package-free).

### packages/ — samples that use `@preview` packages (need a one-time network fetch)

These exercise Typst's package system: on first render `typst` downloads the package from the registry and
caches it (`~/Library/Caches/typst/` on macOS, `~/.cache/typst/` on Linux; offline afterward). Offline + not-yet-cached → the preview shows
the download error. Package versions are pinned to ones that compile with typst 0.15.

- `samples/typst/packages/fletcher-diagram.typ` — an arrow/node diagram (`@preview/fletcher`).
- `samples/typst/packages/cetz-drawing.typ` — a hand-drawn tree diagram on a canvas (`@preview/cetz`).
- `samples/typst/packages/lilaq-chart.typ` — a plotted sine/cosine chart (`@preview/lilaq`).
- `samples/typst/packages/polylux-slides.typ` — a 3-slide deck (`@preview/polylux`); the preview stacks 3 pages.

## structured/ — JSON/YAML/TOML/XML tree + OpenAPI docs preview

- `samples/structured/config.json` — the 3-mode preview renders a collapsible, type-colored tree.
- `samples/structured/config.yaml` — same data as YAML; the preview tree is identical.
- `samples/structured/config.toml` — same data as TOML.
- `samples/structured/config.xml` — same data as XML; the preview renders a collapsible DOM tree
  (tags + attributes + text, text-only elements inlined).
- `samples/structured/petstore.yaml` — an OpenAPI 3 spec; the preview auto-renders browsable API docs
  (toggle to the raw tree with `structured.toggleView`).

## svg/ — SVG image preview

- `samples/svg/shapes.svg` — edit the XML source and the 3-mode preview re-renders the image live (JSVG).

## web/ — HTML live preview + related files

- `samples/web/widget.html` + `samples/web/widget.css` + `samples/web/widget.js` — a small counter
  page split over three files. Open the HTML file and use its floating browser icon for the **live
  preview**, then edit the stylesheet or the script. The shared base name makes **Go to Related
  File** cycle between the three. (`samples/syntax/sample.html` is the single-file version, with
  embedded `<style>` and `<script>` blocks for the grammar switch.)

## crontab/ — crontab schedule preview

- `samples/crontab/deploy.crontab` — jobs, `@reboot`, an env assignment, and a deliberately out-of-range
  line. The 3-mode preview decodes each schedule into English + shows the next run times; the bad line
  turns red.

## fstab/ — fstab mount preview

- `samples/fstab/sample.fstab` — device specs (UUID/LABEL/path/CIFS), swap, tmpfs, and a deliberately
  broken 2-column line. The 3-mode preview decodes each mount into plain English (device, mount point,
  filesystem, options, fsck/dump); the broken line turns red.

## systemd/ — systemd unit preview

- `samples/systemd/backup.timer` — the 3-mode preview decodes `OnCalendar=Mon..Fri *-*-* 02:30:00` into
  English ("At 02:30, Monday through Friday") + the next run times, and glosses `Persistent`/`Unit`/etc.
- `samples/systemd/backup.service` — each directive glossed in plain English (ExecStart, Type, User,
  After/Wants, RestartSec, WantedBy).

## ssh/ — SSH client-config preview

- `samples/ssh/ssh_config` — global defaults + per-`Host` blocks; the preview shows a one-line connection
  summary per host ("Connects to example.com on port 2222 as deploy, key …, via jump host bastion") plus
  option glosses.

## dockerfile/ — Dockerfile stage preview

- `samples/dockerfile/Dockerfile` — a multi-stage build; the preview shows a per-stage digest (base image,
  exposed ports, workdir, user, entrypoint/command, health check, build-step count).
- `samples/dockerfile/.dockerignore` — the **ignore-file grammar** (comments, globs, `**`, a `!`
  negation, a character class, an escape). Every dotfile ending in `ignore` uses it. A `.dockerignore`
  is inert here, which a real `.gitignore` would not be.

## github-actions/ — GitHub Actions workflow preview

- `samples/github-actions/ci.yml` — a workflow (detected by content: top-level `on:` + `jobs:`, so it
  renders the workflow digest instead of the generic YAML tree). The preview lists the triggers in plain
  English ("push to main, develop", "pull request to main", the `schedule:` cron decoded), then each job
  with its runner, `needs`/`if`, and ordered steps.

## config/ — config-file grammars (highlighting only)

Bundled TextMate grammars for common config formats that aren't "core languages," so they aren't in
`syntax/`. Open each and confirm highlighting loads. Several are recognized by **name + location** (not
extension), so the enclosing `etc/`, `network/`, `debian/` dirs matter — don't flatten them. All are
inert samples (fake values, not real system files).

- `samples/config/sample.env` — dotenv (`.env`): `KEY=value`, quotes, `${VAR}` refs, `export`.
- `samples/config/sample.gitconfig` — git-config: `[section]` headers, `key = value`, subsection strings.
- `samples/config/Caddyfile` — Caddy web-server config (site blocks, directives).
- `samples/config/sample.desktop` — XDG `.desktop` entry (`[Desktop Entry]` keys).
- `samples/config/example.sources` — Debian **deb822** APT sources (RFC822 paragraphs).
- `samples/config/etc/hosts` — `/etc/hosts` (matched only under an `etc/` dir).
- `samples/config/etc/apt/sources.list` — classic one-line APT `sources.list` (apt-sources grammar).
- `samples/config/etc/network/interfaces` — Debian ifupdown config (matched under a `network/` dir).
- `samples/config/debian/changelog` — Debian packaging changelog (matched under a `debian/` dir).

## hex/ — hex viewer (binary files)

- `samples/hex/sample.bin` — a small binary blob (magic bytes, embedded ASCII, control + high bytes, and
  NUL separators). A file detected as binary by content opens in the read-only **hex viewer** (`offset |
  hex | ASCII`) instead of dumping bytes as text. `view.openAsHex` force-opens any file this way.

## pdf/ — PDF viewer

- `samples/pdf/sample.pdf` — a 2-page PDF; opens in the read-only page viewer (◀/▶ navigation + zoom, PDFBox).

## todo/ — TODO/FIXME highlighting

- `samples/todo/markers.java` — one line per built-in keyword, each in its own color and its own
  group in the TODO tool window: `TODO` (amber), `FIXME` (red), `HACK` (orange), `NOTE` (blue), `XXX`
  (magenta) and `DONE` (green, the "marked done" state). Two decoys (`todo`, `TODOS`) must **not**
  match, and a string literal and a block comment show that both are scanned.

## spell/ — spell check

- `samples/spell/prose-typos.md` — prose: every word is checked; misspellings get red squiggles.
- `samples/spell/code-comment-typos.java` — code: only comment/string words are checked, not
  identifiers.

## search/ — Find in Files

- `samples/search/alpha.txt`, `samples/search/beta.txt` — both contain `needle` and a shared `TERM`;
  use them to test multi-file results, case sensitivity, and whole-word.
- `samples/search/regex-cases.txt` — regex patterns plus a multibyte `é` to verify ripgrep
  byte→char column mapping.
- `samples/search/replace-cases.txt` — the in-file find bar's **replace** paths: regex capture groups
  (`$1`/`$2`), `$` staying literal in non-regex mode, a bad group reference leaving the buffer alone,
  **preserve case** (`AB`) including per-segment `snake_case`, and **find in selection** (`Sel`) with a
  marked block plus decoys outside it. Each section states the query and the expected result inline.

## csv/ — CSV/TSV grid, rainbow columns, delimiter detection

Open any file and confirm: per-column **rainbow** editor coloring, the status-bar **"Field N of M"**
segment (click it to copy as a Markdown table), and the **CSV grid** tool window (bottom stripe) with
its row-count × column-count profiler and click-a-cell → jump. The delimiter is auto-detected from the
first line, so the extension need not match the separator.

- `samples/csv/people.csv` — plain comma CSV; mixed numeric (`id`/`salary`) and text columns exercise
  the grid's type profiler (right-aligned numbers) and content-fit column widths.
- `samples/csv/data.tsv` — **tab**-separated (real tabs); detected as TSV.
- `samples/csv/european.csv` — **semicolon**-delimited (the European convention).
- `samples/csv/pipe-delimited.csv` — **pipe**-delimited content in a `.csv` file (delimiter
  auto-detection picks `|`).
- `samples/csv/quoted.csv` — RFC-4180 edge cases: quoted fields with embedded commas, a `""` escaped
  quote, a **leading-zero** ZIP (`07030` — stays text on export, not `7030`), and a field with an
  **embedded line break** (a multi-line field, so the grid opens read-only — a data row no longer maps
  1:1 to a physical line).
- `samples/csv/ragged.csv` — deliberately **inconsistent** row widths (2–5 fields against a 4-column
  header); the grid tints the ragged rows and the summary appends "· N inconsistent". Try **Align**
  (`csv.align`) then **Shrink** (`csv.shrink`) — it refuses on a multi-line-field file but works here.

## editorconfig/ — EditorConfig overrides (hermetic)

A self-contained tree with its own `root = true` so it does not inherit the repo's `.editorconfig`.

- `samples/editorconfig/.editorconfig` — the fixture rules.
- `samples/editorconfig/two-space.py` — should use 2-space indent.
- `samples/editorconfig/tabs.go` — should use tabs.

## run/ — Run a file (gutter ▶)

- `samples/run/Makefile` — a green ▶ appears in the gutter on every rule target (`all`/`greet`/
  `build`/`test`/`clean`); click it to run `make <target>` in the Run tool window. The variable
  assignment, `.PHONY` line, and `%.o` pattern rule deliberately get **no** glyph. "Run File"
  (right-click / `C-c r`) runs the default goal (bare `make` → `all`). Every recipe is a harmless
  `@echo`, so running any target is instant and side-effect-free. (Needs the LSP feature on — the Run
  affordance rides that gate — and `make` on `PATH`.)
- `samples/run/hello.java` — a Java 25 **compact source file** (JEP 512): a top-level `void main`, no
  class. A ▶ appears on the `void main(` line; running needs JDK 25 on `PATH`.
- `samples/run/hello.py` — a Python script; the ▶ sits on the `if __name__ == "__main__":` guard
  (needs `python3` on `PATH`).
- `samples/run/hello.sh` — a shell script; the ▶ sits on the first line and runs the file with `bash`
  (shell Run follows the Bash LSP toggle).
- `samples/run/shebang-script` — **no file extension**: the `#!/usr/bin/env python3` first line is
  what makes it Python. Check the status-bar language, the highlighting and the ▶.
- `samples/run/debug.py` — a script worth **debugging** (needs the Python adapter, Settings →
  Debugging): locals, a loop, nested calls, a dictionary that grows, and a caught exception. Lines
  marked `break here` suggest where to try a plain breakpoint, a conditional one (`n == 15`), a
  logpoint, a watch on `total`, step-into, and set-value. `samples/run/hello.java` is the Java
  equivalent for a first breakpoint.

## build-tools/ — Maven / Gradle / npm / Cargo / Go toolbar button + actions popup + tests

Five tiny, self-contained projects — one per build tool. Open a file under a project's folder and
its build-tool toolbar button appears (each button stays hidden until its marker file is detected);
click it for the sectioned actions popup. The projects are **standalone** — the repo's own build
never picks them up (the Maven sample isn't a `<module>` of the root pom, the Gradle sample has its
own `settings.gradle`), and every command is harmless (an `@echo`/`println`, or a lifecycle phase you
choose to run). Each needs its tool on `PATH` to actually run (`mvn`/`gradle`/`npm`/`cargo`/`go`); the
button + popup show regardless. (Build Tools are disabled in Simple UI mode and for remote files.)

Four of the five have **tests**, for the Test Results tool window and the test gutter ▶. Each suite has one
passing test, one that **fails on purpose** and one that is **skipped on purpose**, so every status
has an example — a red result here is the sample working, not a regression. Each test file sits
beside the source it tests under a conventional name, so **Go: Related File** jumps between them.

- `samples/build-tools/maven/pom.xml` + `samples/build-tools/maven/src/main/java/com/example/App.java`
  — the popup lists the Lifecycle phases (a Task each), the `release` **profile** (a checkable
  toggle → `-Prelease`), and the surefire `integration-tests` execution goal under Plugins. The
  `pom.xml` also has a **preview** (coordinates, properties, dependencies and plugins in aligned
  columns). `samples/build-tools/maven/src/test/java/com/example/AppTest.java` is a JUnit 5 class
  with a `@ParameterizedTest`, a `@DisplayName`, a `@Disabled` test and a `@Nested` class; `mvn test`
  downloads JUnit on first run.
- `samples/build-tools/gradle/build.gradle` + `samples/build-tools/gradle/settings.gradle` — the
  popup shows the static Common section (build/clean/test/assemble/check/jar/run/bootRun) plus
  **Load all tasks…** (runs `gradle tasks --all` to list the rest, including the custom `hello` task).
- `samples/build-tools/npm/package.json` + `samples/build-tools/npm/index.js` — the popup lists the
  `scripts` (`start`/`build`/`test`/`lint`, each `npm run <name>`) + a Common `install`/`ci`; the
  `packageManager` field would switch the runner (npm/yarn/pnpm/bun).
  `samples/build-tools/npm/index.test.js` uses Node's built-in test runner (no dependencies); the
  `test` script prints TAP.
- `samples/build-tools/cargo/Cargo.toml` + `samples/build-tools/cargo/src/main.rs` — the popup shows
  the standard subcommands, the explicit `cargo-demo` binary under Targets (`cargo run --bin
  cargo-demo`), and a `--release` toggle. The tests are a `#[cfg(test)]` module in `main.rs`.
- `samples/build-tools/go/go.mod` + `samples/build-tools/go/main.go` — the popup shows the standard
  `go` subcommands over the whole module (`build ./...`, `run .`, `test ./...`, `vet`, `fmt`, `mod
  tidy`, …); the `module example.com/go-demo` line is the Settings "Found: …" label.
  `samples/build-tools/go/main_test.go` holds the tests, including a table-driven one with subtests.

## images/ — image viewer

- `samples/images/sample.png` — opens in the read-only image viewer (zoom out/in/fit/actual, Ctrl+wheel)
  instead of the hex viewer. It has an alpha channel.
- `samples/images/sample.jpg`, `samples/images/sample.gif` (two frames), `samples/images/sample.bmp`
  (60 × 40, to test zooming in on a small image) — the other raster formats the viewer accepts.

## http/ — HTTP client

- `samples/http/requests.http` — run the per-request ▶; uses `{{title}}`/`{{token}}` variables.
- `samples/http/advanced.rest` — the `.rest` extension, and the rest of the format: in-file `@variables`,
  a **named request** and a later one **chained** to its response (`{{create.response.body.$.json.item}}`),
  dynamic variables (`{{$uuid}}`, `{{$isoTimestamp}}`, `{{$randomInt}}`), the **Basic auth** shorthand, an
  **external body** (`< ./body.json`, from `samples/http/body.json`), a **multipart** form, the
  `@no-redirect` / `@timeout` directives, and a request with no method. Run the first two in order to
  see chaining. All requests go to httpbin.org, so they need a network connection.
- `samples/http/http-client.env.json` — `dev`/`prod` environments with **fake** tokens (never real
  secrets).

## log/ — log viewer

- `samples/log/levels.log` — every level TRACE→FATAL; test the level filter + per-level coloring.
- `samples/log/stacktraces.log` — Java/Python/Node frames; double-click a frame to jump (clickable
  stack traces).
- `samples/log/app.log.1` — a **rotated** name (recognised without a `.log` extension), in Logback
  layout, with **multi-line records**: a wrapped warning and an exception with a `Caused by:` chain.
  Set the level floor to WARN, or filter for `pool`, and check each record stays whole and keeps its
  real line numbers. One TRACE line has the word "error" in its message and must stay TRACE.
- `samples/log/json.log` — **structured** logs, one JSON object per line: string levels with the keys
  in any order, pino's numeric levels (30/40/50/60), and a `severity` field.
- `samples/log/kubernetes.log` — **klog**: the level is the first letter (`I`/`W`/`E`/`F`).
- `samples/log/syslog` — an **extensionless** name; only the lines whose message carries a level
  prefix (`error:`, `warning:`) are colored, the rest stay neutral.
- `samples/log/access_log` — an Apache/nginx **access log**: the level comes from the status code
  (2xx/3xx info, 4xx warning, 5xx error).
- `samples/log/server.out` — recognised by **content**, not by name: .NET console logging (`info:`,
  `warn:`, `fail:`, `crit:`, each with an indented continuation line), nginx's error log (`[error]`),
  and `java.util.logging` (`SEVERE:` on the line after the timestamp).

To exercise **Follow**, append to a scratch file under the git-ignored `samples/perf/` folder and open it:

```sh
mkdir -p samples/perf && while true; do echo "$(date '+%F %T') INFO tick" >> samples/perf/follow.log; sleep 1; done
```

## diff/ — diff viewer + merge

- `samples/diff/original.txt` + `samples/diff/modified.txt` — Compare With… to see a side-by-side
  diff.
- `samples/diff/inventory-before.py` + `samples/diff/inventory-after.py` — a realistic pair for
  Compare With…: a **moved** function, a rewritten one, a **word-level** change inside a line
  (`%s` → `%.1f`, `64` → `128`), an **indentation-only** change (try the ignore-whitespace toggle),
  an added import and an added parameter. Good for next/previous-change navigation and patch export.
- `samples/diff/conflict.txt` — Git merge-conflict markers; opens in the merge resolver.
- `samples/diff/conflict-diff3.txt` — two conflicts in the **diff3** style: each carries the common
  ancestor between `|||||||` and `=======`, with unconflicted text between and after them.

## encodings/ — charset + EOL detection

Bytes are preserved verbatim via `.gitattributes` (`-text`), so don't "fix" them.

- `samples/encodings/utf8-bom.txt` — UTF-8 with a BOM.
- `samples/encodings/utf16le.txt` — UTF-16 LE (no BOM).
- `samples/encodings/utf16be.txt` — UTF-16 BE, with a BOM.
- `samples/encodings/latin1.txt` — ISO-8859-1.
- `samples/encodings/crlf.txt` — CRLF line endings (status bar should show `CRLF`).

## perf/ — large files (generated, not committed)

Run `java scripts/GenSamples.java` (a JDK 25 compact source file — run it from the repo root) to create
`samples/perf/` (git-ignored): files crossing the 5 MB highlight/minimap cutoff and the 50 MB
read-only/capped-load cutoff. They are generated rather than committed so they never bloat git history.
Pass an MB count to scale the huge file, e.g. `java scripts/GenSamples.java 120`.

## Conventions for this corpus

- **Inert by name.** Config-like samples are named so they can't act on the repo (e.g. there's no
  real `.gitignore` here; the EditorConfig fixture is sandboxed with `root = true`).
- **No real secrets.** `.http` samples use obviously-fake tokens.
- **Keep it small.** A fixture is as long as its feature needs and no longer: most are a few lines,
  the syntax samples are 40–80, and `navigation/Warehouse.java` is long because length is its point.
  Large/perf inputs are generated, not committed.
- **Valid unless broken on purpose.** A sample should compile, parse or render cleanly, so the only
  squiggles a language server draws are the ones the sample is about. Deliberately broken files say
  so in a comment and in this README. Samples are self-contained: no imports that need a package
  install to resolve.
- **Prefer offline.** Use local images and files where a feature allows it. The HTTP samples and the
  Markdown badge are the exceptions, and are marked.
- **Update this README** when you add or remove a sample — `SamplesCorpusTest` fails otherwise.
