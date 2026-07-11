# Editora — TODO / Roadmap

A backlog of planned features and improvements. Unordered within each section.

## Recently shipped
- [x] systemd / SSH-config / Dockerfile previews — three more config-file 3-mode previews that decode into
      English. **systemd** (`.service`/`.timer`/…): per-directive glosses + a `.timer`'s `OnCalendar=`
      decoded to English + next run times (shorthands + `On*Sec=` monotonic timers via time-span decode);
      pure `systemd/` core (`SystemdUnit`/`SystemdCalendar`/`TimeSpan`/`SystemdDescribe`). **SSH config**: a
      per-`Host` connection summary + option glosses; pure `sshconfig/` core. **Dockerfile**: a per-stage
      digest (base image, ports, workdir, user, entrypoint/cmd, healthcheck, step count); pure `dockerfile/`
      core. All render into the shared self-scrolling tree host; snapshot Export-to-PDF/Print for free. On
      by default; `Settings.systemdPreview`/`sshConfigPreview`/`dockerfilePreview` (schema 73→74). No new
      dependency. *Deferred: systemd second-granularity next-runs + `.network`/`.link` specifics, ssh
      `Match` criteria eval + Include resolution, Dockerfile ARG-substitution in image names.*
- [x] fstab mount preview — an `/etc/fstab` file (already syntax-highlighted) gets the 3-mode preview: each
      line decoded into English (device spec — UUID/LABEL/path/CIFS/NFS —, mount point, filesystem, the
      comma-separated options, and the fsck/dump columns); a malformed line (too few columns, non-numeric
      dump/pass) turns red. Pure `fstab/` core (`Fstab` parser + `FstabDescribe` option dictionary),
      unit-tested; `editor/FstabPreview` renders into the shared self-scrolling tree host; snapshot
      Export-to-PDF/Print for free. On by default; `Settings.fstabPreview` (schema 67→68). No new
      dependency. *Deferred: btrfs subvolume tree, per-option man-page links, a mount-vs-running-mounts diff.*
