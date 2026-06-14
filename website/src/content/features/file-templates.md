---
title: "File templates"
group: "Workspace & files"
order: 4
beta: true
summary: "New File From Template \u2014 single- or multi-file scaffolds with interactive placeholders (author, date, file name, \u2026)."
---

**New File From Template** (`C-c C-n`) scaffolds a file — or a whole set of files — from a reusable template, prompting for any variables (author, date, file name, package…) in a small wizard.

Templates use the same `${var}` / `$0` syntax as snippets; bundled ones cover a Java class, an HTML page/bundle, a Markdown doc, and a Python script. Add your own under `~/.editora/templates/`.
