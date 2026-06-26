# Command & keymap subsystem

How keyboard input becomes an action in Editora. Back to [the docs index](../README.md).

Editora is command-driven: every user action is a registered `Command`, dispatched
either from a keybinding or the command palette. The `command/` package is the core,
and `ui/MainController` wires it into a window. The pieces:

- [`command/Command.java`](../../src/main/java/com/editora/command/Command.java) — the unit of action.
- [`command/CommandRegistry.java`](../../src/main/java/com/editora/command/CommandRegistry.java) — the registry every action is looked up in.
- [`command/KeymapManager.java`](../../src/main/java/com/editora/command/KeymapManager.java) — chord sequence → command id.
- [`command/KeyDispatcher.java`](../../src/main/java/com/editora/command/KeyDispatcher.java) — the scene-level key filter that builds chords and dispatches.
- [`command/KeybindingEdits.java`](../../src/main/java/com/editora/command/KeybindingEdits.java) — pure logic behind the keybinding editor.

## Command

A `Command` is an `id`, a `title`, and a `run()`. Prefer the title-less factory; it
resolves the title and description from the message catalog lazily, so they follow the
active UI language:

```java
registry.register(Command.of("edit.myThing", this::myThing));
```

`Command.of(id, runnable)` reads `command.<id>` for the title via `Messages.tr`. The
default `description()` reads `command.<id>.desc` and returns `""` when the key is absent
(`tr` returns the key itself on a miss), so dynamically-registered commands without a
catalog entry degrade gracefully. There is also an explicit-title overload
`Command.of(id, title, runnable)`, used for synthetic commands whose title is data rather
than a catalog string (e.g. a saved macro's `macro.run.<slug>`).

## CommandRegistry

A `LinkedHashMap<String, Command>` keyed by id; insertion order is preserved so the palette
lists commands in registration order. `register(command)` adds, `remove(id)` drops (used to
clear stale `macro.run.*` commands on rename/delete), `get(id)` looks up, and `all()` returns
every command (the palette and keybinding editor populate from this).

`run(id)` executes the command and then notifies the **execution listener** — a single
`Consumer<String>` installed via `setExecutionListener`. It fires *after* the command runs, so
a control command like `macro.startRecording` has already flipped state before the notification
arrives. `MainController.setKeyDispatcher` wires it to the macro coordinator:

```java
registry.setExecutionListener(macroCoordinator::onCommand);
```

`MacroService.onCommand` ignores `macro.*` ids and `palette.show` (so recording the act of
recording, or opening the palette to invoke a command, isn't captured), and short-circuits when
not recording or while replaying — see
[`macro/MacroService.java`](../../src/main/java/com/editora/macro/MacroService.java).

## KeymapManager

Maps chord sequences to command ids. A named keymap loads from a bundled JSON resource;
user (and plugin) overrides layer on top.

### The five bundled keymaps

The static `AVAILABLE` map (insertion-ordered) is the source of truth for which keymaps exist:

| id | display name |
| --- | --- |
| `emacs` | Emacs (default) |
| `cua` | CUA |
| `sublime` | Sublime Text |
| `vscode` | Visual Studio Code |
| `intellij` | IntelliJ IDEA |

`displayName(id)` returns the display string, or the id itself if unknown. The four non-Emacs
keymaps are non-modal: they only remap chords onto the same command ids, so they fit the flat
resolver and never strand functionality (every command is palette-reachable).

### Per-OS `.mac` variants

Each GUI keymap ships a base `<name>.json` (Ctrl-based, Win/Linux) **and** a complete
`<name>.mac.json` (Cmd-based). `loadNamed(name)` prefers the `.mac` variant on macOS when the
resource exists, falling back to the base:

```java
String resource = mac && KeymapManager.class.getResource(macResource) != null
        ? macResource : baseResource;
```

It is a **full replacement**, not a merge, so there's no Ctrl-shadowing or modifier-token-order
bug. Emacs is single-file (`emacs.json`; Control on every platform — no `.mac`). A
package-visible `loadNamed(name, mac)` overload takes an explicit platform flag so tests don't
depend on the host OS.

### Overrides and the UNBIND sentinel

`applyOverrides(Map<String,String>)` layers a chord → id map on top of the loaded keymap. A
blank value is the `KeymapManager.UNBIND` sentinel (`= ""`): instead of binding, it **removes**
that chord, so a user override can suppress a base-keymap default. This is what the keybinding
editor's clear/rebind uses.

### Resolving chords

- `commandFor(sequence)` — the command id bound exactly to a chord sequence, or null.
- `isPrefix(sequence)` — true if some binding starts with `sequence + " "`, i.e. more keys are
  expected (e.g. `C-x` is a prefix of `C-x C-s`).
- `bindings()` — an immutable copy of the current map.

### Chord token format

A keymap JSON value is the command id; the key is a chord sequence built from one or more chord
tokens joined by spaces. A token is produced by `KeyDispatcher.chord()`: modifier prefixes in the
canonical order `C- M- Cmd- S-` followed by the key name. Examples from the bundled keymaps:

```json
"C-x C-s": "file.save",
"C-space":  "edit.setMark",
"Cmd-S-s":  "file.saveAs"
```

Letter keys lowercase; digits as-is; named keys like `space`, `enter`, `tab`, `backspace`,
`left`, `pageup`. Function keys fall through `keyName`'s default branch (`KeyCode.F5` → `"f5"`),
so VSCode/IntelliJ F-key bindings work with no engine change.

### Live switching

There is a **single shared** `KeymapManager` per launch, owned by `WindowManager` and read by
every window's `KeyDispatcher`. Switching the keymap (Settings → Keymaps picker or the
`keymap.select` palette command) sets `Settings.keymap`, persists, then calls
`WindowManager.reloadSharedKeymap()`, which rebuilds the one instance and re-applies overrides:

```java
keymap.loadNamed(settings.getKeymap());
keymap.applyOverrides(settings.getKeybindings());
// then, if plugins are on, each enabled plugin's manifest.keymap
broadcastSettingsApplied();
```

Because every dispatcher reads the same instance, the switch is instant with no restart; a stale
mid-chord prefix in any dispatcher self-cancels on the next key. The broadcast lets each window
refresh chord-derived hints (toolbar tooltips, palette bindings via `CommandPalette.refreshBindings()`,
tool-window tooltips, Welcome shortcut labels) so nothing stays frozen to the old keymap.

## KeyDispatcher

A per-window object installed on the scene as a `KEY_PRESSED` event filter (plus a `KEY_TYPED`
filter, and a `KEY_RELEASED` filter on non-macOS — see the Alt fix below). It translates each key
press into a chord token and resolves it against the shared `KeymapManager`.

### The dispatch loop

`handle(KeyEvent)` builds the token with `chord(event)` (null for a modifier-only press), then
forms the sequence (`pending + " " + token` when a prefix is buffered). With `commandFor`/`isPrefix`:

- a bound chord → consume the event, reset, `registry.run(commandId)`;
- a prefix → consume, buffer it in `pending`, echo `"<seq> -"` via the status listener;
- mid-chord with no continuation → consume, echo `"<seq> is undefined"`, reset;
- a lone unbound key → fall through (so normal typing works).

`pending` is the **multi-key chord buffer** for Emacs-style sequences (`C-x C-s`).

When a press is consumed, `consumedPress` is set so the paired `KEY_TYPED` is swallowed in
`handleTyped` — this matters when a command opens a modal dialog, whose deferred `KEY_TYPED`
would otherwise reach the editor after the dialog closes. On macOS, `handleTyped` also swallows
any Option-produced character (Option is the Meta key).

### `chord()` is public

`KeyDispatcher.chord(KeyEvent)` is `public static` because the keybinding-editor recorder reuses
it to capture a chord in the Settings scene (which has no global dispatcher). It returns tokens in
the canonical `C- M- Cmd- S-` order.

### The typed-char listener

`setTypedListener(Consumer<Character>)` installs a hook fed each genuine, non-consumed typed
character. The macro recorder uses it to capture typed text interleaved with command invocations.
`isRecordableChar` filters to printable characters plus tab/newline/carriage-return.

### `editora.ownsKeys` and the editor-context carve-out

A focused component (e.g. a tool window) can opt out of global dispatch by setting the
`editora.ownsKeys` node property. The dispatcher walks the target's ancestor chain
(`ownsKeys(target)`) and, for such a window, leaves only the **editor-context** chords to it — the
caret/text chords it repurposes for local navigation, identified by id prefix in `isEditorContext`
(`nav.*` and `edit.*`). Jump/window/view commands (`M-x`, `M-1`, `M-g`, …) and prefixes (`C-x …`)
stay global so they work even while a tool window is focused. The completion popup uses the same
property so its `C-n`/`C-p`/arrows aren't hijacked.

### `setPreDispatch` hook

`setPreDispatch(BiPredicate<String, EventTarget>)` is a first-look hook consulted only when no
prefix is pending: given the chord token + target, returning true means it handled the key (the
event is consumed and dispatch stops). `MainController` uses it so `M-g` closes a focused tool
window:

```java
dispatcher.setPreDispatch((token, target) -> {
    if (!"M-g".equals(token)) return false;
    ToolWindow tw = toolWindows.toolWindowOf(target);
    if (tw == null) return false;
    toolWindows.close(tw);
    // refocus the editor
    return true;
});
```

### Windows/Linux Alt menu-mode fix

On Windows a bare `Alt` — or an *unbound* `Alt+<key>` — is treated by the OS as menu/mnemonic
activation, which puts the native window into "menu mode": that freezes `KEY_TYPED` app-wide and
breaks the many `M-` chords (`M-x`, `M-g`, `M-1`…`M-9`, …) until restart. So on non-macOS,
`handle()` consumes a bare `Alt` press and any unbound key pressed while plain Alt is held, and
`install()` adds a `KEY_RELEASED` filter that consumes the bare `Alt` release.

The plain-vs-AltGr decision is the pure, unit-tested predicate:

```java
static boolean plainAltActive(boolean isMac, boolean altDown, boolean controlDown) {
    return !isMac && altDown && !controlDown;
}
```

AltGr is reported as Ctrl+Alt, so requiring Alt-down **and** Ctrl-up excludes it — international
AltGr typing and explicit Ctrl+Alt chords keep working. macOS is never affected (Option = Meta),
and a *bound* `M-` chord still runs and consumes normally.

## The keybinding editor

Settings → Keymaps lists every command (from `CommandRegistry.all()`) with its current chord and
lets the user rebind, reset, or reset-all. The mutation logic is the pure, toolkit-free
`KeybindingEdits`, operating over the base bindings + the current user-overrides map:

- `rebind(base, overrides, commandId, newSeq)` — drop the command's prior user entries, suppress
  each base default chord that isn't the new one (via the `UNBIND` sentinel), then bind the new
  chord. A blank `newSeq` falls back to `clear`.
- `clear(base, overrides, commandId)` — drop user entries and suppress every base default.
- `reset(base, overrides, commandId)` — drop user entries and remove the command's
  default-suppressors so the base default reappears.

`defaultChords(base, commandId)` lists every base chord bound to a command.

`MainController` wires it through the `SettingsWindow.ShortcutActions`/`Shortcut` interface:

- `shortcutRows()` builds the rows from `registry.all()` + `invertBindings()` (the current
  effective chord per command).
- `baseBindings()` is a fresh `KeymapManager.loadNamed` of the active keymap with **no** overrides
  — the defaults to rebind/reset against.
- `rebindShortcut`/`resetShortcut`/`resetAllShortcuts` call the `KeybindingEdits` helpers, persist
  the result to `Settings.keybindings`, and call `reloadKeymap()` so the change is live across all
  windows (overrides are global and layer on the active keymap).

The **recorder** turns a row into a live capture field that calls `KeyDispatcher.chord(e)`
(space-joining a multi-key sequence; Esc cancels) — it runs in the Settings window's own scene, so
there's no global dispatcher to interfere. The **conflict check** lives in
`SettingsWindow.rebindWithConflictCheck`: before binding, `ShortcutActions.commandUsing(seq)` (→
`KeymapManager.commandFor`) reports whether the chord is already taken, and a confirmation dialog
warns before stealing it. The same path serves the inline Macros keybinding row.

User overrides persist in `Settings.keybindings` (a `Map<String,String>` of chord → id, with blank
values meaning UNBIND), serialized with the rest of `settings.toml`.

## Adding a command

See [extending.md → Add a command](../extending.md#add-a-command). In short: register in
`MainController.registerCommands()`, add `command.<id>` + `command.<id>.desc` to all six i18n
catalogs, and (optionally) add a chord → id mapping in the bundled keymap JSON (and the `.mac`
variant for GUI keymaps). The palette and keybinding editor populate from the registry, so a
properly registered command appears automatically — never wire a user-facing action that bypasses
the registry.

## What `KeymapsTest` guarantees

[`KeymapsTest`](../../src/test/java/com/editora/command/KeymapsTest.java) guards every bundled
keymap without a GUI:

- **Parse + valid ids** — each keymap (including `.mac` variants) parses, and every value is a real
  `command.*` i18n key (the registry-independent source of truth).
- **Canonical token order** — every chord token uses modifiers in the exact `C- M- Cmd- S-` order
  `KeyDispatcher.chord()` emits.
- **Base ↔ `.mac` parity** — for each GUI keymap, the base and `.mac` files bind the **same set of
  command ids** (only the accelerators differ).
- **UNBIND behavior** — a blank override value removes a base chord (`applyOverrides`).
- **Registry resolves** — every `AVAILABLE` id loads non-empty on both the base and `.mac` paths.
