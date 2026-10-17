# Integrated terminal

Status: **in progress** ([ADR 0014](../decisions/0014-integrated-terminal.md)). The emulator, the view and
a local shell are in `com.editora.terminal`; nothing opens a terminal yet. The tool window, "Open in
Terminal", and the SSH, container and WSL connectors follow.

## Where the code came from

The package is a port of [Termina](https://github.com/adriandeleon/Termina) at **`caebc9f`** (0.7.1, MIT,
same author). Copied, not shared: fixes made here are back-ported to Termina by hand. When porting a later
Termina change, diff Termina from `caebc9f` and update this commit here.

| Here | Termina | Changed in the port |
| --- | --- | --- |
| `TerminalView` | `ui/TerminalView` | Starts from a `TerminalConnector.Factory` instead of launch options. `TerminalCursor`, `TerminalPalettes` and a default font family replace Termina's `Settings`, `Theme` and `MonospaceFonts`. The menu keeps the terminal's own items: Open Link, Copy Link Address, Copy, Paste, Select All, Clear Scrollback. The window adds the rest (`setMenuExtras`) and supplies icons (`setMenuIcons`). The dev-capture hooks, zoom row, menu-bar toggle and new tab/window items are gone. |
| `TerminalSession` | `term/TerminalSession` | Takes a `TerminalConnector`; no longer starts a process. |
| `TerminalConnector` (new) | — | JediTerm's `TtyConnector` plus `pid()`: `-1` for a shell that is not on this machine. |
| `LocalShell`, `LocalPtyConnector` | `pty/ShellLauncher`, `pty/PtyTtyConnector` | Environment from `ProcessRunner.applyUserEnv` (augmented PATH, user locale) plus `TERM`, `COLORTERM`, `TERM_PROGRAM=Editora`. Drops an inherited `COLUMNS`/`LINES`. |
| `TerminalPalettes`, `SyntaxColors` (new) | `ui/Theme` | Derives the 16 ANSI colours from an editor theme's token colours (see below) instead of four fixed themes. |
| `FxTerminalDisplay`, `TerminalTitle` | `ui/…` | An untitled terminal reports `""`, and the window shows its own label. |
| `KeyTrace` | `ui/KeyTrace` | Property `-Deditora.terminal.keyTrace`, logger `com.editora.terminal.keys`. |
| `KeyEncoding`, `MouseEncoding`, `CellWidth`, `ColumnMap`, `RunColumns`, `ScrollBarModel`, `TerminalPalette`, `Link*`, `ProcessCwd`, `CwdWatcher`, `TerminalExecutors`, `DisabledTypeAhead` | same names | Package only. |

Termina's window, tabs, settings, profiles, command palette, update check, CLI and `DevCapture` are not
ported; Editora has its own. The tests came with the code. `TerminalViewFxTest` is new: it drives the view
over an in-memory connector.

## How it fits together

- **`TerminalConnector`** is the only part that knows where the shell runs. A view never asks.
- **`TerminalSession`** wires a connector to JediTerm's `TerminalStarter` and owns the emulator thread, which
  writes into the `TerminalTextBuffer` under its lock.
- **`TerminalView`** (a `Region`) paints that buffer on a `Canvas` once per frame at most, per style run, and
  turns keys, mouse and wheel into terminal bytes. Termina's README explains the encoder, selection,
  mouse-reporting and link rules; the code comments carry the same reasons.
- **`FxTerminalDisplay`** is JediTerm's callback into the UI (cursor, title, alternate screen, mouse mode),
  called on the emulator thread.

## Palette

`TerminalPalettes.derive(SyntaxColors)` maps an editor theme's colours: keyword → red, escape → green,
type → yellow, number → blue, function → magenta, caret → cyan. Bright colours move toward white on a dark
ground and toward black on a light one. ANSI white is the text colour pulled toward the background, so on a
light theme it is dark: a program that sets colour 7 must stay readable. `EDITORA_DARK` and `EDITORA_LIGHT`
are derived from the flagship themes; their normal colours match Termina's hand-made pair.

Reading the *active* theme's colours needs a scene. The adaptive themes take theirs from the AtlantaFX
`-color-*` variables, which only a window's stylesheets resolve. That comes with the tool window.

## Known behaviour

- On Linux pty4j sets the window size just after the child starts, so a program started directly (not a
  shell) can read 0×0 if it asks at once. See `TerminalImageCheck`.
- Local shell cwd tracking (`ProcessCwd`) works on Linux (`/proc`) and macOS (`proc_pidinfo` via JNA), not
  Windows. Remote shells will report their directory with OSC 7.
- The scrollbar is coloured through the looked-up colours `-terminal-thumb` and `-terminal-thumb-hover`.
  Editora's stylesheet has no `.terminal-scroll-bar` rule yet; it comes with the tool window.
