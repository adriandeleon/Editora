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
`samples/syntax/sample.ini`

Plain-text developer formats: `samples/syntax/sample.patch` (unified diff — added/removed lines
tint green/red), `samples/syntax/Makefile` (recipe lines are real tabs), `samples/syntax/justfile`,
`samples/syntax/sample.proto`, `samples/syntax/sample.graphql`, `samples/syntax/sample.properties`
(both `=`/`:` separators, escapes, backslash continuations). There is deliberately no
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

## editorconfig/ — EditorConfig overrides (hermetic)

A self-contained tree with its own `root = true` so it does not inherit the repo's `.editorconfig`.

- `samples/editorconfig/.editorconfig` — the fixture rules.
- `samples/editorconfig/two-space.py` — should use 2-space indent.
- `samples/editorconfig/tabs.go` — should use tabs.

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
