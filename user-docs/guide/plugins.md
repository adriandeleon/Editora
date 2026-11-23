---
title: Plugins
description: Install plugins from the registry, fix one that does not work, and write your own to extend Editora.
category: Customization
order: 4
---

Editora can be extended with **plugins**, which add commands, keybindings, tool
windows, editor-menu items, and status-bar segments. Browse the
[plugin catalog](/plugins) for what's available, or write your own.

> Plugins are **off by default** and run with full access (no sandbox). Only
> install plugins you trust.

## Installing

1. Enable plugins in **Settings → Plugins** (or **View: Toggle Plugins**,
   `view.togglePlugins`). Plugins are off in Simple UI mode.
2. **Browse plugins…** (or the `plugins.browse` command) installs from the
   official registry; **Install from file…** (`plugins.installFromDisk`)
   installs a local `.zip`. Both buttons are greyed while plugins are off. The
   plugin list opens in the main window: the Settings window steps aside when
   it appears and comes back on the Plugins page when it closes.
3. Read the **Enable this plugin?** dialog, which lists what the plugin will do
   (see [What the consent dialog shows](#what-the-consent-dialog-shows)), and
   choose **Enable**. Nothing in your plugins folder changes before you do: if
   you cancel, nothing is installed.
4. Restart Editora to load it.

You can also copy a plugin folder into `plugins/<id>/` in your config folder
yourself, click **Reload Plugins**, and tick it (behind the same dialog).

![Settings on the Plugins page: Enable plugins on, the Installed Plugins section with Reload Plugins and the plugins folder, Browse plugins and Install from file buttons, and the Registry URL](/screenshots/docs/settings-plugins.png "Settings → Plugins with plugins turned on and none installed yet.")

Browse marks each registry entry: **Install**, **Installed**, **Update
available** (with the version you have), **Newer version installed**,
**Requires Editora ≥** a version (greyed, not installable), or **Deprecated**
with the registry's reason. Before downloading, Editora asks whether to
**Install**, **Update** (old → new version), **Reinstall** or **Downgrade**,
comparing versions the semver way: `1.10` is newer than `1.9`, `rc10` newer than
`rc2`, a pre-release older than its release, a leading `v` and `+build` metadata
are ignored. An entry from a registry other than the built-in one names that
registry in the question, and with **Require a signed registry** off, the
question says when the registry is not signed by the trusted key.

Installed plugins live in your config folder under `plugins/<id>/`, where the
folder name is the plugin's id, and the enabled set is tracked in
`plugins.json`.

### Updating and removing

An update replaces the plugin's folder but keeps `plugins/<id>/data/`, where a
plugin stores its own files. If you decline an update, the version you had
stays installed, enabled if it was.

If a different plugin declares the id of one you have installed, Editora asks
**Replace** *A* **with** *B*? first: it would take that plugin's place and the
data it saved.

**Remove** on a row deletes that row's plugin, after asking with the plugin's
name and, when it has saved data, saying that the data goes too. Its `plugin.json` is deleted
last, so a folder that cannot be fully deleted is still listed and can be
removed again.

Export Configuration leaves out the data plugins saved unless you tick
**Include the data plugins saved** (it is asked only when a plugin has saved
data). A plugin's data folder can hold tokens, keys or notes, so leave it off
for an export you will share. The installer's staging folders are never
exported.

### When changes take effect

Plugins are loaded once, at startup, and every window of a session runs the
plugins that loaded at launch, including windows you open later. Installing,
updating, enabling, disabling or removing a plugin takes effect at the next
start. A running plugin's jar stays open, so its update or removal is applied at
the next start, before anything loads.

Each row in **Installed Plugins** says what a restart will change, with a
**Restart Now** button beside it:

- **Restart to apply**: a plugin you switched on or off.
- **Version** *N* **runs after a restart**: an update waiting to replace the
  running version.
- **Removed when Editora restarts**: a running plugin you removed.
- **Changed since you enabled it: tick it to see what it does now**: see
  [Security](#security). This row has no Restart Now; tick it to agree again.

### The Settings page

**Settings → Plugins** has three parts:

- The warning that plugins run with full access, **Enable plugins**, and a note
  that changes apply after a restart.
- **Installed Plugins**: **Reload Plugins**, the plugins folder, and one row per
  plugin with its checkbox, version, id, **Remove**, any pending restart, and
  any problem. A plugin whose `plugin.json` cannot be read is listed with the
  reason (and the line and column where reading stopped, when known) and cannot
  be ticked; the parser's own text is in the tooltip. Rows are greyed while
  **Enable plugins** is off.
- **Browse & Install**: **Browse plugins…**, **Install from file…**, the
  **Registry URL**, and **Require a signed registry**.

The result of a Browse or install started here is shown under its buttons, as
well as in the status bar. Install and registry errors are sentences in your
language, in a dialog titled **Plugins**; a plugin that fails to start, or
whose main class is missing, is reported in the Message Log and on its row.

## Security

Plugins are not sandboxed: an enabled plugin runs with the same access to your
files and system as Editora itself. What Editora checks is where a plugin came
from, and that you agreed to it.

- **Signed registry.** The registry's `index.json` is verified against an
  Ed25519 public key bundled with Editora. **Require a signed registry**
  (Settings → Plugins, on by default; **Plugins: Toggle Require Signature**,
  `plugins.toggleRequireSignature`) blocks browsing and installing from a
  registry whose signature is missing or does not verify. A `.zip` you install
  from a file is not checked. A registry of your own
  (`plugins.setRegistryUrl`) cannot be signed with that key, so using one
  means turning the requirement off.
- **Checksum.** A plugin downloaded from the registry must match the SHA-256
  listed in the index, and must be the plugin and version its entry names. The
  registry and its downloads are HTTPS-only. The **Registry URL** field and
  **Plugins: Set Registry URL…** take only an `https://` address: anything else
  is refused (the field turns red), and the registry in use stays. A `.zip`
  installed from a file has no checksum to match. Reading a registry index stops
  after a minute and reading a download after five minutes, so a server that
  stalls cannot hang the install; an archive over 128 MB is refused, and a
  picked file that large is refused before it is read.
- **Staged installs.** An archive is unpacked into a staging folder beside the
  plugins, and nothing in `plugins/` changes until you have seen what it does
  and chosen **Enable**. Staging folders a crash left behind are removed at
  start. An install over a plugin folder that is a link to a folder elsewhere
  (a plugin you are developing) is refused.
- **Consent.** Before a plugin is enabled, a dialog lists what it can do. Each
  plugin has its own enable switch, and a plugin whose capabilities cannot be
  shown stays disabled.
- **Consent belongs to the folder.** A plugin's id is its folder's name. A
  folder whose `plugin.json` declares another id is listed with an error ("rename
  the folder to …") and never loads, so a second folder cannot run under the
  consent given to the plugin whose id it copied.
- **Consent belongs to what you were shown.** `plugins.json` stores a
  fingerprint of the disclosure with each consent: the main class, the names of
  the jars, each command, and each chord, but not the name or version. A plugin
  whose disclosure differs from what you agreed to (an update that adds code, a
  command or a chord) does not load; its row says **Changed since you enabled
  it**, and ticking it shows the new disclosure. A release that only changes its
  version keeps your consent. Entries of plugins whose folder is gone are
  dropped at start, so a stale consent cannot arm whatever appears under that
  name later.
- **Agents.** MCP clients cannot switch plugins on, change the signed-registry
  requirement, set the registry, or install or remove plugins; see
  [MCP server](/docs/mcp#security).

A valid signature tells you who published the registry. It does not make a
plugin safe.

### What the consent dialog shows

The dialog lists:

- whether the plugin runs executable code: its **main class** and **every jar**
  it loads;
- each **external command**, with its title, its exact arguments and the
  folder it runs in;
- each **chord** it binds, in your keymap's notation, with the command it
  replaces or the shortcut it removes, including chords for other keymaps;
- whether it adds snippets or file templates.

Text from the manifest is escaped (line breaks, tabs, control and
bidirectional characters are shown, not obeyed) and shortened when long, the
list scrolls inside a fixed-height box, and **Cancel** is the default button.

## What a plugin can do

A plugin can:

- **Register commands** (they appear in the palette, with the chords bound to
  them) and **bind keys** to them.
- **Add a tool window** (left / right / bottom) with custom UI. Its toggle is
  named **Tool Window:** followed by the window's title.
- **Add editor right-click menu items**, in their own group of the menu, and
  **status-bar segments**, which can be reached by keyboard and name their
  plugin in the tooltip.
- **Ship snippets and file templates** that merge into the built-in sets. A
  plugin's entry overrides a bundled one with the same name or id, and your own
  overrides both.
- **Run external tools** (formatters, task runners) via a subprocess. What a
  declared command prints is shown in the status bar and the Message Log.

A plugin's commands and tool windows are always in its own namespace: their ids
are `plugin.<id>.<name>`, so a plugin cannot replace a built-in command or
another plugin's. A command registered without a title shows the plugin's name.
A command, menu item or status segment that fails is reported with the plugin's
name.

Your own key bindings always win over a plugin's. A chord a plugin takes from
another command is named in the status bar when the plugin loads.

## Writing a plugin

A plugin is a folder with a `plugin.json` manifest plus, optionally, a Java jar
and `snippets/` / `templates/` directories. The simplest plugins are purely
**declarative** (commands that run a shell command, keybindings, asset folders);
richer ones implement the `Plugin` interface against the exported
`com.editora.plugin` API.

The manifest's `id`, if given, must be the folder's name. Its `keymap` binds
chords in every keymap; `keymaps` gives the chords for one keymap only
(`emacs`, `cua`, `vscode`, `intellij` or `sublime`), used **in place of**
`keymap` while that keymap is active:

```json
{
  "keymap": { "C-M-S-e": "insertStamp" },
  "keymaps": { "emacs": { "C-c C-x s": "insertStamp" } }
}
```

A bare command name (`insertStamp`) is the plugin's own command; a dotted id is
a built-in. Chords mean different things per keymap (`C-c` copies in CUA but
starts a prefix in Emacs), so one flat map rarely suits them all.

A Java plugin's `start` runs on the JavaFX thread as each window opens, so it
must return quickly. Editora times it: one that takes 250 ms or more is named in
the Message Log, on its Settings row and in the Doctor. If `start` throws, what
it had added (commands, chords, tool windows, menu items, status segments) is
taken back and its `stop` is called. Plugin API calls belong on the JavaFX
thread: from another thread, a write is handed to it and a read throws.

`activeEditor()` returns the buffer that is active when it is called, and stays
on it: a formatter that finishes on a worker thread writes into the file it
started on, never into whichever tab is in front by then. Once that tab is
closed it reads as empty and ignores writes; ask again for the current tab.
`caretLine()` counts from the top of the file in a narrowed buffer too.

Start from the **[Example Plugin](https://github.com/adriandeleon/editora-plugins/tree/main/plugins/example)**,
which exercises every extension point. The copy in Editora's repository
([`examples/example-plugin`](https://github.com/adriandeleon/Editora/tree/master/examples/example-plugin))
binds Ctrl+Alt+Shift+E and Ctrl+Alt+Shift+Y, chords that are free in every
keymap. Read the full authoring guide in
[`docs/plugins.md`](https://github.com/adriandeleon/Editora/blob/master/docs/plugins.md).

## Publishing

The official registry is
[adriandeleon/editora-plugins](https://github.com/adriandeleon/editora-plugins),
a curated `index.json` plus each plugin's source and a downloadable `.zip`
(verified by sha-256 on install). Open a pull request there to add yours. Each
entry's `id` and `version` must match the `plugin.json` inside its archive.

A registry plugin whose feature moved into Editora is retired: an installed copy
is removed at startup and its id is never listed, installed or loaded again.
The GnuPG plugin (`gpg-tools`) is retired, since
[encrypted files](/docs/encrypted-files) are built in; its gpg path and last
recipient are kept.

## Troubleshooting

### A plugin does nothing

Look at its row in **Settings → Plugins** first. A plugin that did not load
says why there: its `plugin.json` could not be read, its main class was not
found, it failed to start, or it changed since you enabled it. A row that says
a restart will change something means the plugin you see is not the one
running yet. The Doctor's **Plugins** section (**View: Doctor**) lists the
plugins that failed to load or start, and a plugin that fails to start is also
named in the Message Log.

### A plugin's chord does nothing

Your own key bindings win over a plugin's, so a binding of yours on the same
chord shadows it. A plugin can also give chords for one keymap only (see
[Writing a plugin](#writing-a-plugin)), which do nothing in another keymap.
The command is still in the palette either way.

### If a plugin breaks startup

Start Editora with [`--disable-plugins`](/docs/cli#flags) to load no plugin for
that session; nothing is saved, and Settings → Plugins and the Doctor say it is
on. Then switch the plugin off or remove it, and restart. **Restart Now** from
that session starts Editora without the flag.

The Doctor's **Plugins** section shows how many plugins run and how many are
installed, and has a row for each plugin that failed to load, failed to start,
or started slowly, with a link to Settings → Plugins.
