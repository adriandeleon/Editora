# Example Editora plugin

A minimal plugin that demonstrates **every** extension point Editora exposes:

| Contribution | Where it comes from |
| --- | --- |
| Palette command `Example: Say Hello` + keybinding `C-c C-h` | Java (`HelloPlugin.registerCommand` + `bindKey`) |
| `Example: Insert Marker` (edits the buffer) + keybinding `C-c C-e` | Java command + `plugin.json` `keymap` |
| **Example** tool window (bottom) | Java (`registerToolWindow`) |
| Editor right-click **Uppercase selection (example)** | Java (`addEditorMenuItem`) |
| Status-bar **👋 Example** segment | Java (`addStatusBarSegment`) |
| `Example: Print Date (external command)` | declarative `plugin.json` `commands` (runs `date`) |
| `callout` Markdown snippet | declarative `snippets/markdown.json` |

## Layout

```
example/                 (the install folder name = the plugin id)
├── plugin.json          manifest: id/name/version/main, keymap, external commands
├── example-plugin.jar   the compiled Java plugin (built by build.sh)
└── snippets/
    └── markdown.json    a declarative snippet contributed to Markdown buffers
```

## Build & install

```sh
./build.sh                       # compiles example-plugin.jar against Editora's API + JavaFX
# then follow the printed cp commands to copy it into <configDir>/plugins/example/
```

Enable it in **Settings → Plugins** (tick *Enable plugins* and *Example Plugin*) and **restart** Editora —
plugins load only at startup.

## How loading works

Editora is a sealed jlink image at runtime (no module path), so the plugin jar is loaded by a child
`URLClassLoader` whose parent is the app's class loader. The plugin can use Editora's **exported** packages
(`com.editora.plugin` and the public bits of `command`/`ui`/`editor`/`config`) plus the JDK + JavaFX baked
into the image. Compile your plugin on a plain classpath (see `build.sh`) — not the module path.

> ⚠️ Plugins run with **full trust** (no sandbox), exactly like VS Code / IntelliJ extensions. Only install
> plugins you trust.
