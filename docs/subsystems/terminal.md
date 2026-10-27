# Integrated terminal

Status: **in progress**, reviewed 2026-10-18 and fixed in one pass ([ADR 0014](../decisions/0014-integrated-terminal.md)). The Terminal tool window
runs shells where a project's commands run: this machine, an SSH host, a dev container or a WSL distribution.
Open Terminal Here opens one, links in the output open in the editor, and shell integration reports the
shell's folder and its prompts. Scrollback search, OSC 8 links and type-ahead work in every tab, and Run and
Debug can run a program in a tab instead of their consoles.

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
- **CI:** the **Terminal probe** job (`full-tests.yml`) runs the remote probe's terminal steps against dropbear
  on the runner (`-Dremote.probe.only`) and the container probe's terminal test against Docker: daily on
  `master`, before every release, and on a pull request labelled `full-ci` — give that label to every pull
  request that touches the terminal.
- **Tests:** `SshTerminalFxTest` (embedded server: answers, budget, exit vs close vs drop),
  `RemoteTerminalPanelFxTest` (a tab answers, survives a drop, and reconnects on the new connection), and
  `RealSshdProbeFxTest`'s two terminal steps. Those check a real tty and `stty size` before and after a resize,
  and that a dropped connection kills the terminal's foreground program. They pass against OpenSSH 10.0.

## The tool window