- [x] Crontab schedule preview — a `crontab`/`*.cron`/`cron.d/*` file (already syntax-highlighted) gets the
      3-mode preview: each schedule decoded into English (`30 2 * * 1-5` → "At 02:30, Monday through
      Friday"), the next fire times, `@reboot`/`@daily`/… macros, and a red field-error for a malformed
      line. Pure `cron/` core (`CronExpression` with the Vixie DOM/DOW OR-rule + `Crontab` parser),
      unit-tested; `editor/CrontabPreview` renders into the shared self-scrolling tree host; snapshot
      Export-to-PDF/Print for free. On by default; `Settings.crontabPreview` (schema 65→66). No new
      dependency. *Deferred: seconds/year 6-field cron, `L`/`W`/`#` (Quartz) modifiers, a 24h×7d heat-grid
      view, timezone-aware next-run display.*
- [x] XML tree preview — `.xml` joins the structured-data preview with a collapsible DOM tree (tags +
      attributes + text, text-only elements inlined). A *faithful* DOM model, not an XML→JSON shoehorn.
      Parsed off-thread with the JDK DOM parser (no new dependency), XXE-hardened like `PomParser`. New pure
      `structured/XmlNode`/`XmlParser` (unit-tested, incl. XXE-blocked) + `editor/XmlTree`; reuses the
      structured host + the same `Settings.structuredPreview` toggle. `.svg` still renders as an image
      (excluded); `.xhtml` still uses the browser preview. *Deferred: dialect renderers (RSS/Atom feed view,
      like OpenAPI for JSON), attribute/text search. Third of the reuse-existing-deps cluster (SVG, PDF, XML
      done; next: Excel/ODS via POI, font specimen, archive contents).*
- [x] PDF viewer — `.pdf` files open in a read-only page viewer (PDFBox-rasterized, already a dependency)
      instead of the hex viewer: ◀/▶ page nav + zoom (fit/actual/Ctrl+wheel). Pages rasterized one at a time
      off the FX thread on a single daemon thread (PDDocument isn't thread-safe), so only the current page's
      texture is held. New `ui/PdfViewerPane implements TabContent` (mirrors `ImageViewerPane`); routed in
      `openPath` before the hex fallback + round-tripped through session restore/`tabPath`. Local + remote
      (SFTP); 128 MB cap. No new dependency. *Deferred: continuous page scroll, page-jump field, text
      selection/search, password-protected PDFs. Second of the reuse-existing-deps preview cluster (SVG done;
      next: Excel/ODS via POI, XML tree, font specimen, archive contents).*
- [x] SVG image preview — `.svg` files stay editable XML (highlighting + LSP) but gain a rendered-image
      preview in the 3-mode view; edit source → re-render live. Rasterized off-thread via the already-bundled
      JSVG (`PreviewImageLoader.rasterizeSvg`, no new dependency), cached by source hash, zoom re-fits. New
      `editor/SvgImages` (mirrors `MermaidImages`/`DiagramImages` but in-process) + `EditorBuffer.isSvg()`/
      `hasSvgPreview()` + a `scheduleRenderPreview()` branch. On by default; `Settings.svgPreview` (schema
      64→65). *Deferred: export-as-PNG from the preview, checkerboard backdrop for transparent SVGs. First
      of the "reuse-existing-deps" preview cluster — see PDF/Excel/XML-tree/font/archive next.*
- [x] Structured-data preview (JSON/YAML/TOML tree + OpenAPI/Swagger docs) — `.json`/`.yaml`/`.toml` files
      get the 3-mode preview (like Markdown): a collapsible, type-colored data tree rendered off-thread; a
      JSON/YAML doc detected as an OpenAPI 3 / Swagger 2 spec renders as browsable API docs instead
      (endpoints + method badges + params/responses + schemas), with a tree ⇄ docs toggle
      (`structured.toggleView`). New pure `com.editora.structured`
      (`StructuredNode`/`StructuredParser`/`OpenApiModel`/`OpenApiParser`, all Jackson-contained,
      unit-tested) + `editor/StructuredTree`/`OpenApiDoc` (self-scrolling, hosted the CSV-grid way so the
      `TreeView` keeps virtualization). Render-branch model (no coordinator, like Markwhen); 50k-node cap.
      On by default; `Settings.structuredPreview` (schema 63→64). Adds jackson-dataformat-yaml + snakeyaml
      2.4 (both real JPMS modules, no moditect). *Deferred: GeoJSON map (offline vector plot),
      jump-from-tree-to-source, per-file view persistence, expand/collapse-all, editing.*
- [x] Diagram-as-code preview (Graphviz DOT + PlantUML) — `.dot`/`.gv` and `.puml`/`.plantuml` files get
      the same 3-mode preview as Markdown/Mermaid, rendered off-thread via the external `dot`/`plantuml`
      CLIs (both PNG-native, no headless browser) and cached by source hash; zoom resizes the image and a
      diagram exports to SVG/PNG/PDF. New generic, Mermaid-independent seam: pure `com.editora.diagram`
      (`DiagramKind`/`DiagramRenderer`/`DiagramService`) + `editor/DiagramImages` (async cache mirroring
      `MermaidImages`) + `ui/DiagramCoordinator`. Authored `dot`/`plantuml` TextMate grammars +
      `LanguageRegistry`/`GrammarRegistry`/`Commenter`/`FoldRegions` entries. On by default, self-gating on
      detection; `Settings.diagramSupport`/`dotPath`/`plantumlPath` (schema 62→63). *Deferred: PlantUML
      `@start/@end` folding, a themed (dark) render, live linting, and the browser-raster tools (D2,
      WaveDrom) — next up in the diagram-as-code section.*
- [x] Typst document preview — standalone `.typ` files get the same 3-mode preview as Markdown, rendered
      off-thread via the external `typst` CLI as a **multi-page** stack (one image per page). Its own seam
      (not a `DiagramKind`, since a document paginates): `com.editora.typst`
      (`TypstRenderer`/`TypstService`) + `editor/TypstImages` (async page-list cache with retain-last-image
      so live editing doesn't flicker) + `ui/TypstCoordinator`. Compiled with `--root` = the file's folder
      so relative `#image`/`#import` resolve. Native PDF export + PNG/SVG + paginated print. Authored
      `source.typst` grammar + `LanguageRegistry`/`GrammarRegistry`/`Commenter`/`FoldRegions`/`FileIcons`
      entries. On by default, self-gating on detection; `Settings.typstSupport`/`typstPath` (schema 66→67).
      **Editing parity with Markdown:** Enter list continuation (`-`/`+`/`N.`) + empty-marker exit/backspace,
      the floating selection format bar (bold `*`/emph `_`/raw `` ` ``/link/bullet/heading), right-click
      Format items, and `typst.*` palette commands — via the pure `com.editora.typst.TypstMarkup` (headings
      `=`, `#link("url")[…]`, list markers excluding `*`), reusing the shared inline-wrap/bullet cores.
      Preview is gated `!hugeFile` and caps the stacked pages at 40 (a "… N more pages" note; export/print
      use all). **Multi-file projects resolve:** the throwaway input is written in the file's own folder (so
      relative `#image`/`#import` resolve as on disk) and `--root` is the nearest `typst.toml` ancestor, else
      the active Editora project root when the file is inside it, else the file's folder (`RootResolver`, the
      LSP idiom; injected via `EditorBuffer.setTypstRootResolver` + `TypstCoordinator`) — so a doc deep in a
      project can `#import "/template.typ"` above its folder. Local files only — a remote/SFTP doc falls back
      to an isolated temp root, so a self-contained one still renders. **Inherent typst constraints
      (documented, not bugs):** `@preview` packages need a network fetch (offline → the doc errors in the
      preview); refs that escape the resolved root are blocked by typst's sandbox; an untitled buffer can't
      resolve any relative ref; uninstalled fonts fall back. **Editor conveniences shipped:** bundled
      snippets, `#table` insert (grid picker), `#outline()`/TOC insert, image paste/drop/drag → `assets/` +
      `#image("…")`, and preview → PNG/SVG export (native `typst -f`). **In-app typst-CLI installer shipped:**
      Settings → Typst **Install…** (+ `install.typstCli`) downloads the typst binary from GitHub releases
      (added `.tar.xz` extraction via `tar -xf`; archive id `typst-cli` distinct from the tinymist server's
      `typst`), applies it to `Settings.typstPath`, and re-detects — end-to-end verified against typst 0.15.
      *Deferred: a ppi/quality setting; **click-in-preview → jump-to-source** (infeasible on the raster
      preview — needs tinymist's websocket preview protocol + a `javafx.web` host Editora doesn't ship); and
      the generic "CLI → paginated image" seam (gnuplot, asciidoc-via-PDF).*
- [x] Typst language server (tinymist) — the 22nd LSP server: completion, hover, go-to-definition,
      find-references, document-symbol outline, push diagnostics (Problems window + squiggles), and Format
      Document for `.typ` files, complementing the rendered preview. One `ServerDef` in `LspServerRegistry`
      (`tinymist lsp`, root markers `typst.toml`/`.git`) + the served `typst` language id + a Settings
      enable/command pair (data-driven LSP page) + an install-catalog binary archive entry (tinymist GitHub
      releases) so Settings → LSP + the editor banner offer a one-click install. Live-verified: `tinymist lsp`
      answers `initialize` advertising completion/hover/definition/references/symbols/formatting/semantic
      tokens (tinymist 0.15.2). `Settings.typstLspEnabled`/`typstLspCommand` (schema 72→73). Supersedes the
      Typst preview's deferred "live linting / autocomplete". *Deferred: bundled `snippets/typst.json`.*
- [x] CSV/TSV grid preview moved into the editor as an IntelliJ-style Editor/Split/Preview view (mirroring
      the Markdown preview), replacing the bottom `csvGrid` tool window. The floating top-right toggle drives
      Editor (source), Split (source + live grid), and Preview (grid only); the mode is remembered per file.
      The grid keeps everything — header-row toggle, type profiler, sort/filter, ragged-row diagnostics,
      in-cell editing, click-to-jump, and PDF/Excel/ODS/print exports. Reuses `EditorBuffer`'s existing
      `MarkdownViewMode` machinery (a CSV buffer now reports `hasPreview()` once its grid is injected via
      `setCsvPreviewNode`); one `CsvGridPanel` per buffer; the `tool.csvGrid` command was retired.
      *Deferred: editor↔grid scroll-sync in Split (the grid scrolls independently; click-to-jump bridges it).*
- [x] Maven support — a toolbar icon (shown only when a pom.xml is detected for the active file/project)
      opens an IntelliJ-style actions popup: the standard lifecycle phases, the pom's declared profiles
      (checkable, composing with a run via `-P<id>`, marking any `activeByDefault` one), and each declared
      plugin's explicitly-bound `<executions>` goals as `<prefix>:<goal>` rows via Maven's own plugin-prefix
      convention — plus a "Run custom goal(s)…" freeform prompt. New pure `com.editora.maven` package
      (`PomModel`/`PomParser`/`MavenPluginPrefix`/`MavenLifecycle`/`MavenExecutable`/`MavenArgs`) parses
      pom.xml directly with the JDK's own XXE-hardened DOM parser (no new dependency, no
      `mvn help:effective-pom` shell-out); profile-scoped plugins nest under their own profile once
      checked. `MavenService` mirrors `RunService`'s streaming shape; runs prefer the project's `./mvnw`
      wrapper, falling back to `mvn` on PATH (or a Settings override), streaming to a default-hidden Maven
      console tool window. `Settings.mavenSupport` (default on, schema 61→62) + Simple-Mode/remote gating;
      also adds `OverlayHost.showBelow(...)` (mirroring `positionAbove`) for anchoring a popup below a
      top-of-window toolbar button. *Deferred: full effective-pom resolution (default-lifecycle bindings,
      parent inheritance, `<pluginManagement>`), a persistent run-configuration list.*
- [x] Build-tool framework + npm — generalized the Maven integration into one tool-agnostic
      `com.editora.build` framework (`BuildTool` enum, `BuildActionsProvider`/`BuildAction` model,
      `BuildService`, `BuildExecutable`, `OutputStyle`) driving a reusable `ui/BuildCoordinator` (one instance
      per tool: its own toolbar button, actions popup, streaming console, and `<tool>.*` commands). Maven was
      refactored onto it with no behavior change (the pure `maven/PomParser`/`PomModel`/… stay), and **npm**
      was added: a toolbar icon (shown only when a `package.json` is detected) whose popup lists every
      `scripts` entry (run portably as `<pm> run <name>`) plus common tasks (`install`; `ci` for npm), using
      the package manager detected from the `packageManager` field or the lockfile (npm/yarn/pnpm/bun). Pure
      `NpmProject`/`NpmPackageManager`/`NpmActionsProvider` parse `package.json` with the existing Jackson
      (no new dependency, no `module-info` change). `Settings.npmSupport`/`npmCommand` (default on, schema
      65→66); the Settings → Languages & Tools → **Build Tools** page is data-driven (one section per tool);
      palette gating is generic (per-tool ids). Adding the next tool (Cargo/Go/Gradle) is a new `BuildTool`
      constant + provider + icon + Settings fields + i18n — no `MainController`/coordinator change.
      *Deferred: Cargo, Go, Gradle (each its own follow-up); clickable console links stay Maven/JVM-only.*
- [x] Cargo, Go, and Gradle build tools — three new `BuildTool` constants on the framework, no
      `MainController`/coordinator change. **Cargo** (`Cargo.toml` via `TomlMapper`): the standard subcommands
      (build/run/test/check/clean/doc/bench/update/clippy/fmt) + additive `run --bin X`/`run --example Y` from
      `[[bin]]`/`[[example]]` (a virtual `[workspace]` → static only) + a `--release` `Toggle`; runs `cargo`.
      **Go** (`go.mod`/`go.work`): static subcommands over the whole module (`build ./...`, `test ./...`, `vet`,
      `fmt`, `mod tidy`/`download`, `generate`, `clean`, `install`); the `go.mod` module line is the label;
      runs `go`. **Gradle** (`build.gradle[.kts]`/`settings.gradle[.kts]`): the DSL can't be statically parsed,
      so static common tasks (build/clean/test/assemble/check/jar/run/bootRun) + the framework's "Run custom…"
      + an on-demand **Load all tasks…** popup action that runs `gradle tasks --all` on a short-lived process
      (pure `GradleTasks.parse`) and repopulates the popup in place (a new `BuildActionsProvider.addLoadedTasks`
      hook + a non-closing secondary popup action + `BuildTool.taskLoadLabel()`/`loadTasks()`); prefers the
      project's `./gradlew` wrapper, else `gradle`. Pure `CargoProject`/`CargoActionsProvider`/`GoProject`/
      `GoActionsProvider`/`GradleTasks`/`GradleActionsProvider` (all unit-tested). `Settings.{cargo,go,gradle}
      Support/Command` (default on, schema 66→67→68→69, additive-identity); Icons vendored from Simple Icons
      (Rust/Go/Gradle, CC0). **No new dependency / `module-info` change** (Cargo rides the existing TOML mapper;
      Go/Gradle are static + a regex). *Deferred: a `--release`-only subset for Cargo (the global toggle can
      combine with `fmt`/`update`); a per-root cache for Gradle's loaded tasks; PlantUML-style block folding;
      clickable console links stay Maven/JVM-only.*
- [x] Clickable links in the Markdown preview — a rendered link shows a hand cursor and opens in the
      system default browser on click (previously inert). `MarkdownRenderer.renderDocument` gained an
      overload taking an optional click handler, threaded through the block/inline render chain
      alongside `baseDir`; only the live interactive preview wires one — print/PDF and the
      hover/completion-doc popups keep the no-handler path.
- [x] Auto Close Tags (VS Code parity) — typing the `>` completing an HTML/XML open tag inserts
      `</name>` after the caret. Pure/unit-tested `editops/TagAutoClose` (one forward pass over a
      bounded pre-caret window; quote state tracked only inside tags so apostrophes in text and
      `>`/`<` inside closed attribute strings can't derail it; skips closers, `/>`, doctype/comment/PI,
      HTML void elements). Wired into `EditorBuffer.applyAutoCloseTyped` so the live key filter and
      macro replay share it; `Settings.autoCloseTags` (default on, schema 51→52) + Settings → Editor
      checkbox + palette `view.toggleAutoCloseTags`.
      *Deferred: `</` closing-tag name completion, JSX/TSX.*
- [x] Auto Rename Tag (VS Code parity) — editing an HTML/XML tag name mirrors the rename onto the
      paired open/close tag, per keystroke. Pure/unit-tested `editops/TagRename`: the pre-edit (old)
      name is reconstructed by reverting the change, then the pair is found by same-name depth counting
      over a single forward lex — only old-name tags participate, so real-world HTML's unclosed
      optional-close tags (`<li>`/`<p>`/…) can't misalign the match (v1 paired positionally and any
      unclosed tag suppressed the mirror) — comments/CDATA/doctype/PI/quoted attrs/self-closing skipped,
      HTML void + raw-text elements handled; half-typed new tags never rename the wrong closer.
      Wired in `EditorBuffer` on the immediate `plainTextChanges` pulse (html/xml only, off in
      large/huge files, suppressed during undo/redo); `Settings.autoRenameTag` (default on, schema
      50→51) + Settings → Editor checkbox + palette `view.toggleAutoRenameTag`.
      *Deferred: JSX/TSX tags, surviving a fully-emptied tag name (retyping from `<>` loses the link),
      single-undo-step mirroring (the user edit + mirror are two undo entries).*
