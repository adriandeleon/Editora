# Integrated terminal

Status: **in progress** ([ADR 0014](../decisions/0014-integrated-terminal.md)). The Terminal tool window
runs shells on this machine, Open Terminal Here opens one, and links in the output open in the editor. The
SSH, container and WSL connectors follow.

## The tool window

- **`ui/TerminalCoordinator`** owns the panel and the commands: `tool.terminal` (toggle; opening it starts a
  shell if none is running), `terminal.new` (another tab), `terminal.close` (the selected one).
  `MainController` keeps the `ToolWindow` (bottom, `Icons.terminal()`; Build Output moved to `Icons.tools()`)
  and calls `applySettings`/`applyTheme` from `applyEditorTheme`, which runs on every settings apply.
- **`ui/TerminalPanel`**: one tab per shell, with a "+" floating at the strip's right end and a target chip
  ("Local") on each tab. A tab's title follows the shell's title or directory. Disposal is driven off the
  tab list (✕, Close Terminal, the shell exiting, the window closing), so every path ends the shell. Tabs are
  not reorderable: a reorder is a remove plus an add, which the list listener would read as a close.
- **Where a shell starts:** the project folder when it is local, else the active file's folder, else home. A
  remote project still gets a local shell until its host has a connector.
- **Closing a busy shell asks first** (`RunningProgram`: the shell's child processes; Settings → Terminal can
  turn it off). Local shells are tracked by `ProcessRegistry` so a crash does not orphan them. pty4j's
  `UnixPtyProcess` throws from `toHandle()`, which the registry needs, so `LocalPtyConnector.trackableProcess()`
  wraps it (`PtyHandleProcess`). Before that wrapper, tracking threw after the shell had started.

## Open Terminal Here and links

- **Open Terminal Here** (`file.openTerminal`, the tab menu, the Project tree, the breadcrumb) all go through
  `MainController.openTerminalAt`. It opens a tab in the Terminal tool window, in the folder
  (`TerminalCoordinator.openAt`; the first shell the window would start by itself is skipped, so it does not
  open with a second tab in the project folder). With `terminalUseExternal` (Settings → Terminal, schema
  131) it starts the system's terminal as before (`DesktopActions`). A remote path still says it cannot.
- **Links:** a path or URL under the pointer with Ctrl held (Cmd on macOS) is underlined; a click, or the
  menu's Open Link, opens it. `TerminalView` resolves a relative path against the shell's directory and only
  offers one that exists. The coordinator's `LinkActions` sends files to `MainController.openAndGoto` at the
  1-based `line:col` the output carried, folders to the Project tree, and URLs to the browser.

## Keys

A focused terminal owns the keyboard. `TerminalPanel` sets `KeyDispatcher.SHELL_KEYS`, a predicate that says
which chords still reach Editora (`TerminalKeys.reachesEditora`). The dispatcher leaves every other press to
the terminal, before prefixes, prefix arguments and the plain-Alt guard. So Ctrl+C is SIGINT, Ctrl+W deletes a
word, Escape is vim's, and Emacs's C-x is the shell's.

- Through by default: `tool.*`, `palette.show`, `search.everywhere`, `terminal.*` (Settings → Terminal →
  Commands that reach Editora; `Settings.DEFAULT_TERMINAL_IDE_COMMANDS`, kept equal to
  `TerminalKeys.DEFAULT_IDE_COMMANDS` by a test). On macOS every Cmd chord reaches Editora too.
- Copy and Paste are the terminal's own: Ctrl+Shift+C/V, Cmd+C/V on macOS. A filter on the panel runs before
  the view's, which would otherwise encode Ctrl+Shift+C as ^C.
- Bindings: `tool.terminal` is Alt+F12 (IntelliJ) and Ctrl+` (VS Code, CUA, Sublime); `terminal.new` is
  Ctrl+Shift+` in the last three. Emacs has no binding (`M-x`).

## Settings

Settings → Terminal (Languages & Tools), schema 130: `terminalShell` (blank = login shell),
`terminalFontSize` (0 = the editor's size; the face is always the editor's), `terminalCursor`, `terminalBell`,
`terminalScrollback`, `terminalIdeCommands`, `terminalConfirmClose`. Font, cursor, bell and the key list apply
to open terminals. Scrollback and shell apply from the next tab.

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

The active theme's colours are read from the scene (`ThemeSampler`). It adds invisible probe nodes styled
like the editor (`.editor-area`, `.text.keyword`, …) to the panel, applies CSS, and reads back what resolved.
That works for the adaptive themes too, whose colours are AtlantaFX `-color-*` variables only the window's
stylesheets resolve. If the panel is not in a scene or a colour is not plain, the flagship palette matching
the UI's darkness is used. Each tab's content wrapper is painted the terminal's background, so a part row
below the grid matches. The selected tab uses the app-wide `-editor-surface` fill, so it merges into the
terminal as it does into the editor.

## Known behaviour

- On Linux pty4j sets the window size just after the child starts, so a program started directly (not a
  shell) can read 0×0 if it asks at once. See `TerminalImageCheck`.
- Local shell cwd tracking (`ProcessCwd`) works on Linux (`/proc`) and macOS (`proc_pidinfo` via JNA), not
  Windows. Remote shells will report their directory with OSC 7.
- The scrollbar is coloured through the looked-up colours `-terminal-thumb` and `-terminal-thumb-hover`
  (`.terminal-scroll-bar` in `app.css`, Termina's rules).
- No palette rescues a prompt built for a dark terminal. Oh My Bash's agnoster theme draws `user@host` in the
  default foreground on ANSI black, which is dark on dark in any light theme.
