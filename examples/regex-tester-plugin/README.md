# Regex Tester plugin

A live regular-expression tester **tool window** (docks at the bottom; toggle from the tool stripe or the
palette → *Regex Tester*). Enter a pattern, toggle flags (ignore case / multiline / dot-matches-newline),
type a test string, and see the match count, each match's span, and its capture groups — recomputed as you
type. Invalid patterns show the syntax error.

A second tool-window example (after Scratchpad), using `java.util.regex`. Build with `./build.sh`; install
via *Settings → Plugins → Install from file…* or copy into `<configDir>/plugins/regex-tester/`. See
[`../../docs/plugins.md`](../../docs/plugins.md).