- [x] String manipulation commands (the JetBrains String-Manipulation plugin family, P1) — case-style
      conversions on the selection/identifier at the caret (camelCase/PascalCase/snake_case/
      SCREAMING_SNAKE_CASE/kebab-case/dot.case + Cycle Case Style + Swap Case) and whole-line transforms
      on the selection/whole file (sort asc/desc — natural numeric order, case-insensitive — sort by
      length, reverse, shuffle, remove duplicate/empty lines, trim trailing whitespace). Each its own
      palette command; one filterable picker `Edit: String Manipulation…` (`C-c x`). Pure/unit-tested
      `editops/StringCase` + `editops/LineTransforms`; single undoable edit, result re-selected.
      *Deferred (P2/P3): escape/unescape (Java/JSON/XML/HTML) + URL/Base64/unicode-escape encode/decode +
      diacritics→ASCII, align-by-separator, keep/remove lines matching a pattern, increment numbers /
      sequences across multi-carets, multi-caret fan-out.*
- [x] Plain-text developer formats — syntax highlighting for unified diffs (`.diff`/`.patch`, green/red
      added/removed lines via new `.text.diff-*` semantic classes), Makefile (`Makefile`/`GNUmakefile`/`.mk`),
      justfile, Protocol Buffers (`.proto`), GraphQL (`.graphql`/`.gql`), and `.gitattributes`
      (+ `.git/info/attributes`); a dedicated Java `.properties` grammar (Unicode escapes, `:` separator,
      `\`-continuations) replaces the INI borrow. Proto/GraphQL fold + auto-indent as brace languages;
      comment toggling wired for all. Grammars vendored MIT from shiki tm-grammars (diff/make/proto/graphql/
      just); properties/gitattributes written in-house.
      *Deferred: LSP servers (buf / graphql-lsp), Makefile forced-tab indent for brand-new files (existing
      files detect tabs).*
- [x] "Open this .patch in the diff viewer" — a right-click "Open in Diff Viewer" item (shown only for a
      `.patch`/`.diff` file) parses the buffer's own unified-diff text and opens its first file-section as a
      read-only structured diff (side-by-side, word-level highlighting, prev/next-change nav) instead of just
      syntax-highlighted diff text. New pure/unit-tested `diff/PatchParser` (the reverse of `PatchWriter`)
      reconstructs each file's old/new line sequences — tolerant of a bare `diff -u` file, a git `diff --git`
      preamble, several files back to back, `/dev/null` add/delete sides, and a "\ No newline at end of file"
      marker — then feeds them straight back into the existing `DiffEngine` pipeline. Palette-only
      `diff.openPatchFile`; a multi-file patch shows a status note and just the first file (v1 scope).
- [x] Sample corpus for manual feature testing — a curated, feature-organized `samples/` folder (syntax per
      language, folding, indent, markdown, mermaid, todo, spell, search, editorconfig, http, log, diff,
      encodings) with a `README.md` manifest. `SamplesCorpusTest` guards manifest↔files sync + core-language
      coverage; large/perf inputs are generated by `java scripts/GenSamples.java` (git-ignored); a scoped
      `.gitattributes` preserves the encoding/EOL/conflict-marker bytes.
      *Deferred: a richer perf-generator, optional per-feature automated assertions beyond the manifest guard*
- [x] Server log viewer — `.log` files get level highlighting (ERROR/WARN/INFO/DEBUG/TRACE, inline +
      size-independent left-edge bar), **Follow tail** (`tail -f`, floating toggle, auto-scroll), **open-the-
      tail** for huge logs (read-only, last N MB), and **live level + regex filtering** (filter-as-you-type;
      regex with a literal-substring fallback; stack traces inherit their record's level). Detects
      Logback/Log4j/JUL/syslog/nginx/structured/zerolog + access-log status. Logs open in View mode. On by
      default (Settings → Editor → Logs; `View: Toggle Log Viewer`); palette `Log: Toggle Follow` /
      `Filter by Level` / `Filter by Pattern` / `Clear Filter` / `View as Log`.
      *Deferred: tailing remote (SFTP) logs, multi-file merged tail, a dedicated Logs tool window,
      timestamp-range filtering, jump-to-next-error navigation*
- [x] Markdown support improvements — preview **CommonMark extensions** (YAML front matter, footnotes,
      heading anchors, `++inserted++`); **heading outline** in the Structure tool window; **Markdown lint**
      (squiggles + tool window; `View: Toggle Markdown Lint`, on by default — now a wider markdownlint rule
      set with per-rule config, inline `markdownlint-disable` comments, `.markdownlint.json` discovery,
      scrollbar/minimap overview stripes, and `Markdown Lint: Fix Issues` auto-fix); **image paste & drag-drop**
      (into a sibling `assets/` folder); **smart link paste** (URL over a selection → `[sel](url)`); **table
      cell navigation** (Tab/Shift-Tab between cells, Enter adds a row, reflow); **LaTeX math** via
      JLaTeXMath (inline `$…$` + display `$$…$$` in the preview and PDF, off by default — `View: Toggle Math
      Rendering`); and **Export to HTML** (`Preview: Export to HTML`, standalone self-contained file).
      *Deferred: inline-math in PDF/print, live Mermaid in exported HTML, full base64 image embedding in HTML*
- [x] TODO / highlight patterns (IntelliJ-style) — configurable regex patterns (TODO + FIXME by default,
      each with a color) highlighted wherever they match in the editor, and listed in a **TODO** tool
      window (`M-g o`) grouped by file (scans the open project's tree, else the open files; double-click to
      jump). On by default; add/edit/remove patterns in Settings → Editor → TODO Highlighting; commands
      `tool.todo` / `todo.refresh` / `todo.addPattern` / `view.toggleTodoHighlight`
- [x] Global indent-style preference — Settings → Editor → "Indent style" + **Editor: Set Indent Style…**
      palette command: force Spaces or Tabs for Tab/Enter, or keep Detect (per-file auto-detection).
      A file's `.editorconfig` `indent_style` wins; the global pref is the fallback above per-file detect
- [x] EditorConfig (`.editorconfig`) — resolves the nearest config chain (nearest-dir-wins, up to `root`)
      and applies indent style/size + `tab_width`, `end_of_line`, `charset` (utf-8/utf-8-bom/latin1/
      utf-16le/be, round-tripped on read & save), `max_line_length` (column ruler), and on-save
      `trim_trailing_whitespace` / `insert_final_newline`. Glob sections (`*` `**` `?` `[seq]` `{a,b}`
      `{n1..n2}`). On by default; Settings → Editor + **View: Toggle EditorConfig**. Local files only
- [x] AI inline completion — ghost-text continuations at the caret after a ~600 ms pause (Tab accepts),
      riding the existing prose-ghost presentation; windowed prefix/suffix prompt, stop-at-newline,
      128-token cap, generation-guarded (typing supersedes the in-flight request); its own fast model
      (default `claude-haiku-4-5`). Off by default under Settings → AI Actions. *(Next: multi-line
      ghost rendering, accept-word-by-word, per-language enable list.)*
- [x] AI connection status — a live green/red health check on Settings → AI Actions (and palette
      AI: Test Connection): a tiny one-token ping to the configured provider/endpoint/key/model,
      re-checked on page-open and debounced on edits. Surfaces a wrong endpoint / bad key / unknown
      model immediately. Mirrors the Git/Mermaid/LSP found-not-found status idiom.
- [x] Local LLM support (LM Studio / Ollama / vLLM) — an OpenAI-compatible provider for every AI feature
      (actions + inline completion): AiProvider enum + OpenAiSse reader (data-only SSE, `[DONE]`,
      `choices[].delta.content`, `finish_reason`→Anthropic stops), provider-aware AiClient headers/body,
      Settings.aiProvider/aiEndpoint (default LM Studio localhost, no key). Palette AI: Set Provider /
      Set Endpoint. *(Next: base/FIM completion models, per-request timeout tuning, model-list picker.)*
- [x] AI actions (direct Anthropic API) — streamed one-shot features over `java.net.http` (no SDK, no new
      dependency): commit-message generation from the staged diff (into the Commit window), explain
      selection (into a Markdown buffer), rewrite selection per instruction (one undoable edit, aborts if
      the buffer changed). Off by default (Settings → AI Actions); model + API-key settings with palette
      parity. *(Next: inline ghost-text completion (Tier 4), diff-preview for rewrites, keychain key
      storage, token/cost readout.)*
- [x] Embedded AI agent (ACP) — an Agent Client Protocol client (newline-delimited JSON-RPC over stdio)
      driving a user-installed agent (default: Claude Code via `claude-code-acp`) in an **AI Agent** chat
      tool window; fs reads serve open buffers' live text, fs writes apply as undoable buffer edits,
      permission requests pop a dialog. Off by default (Settings → AI Agent). No new dependency.
      *(Next: rich transcript (markdown/tool-call cards), diff-preview for edits, session persistence,
      agent-side MCP server pass-through, multiple sessions.)*
- [x] MCP server — a minimal Model Context Protocol server (loopback HTTP + bearer-token auth) embedded in
      the editor, exposing live state + the command registry to an LLM agent (twelve tools, incl. undoable
      buffer edits + save + open/navigate + symbols + git status); off by default
      behind a security notice (Settings → MCP Server). No new dependency
- [x] Local file history — IntelliJ-style snapshots of local files on save / auto-save / before an
      external reload, independent of any VCS; a **File History** tool window (`M-g l`) lists revisions
      (date/time, reason, size; latest tagged *Current*), double-click for a read-only diff vs current,
      restore = undoable whole-file replace. Gzip'd content-addressed blobs under `<configDir>/history/`,
      deduped, configurable retention. On by default; local-only; off in Simple UI
- [x] Emacs fill commands — Fill Paragraph (`M-q`), Fill Region, Set Fill Column (`C-x f`): re-wrap to a
      fill column, preserving indentation + an adaptive fill prefix (line comments, `>` quotes, Javadoc `*`)
- [x] LSP Format Document — whole-file reformat via the language server (palette + editor right-click),
      undoable, when the server advertises formatting
- [x] File-type icons — per-type glyphs everywhere a file is listed (tabs, Project tree, pickers, Switcher,
      finders); plus a "Current Folder" Project explorer when no project is open
- [x] Plugin support + a signed plugin registry — extend Editora via a Java SPI or a declarative
      `plugin.json` (commands, keybindings, tool windows, editor menu items, status-bar segments;
      snippets/templates). Off by default, full-trust, loaded via a child `URLClassLoader` so the same jar
      works in dev and the packaged installers. **Browse & install** from a curated GitHub registry or a
      local `.zip`; **19 plugins published** (text/encode/hash/json-xml/slug/box, UUID-timestamp inserters,
      markdown-TOC, formatter, open-on-GitHub, reveal/terminal, scratchpad, regex-tester, color-picker,
      word-count, calculator, task-runner, lorem-ipsum). **Security:** the index is verified against a
      bundled Ed25519 signature (*Require signed plugins*, default on), downloads are SHA-256-verified over
      HTTPS with bounded reads, and a capability-disclosure confirm is shown before enabling. See
      `docs/plugins.md`
- [x] Git history, blame & stash (IntelliJ/VSCode parity) — a **Git Log** tool window (`M-g h` / *Show File
      History*): browse commits, see a commit's files, double-click for a read-only diff, right-click to
      Copy Hash / Checkout / Reset / Revert / Cherry-Pick / New Branch. **Inline blame** (`M-g a`,
      GitLens-style "author, time ago • summary" on the current line; off by default). **Stash**
      push/pop/apply/drop (palette + branch dropdown). All Git-gated (off in Simple UI mode)
- [x] Simple UI mode — a one-toggle minimal layout (toolbar icon, **View: Toggle Simple UI Mode**,
      Settings → Application, or `--simple`): hides the extra toolbar groups (new-from-template, recent,
      find-in-files, split, project selector), the tool-window stripe, breadcrumb, the entire gutter
      (collapsed regions unfolded first), minimap, and most status-bar segments — keeping tabs, the core
      toolbar icons (incl. **Open**), and echo/read-only/zoom/Ln-Col/file-size. Also disables the heavier features
      (LSP, debugging, HTTP client, Git, multiple cursors / column selection). Persisted; `--simple` is a
      session-only override; saved preferences are untouched and restored on exit
- [x] Remote file access (SFTP) — connect over SSH/SFTP and edit a server's files as if local: the remote
      folder mounts in the Project tool window, open/edit/save go straight over SFTP, saved connections
      (metadata only) reconnect via a picker, a Remote Sites tool window (M-g r), a Settings → Remote
      management page, or a Welcome-page quick-connect list; local-process features (LSP/DAP/Git/Run/HTTP)
      auto-disable for remote files. Off by default; built on Apache MINA SSHD (Remote: Connect / Saved
      Connections / Manage Remote Sites / Open File / Disconnect)
- [x] HTTP Client (`.http`/`.rest` files) — a green ▶ on every request runs it with the built-in JDK
      HTTP client; response (status/headers/pretty-JSON/timing) in an HTTP Client tool window (`M-0`) with a
      highlighted viewer, history, Copy/Import as cURL. Near IntelliJ parity: `{{var}}`/`@var` + dynamic vars
      (`$random`/`$datetime` with date math/`$dotenv`), request chaining, multipart + external-file bodies,
      environment files (`http-client.env.json` + `$shared`), Basic/Digest auth, auto URL-encoding,
      response-to-file, per-request directives, run-whole-file
- [x] HTML Live Preview — a floating browser icon on `.html`/`.htm`/`.xhtml` files opens them in a detected
      browser (Safari/Chrome/Firefox/Edge/system default) served over a loopback JDK `HttpServer`, with
      live-as-you-type reload (assets load from disk; the page from the live buffer text); off by default
      (Settings → HTML Preview); `htmlPreview.open` / `htmlPreview.openIn` / `view.toggleHtmlPreview`
- [x] File templates — "New File From Template" (`C-c C-n`): single- or multi-file templates with a
      `${var}` wizard and `$0`/`${cursor}` placeholders; bundled (Java class, HTML page/bundle, Markdown,
      Python) + user templates in `~/.editora/templates/`
- [x] Debugging (DAP) — full debugger for **Java** (java-debug over jdtls), **Python** (debugpy), and
      **JavaScript/Node** (vscode-js-debug): breakpoints (conditional/logpoints), step/resume/pause/
      run-to-cursor/jump-to-line, call stack + variables + watches + set-value, inline values + hover, an
      IntelliJ-style Debug tool window (`M-g d`); off by default
- [x] Diff viewer & merge — side-by-side / unified diff (vs HEAD, a commit, or another file) with
      word-level highlights, prev/next nav, apply-hunk / apply-all (undoable), live refresh, patch export;
      a merge-conflict resolver (accept ours/theirs/both)
- [x] Multiple cursors & column/box selection — VS Code–style multi-caret editing (add caret at next
      occurrence / above / below) + Alt-drag column selection, via the personal RichTextFX fork
- [x] LSP support — **21 language servers** auto-detected on PATH (per-server Settings command + enable,
      off by default): Java (JDT LS), TypeScript/JavaScript, Python (Pyright), XML (lemminx), JSON,
      Bash/Shell, YAML, Go (gopls), Rust (rust-analyzer), PHP (phpactor), Ruby (ruby-lsp), C/C++ (clangd),
      C# (csharp-ls), HTML, CSS, Kotlin, Lua, Dockerfile, SQL (sqls), Terraform (terraform-ls), TOML (taplo) —
      diagnostics + Problems window (`M-8`) + minimap/scrollbar stripes, go-to-definition (`M-.`),
      find references (`M-?`), hover (`C-c h`), LSP completion, and TS/PHP auto-imports
- [x] Markdown editing — IntelliJ-style floating format bar on selection (bold/italic/strikethrough/code/
      link/list + Normal–H1…H6), `C-c`-prefixed shortcuts + right-click Format menu; smart list/blockquote
      continuation on Enter, heading promote/demote, link helpers (Ctrl/Cmd-click to open), GFM table reflow
      + cell navigation (Tab/Shift-Tab, Enter adds a row), image paste/drag-drop, and smart link paste
- [x] Run a file from a gutter ▶ — Java 25 compact source (`java <file>`), Python (`python3`), and shell
      (`bash`, when the Bash LSP is enabled); streams output into a Run tool window (`M-9`); gated by LSP
- [x] Print — native printing of code or the Markdown preview with a print-preview window (always light),
      reusing the PDF layout core (Settings → Editor → Export & Print); `editor.print` / `preview.print`
- [x] Export to PDF — code (searchable, embedded font, syntax highlighting + optional line numbers,
      always light theme), Markdown (native vector text), and standalone Mermaid `.mmd` (via mmdc);
      `editor.exportPdf` / `preview.exportPdf`; Settings → Editor (line numbers / highlighting / page size)
- [x] Mermaid diagrams — `.mmd` files + ` ```mermaid ` blocks in the preview (mmdc), export to SVG/PNG/PDF,
      live `maid` linting (squiggles), keyword + snippet autocomplete (Settings → Mermaid, off by default)
- [x] Welcome page — VSCode-style editor-area empty state (New File / Open File / recent) shown when no
      files are open, replacing the empty Untitled buffer; `--new-file[=name]` bypass
- [x] UI localization (i18n) — interface translated to English, Italian, Spanish, French, Portuguese,
      German; language picker in Settings → Appearance (applies on restart); key-parity test
- [x] Settings window redesign — sidebar categories, search, live preview, Reset to Defaults; Tool
      Windows + About moved out
- [x] Git support — native CLI: status-bar branch + ahead/behind, gutter change bars vs HEAD, Git tool
      window (stage/unstage/discard/commit), and fetch/pull/push + branch switch/create commands
- [x] Personal Notes — file-attached annotations (word/line/range/file scope, body/tags/status),
      content-hash + path identity (survive rename/move), gutter + highlight + hover indicators,
      tool window (`M-5`), `M-g n` jump, JSON export, per-project `notes.json`
- [x] Bookmarks — per-project, gutter markers + notes, tool window (filter, reorder via Alt+Up/Down /
      menu / drag-and-drop), `M-g b` cross-file jump picker, stored in `bookmarks.json`
- [x] Markdown preview — IntelliJ-style Editor / Split / Preview, live + off-thread, Ctrl+wheel zoom
- [x] Read-only / View mode (`C-x C-q`) — with "View Mode" banner and Space/Backspace paging
- [x] Projects — single-folder workspaces, per-project session + bookmarks
- [x] Switcher — open-files popup in tab order
- [x] Tool windows (Project, Structure, Bookmarks, File Information) + focused-window highlight
- [x] Zen mode + floating "Z" exit button
- [x] Navigation key hints in the Command Palette, Jump-to pickers, and file finder
- [x] Recent files, editor themes, text zoom
- [x] Snippets — VS Code/TextMate syntax, bundled for all 21 languages + user overrides
- [x] `--dev` mode (isolated `~/.editora-dev`), `--config-dir` / `EDITORA_CONFIG_DIR`, CLI file/project/zen args

## Editing
- [x] Smart backspace — clear the indent in one press / jump back on a blank auto-indented line
- [x] Auto indent
- [x] Smart indentation
- [x] Language indentation aware for the 21 languages we support
- [x] Autoclose `()[]{}` and quotes
- [x] Highlight matching braces
- [x] Comment/uncomment code region
- [x] Fill paragraph/region (Emacs `M-q` / Fill Region / `C-x f` set fill column) — re-wrap to a fill
      column, preserving indentation + an adaptive fill prefix (line comments, `>` quotes, Javadoc `*`)
- [x] Smart line start (`C-a`) — first press to the first non-whitespace, second toggles to column 0
- [x] Markdown formatting — format bar + smart list/heading/link/table editing (see "Recently shipped")
- [x] Format document — **LSP: Format Document** reformats the whole file via the language server
      (`textDocument/formatting`, when it advertises formatting), undoable; palette + editor right-click.
      (GFM table reflow also exists.)
- [x] Column select support — column/block selection (overlay + column-aware edits)
- [x] Multiple cursors support — VS Code–style multi-caret (add at next occurrence / above / below) +
      Alt-drag column selection (personal RichTextFX fork); see "Recently shipped"
- [x] Advanced Undo/Redo support — word/line-level undo coalescing (boundary + idle breaks) **and** an
      Undo History tool window (`M-g u`: in-session checkpoints, jump-to-restore). *The undo tree (branch
      recovery) was deliberately not pursued — it would mean replacing RichTextFX's linear undo engine for a
      niche payoff; revisit if there's demand.*
- [x] Spell check support — Lucene Hunspell, red squiggles, suggestions, user dictionary, en_US/en_GB; bundled technical-terms dictionary (toggle in Settings)
- [x] Private comments/notes — see **Personal Notes** under "Recently shipped"

## Search
- [x] Incremental Search — find bar searches as you type (debounced), jumps to the nearest match
- [x] Regex search — regex + case-sensitive + whole-word toggles in the find bar
- [x] Multi-file search — Find in Files (`C-S-f`): project + open buffers, off-thread, with replace-in-files
- [x] Search results panel — Search Results tool window (`M-6`), grouped by file, Enter/double-click to jump
- [x] Find in Files extras — include/exclude globs, query history (editable combo), regex `$1` replace, ripgrep badge, bold file names, right-side default
- [x] Highlight all matches — every match highlighted live in the editor (current one accented)
- [x] AceJump support — `M-g j`: type a char, then a label, to jump the caret to any on-screen occurrence

## Code intelligence
- [x] Autocomplete support — code: snippet popup (Enter/Tab); prose: inline ghost text (Tab); auto +
      `C-M-i`/`M-/` trigger; Settings toggle. (Next: document-words, LSP, fuzzy matching.)
- [x] LSP support — **21 servers** (see "Recently shipped"): diagnostics + Problems window (`M-8`) +
      minimap/scrollbar stripes, go-to-definition (`M-.`), find references (`M-?`), hover (`C-c h`),
      LSP-backed completion, TS/PHP auto-imports, and **Format Document** (whole-file reformat).
      Server-centric registry, per-server Settings, off by default. Document symbols power the Structure
      tool window. (Next: format-on-save; rename, code actions, quick fixes.)
- [x] Fix structure for the 21 languages we support — the Structure tool window now builds from the
      language server's `textDocument/documentSymbol` (precise hierarchy, real kinds, per-kind icons,
      method signatures), with the fold-region/TextMate heuristic as the fallback for non-LSP files;
      sort (Position/Name/Kind) + kind filter, expanded by default
