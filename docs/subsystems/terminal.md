# Integrated terminal

Status: **in progress** ([ADR 0014](../decisions/0014-integrated-terminal.md)). The Terminal tool window
runs shells where a project's commands run: this machine, an SSH host, a dev container or a WSL distribution.
Open Terminal Here opens one, links in the output open in the editor, and shell integration reports the
shell's folder and its prompts.

## Terminals on an SSH host

- **Seam:** `ExecTarget.startTerminal(TerminalSpec)` returns a `process.TerminalChannel` (output, input, resize,
  `lostConnection()`). It is kept apart from `ExecSpec`, which never asks for a PTY. Targets without terminals
  inherit the default, which says so; `ExecTargets`' unavailable target gives its usual reason (turned off, not
  allowed, not connected). `terminal.ChannelConnector` adapts a channel to JediTerm.
- **SSH (`vfs/SshTerminalChannel`):** an exec channel of the window's own session that asks for a PTY:
  `xterm-256color`, the laid-out size, `window-change` on resize. **`setUsePty(true)` is required:** MINA's
  `ChannelExec` sends no `pty-req` by default, even with a PTY configuration (`ChannelShell` does). Without it
  the shell had no tty, no size and no hang-up, which only the real-server probe showed. The command is
  `exec /bin/sh -c '… cd -- <dir> || cd; exec "${SHELL:-/bin/sh}" -l'`, wrapped for fish and csh accounts.
  The local `terminalShell` setting is never sent to a host.
- **Budget:** a terminal takes one of the connection's five long-lived slots (D2) and frees it when its channel
  closes. The refusal says so ("each terminal on it is one").
- **A dropped connection:** the server hangs up the PTY, so the shell and its foreground jobs get SIGHUP as with
  a dropped `ssh`; there is no watchdog (D8). The channel reports `lostConnection()`, and the tab keeps its text
  under an amber "Connection lost" banner with **Reconnect**. A shell that exits, or one Editora closes, still
  closes its tab. Tabs remember their folder as a `Vfs` storable string, so Reconnect finds it on the new
  connection; a dead path on the old file system would not do.
- **Where:** a remote project's terminals start in its root on the host, and Open Terminal Here on a remote
  folder opens there (the system terminal is never used for it). The tab chip shows the host
  (`TerminalCoordinator.hostOf`).
- **Tests:** `SshTerminalFxTest` (embedded server: answers, budget, exit vs close vs drop),
  `RemoteTerminalPanelFxTest` (a tab answers, survives a drop, and reconnects on the new connection), and
  `RealSshdProbeFxTest`'s two terminal steps. Those check a real tty and `stty size` before and after a resize,
  and that a dropped connection kills the terminal's foreground program. They pass against OpenSSH 10.0.

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

## Terminals in a container or a WSL distribution

A container's mounted folder and a `\\wsl.localhost` folder are paths of this machine that `ExecTargets` maps to a
target, so the coordinator asks `ExecTargets.forPath(dir).isLocal()`, not `Vfs`, whether a folder's shell
belongs elsewhere.

