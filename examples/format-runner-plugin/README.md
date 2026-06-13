# Format Runner plugin

Adds a **Format: Format File** palette command that runs an external code formatter on the active buffer and
replaces it with the result. The formatter is chosen by file extension and run **stdin → stdout** (the file
on disk is never touched):

| Files | Formatter |
| --- | --- |
| js/ts/jsx/tsx/json/css/scss/less/html/md/yaml/vue | `prettier --stdin-filepath <name>` |
| py | `black -q -` |
| go | `gofmt` |
| rs | `rustfmt --emit stdout` |
| c/cc/cpp/h/hpp/java | `clang-format --assume-filename=<name>` |

The formatter must be installed and on `PATH` (the plugin augments `PATH` with the usual Homebrew/npm/cargo
dirs so a GUI-launched app can find it). The subprocess runs off the FX thread; the formatted text is
applied via `ActiveEditor.setText` on the FX thread.

This is the canonical **external-command** plugin example (showing `ProcessBuilder` + `setText`). Build with
`./build.sh`; install via *Settings → Plugins → Install from file…* or copy into
`<configDir>/plugins/format-runner/`. See [`../../docs/plugins.md`](../../docs/plugins.md).
