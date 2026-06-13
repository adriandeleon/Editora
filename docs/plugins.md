# Editora plugins

Editora supports **plugins** so you can add functionality without forking the app. A plugin is a folder in
your config directory containing a `plugin.json` manifest plus, optionally, a Java jar and asset folders.

> ⚠️ **Plugins run with full trust** — no sandbox, exactly like VS Code / IntelliJ extensions. A plugin can
> read and write your files and run programs. Only install plugins you trust. The feature is **off by
> default**; enable it (and each plugin individually) in **Settings → Plugins**, then **restart**.

## Where plugins live

```
<configDir>/plugins/<id>/        # the folder name is the plugin id
├── plugin.json                  # manifest (required)
├── <anything>.jar               # a Java plugin (optional)
├── lib/*.jar                    # extra jars on the plugin's classpath (optional)
├── snippets/<lang>.json         # declarative snippets (optional)
└── templates/<id>.json          # declarative file templates (optional)
```

`<configDir>` is `~/.editora` (or `~/.editora-dev` under `--dev`, or whatever `--config-dir` /
`EDITORA_CONFIG_DIR` points at). The enabled set is stored in `<configDir>/plugins.json`.

Plugins are discovered and their classes loaded **once at startup**. Enabling, disabling, or installing a
plugin takes effect **on the next launch** — there is no hot reload.

## The manifest (`plugin.json`)

```json
{
  "id": "example",
  "name": "Example Plugin",
  "version": "1.0.0",
  "main": "com.example.editora.HelloPlugin",
  "keymap": {
    "C-c C-e": "plugin.example.insertStamp"
  },
  "commands": [
    {
      "id": "date",
      "title": "Example: Print Date (external command)",
      "run": ["bash", "-lc", "date"]
    }
  ]
}
```

| Field | Meaning |
| --- | --- |
| `id` | Stable plugin id; defaults to the folder name. Command ids are namespaced `plugin.<id>.<cmd>`. |
| `name` / `version` | Shown on the Settings → Plugins page. |
| `main` | Fully-qualified class implementing `com.editora.plugin.Plugin`. Omit for a **declarative-only** plugin. |
| `keymap` | Chord → command id. Applied to the shared keymap (use a full command id, e.g. `plugin.<id>.<cmd>` or a built-in). |
| `commands` | Palette commands that run an external program (`run` is an argv list; `dir` is an optional working dir relative to the plugin). |

Unknown fields are ignored (the parser is lenient), so a manifest can carry extra metadata.

## Declarative contributions (no code)

- **Snippets** — drop `snippets/<lang>.json` (VS Code / TextMate snippet format). They merge into the
  built-in snippets for that language.
- **Templates** — drop `templates/<id>.json` (the same format as Editora's bundled file templates).
- **Keybindings** — the manifest `keymap` map.
- **External commands** — the manifest `commands` array; each becomes a palette entry
  `Example: …` that runs its argv via Editora's subprocess runner (with a timeout).

## Java plugins (the SPI)

Implement `com.editora.plugin.Plugin`:

```java
package com.example.editora;

import com.editora.plugin.*;

public class HelloPlugin implements Plugin {
    @Override public void start(PluginContext ctx) {
        ctx.registerCommand("sayHello", "Example: Say Hello",
                () -> ctx.setStatus("Hello!"));
        ctx.bindKey("C-c C-h", "sayHello");
        // … tool windows, editor menu items, status segments …
    }
    @Override public void stop() { /* window closed */ }
}
```

`PluginContext` is the capability surface (one instance **per window** — Editora is multi-window, so
`start` runs once per open window; tool-window content nodes are built fresh each time):

| Method | Adds |
| --- | --- |
| `registerCommand(id, title, action)` | A palette command (id namespaced to the plugin). |
| `bindKey(chord, commandId)` | A keybinding. |
| `registerToolWindow(id, title, side, content, commandId)` | A dockable tool window (`LEFT`/`RIGHT`/`BOTTOM`). |
| `addEditorMenuItem(label, action)` | An editor right-click item; the action gets an `ActiveEditor`. |
| `addStatusBarSegment(label, commandId)` | A clickable status-bar segment. |
| `activeEditor()` | The live active-buffer facade (`filePath`/`text`/`selectedText`/`replaceSelection`/`insertAtCaret`/`openPath`). |
| `pluginDir()` / `dataDir()` / `configDir()` | Paths (a writable per-plugin `data/` is created on demand). |
| `log(msg)` / `setStatus(msg)` | Debug log / status echo. |

A Java plugin may use Editora's **exported** API (`com.editora.plugin` and the public parts of
`command` / `ui` / `editor` / `config`) plus the JDK and JavaFX. Bundle any other dependencies inside your
jar (or `lib/`). One bad plugin never blocks the others — a failure is isolated, logged, and shown as a
load error on the Settings page.

