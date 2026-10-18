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

A plugin id is letters, digits, `-`, `_` and `.` (not starting with a dot, no `..`), and **must equal its
folder name**: a plugin *is* its folder. A folder whose `plugin.json` declares some other id is listed under
its folder name with a load error ("rename the folder to …") and nothing from it loads — otherwise a second
folder could run under the consent given to the plugin whose id it copied, and Remove on one row deleted the
other. `lsp`, `dap`, `typst` and `tools` are reserved: Editora keeps downloaded language servers, debug
adapters, the Typst CLI and single-binary tools (ShellCheck, shfmt) in `plugins/lsp`, `plugins/dap`,
`plugins/typst` and `plugins/tools`, so those folders are never treated as plugins (and Export Configuration
leaves them out). A folder whose `plugin.json` declares an invalid or reserved id is listed under its folder name with
a load error and nothing from it is loaded. *Remove* deletes the plugin's own folder — always a direct child
of `plugins/` — never a path built from the manifest id.

Plugins are discovered and their classes loaded **once at startup**, and that **launch set** is what every
window of the session runs — the first window and any opened later. Enabling, disabling, installing,
updating or removing a plugin takes effect **on the next launch** — there is no hot reload. (New windows used
to read the live list, so a declined update, a plugin just switched off, or two versions of one plugin could
run before a restart.) Settings → Plugins compares each row with the launch set and says what a restart will
change, with a *Restart now* next to it.

Starting Editora with **`--disable-plugins`** loads no plugin at all for that session (nothing is saved) — the
way out of a plugin that hangs or breaks Editora at startup. The Settings page and Doctor say when it is on.

## The manifest (`plugin.json`)

```json
{
  "id": "example",
  "name": "Example Plugin",
  "version": "1.0.0",
  "main": "com.example.editora.HelloPlugin",
  "keymap": {
    "C-M-S-e": "insertStamp"
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
| `id` | The plugin id: its folder's name (it may be omitted). Command ids are namespaced `plugin.<id>.<cmd>`. |
| `name` / `version` | Shown on the Settings → Plugins page. |
| `main` | Fully-qualified class implementing `com.editora.plugin.Plugin`. Omit for a **declarative-only** plugin. |
| `keymap` | Chord → command id. A bare id (`"insertStamp"`) is this plugin's own command (`plugin.<id>.insertStamp`); a dotted id is used as written (a built-in, e.g. `file.save`); `""` removes the chord. |
| `keymaps` | Optional chords for one keymap only: `{"emacs": {"C-c C-x s": "insertStamp"}, "cua": {...}}`. While that keymap (`emacs`, `cua`, `vscode`, `intellij`, `sublime`) is active its entry is used **instead of** `keymap`; other keymaps get `keymap`. Chords mean different things per keymap (`C-c` copies in CUA but starts a prefix in Emacs), so one flat map rarely suits them all. |
| `commands` | Palette commands that run an external program (`run` is an argv list; `dir` is an optional working dir relative to the plugin). What the program prints is shown in the status bar and the Message Log. |

Unknown fields are ignored (the parser is lenient), so a manifest can carry extra metadata.

## Declarative contributions (no code)

- **Snippets** — drop `snippets/<lang>.json` (VS Code / TextMate snippet format). They merge into the
  built-in snippets for that language, in the order **bundled < plugin < user**: a plugin entry with the
  name of a bundled snippet replaces it, and a plugin trigger shadows a bundled snippet's — but the
  user's own `<configDir>/snippets/<lang>.json` wins over both, by name and by trigger, and can switch a
  plugin's snippet off with `"<name>": { "disabled": true }`. An optional `"scope"` (comma-separated
  language ids) limits an entry, which is how a `snippets/global.json` entry targets a few languages.
- **Templates** — drop `templates/<id>.json` (the same format as Editora's bundled file templates; see
  [templates](subsystems/templates.md)). A plugin template overrides a bundled one with the same id; the
  user's own `~/.editora/templates/<id>.json` overrides both. Plugin templates are listed in Settings →
  Templates tagged "plugin".
- **Keybindings** — the manifest `keymap` (and `keymaps`) map. A chord that takes one another command had is
  named in the Message Log when the plugin loads, and the consent dialog says what it replaces. Your own
  key bindings always win over a plugin's.
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
        ctx.bindKey("C-M-S-y", "sayHello");
        // … tool windows, editor menu items, status segments …
    }
    @Override public void stop() { /* window closed */ }
}
```