- **`ui/TerminalCoordinator`** owns the panel and the commands: `tool.terminal` (shows the terminal and
  focuses it; hides it only when it already has the keyboard, as IntelliJ's Alt+F12 does), `terminal.new`,
  `terminal.close`, `terminal.find`, `terminal.next`/`previous` (also Ctrl+PageDown/PageUp in a terminal),
  `terminal.rename`, `terminal.moveTabLeft`/`Right` (also Ctrl+Shift+PageUp/PageDown, a drag of the tab, or the
  tab's own menu) and `terminal.focusEditor` (Shift+Escape in every keymap). The Window menu has Terminal and New
  Terminal, the File menu Open Terminal Here, and the Emacs keymap binds `M-0`.
  `MainController` keeps the `ToolWindow` (bottom, `Icons.terminal()`; Build Output moved to `Icons.tools()`)
  and calls `applySettings`/`applyTheme` from `applyEditorTheme`, which runs on every settings apply.
- **`ui/TerminalPanel`**: one tab per shell, with a "+" floating at the strip's right end and a target chip
  ("Local") on each tab. A tab's title follows the shell's title or directory, unless the user named it; tabs
  with the same title are numbered. Disposal is driven off the tab list (✕, Close Terminal, the shell exiting,
  the window closing), so every path ends the shell. A reorder is a remove plus an add, so it runs with
  `reordering` set and the listener closes nothing (Termina's rule); `TerminalTabReorder` is the drop arithmetic.
- **Focus:** the panel is a `ToolWindowContent`, so every path that shows it (stripe, float, jump, the
  next-window command) focuses the selected shell, not the panel. A shell still starting takes the keyboard when
  it comes up only if something asked for it: the shell a restored window starts by itself
  (`newTerminalQuietly`) leaves the editor its keys. A floating terminal's stage gets the window's
  `KeyDispatcher` (`ToolWindowManager.setFloatingSceneSetup`). When the last tab goes, the tool window hides.
- **Where a shell starts:** the project folder (on its host for a remote project), else the active file's
  folder, else home. A local shell is opened off the FX thread too (`Factory.background`: the fork, and the
  first time pty4j's native library, took a quarter to half a second), without a "Connecting…" note.
- **Closing a busy shell asks first** (`RunningProgram`: the shell's child processes; Settings → Terminal can
  turn it off). A remote shell is asked off the FX thread (`TerminalPanel.requestClose`; the tab stays until
  the answer), with a `/proc` fallback where the target has no `ps`. Closing the window and quitting ask too,
  one question listing every busy tab in every window (`busyTabs`, remote ones within 2 s). Local shells are tracked by `ProcessRegistry` so a crash does not orphan them. pty4j's
  `UnixPtyProcess` throws from `toHandle()`, which the registry needs, so `LocalPtyConnector.trackableProcess()`
  wraps it (`PtyHandleProcess`). Before that wrapper, tracking threw after the shell had started.
- **How a terminal's process ends:** a shell is *hung up* (`ProcessRegistry.HangsUp`): its pty is closed, so it
  gets SIGHUP and passes it to its jobs (no SIGTERM: pty4j's `destroy` signals the whole process group), a job the user detached (`nohup`, `disown`) keeps running on Linux (not on macOS, where CI saw it end with the shell; cause unknown), and
  only the shell itself is force-killed after the grace period. The shutdown hook does the same, rather than
  killing every descendant. A program (Run in Terminal) is ended as console Run ends one, `killTree`: TERM to
  its tree, KILL to what ignored it; its streams stay open until it is gone, so its last words are shown.

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
- **Tracked:** once the pid line arrives the shell is `ProcessRegistry.track`ed as a `RemoteProcess`
  (`PtyChannel.Shell`): the shutdown hook waits, bounded, for the hang-up in progress instead of exiting under
  it, and the target's orphan record lets the next start reap a shell a crash left behind.
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
  `~/.profile`), and `~/.bashrc` when that profile does not mention it (installers' `.bash_profile`s often do
  not). It defines `logout` and runs `~/.bash_logout` at exit, which a login shell would, then adds its hook to
  the front of `PROMPT_COMMAND` and the `C` mark to `PS0`. `PS0`'s `$(…)` runs in a subshell, so the mark is a
  literal escape, and "a command ran" (for `D`) is the command number `\#` moving (`${…@P}`, bash 4.4+; the
  history number before that, which `HISTCONTROL=ignoreboth` does not move for a repeat). The folder is sent with
  every byte outside the unreserved set percent-encoded, and `ShellIntegration.parseCwdUrl` decodes by hand
  (`URI` cut a path at `#` and `?`). A local tab takes a report only from this machine's host name, so a remote
  prompt after `ssh` does not move it. zsh
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
- **Marks stay with their text** as lines scroll into history. JediTerm's history listener fires only on
  resizes and clears, so `TerminalView` derives the shift on every model change from the history count plus the
  lines a full history drops (`linesDiscardedFromHistory`), and adds it to `pushedLines`. Marks and search hits
  are kept at absolute lines (row + `pushedLines`), so nothing is rewritten per line of output (holding 1,000
  hits made output 3–6× slower). A scrolled-back view and a selection still shift with it.
- **Closing a remote shell that is running something asks first**, as a local one does: the script's pid line
  gives the shell's pid on the target, and `ps -A -o ppid=,comm=` there lists its children
  (`TerminalCoordinator.remotePrograms`, 3 s budget; no answer means close without asking).
- **Tests:** `OscTapTest`, `ShellIntegrationTest`, `ShellIntegrationPtyTest` (real bash and zsh on a PTY:
  folder, marks, exit status; zsh is skipped where it is not installed), `TerminalViewFxTest` (cwd report, last
  output, prompt jumps through history, a scrolled-back view staying put), the `PosixShellTest` and
  `LocalShellTest` argument cases, and `RealSshdProbeFxTest`'s shell-integration step: the scripts installed
  through the host's cache, OSC 7 and 133 from the host's bash or zsh, and the close check seeing a `sleep`
  (passes against OpenSSH 10.0; skipped for an account with another shell).

## IDE features

- **Find** (`terminal.find`, the menu's Find…, Ctrl+Shift+F or Cmd+F): `TerminalFindBar` sits over the selected
  tab's content, top right, and follows the selection to another tab. It searches once the query has held still
  for 150 ms, and the scan (`TerminalView.searchAsync`) runs on a background thread, newest row first, a chunk of
  rows per hold of the buffer lock, keeping the newest 1,000 hits. `TerminalSearch.inRow` folds case per
  character and skips JediTerm's wide-character placeholder, so CJK text is found. The view paints the hits in a
  wash of the palette's yellow, stronger on the current one, which starts at the latest hit at or above the
  bottom of the view. Enter steps up (older output), Shift+Enter down, both wrap; Escape closes and gives the keys back. Hits
  shift with their text as lines scroll into history, like the prompt marks. Output that arrives while the bar
  is open marks the search stale, and the next step finds again from the current hit.
- **OSC 8 links**: `TerminalSession` gives JediTerm `Osc8Links.filter()`. JediTerm keeps what it returns in the
  style of every cell the program writes until the closing sequence, so the URI is in the cells. `linkAt`
  reads the cell under the pointer before scanning the text (`osc8At`), and the run of cells with the same
  target is the link. Only http, https, ftp, mailto and file are links. A link whose text is not its address
  shows the address in a tip while hovered with the modifier. A remote shell's `file://` goes through the tab's
  path mapping.
- **Type-ahead** (`terminalTypeAhead`, on): JediTerm's `TerminalTypeAheadManager` predicts into
  `TypeAheadOverlay`, our model. JediTerm's own model writes predictions into a package-private field only its
  Swing renderer reads, so ours keeps the predicted text of the cursor's row, and the view paints it from the
  first differing cell in ANSI bright black, with the cursor where the prediction puts it. The data stream
  calls `onTerminalStateChanged` after each chunk, and `JediTermDebouncerImpl` clears stale predictions. The
  manager predicts only once two echoes took 100 ms or more, and stops when they get fast, so local shells
  never show a prediction. Nothing is predicted on the alternate screen.
- **Kitty keyboard protocol**: `KeyboardModeInterceptor` wraps the stream JediTerm reads and takes out the four
  mode requests (`CSI > < = ? u`), which JediTerm would read as SCORC and move the cursor; `ESC c` resets them.
  `KeyboardFlags` keeps a stack per screen and answers the query. While the screen in front has flags set,
  `KittyKeyInput` encodes presses, repeats and the releases owed (`KittyKeys`); with none, every key takes the
  legacy path.
- **Selection**: one convention, end exclusive and in slots (`SelectionGeometry`), so the highlight is what is
  copied. A drag ends at the nearest cell boundary; past an edge it scrolls by time, faster further out
  (`AutoScroll`, ticked by a `Timeline`). Alt+drag (Option, or Ctrl+Alt) makes a block in columns
  (`BlockSelection`). A selection is "settled" at the end of a drag, a double or triple click and Select All:
  its text is kept, set as PRIMARY on Linux (`PrimarySelection`), and copied when `terminalCopyOnSelect` is on.
  Keystrokes leave it; the next frame after a buffer change drops it if the text under it is not the settled
  text. Middle-click pastes PRIMARY, or the clipboard where there is none. Shift+Insert pastes, Ctrl+Insert
  copies.
- **Notifications and the clipboard**: `OscTap` takes OSC 9, 777 and 52 out of the stream too
  (`OscSequences` parses them). A notification marks its tab (`terminal-notified`) when it is out of sight and,
  at most once per terminal every two seconds, `DesktopNotifier` posts it unless that terminal is in front or
  `terminalNotifications` is off. OSC 52 sets the clipboard when `terminalClipboardWrites` is on; a query is
  never answered.
- **Tab titles**: `CwdWatcher` also reports the shell's first child (`RunningProgram`, children read by parent
  through `ChildProcesses`), and `TerminalTitle.tab` shows it before the folder when the shell set no title.
- **Programs in a tab**: `TerminalCoordinator.runProgram(title, dir, argv, env, onExit)` opens a tab running a
  program instead of a shell, where `dir`'s commands run.
  - Locally it uses `LocalShell.startProgram` (the user's environment plus `env`, a null value unsetting).
  - A local shell's environment drops what describes Editora's own launch (`LocalShell.LAUNCH_VARIABLES`:
    `TMUX`, `STY`, `VTE_VERSION`, the AppImage's), sets `TERM_PROGRAM_VERSION`, and fills a missing locale
    with `C.UTF-8` on Linux. Test runs give shells their own HOME and no history (`editora.terminal.home`).
  - On a target it uses `TerminalSpec.program`. Its script (`PosixShell.programScript`) marks the pid, sets
    the variables and runs the program through the account's login shell, with `exec` all the way down, so the
    marked pid is the program's. fish and csh are passed over for `/bin/sh`.
  - When the program ends the tab stays, with a corner chip giving the exit code (`run.exited`), and `onExit`
    gets it: -1 when the tab was closed under the program or the connection went.
  - `ProgramRun.stop` ends the program and keeps the tab.
  - Closing the tab while its program runs asks first: the busy-shell question, naming the program's
    executable, under the same `terminalConfirmClose` setting. An ended program's tab closes without asking.
- **Run in a Terminal tab** (a run configuration's Console option, `RunConfiguration.runInTerminal`, workspace
  schema 21): `RunCoordinator.runConfig` marks the request, and `streamRun`, where every type's launch ends,
  sends the command to `runProgram` instead of `RunService`.
  - The before-launch step still runs in the console.
  - The `read -p` shim's variables are dropped, since a terminal is a tty.
  - The status line gives the exit code. Run's Stop, the toolbar and the Run stripe follow the program
    (`RunCoordinator.terminalProgram`); a stopped program's end says "stopped".
  - The next run of the same configuration (same title and folder) reuses the ended run's tab; one still running
    keeps its tab.
  - On a target the configuration's variables are set after the login profile ran (`PosixShell.AFTER_LOGIN`,
    passed as arguments), as console Run sets them; a working folder that is not there fails the run (exit 126,
    or "folder not found" locally) instead of running in the home folder.
  - Rerun is left unavailable rather than replaying an older console run.
- **The debugged program in a tab** (`debugProgramInTerminal`, off; needs the program console): `DebugCoordinator`
  gives `DapManager` a `ProgramTerminal` (the window's `runProgram`), and `Debuggee` then starts the adapter's
  `runInTerminal` command there, untouched, instead of through `RunService`.
  - The tab is "Debug: <file>".
  - The Debug console offers no input.
  - The program's exit ends the session, and Stop ends the program.
  - The reply names no pid while a remote program's pid line has not arrived (rather than -1).
- **Tests:**
  - `TerminalSearchTest`, plus `TerminalViewFxTest` (search through history and its shift; type-ahead over a
    slow, fast or disabled link) and `TerminalPanelFxTest` (the find bar).
  - OSC 8: `Osc8LinksTest` and `TerminalPanelFxTest`'s OSC 8 test, which covers the target vs the text, the
    scheme filter, `file://` and the tip. Without the filter it fails.
  - Program tabs: `TerminalPanelFxTest` (exit chip and code, close, Stop, a start failure), `LocalShellTest`
    (a program on a PTY), `PosixShellTest` (the program script run with `/bin/sh`: the pid kept, env set and
    unset, arguments whole, the exit code).
  - Run and Debug: `RunCoordinatorFlowsFxTest`, `RunConfigurationsWindowFxTest`, `DebugProgramConsoleFxTest`
    (two cases).
  - `RealSshdProbeFxTest`'s program step: a tty, its variables and exit code 7 from a real OpenSSH host.

## Keys

A focused terminal owns the keyboard. `TerminalPanel` sets `KeyDispatcher.SHELL_KEYS`, a predicate that says
which chords still reach Editora (`TerminalKeys.reachesEditora`). The dispatcher leaves every other press to
the terminal, before prefixes, prefix arguments and the plain-Alt guard. So Ctrl+C is SIGINT, Ctrl+W deletes a
word, Escape is vim's, and Emacs's C-x is the shell's.

- Through by default: `tool.*`, `palette.show`, `search.everywhere`, `terminal.*` (Settings → Terminal →
  Commands that reach Editora; `Settings.DEFAULT_TERMINAL_IDE_COMMANDS`, kept equal to
  `TerminalKeys.DEFAULT_IDE_COMMANDS` by a test), **but only on chords a shell does not use**
  (`TerminalKeys.isShellChord`: any key with no modifier or Shift alone, and a character key with Ctrl, Alt or
  both). The command-id rule alone took IntelliJ's Ctrl+K and M-<n>, VS Code's Ctrl+B and F1, and Emacs's
  M-<n> from the shell. `tool.terminal` and `terminal.*` pass on any chord (there must be a way out), and so
  does the Emacs keymap's `M-x` for the palette (adl, 2026-10-18). An entry without a dot is a chord
  (`M-1`) that passes whatever it is bound to. On macOS every Cmd chord reaches Editora too.
  `TerminalKeysTest` walks every bundled keymap.
- Alt is Meta (ESC + key) on Linux and Windows; on macOS only with "Option key as Meta"
  (`terminalMacOptionIsMeta`, off), so Option composes `@ [ ] { }` on non-US layouts. Ctrl+Alt+key is C-M-key on
  Linux unless AltGr is held (it is the composing key on Windows). Ctrl+3…8 send xterm's controls.
- Prompt jumps (Ctrl+Shift+Up/Down, Cmd+Up/Down on macOS) and Find (Ctrl+Shift+F, Cmd+F) are the terminal's own
  too, never Editora's.
- Copy and Paste are the terminal's own: Ctrl+Shift+C/V, Cmd+C/V on macOS. A filter on the panel runs before
  the view's, which would otherwise encode Ctrl+Shift+C as ^C.
- Bindings: `tool.terminal` is Alt+F12 (IntelliJ), Ctrl+` (VS Code, CUA, Sublime) and M-0 (Emacs);
  `terminal.new` is Ctrl+Shift+` in VS Code, CUA and Sublime; `terminal.focusEditor` is Shift+Escape everywhere.
- Shift+PageUp/PageDown/Home/End scroll the history (not on the alternate screen). A paste is cleaned of control
  characters (`KeyEncoding.pasteText`), and one of several lines into a program without bracketed paste asks
  first (`terminalConfirmMultilinePaste`).

## Settings

Settings → Terminal (Languages & Tools), schema 130: `terminalShell` (blank = login shell),
`terminalFontSize` (0 = the editor's size; the face is always the editor's), `terminalCursor`, `terminalBell`,
`terminalScrollback`, `terminalIdeCommands`, `terminalConfirmClose`. Font, cursor, bell and the key list apply
to open terminals. Scrollback and shell apply from the next tab. Schema 131 adds `terminalUseExternal`; 132
adds `terminalShellIntegration`, which applies from the next tab; 133 adds `terminalTypeAhead` (applies at
once) and Debug's `debugProgramInTerminal`. 135 adds `terminalMacOptionIsMeta` and
`terminalConfirmMultilinePaste`, and lowers the scrollback maximum to 50,000 lines (about 94 MB a tab at the old
100,000); the migration clamps a larger value. 150 adds `terminalCopyOnSelect` (off), `terminalClipboardWrites`
(on: OSC 52) and `terminalNotifications` (on: OSC 9 / 777), all applying at once. The terminal's font size
follows the editor's text zoom.

## Where the code came from

The package is a port of [Termina](https://github.com/adriandeleon/Termina), first at `caebc9f` (0.7.1, MIT,
same author) and brought up to **`4443118`** (after 0.12.0) on 2026-10-27. Copied, not shared: fixes made here
are back-ported to Termina by hand. When porting a later Termina change, diff Termina from `4443118` and
update this commit here.

| Here | Termina | Changed in the port |
| --- | --- | --- |
| `TerminalView` | `ui/TerminalView` | Starts from a `TerminalConnector.Factory` instead of launch options. `TerminalCursor`, `TerminalPalettes` and a default font family replace Termina's `Settings`, `Theme` and `MonospaceFonts`. The menu keeps the terminal's own items: Open Link, Copy Link Address, Copy, Paste, Select All, Clear Scrollback. The window adds the rest (`setMenuExtras`) and supplies icons (`setMenuIcons`). The dev-capture hooks, zoom row, menu-bar toggle and new tab/window items are gone. |
| `TerminalSession` | `term/TerminalSession` | Takes a `TerminalConnector`; no longer starts a process. |
| `TerminalConnector` (new) | — | JediTerm's `TtyConnector` plus `pid()`: `-1` for a shell that is not on this machine. |
| `LocalShell`, `LocalPtyConnector` | `pty/ShellLauncher`, `pty/PtyTtyConnector` | Environment from `ProcessRunner.applyUserEnv` (augmented PATH, user locale) plus `TERM`, `COLORTERM`, `TERM_PROGRAM=Editora`. Drops an inherited `COLUMNS`/`LINES`. |
| `TerminalPalettes`, `SyntaxColors` (new) | `ui/Theme` | Derives the 16 ANSI colours from an editor theme's token colours (see below) instead of four fixed themes. |
| `FxTerminalDisplay`, `TerminalTitle` | `ui/…` | An untitled terminal reports `""`, and the window shows its own label. |
| `KeyTrace` | `ui/KeyTrace` | Property `-Deditora.terminal.keyTrace`, logger `com.editora.terminal.keys`. |
| `KeyEncoding`, `MouseEncoding`, `CellWidth`, `ColumnMap`, `RunColumns`, `ScrollBarModel`, `TerminalPalette`, `Link*`, `ProcessCwd`, `CwdWatcher`, `TerminalExecutors` | same names | Package only. |
| `TypeAheadOverlay` (new) | `DisabledTypeAhead` | A working type-ahead model in place of Termina's switched-off one. |
| `KeyboardFlags`, `KeyboardModeInterceptor`, `KittyKeys`, `KittyKeyInput` | `term/…`, `ui/…` | The kitty keyboard protocol. `KittyKeyInput` takes Editora's Ctrl+Alt rule (`ctrlAltIsMeta`) instead of Termina's `isPlainCtrlAlt`. |
| `SelectionGeometry`, `BlockSelection`, `AutoScroll` | `ui/…` | Package only. The view drives autoscroll from a `Timeline`, not the painter's frames, since Editora's painter stops when idle. |
| `PrimarySelection`, `DesktopNotifier` | `ui/…` | Tools are found on `ProcessRunner`'s augmented PATH and run in the user's locale (see `ChildLocalePolicyTest`). |
| `OscSequences` | `term/OscSequences` | Only notifications (OSC 9, 777) and OSC 52. `OscTap`, Editora's own, takes them out of the stream; Termina's `OscInterceptor` is not ported. |
| `IconFont` | `ui/IconFont` | Installed Nerd Fonts only; see below. |
| `ChildProcesses` | `process/ChildProcesses` | Package only. |

Termina's window, tabs, panes, settings, profiles, colour-scheme import, settings sync, command palette, update
check, CLI and `DevCapture` are not ported; Editora has its own. Also left out at `4443118`: the bundled Symbols
Nerd Font Mono (2.6 MB, its icon sets under MIT, CC BY 4.0, Apache 2.0, OFL and one unlicensed set, where every
font Editora bundles is OFL), so a machine without a Nerd Font still gets its fallback list's icons; reading a
Windows shell's directory from its process (Termina's `WindowsCwd`, never run on Windows); and `HistoryFollow`'s
resize detection. The tests came with the code. `TerminalViewFxTest` is new: it drives the view
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

The `terminalTheme` setting (Settings ▸ Terminal ▸ Theme) gives the terminal colours of its own. Blank, the
default, is everything above. Any other value names a fixed scheme in `TerminalThemes` — Solarized, Dracula,
Nord, Gruvbox, One Half, Tango, Campbell with their published sixteen colours, and the two derived Editora
palettes — and `TerminalCoordinator.applyTheme` hands it to `TerminalPanel.usePalette` without sampling the
scene, so it stays put when the editor theme changes. A name this version does not know follows the editor
theme. Published values are kept as they are (Solarized's bright black is its own background); the view's
`MIN_CONTRAST` pass keeps drawn text readable.

## Performance

- **The FX thread never waits for the buffer lock.** Painting, hover, the accessibility text and the find bar
  use `tryLock` (a frame that cannot get it is skipped, `dirty` stays set). A reflow of a long scrollback or a
  flood of output holds JediTerm's non-fair lock for long stretches; waiting on it froze every window for up
  to a minute during a splitter drag.
- **Resize settles first:** a size change is applied 120 ms after the last one (`RESIZE_SETTLE`), so a drag
  reflows the scrollback once, not per column.
- **The painter runs only while there is something to paint**, and not for a hidden tab or window
  (`isPaintable`); an idle terminal used to keep the FX pulse at 60 Hz forever. Window titles are posted to the
  FX thread at most one update at a time.
- **Colours:** text is drawn with at least 4.5:1 contrast against its background (`TerminalPalette.withContrast`,
  cached per pair); dim text is left as it asked. OSC 10/11 queries are answered with the palette.
- **Widths:** JediTerm puts no placeholder after an astral character, so `CellWidth.of` gives a wide one (an
  emoji) two columns, as the shell's `wcwidth` does; the cursor is drawn by column through the row's text.

## Known behaviour

- On Linux pty4j sets the window size just after the child starts, so a program started directly (not a
  shell) can read 0×0 if it asks at once. See `TerminalImageCheck`.
- Local shell cwd tracking (`ProcessCwd`) works on Linux (`/proc`) and macOS (`proc_pidinfo` via JNA), not
  Windows. A shell with integration reports its own directory (OSC 7), which wins over the pid's.
- A shell on another machine opens off the FX thread. `remoteShell`'s factory is marked
  `TerminalConnector.Factory.slow`, and the panel starts such a view with `TerminalView.startAsync`.
  - **While it connects:** the tab says "Connecting to <host>…". A reconnect keeps the old text and banner
    until the new shell is there.
  - **When it fails:** a new tab goes away, a reconnecting one keeps its banner, and the error says why.
  - **Closed meanwhile:** a tab closed while connecting closes the connection that arrives late.
  - The channel open and, the first time on a target, writing the integration scripts both run on that thread.
  - A program tab on a target (Run or Debug in a terminal) opens there too: a failure arrives as the program's
    end (-1) and the error.
- Search matches within a row: text a soft wrap split across two rows is not found as one.
- JediTerm's history listener fires only on resizes and clears; `TerminalView` derives the history shift on
  every model change instead (`pushedLines`, a scrolled-back view, the selection).
- Not done, from the 2026-10-18 review: strikethrough and curly underline (JediTerm 3.74 has no such style),
  `PIPESTATUS` for a user's own `PROMPT_COMMAND` after ours (bash cannot rebuild it at the top level), box-drawing
  glyphs drawn as shapes.
- The scrollbar is coloured through the looked-up colours `-terminal-thumb` and `-terminal-thumb-hover`
  (`.terminal-scroll-bar` in `app.css`, Termina's rules).
- No palette rescues a prompt built for a dark terminal. Oh My Bash's agnoster theme draws `user@host` in the
  default foreground on ANSI black, which is dark on dark in any light theme.