- [x] Multi language support — UI string translation (en/it/es/fr/pt/de); see "UI localization (i18n)"
      under "Recently shipped"

## Snippets
- [x] GUI for Snippet management — Settings → Snippets: a master-detail editor (language picker +
      per-language user-snippet list + name/trigger/description/body form), saving to
      `<configDir>/snippets/<lang>.json`. Palette `Snippets: Manage Snippets…` (`snippets.manage`)

## Files & version control
- [x] Git support — native CLI (branch/status, gutter change bars, commit workflow, fetch/pull/push)
- [x] Diff viewer + merge-conflict UI — side-by-side / unified diff (vs HEAD / commit / another file),
      word-level highlights, apply-hunk / apply-all, patch export, merge-conflict resolver
- [x] Local file history — IntelliJ-style snapshots on save / auto-save / before an external reload; a
      **File History** tool window (`M-g l`) lists revisions (date/time, reason, size; latest tagged
      *Current*), double-click for a read-only diff vs current, restore = undoable whole-file replace.
      Gzip'd content-addressed blobs + a per-project index under `<configDir>/history/`, deduped, with
      configurable retention (revisions/file, age, size/project). On by default; local-only; off in Simple UI
- [x] Detect external file changes — prompt to reload when a file changes on disk (focus-regain / tab switch)
- [x] Auto-reload modified files
- [x] Remote file editing support — SSH/SFTP: browse/open/edit/save remote files; saved connections
      (metadata only); local-process features auto-disable for remote (see "Recently shipped")
