# Markdown TOC plugin

Adds **Markdown: Insert Table of Contents** — inserts a nested bullet list of anchor links at the caret,
built from the document's ATX headings (`#`…`######`). Skips headings inside fenced code blocks and uses
GitHub-style anchor slugs (with `-1`/`-2` de-duplication).

Pure logic in `Toc`. Build with `./build.sh`; install via *Settings → Plugins → Install from file…* or copy
into `<configDir>/plugins/markdown-toc/`. See [`../../docs/plugins.md`](../../docs/plugins.md).
