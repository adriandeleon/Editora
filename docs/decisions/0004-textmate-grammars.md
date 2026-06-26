# 0004 — TextMate grammars via tm4e, not a hand-written highlighter

**Status:** Accepted

## Context

Syntax highlighting needs to cover many languages and stay maintainable. The options are a
hand-written tokenizer per language, a parser-generator approach, or reusing the TextMate grammar
ecosystem (the `.tmLanguage.json` grammars that VS Code and many editors use).

## Decision

Use **TextMate grammars** via **tm4e** (`org.eclipse.tm4e.core`). `GrammarRegistry` maps a file
extension to a bundled grammar under `resources/com/editora/grammars/`; `TextMateHighlighter`
tokenizes the document line-by-line (carrying grammar state across lines) and maps each token's
scope to a CSS class themed in `styles/syntax.css`.

## Consequences

- Adding a language is mostly **data**: drop a `.tmLanguage.json`, register a scope→resource and
  extension→scope mapping, add fold/indent/comment entries. See
  [extending.md](../extending.md#add-a-language--textmate-grammar).
- We inherit a large, battle-tested grammar corpus (vendored from VS Code / shiki / authored;
  attributed in `NOTICE`).
- tm4e is not on Maven Central — it ships as the NetBeans repackaging, which is **code-signed**,
  so the dist build strips the jar signature before jlink (jlink rejects signed modular jars).
- **JPMS trap:** the grammar package must `opens com.editora.grammars to org.eclipse.tm4e.core`,
  or tm4e's `getResourceAsStream` returns null at runtime on the module path (classpath tests
  still pass). See [gotchas.md](../gotchas.md).
- `LanguageRegistry` is now only an extension→language-name resolver (folding + File Info); it no
  longer does highlighting.
