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
| `registerToolWindow(id, title, side, content, commandId)` | A dockable tool window (`LEFT`/`RIGHT`/`BOTTOM`), using the default plugin (jigsaw) stripe icon. Its stripe is gated on an open editor buffer (hidden on a non-buffer tab like Welcome). |
| `registerToolWindow(id, title, side, content, commandId, icon)` | Same, with a custom stripe icon: `icon` is a `Supplier<javafx.scene.Node>` (e.g. an `SVGPath` with the `toolbar-icon` style class, themed for light/dark). Called per window/repaint, so it must return a fresh node each time; `null` (or a null result) falls back to the jigsaw. Also buffer-gated. |
| `registerToolWindow(id, title, side, content, commandId, icon, needsBuffer)` | Full form. `needsBuffer = true` (the default of the other overloads) gates the stripe on an open editor buffer — for a tool window that acts on the active editor. Pass `false` for a self-contained tool window (scratchpad, calculator, color picker, …) that should stay available regardless of the active tab. |
| `addEditorMenuItem(label, action)` | An editor right-click item; the action gets an `ActiveEditor`. |
| `addStatusBarSegment(label, commandId)` | A clickable status-bar segment. |
| `activeEditor()` | The live active-buffer facade (`filePath`/`text`/`selectedText`/`replaceSelection`/`insertAtCaret`/`setText`/`openPath`). `setText` replaces the whole buffer (undoable) — for whole-file transforms like formatting. |
| `pluginDir()` / `dataDir()` / `configDir()` | Paths (a writable per-plugin `data/` is created on demand). |
| `log(msg)` / `setStatus(msg)` | Debug log / status echo. |

A Java plugin may use Editora's **exported** API (`com.editora.plugin` and the public parts of
`command` / `ui` / `editor` / `config`) plus the JDK and JavaFX. Bundle any other dependencies inside your
jar (or `lib/`). One bad plugin never blocks the others — a failure is isolated, logged, and shown as a
load error on the Settings page.

Generate the Javadoc for the plugin API with `./mvnw -Papidocs javadoc:javadoc` (output under
`target/reports/apidocs`). That run also doclint-validates the API's references/HTML, so a broken `@link`
in the public surface fails the build.

### How loading works (and why it's jlink-safe)

The packaged installers are a **sealed jlink image with no module path at runtime**. Plugins are therefore
loaded by a child `URLClassLoader(jarUrls, appClassLoader)` — the parent is the app module's loader, so the
child can resolve Editora's exported types and the baked-in JDK/JavaFX modules via normal parent
delegation. There is **no `ServiceLoader`, no `ModuleLayer`** — compile your plugin on a plain **classpath**
(not the module path). This is identical in `mvn javafx:run` and in the installed `.app`/`.msi`/`.deb`.

## Building a plugin

See [`examples/example-plugin/`](../examples/example-plugin/) — a complete reference plugin exercising
every extension point, with a `build.sh` that compiles against Editora's API + JavaFX, packages the jar,
and produces a distributable `example.zip` (+ its SHA-256). For more, real single-purpose plugins (text
transforms, hashing, a scratchpad tool window, an external-formatter runner, a live regex tester, …) — each
with complete source — see the catalog in the registry repo
[adriandeleon/editora-plugins](https://github.com/adriandeleon/editora-plugins) under `plugins/<id>/`.

## Installing plugins

Three ways, all gated by *Settings → Plugins* (enable plugins first; an install loads on the next launch):

1. **Browse a registry** — *Settings → Plugins → Browse plugins…* (or the `plugins.browse` command) fetches
   a curated `index.json` over HTTPS and lists installable plugins. Picking one downloads its `.zip`,
   **verifies the SHA-256**, and unpacks it. The registry URL is *Settings → Plugins → Registry URL*
   (a baked-in default you can override).
2. **Install from a file** — *Settings → Plugins → Install from file…* (or `plugins.installFromDisk`) picks
   a local plugin `.zip` and unpacks it (no checksum — you chose the file).
3. **Hand-copy** — drop a plugin folder into `<configDir>/plugins/<id>/` and hit *Reload*.

Every install unpacks with a **zip-slip guard** + size caps; the registry index and remote downloads are
read with a hard size cap (a hostile registry can't exhaust memory), HTTPS-only, with no `https→http`
downgrade. Remote installs require a matching SHA-256.

**Consent at enable.** Because enabling a plugin arms its code on the next launch, Editora shows a
**capability-disclosure confirm** before any plugin is enabled (after a Browse/file install, and when you
tick a plugin's checkbox): it lists whether the plugin **runs executable code**, the **exact external
commands** it declares, and any **keybindings it remaps** — so you see what you're authorizing. The
registry URL is configurable, and a **non-default registry** is flagged with its host (a phishing-vector
guard). This is consent, not a sandbox — see Limitations.

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

The live registry — a complete, working `index.json` (signed) plus every plugin's source — is
[adriandeleon/editora-plugins](https://github.com/adriandeleon/editora-plugins) (Editora's baked-in
default); use it as the template for your own.
`download` + the registry URL must be **HTTPS**; entries needing a newer Editora than yours are shown but
not installable (`minEditoraVersion`).

### Signing the registry (authenticity)

The SHA-256 in each entry gives *integrity* (the download matches the index) but not *authenticity* — a
compromised registry could change both. Editora therefore verifies a **detached Ed25519 signature of the
whole `index.json`** against a **bundled public key**, and **"Require signed plugins"** (Settings → Plugins,
default **on**) blocks installs from a registry that doesn't verify. A signed index + the per-entry SHA-256
then chain authenticity to every plugin.

Workflow (one keypair, JDK-only — no dependencies; see `scripts/PluginSigningTool.java`):

```sh
# once: generate the registry keypair (keep the private key secret — never commit it)
java scripts/PluginSigningTool.java keygen editora-registry.pub editora-registry.key
#   → bundle editora-registry.pub at resources/com/editora/plugin/editora-registry.pub

# after every edit to index.json: re-sign and publish index.json.sig next to it
java scripts/PluginSigningTool.java sign editora-registry.key index.json   # writes index.json.sig
```

Editora fetches `<registry-url>.sig` alongside the index and verifies it. If you run your **own** registry
(it can't be signed with Editora's bundled key), either turn off "Require signed plugins" or ship a build
with your own bundled public key. Signing proves *who* published — it is **not** a sandbox.

## Limitations (v1)

No sandbox, no hot reload, no inter-plugin dependencies, no signing beyond SHA-256, and no gutter-marker
contributions yet. These are deferred; the manifest/registry generalizes to them.