`PluginContext` is the capability surface (one instance **per window** — Editora is multi-window, so
`start` runs once per open window; tool-window content nodes are built fresh each time).

**Threads.** `start` and `stop` run on the JavaFX application thread, during window construction and
window close: they must return quickly — do I/O, network calls and long setup on your own (daemon) thread.
Editora times every `start`; one that takes 250 ms or more is named in the Message Log and on its Settings
row, because it holds up every window as it opens. Call the context and `ActiveEditor` from the FX thread
too: from another thread a write (`setText`, `insertAtCaret`, `registerCommand`, `setStatus`, …) is handed to
the FX thread, and a read (`text()`, `selectedText()`, `caretLine()`, `filePath()`, `activeEditor()`) throws
`IllegalStateException` instead of racing the editor. Editora exits when its last window closes even if a
plugin thread is still running.

**Failures.** A plugin whose `main` class is missing or not a `Plugin`, or whose `start` throws, is reported
in the Message Log and on its Settings row, and shows in Doctor → Plugins. What a failing `start` had added
(commands, chords, tool windows, menu items, status segments) is taken back, and its `stop` is called so it
can release what it opened. A command, menu item or status segment that throws is reported with the plugin's
name.

| Method | Adds |
| --- | --- |
| `registerCommand(id, title, action)` | A palette command. The id is **always** namespaced to the plugin (`gpg.encrypt` becomes `plugin.<id>.gpg.encrypt`), so a plugin cannot replace a built-in or another plugin's command. A blank title shows as "<plugin name>: <id>". |
| `bindKey(chord, commandId)` | A keybinding: a bare id is the plugin's own command, a dotted one a built-in. Kept across keymap switches and keybinding edits. |
| `registerToolWindow(id, title, side, content, commandId)` | A dockable tool window (`LEFT`/`RIGHT`/`BOTTOM`), using the default plugin (jigsaw) stripe icon. Its stripe is gated on an open editor buffer (hidden on a non-buffer tab like Welcome). |
| `registerToolWindow(id, title, side, content, commandId, icon)` | Same, with a custom stripe icon: `icon` is a `Supplier<javafx.scene.Node>` (e.g. an `SVGPath` with the `toolbar-icon` style class, themed for light/dark). Called per window/repaint, so it must return a fresh node each time; `null` (or a null result) falls back to the jigsaw. Also buffer-gated. |
| `registerToolWindow(id, title, side, content, commandId, icon, needsBuffer)` | Full form. `needsBuffer = true` (the default of the other overloads) gates the stripe on an open editor buffer — for a tool window that acts on the active editor. Pass `false` for a self-contained tool window (scratchpad, calculator, color picker, …) that should stay available regardless of the active tab. |
| `addEditorMenuItem(label, action)` | An editor right-click item; the action gets an `ActiveEditor`. |
| `addStatusBarSegment(label, commandId)` | A clickable (and keyboard-reachable) status-bar segment; its tooltip names the plugin. |
| `activeEditor()` | A facade over the buffer that is active **when it is called** (`filePath`/`text`/`selectedText`/`caretLine`/`replaceSelection`/`insertAtCaret`/`setText`/`openPath`). It stays on that buffer: a formatter that finishes on a worker writes into the file it started on, never into whatever tab is in front by then; once that tab is closed it reads as empty and ignores writes. Ask for it again for the current tab. `setText` replaces the whole buffer (undoable) — for whole-file transforms like formatting. `caretLine` counts from the top of the file in a narrowed buffer too. |
| `pluginDir()` / `dataDir()` / `configDir()` | Paths (a writable per-plugin `data/` is created on demand). `data/` survives an update of the plugin: the rest of the folder is replaced, `data/` is carried over, and a file the user already has wins over one of the same name shipped in the archive. Removing the plugin deletes it. |
| `log(msg)` / `setStatus(msg)` | Debug log / status echo. |
| `openUrl(url)` | Opens a web address in the browser (through the same checks as a link in a document). |