- [x] Log mode support

## Keybindings
- [x] Complete emacs movement/text manipulation keybindings — backward-kill-word (`M-DEL`),
      upcase/downcase/capitalize-word + region (`M-u`/`M-l`/`M-c`, `C-x C-u`/`C-x C-l`), join-line (`M-^`),
      delete-horizontal-space (`M-\`), just-one-space (`M-SPC`), delete-blank-lines (`C-x C-o`),
      open-line (`C-o`), kill-whole-line (`C-S-DEL`), zap-to-char (`M-z`), forward/backward-sexp +
      mark/kill-sexp (`C-M-f`/`C-M-b`/`C-M-SPC`/`C-M-k`), beginning/end-of-defun (`C-M-a`/`C-M-e`),
      mark-paragraph (`M-h`), mark-whole-buffer (`C-x h`), move-to-window-line (`M-r`). *Kill ring
      (yank-pop / consecutive-kill accumulation) still deferred.*
- [x] Fully configurable shortcuts — keybinding editor in Settings → Keymaps: searchable command list,
      multi-key chord recorder, conflict warnings, per-command + global reset; live (no restart), persisted
      as overrides on top of the active keymap theme
- [x] Keybinding themes — switchable in Settings → Keymaps / `keymap.select`, live (no restart), per-OS
      (Ctrl vs Cmd): **Emacs** (default), **CUA**, **Sublime Text**, **VSCode**, **IntelliJ IDEA**
- [ ] Vim keybindings (modal — needs a mode state machine: normal/insert/visual, operators, counts,
      registers, `:` command line; deferred as its own feature)
- [x] Standard accelerator commands — `edit.selectAll` / `edit.duplicateLine` / `edit.moveLineUp` /
      `edit.moveLineDown`, bound in the CUA/Sublime/VSCode/IntelliJ keymaps

## UI / UX
- [ ] UI final touches (fonts, colors, etc.)
- [x] Pretty up Settings Window — sidebar categories, search, live preview, reset
- [x] File-type icons — a per-type glyph (language logos, image/archive/PDF/table/…, generic fallback)
      everywhere a file is listed: tabs, Project tree, Open-Files/Recent pickers, Switcher, file/folder finders
- [x] "Current Folder" explorer — with no project open, the Project tool window roots at the active file's
      folder and follows the focused tab
- [x] Upgrade breadcrumbs support — _partial:_ Reveal in File Manager / Open Terminal Here on a crumb
- [x] Fix Zen mode
- [~] Font ligatures (Fira Code / JetBrains Mono `=>`, `!=`, …) — **not feasible on the current stack.**
      Programming ligatures are OpenType contextual alternates (`calt`), and JavaFX exposes no
      feature-control API (no `-fx-font-feature-settings`, no `Font` method) — it only auto-shapes
      complex scripts, never Latin programming ligatures. Even if it did, RichTextFX's editing model
      maps one char → one glyph cell for caret/selection/hit-testing, which ligature glyph-substitution
      breaks (the caret lands in the wrong column). Would require both JavaFX feature support *and*
      ligature-aware caret math in the fork. Deferred unless JavaFX adds OpenType feature control

## Extensibility & integration
- [x] Plugins/API support — Java SPI (`com.editora.plugin.Plugin`) + declarative `plugin.json`
  (keymap / external commands / snippet & template dirs); contributes commands, keybindings, tool windows,
  editor right-click items, and status-bar segments. The `ActiveEditor` facade does
  `filePath`/`text`/`selectedText`/`caretLine`/`replaceSelection`/`insertAtCaret`/`setText`/`openPath`, and
  `PluginContext` adds `openUrl`/`log`/`setStatus` + path accessors. Off by default (Settings → Plugins).
  Loaded via a child `URLClassLoader` so it works in the sealed jlink installers. **Registry + install:**
  browse a curated GitHub-hosted `index.json`, install (download + SHA-256 verify + zip-slip-guarded unzip)
  or install from a local `.zip`; per-plugin Remove. **19 plugins published** in the
  [adriandeleon/editora-plugins](https://github.com/adriandeleon/editora-plugins) registry (which also
  carries each plugin's source), with 18 worked examples under `examples/`. **Security:** the index is
  verified against a bundled **Ed25519 signature** (`Settings.pluginRequireSignature`, default on, blocks an
  unsigned/unverified registry; sign with `scripts/PluginSigningTool.java`); a **capability-disclosure
  confirm** (jar? external commands? keybinding remaps?) runs before enabling at every arming point; reads
  are size-bounded and a non-default registry host is flagged. See `docs/plugins.md`.
  *Deferred: sandboxing, hot reload, gutter-marker contributions, GitHub-API/per-repo discovery,
  per-plugin/TOFU signing, auto-update.*
- [x] External Tools support
- [x] MCP support — a minimal **Model Context Protocol** server embedded in the editor (loopback HTTP +
      bearer-token auth) so an LLM agent (Claude Code, …) can observe state, edit files, and drive the
      command registry. Twelve tools — reads `list_open_files` / `read_buffer` / `get_selection` /
      `get_diagnostics` / `document_symbols` / `git_status` / `find_in_files` / `list_commands`, writes
      `edit_buffer` (undoable str-replace / whole-buffer edits) / `save_buffer`, actions `open_file`
      (with line:col navigation) / `execute_command`; writes `<configDir>/mcp-endpoint.json` for discovery;
      status-bar indicator (click to copy the connection command). Off by default behind a security-notice
      dialog (Settings → MCP Server; `view.toggleMcp` / `mcp.copyEndpoint`). No new dependency
      (`jdk.httpserver`). (Next: resources/prompts, stdio transport, TODO-scan + open-tabs tools.)

## Packaging
- [ ] Sign native installers
