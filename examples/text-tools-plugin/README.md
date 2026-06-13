# Text Tools plugin

Selection/document text transforms, contributed as palette commands. Each operates on the **selection** if
there is one, otherwise the **whole document**:

`Text: UPPERCASE` · `lowercase` · `Title Case` · `camelCase` · `snake_case` · `kebab-case` ·
`Sort Lines` · `Unique Lines` · `Reverse Lines` · `Trim Trailing Whitespace` · `Squeeze Blank Lines`

Pure logic lives in `TextTransforms`; `TextToolsPlugin` is the wiring (`selectedText` → `replaceSelection`,
or `text` → `setText`). Build with `./build.sh`; install via *Settings → Plugins → Install from file…* or
copy into `<configDir>/plugins/text-tools/`. See [`../../docs/plugins.md`](../../docs/plugins.md).
