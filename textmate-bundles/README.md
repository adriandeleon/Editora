# TextMate Bundles

Drop additional plist-based TextMate grammars in this directory to extend Editora syntax highlighting.

Supported files:
- `*.tmLanguage`
- `*.plist`

Current loader expectations:
- plist root contains `name`, `scopeName`, `fileTypes`, `patterns`, and optional `repository`
- regex rules support `match`
- multiline rules support `begin` / `end`
- repository includes support `#ruleName`, `$self`, and `$base`

Editora ships bundled TextMate grammars for JavaScript, JSON, Markdown, XML/HTML, and CSS.

