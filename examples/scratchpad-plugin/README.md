# Scratchpad plugin

A persistent scratchpad **tool window** (docks on the right): a plain text area whose contents are
auto-saved (debounced) to the plugin's `dataDir()` and reloaded on startup. Toggle it from the tool stripe
or the palette (*Scratchpad*).

Demonstrates `PluginContext.registerToolWindow(...)` + building a JavaFX content node + per-plugin storage
(`dataDir()`). Build with `./build.sh`; install via *Settings → Plugins → Install from file…* or copy into
`<configDir>/plugins/scratchpad/`. See [`../../docs/plugins.md`](../../docs/plugins.md).