A Java plugin may use Editora's **exported** API (`com.editora.plugin` and the public parts of
`command` / `ui` / `editor` / `config`) plus the JDK and JavaFX. Bundle any other dependencies inside your
jar (or `lib/`). One bad plugin never blocks the others — a failure is isolated, logged, and shown on the
Settings page.

Generate the Javadoc for the plugin API with `./mvnw -Papidocs javadoc:javadoc` (output under
`target/reports/apidocs`). That run also doclint-validates the API's references/HTML, so a broken `@link`
in the public surface fails the build.

### How loading works (and why it's jlink-safe)

The packaged installers are a **sealed jlink image with no module path at runtime**. Plugins are therefore
loaded by a child `URLClassLoader(jarUrls, appClassLoader)` — the parent is the app module's loader, so the
child can resolve Editora's exported types and the baked-in JDK/JavaFX modules via normal parent
delegation. There is **no `ServiceLoader`, no `ModuleLayer`** — compile your plugin on a plain **classpath**
(not the module path). This is identical in `mvn javafx:run` and in the installed `.app`/`.msi`/`.deb`.

### Retired plugins

A registry plugin whose feature moved into Editora is *retired* (`plugin/RetiredPlugins`): an installed copy is
removed at startup — after what it remembered that the built-in feature can use is carried over — and the id is
never discovered, installed or listed again, so the two never run side by side. Today: `gpg-tools`, replaced by
built-in encrypted files ([ADR 0017](decisions/0017-encrypted-files.md)).

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
   a curated `index.json` over HTTPS and lists installable plugins (greyed when it needs a newer Editora,
   marked when it is deprecated). Picking one downloads its `.zip`, **verifies the SHA-256**, checks that
   the archive is the plugin and version the entry names, and stages it. The registry URL is
   *Settings → Plugins → Registry URL* (a baked-in default you can override; only `https://` addresses are
   taken). Settings steps aside while the picker is open and comes back when it closes.
2. **Install from a file** — *Settings → Plugins → Install from file…* (or `plugins.installFromDisk`) picks
   a local plugin `.zip` and stages it (no checksum — you chose the file).
3. **Hand-copy** — drop a plugin folder into `<configDir>/plugins/<id>/`, hit *Reload*, then tick it (which
   asks the consent below) and restart.

