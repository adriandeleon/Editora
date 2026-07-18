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

## syntax/ — one file per core language (highlighting + folding)

Open and confirm TextMate highlighting and fold chevrons. Languages without a bundled grammar render
as plain text (expected).

`samples/syntax/Sample.java`, `samples/syntax/sample.py`, `samples/syntax/sample.ts`,
`samples/syntax/sample.tsx`, `samples/syntax/sample.go`, `samples/syntax/sample.rs`,
`samples/syntax/sample.c`, `samples/syntax/sample.cpp`, `samples/syntax/Sample.cs`,
`samples/syntax/Sample.kt`, `samples/syntax/sample.php`, `samples/syntax/sample.rb`,
`samples/syntax/sample.lua`, `samples/syntax/sample.html`, `samples/syntax/sample.css`,
`samples/syntax/sample.json`, `samples/syntax/sample.yaml`, `samples/syntax/sample.xml`,
`samples/syntax/sample.toml`, `samples/syntax/sample.sql`, `samples/syntax/sample.sh`,
`samples/syntax/sample.ps1`, `samples/syntax/sample.bat`, `samples/syntax/sample.groovy`,
`samples/syntax/sample.ini`, `samples/syntax/sample.js` (JavaScript, via the TypeScript grammar),
`samples/syntax/sample.tf` (Terraform/HCL), `samples/syntax/Dockerfile` (matched by filename)

Plain-text developer formats: `samples/syntax/sample.patch` (unified diff — added/removed lines
tint green/red), `samples/syntax/Makefile` (recipe lines are real tabs), `samples/syntax/justfile`,
`samples/syntax/sample.proto`, `samples/syntax/sample.graphql`, `samples/syntax/sample.properties`
(both `=`/`:` separators, escapes, backslash continuations), `samples/syntax/sample.mw` (a
**Markwhen** timeline — dates/ranges/`#tags`/`#`-header sections + `//` comments; also has an
Editor/Split/Preview toggle that renders the timeline). There is deliberately no
`.gitattributes` sample — a real one would change Git's behavior for this folder (see
*Conventions*); open any repo's `.gitattributes` to see that grammar.

## folding/ — nested fold regions

- `samples/folding/deeply-nested.json` — collapse/expand several nested levels; the gutter chevrons
  and the Structure outline should mirror the nesting.

## indent/ — auto-indent, smart backspace, closer re-align

- `samples/indent/braces.c` — Enter after `{` indents a level; typing `}` re-aligns to the opener.
- `samples/indent/blocks.py` — Enter after `:` indents; smart backspace on a blank indented line
  jumps back to the end of the previous line.

## markdown/ — preview, GFM, math, embedded Mermaid

- `samples/markdown/gfm.md` — toggle preview (Editor/Split/Preview): bold/italic/code, a task list
  with real checkboxes, a column-aligned table, a fenced code block, and a shields.io SVG **badge**
  (exercises the SVG rasterizer).
- `samples/markdown/math.md` — inline `$…$` and block `$$…$$` math (needs `mathSupport`).
- `samples/markdown/mermaid-in-markdown.md` — a fenced `mermaid` block renders inline (needs `mmdc`).

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
caches it under `~/Library/Caches/typst/` (offline afterward). Offline + not-yet-cached → the preview shows
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

- `samples/todo/markers.java` — `TODO` (amber) and `FIXME` (red) highlight + appear in the TODO tool
  window; `NOTE` only highlights if you add it as a custom pattern.

## spell/ — spell check

- `samples/spell/prose-typos.md` — prose: every word is checked; misspellings get red squiggles.
- `samples/spell/code-comment-typos.java` — code: only comment/string words are checked, not
  identifiers.

## search/ — Find in Files

- `samples/search/alpha.txt`, `samples/search/beta.txt` — both contain `needle` and a shared `TERM`;
  use them to test multi-file results, case sensitivity, and whole-word.
- `samples/search/regex-cases.txt` — regex patterns plus a multibyte `é` to verify ripgrep
  byte→char column mapping.

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

## build-tools/ — Maven / Gradle / npm / Cargo / Go toolbar button + actions popup

Five tiny, self-contained projects — one per build tool. Open a file under a project's folder and
its build-tool toolbar button appears (each button stays hidden until its marker file is detected);
click it for the sectioned actions popup. The projects are **standalone** — the repo's own build
never picks them up (the Maven sample isn't a `<module>` of the root pom, the Gradle sample has its
own `settings.gradle`), and every command is harmless (an `@echo`/`println`, or a lifecycle phase you
choose to run). Each needs its tool on `PATH` to actually run (`mvn`/`gradle`/`npm`/`cargo`/`go`); the
button + popup show regardless. (Build Tools are disabled in Simple UI mode and for remote files.)

- `samples/build-tools/maven/pom.xml` + `samples/build-tools/maven/src/main/java/com/example/App.java`
  — the popup lists the Lifecycle phases (a Task each), the `release` **profile** (a checkable
  toggle → `-Prelease`), and the surefire `integration-tests` execution goal under Plugins.
- `samples/build-tools/gradle/build.gradle` + `samples/build-tools/gradle/settings.gradle` — the
  popup shows the static Common section (build/clean/test/assemble/check/jar/run/bootRun) plus
  **Load all tasks…** (runs `gradle tasks --all` to list the rest, including the custom `hello` task).
- `samples/build-tools/npm/package.json` + `samples/build-tools/npm/index.js` — the popup lists the
  `scripts` (`start`/`build`/`test`/`lint`, each `npm run <name>`) + a Common `install`/`ci`; the
  `packageManager` field would switch the runner (npm/yarn/pnpm/bun).
- `samples/build-tools/cargo/Cargo.toml` + `samples/build-tools/cargo/src/main.rs` — the popup shows
  the standard subcommands, the explicit `cargo-demo` binary under Targets (`cargo run --bin
  cargo-demo`), and a `--release` toggle.
- `samples/build-tools/go/go.mod` + `samples/build-tools/go/main.go` — the popup shows the standard
  `go` subcommands over the whole module (`build ./...`, `run .`, `test ./...`, `vet`, `fmt`, `mod
  tidy`, …); the `module example.com/go-demo` line is the Settings "Found: …" label.

## images/ — image viewer

- `samples/images/sample.png` — opens in the read-only image viewer (zoom out/in/fit/actual, Ctrl+wheel)
  instead of the hex viewer.

## http/ — HTTP client

- `samples/http/requests.http` — run the per-request ▶; uses `{{title}}`/`{{token}}` variables.
- `samples/http/http-client.env.json` — `dev`/`prod` environments with **fake** tokens (never real
  secrets).

## log/ — log viewer

- `samples/log/levels.log` — every level TRACE→FATAL; test the level filter + per-level coloring.
- `samples/log/stacktraces.log` — Java/Python/Node frames; double-click a frame to jump (clickable
  stack traces).

## diff/ — diff viewer + merge

- `samples/diff/original.txt` + `samples/diff/modified.txt` — Compare With… to see a side-by-side
  diff.
- `samples/diff/conflict.txt` — Git merge-conflict markers; opens in the merge resolver.

## encodings/ — charset + EOL detection

Bytes are preserved verbatim via `.gitattributes` (`-text`), so don't "fix" them.

- `samples/encodings/utf8-bom.txt` — UTF-8 with a BOM.
- `samples/encodings/utf16le.txt` — UTF-16 LE.
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
- **Keep it small.** Sample files are a few lines each; large/perf inputs are generated, not
  committed.
- **Update this README** when you add or remove a sample — `SamplesCorpusTest` fails otherwise.
