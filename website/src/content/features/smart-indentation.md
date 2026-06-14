---
title: "Smart indentation"
group: "Editing"
order: 2
beta: false
summary: "Per-language auto-indent on <kbd>Enter</kbd> (block openers, matching-pair stanzas, closer re-alignment), plus smart backspace that clears a whole indent level in one press."
---

Enter does the right thing per language: it keeps the current line's indent, adds a level after a block opener (braces, `:` in Python/YAML, `do`/`then` in shell, an open tag in XML/HTML…), and splits a matching pair into an indented stanza with the closer dropped below. Typing a closer (`)]}` or a keyword like `end`/`fi`/`done`) re-aligns the line to its opener.

**Smart backspace** removes a whole indent level in one press — and on a blank, auto-indented line, a single Backspace jumps back to the end of the previous line. The indent unit (tabs vs spaces) is inferred from the file.