**Staged installs.** An archive is unpacked into a staging folder beside the plugins
(`plugins/.installing-*`) and nothing in `plugins/` changes until you have seen what it does and chosen
*Enable*. *Cancel* leaves the plugins folder as it was: a declined update keeps the version you had —
enabled, if it was. The question says what kind of install it is: *Install*, *Update X 1.0 → 1.1*,
*Reinstall*, a *downgrade*, or *Replace A with B* when a different plugin declares an installed plugin's id
(it would take that plugin's place and its saved `data/`). A plugin that runs in this session is not replaced
under itself — its jar is open, and Windows cannot move or delete an open jar: the new version waits as
`plugins/.pending-<id>` and is swapped in at the next start, before anything loads. An install over a
plugin folder that is a symbolic link (a plugin under development) is refused. Status and errors appear on
the Plugins page under the buttons as well as in the status bar, in your language, in a dialog titled
"Plugins".

**Removing.** *Remove* on a row deletes that row's folder after naming the plugin and the data it saved.
A plugin running in this session is switched off at once and deleted at the next start (a
`plugins/.remove-<id>` marker). `plugin.json` is deleted last, so a folder that cannot be fully deleted is
still listed and can be removed again.

Every install unpacks with a **zip-slip guard** + size caps; the registry index and remote downloads are
read with a hard size cap (a hostile registry can't exhaust memory), HTTPS-only, with no `https→http`
downgrade. Remote installs require a matching SHA-256.

**Consent at enable.** Because enabling a plugin arms its code on the next launch, Editora shows a
**capability-disclosure confirm** before any plugin is enabled (after a Browse/file install, and when you
tick a plugin's checkbox): its **main class and every jar** its loader reads, each **external command** with
its title, its exact argv (quoted, part by part) and where it runs, each **chord** in your keymap's notation
with the command it replaces (or removes), and the snippets and templates it adds. Manifest strings are
escaped (line breaks, tabs, control and bidi characters are shown, not obeyed) and capped, the list scrolls
inside a fixed-height box, and *Cancel* is the default button. A plugin whose `plugin.json` could not be read
cannot be ticked at all — there is nothing to disclose. The registry URL is configurable, and a
**non-default registry** is flagged with its host in Settings and in the install question.

**Consent is bound to what was shown.** `plugins.json` stores, with each enabled plugin, a fingerprint of
that disclosure (main class, jar names, commands, keymaps — not the name or version). A plugin whose
capabilities differ from what you agreed to — an update that adds code or a command, or something else put
into the folder of a plugin removed by hand — does not load; its row says "Changed since you enabled it", and
ticking it shows the new disclosure. Entries of plugins whose folder is gone are dropped at launch, so a
stale consent cannot arm whatever appears under that name later. (Plugins enabled before fingerprints
existed adopt the one of what is installed at the first launch after the upgrade.)

MCP clients (agents) cannot run `view.togglePlugins`, any `plugins.*` command or a trust command: those are
security decisions for the user alone.

This is consent, not a sandbox — see Limitations.

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
      "minEditoraVersion": "0.22.0"
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
`download` + the registry URL must be **HTTPS**; entries needing a newer Editora than yours are shown greyed
but not installable (`minEditoraVersion`: the oldest Editora release the plugin was built and tested
against, or blank). An optional `"deprecated": "<reason>"` marks an entry Browse should warn about (its
feature is built in now, it is unmaintained); it can still be installed. An index whose `schemaVersion` is
newer than this Editora reads is refused. Each entry's `id` and `version` must match the `plugin.json` inside
its archive.

### Signing the registry (authenticity)

The SHA-256 in each entry gives *integrity* (the download matches the index) but not *authenticity* — a
compromised registry could change both. Editora therefore verifies a **detached Ed25519 signature of the
whole `index.json`** against a **bundled public key**, and **"Require a signed registry"** (Settings → Plugins,
default **on**) blocks installs from a registry that doesn't verify. It is about the registry: a `.zip` you
install from a file yourself is not checked. A signed index + the per-entry SHA-256
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
(it can't be signed with Editora's bundled key), either turn off "Require a signed registry" or ship a build
with your own bundled public key. Signing proves *who* published — it is **not** a sandbox.

## Limitations (v1)

No sandbox, no hot reload, no inter-plugin dependencies, no per-plugin signatures (the registry index is
signed; each download is checked by SHA-256), no index expiry (an older signed index still verifies), no
app-level plugin lifecycle or buffer events, no `${file}` variables in declarative commands, no per-plugin
settings page, and no gutter-marker contributions yet. These are deferred; the manifest/registry generalizes
to them.

## Troubleshooting

- **A plugin does nothing.** Look at its row in Settings → Plugins (a load error, "failed to start", "main
  class not found", "changed since you enabled it", or a restart pill) and at Doctor → Plugins.
- **Editora hangs or crashes at startup since a plugin was installed.** Start it with `--disable-plugins`,
  then switch the plugin off or remove it in Settings → Plugins.
- **A plugin's chord does nothing.** It may be shadowed by a binding of yours (yours win), or meant for
  another keymap — see `keymaps` above.