### How loading works (and why it's jlink-safe)

The packaged installers are a **sealed jlink image with no module path at runtime**. Plugins are therefore
loaded by a child `URLClassLoader(jarUrls, appClassLoader)` — the parent is the app module's loader, so the
child can resolve Editora's exported types and the baked-in JDK/JavaFX modules via normal parent
delegation. There is **no `ServiceLoader`, no `ModuleLayer`** — compile your plugin on a plain **classpath**
(not the module path). This is identical in `mvn javafx:run` and in the installed `.app`/`.msi`/`.deb`.

## Building a plugin

See [`examples/example-plugin/`](../examples/example-plugin/) — a complete plugin exercising every
extension point, with a `build.sh` that compiles against Editora's API + JavaFX, packages the jar, and
produces a distributable `example.zip` (+ its SHA-256).

## Installing plugins

Three ways, all gated by *Settings → Plugins* (enable plugins first; an install loads on the next launch):

1. **Browse a registry** — *Settings → Plugins → Browse plugins…* (or the `plugins.browse` command) fetches
   a curated `index.json` over HTTPS and lists installable plugins. Picking one downloads its `.zip`,
   **verifies the SHA-256**, and unpacks it. The registry URL is *Settings → Plugins → Registry URL*
   (a baked-in default you can override).
2. **Install from a file** — *Settings → Plugins → Install from file…* (or `plugins.installFromDisk`) picks
   a local plugin `.zip` and unpacks it (no checksum — you chose the file).
3. **Hand-copy** — drop a plugin folder into `<configDir>/plugins/<id>/` and hit *Reload*.

Every install unpacks with a **zip-slip guard** + size caps. Remote installs require a matching SHA-256.

## Publishing & installing (registry)

A registry is a single **`index.json`** you host over HTTPS (a `raw.githubusercontent.com` file or GitHub
Pages). Each entry points at a plugin **`.zip`** whose top level is the plugin folder contents
(`plugin.json` + jar + `snippets/`…) — unzipping it yields exactly what lives under `plugins/<id>/`.

```json
{
  "schemaVersion": 1,
  "plugins": [
    {
      "id": "example",
      "name": "Example Plugin",
      "version": "1.0.0",
      "description": "…",
      "author": "you",
      "homepage": "https://github.com/you/editora-plugins",
      "download": "https://github.com/you/editora-plugins/releases/download/example-v1.0.0/example.zip",
      "sha256": "<lowercase-hex sha-256 of example.zip>",
      "minEditoraVersion": "1.0.0"
    }
  ]
}
```

To publish a plugin:

1. Build its `.zip` (e.g. `examples/example-plugin/build.sh` prints the archive **and its SHA-256**).
2. Attach the `.zip` to a **GitHub Release** in your plugin/registry repo.
3. Add an entry to `index.json` with the release-asset `download` URL and the `sha256` from step 1
   (the zip stores timestamps, so its hash changes per rebuild — use the value for the asset you upload).

A ready-to-host sample registry is in [`examples/editora-plugins-registry/`](../examples/editora-plugins-registry/).
`download` + the registry URL must be **HTTPS**; entries needing a newer Editora than yours are shown but
not installable (`minEditoraVersion`).

## Limitations (v1)

No sandbox, no hot reload, no inter-plugin dependencies, no signing beyond SHA-256, and no gutter-marker
contributions yet. These are deferred; the manifest/registry generalizes to them.