- **`terminal.PtyChannel`:** the target's own launcher run here on a pty4j PTY. That is `docker|podman exec -i -t
  [-u user] <id> /bin/sh -c <script>` (`ContainerEngine.terminalArgv`; the engine's client wants a tty of its own
  for `-t`, and forwards SIGWINCH), or `wsl.exe -d <distro> --exec /bin/sh -c …`, through
  `WslExecTarget.windowsSafe`, on ConPTY.
- **The script** is `PosixShell.terminalScript(cwd, markPid, integration)`, shared with SSH (which marks its pid
  too, for the close check below). It prints a pid line
  (`TERMINAL_PID_MARK`), exports `TERM`/`COLORTERM`/`TERM_PROGRAM`, changes to the folder, and execs the login
  shell: `$SHELL`, else bash, else sh, since containers often set none. `PidMarkStream` takes the pid line out
  of the output and keeps the pid.
- **Closing:** Docker and Podman keep a program whose `exec` client died (measured: without the hang-up the
  probe's `sleep` stays in the container). So close sends `kill -s HUP -- <pid>` through the target first,
  then ends the launcher. SIGTERM alone does not end an interactive shell, so the PTY is closed too, with a
  forced kill if anything is left after 2 s. WSL 1 ends the shell with `wsl.exe`; the hang-up covers WSL 2.
- No lost-connection state: when the container stops, the shell ends and the tab closes. The chip names the
  container or the distribution (`TerminalCoordinator.labelOf`).
- **Tests:** `PtyChannelTest` (the script on a local PTY: pid line hidden and kept, resize, hang-up on close),
  `PidMarkStreamTest`, `ContainerEngineTerminalTest`. Two opt-in steps: `ContainerProbeFxTest`'s terminal test
  (a tty in the container, `stty size` before and after a resize, the folder, the program gone after close;
  passes under Podman 5.4, and is the docker job of the Remote probe workflow) and `WslProbeTest`'s (the
  Windows job).

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

## Shell integration

On by default (`terminalShellIntegration`, Settings → Terminal, schema 132; D7). The shell reports its folder
with OSC 7 and marks its prompts with OSC 133 (`A` prompt, `C` command output starts, `D;<status>` it ended), as
VS Code, iTerm2 and WezTerm shells do.

- **Read loop:** `TappedConnector` wraps every connector and feeds what it reads through `OscTap`, which takes the
  two sequences out of the stream and passes everything else on (other OSCs too). An event is delivered on the
  *next* read, after JediTerm has drawn the text before it, so the cursor is where the sequence was. A sequence
  longer than 4 KB is passed through unread.
- **Scripts** (`resources/com/editora/terminal/shell-integration/`): `editora.bash` is bash's `--init-file`. It
  reads the login files itself (`/etc/profile`, then the first of `~/.bash_profile`, `~/.bash_login`,
  `~/.profile`), then adds its hook to the front of `PROMPT_COMMAND` and the `C` mark to `PS0`. `PS0`'s `$(…)`
  runs in a subshell, so the mark is a literal escape, and "a command ran" (for `D`) is `HISTCMD` changing. zsh
  gets `ZDOTDIR` wrappers that source the user's own `.zshenv`, `.zprofile`, `.zshrc` and `.zlogin` with
  `ZDOTDIR` restored, plus `precmd`/`preexec` hooks. Other shells start plain.
- **Where they live:** `ShellIntegration.version()` hashes the scripts, and they are written once per version:
  locally under `<config>/terminal/shell-integration/<version>/` (`LocalShell.integrated` adds the arguments),
  on a target through its `TargetCache` (`installOn`), once per target per window. The remote script checks the
  files are readable before using them, and a target without a writable cache gets plain login shells.
- **What it drives:** the tab's folder (OSC 7 replaces `ProcessCwd`, and is the only cwd a remote shell has),
  relative links resolved against it on the target (`LinkPaths.resolveOn` with the target's `toPath`), **New
  Terminal Here** in the terminal's menu (a tab in the shell's current folder), **Copy Last Command Output**
  (what came between the last `C` and `D`), and prompt jumps: Ctrl+Shift+Up/Down (Cmd+Up/Down on macOS) scroll
  to the previous or next prompt.
- **Marks move with their text** as lines scroll into history. JediTerm's history listener fires only on
  resizes and clears, so `TerminalView` derives the shift on every model change from the history count plus the
  lines a full history drops (`linesDiscardedFromHistory`). The same fix keeps a scrolled-back view and a
  selection on their text, which Termina's port did not.
- **Closing a remote shell that is running something asks first**, as a local one does: the script's pid line
  gives the shell's pid on the target, and `ps -A -o ppid=,comm=` there lists its children
  (`TerminalCoordinator.remotePrograms`, 3 s budget; no answer means close without asking).
- **Tests:** `OscTapTest`, `ShellIntegrationTest`, `ShellIntegrationPtyTest` (real bash and zsh on a PTY:
  folder, marks, exit status; zsh is skipped where it is not installed), `TerminalViewFxTest` (cwd report, last
  output, prompt jumps through history, a scrolled-back view staying put), the `PosixShellTest` and
  `LocalShellTest` argument cases, and `RealSshdProbeFxTest`'s shell-integration step: the scripts installed
  through the host's cache, OSC 7 and 133 from the host's bash or zsh, and the close check seeing a `sleep`
  (passes against OpenSSH 10.0; skipped for an account with another shell).

## Keys

A focused terminal owns the keyboard. `TerminalPanel` sets `KeyDispatcher.SHELL_KEYS`, a predicate that says
which chords still reach Editora (`TerminalKeys.reachesEditora`). The dispatcher leaves every other press to
the terminal, before prefixes, prefix arguments and the plain-Alt guard. So Ctrl+C is SIGINT, Ctrl+W deletes a
word, Escape is vim's, and Emacs's C-x is the shell's.

- Through by default: `tool.*`, `palette.show`, `search.everywhere`, `terminal.*` (Settings → Terminal →
  Commands that reach Editora; `Settings.DEFAULT_TERMINAL_IDE_COMMANDS`, kept equal to
  `TerminalKeys.DEFAULT_IDE_COMMANDS` by a test). On macOS every Cmd chord reaches Editora too.
- Prompt jumps (Ctrl+Shift+Up/Down, Cmd+Up/Down on macOS) are the terminal's own too, never Editora's.
- Copy and Paste are the terminal's own: Ctrl+Shift+C/V, Cmd+C/V on macOS. A filter on the panel runs before
  the view's, which would otherwise encode Ctrl+Shift+C as ^C.
- Bindings: `tool.terminal` is Alt+F12 (IntelliJ) and Ctrl+` (VS Code, CUA, Sublime); `terminal.new` is
  Ctrl+Shift+` in the last three. Emacs has no binding (`M-x`).

## Settings

Settings → Terminal (Languages & Tools), schema 130: `terminalShell` (blank = login shell),
`terminalFontSize` (0 = the editor's size; the face is always the editor's), `terminalCursor`, `terminalBell`,
`terminalScrollback`, `terminalIdeCommands`, `terminalConfirmClose`. Font, cursor, bell and the key list apply
to open terminals. Scrollback and shell apply from the next tab. Schema 131 adds `terminalUseExternal`; 132
adds `terminalShellIntegration`, which applies from the next tab.

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
  Windows. A shell with integration reports its own directory (OSC 7), which wins over the pid's.
- A remote terminal opens on the FX thread (as since SSH terminals came in), and the first one on a target also
  writes the integration scripts there: a few SFTP writes, or one `exec` per file in a container.
- The scrollbar is coloured through the looked-up colours `-terminal-thumb` and `-terminal-thumb-hover`
  (`.terminal-scroll-bar` in `app.css`, Termina's rules).
- No palette rescues a prompt built for a dark terminal. Oh My Bash's agnoster theme draws `user@host` in the
  default foreground on ANSI black, which is dark on dark in any light theme.
