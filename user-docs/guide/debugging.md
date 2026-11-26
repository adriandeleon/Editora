---
title: Debugging
description: "Breakpoints, stepping, values, watches, threads and several sessions through the Debug Adapter Protocol, with launch.json, compounds and adapters of your own."
category: Run & debug
order: 2
---

Editora debugs [**Java**](/docs/java#debugging), [**Python**](/docs/python#debugging),
[**JavaScript and TypeScript**](/docs/javascript-typescript#debugging) (Node.js and browsers),
[**Groovy**](/docs/groovy#debugging), [**Kotlin**](/docs/kotlin#debugging),
[**Clojure**](/docs/clojure#debugging), [**Scala**](/docs/scala#debugging), [**Go**](/docs/go#debugging),
[**Rust**](/docs/rust#debugging), [**C and C++**](/docs/c-cpp#debugging),
[**C#**](/docs/csharp#debugging), [**Ruby**](/docs/ruby#debugging),
[**PHP**](/docs/php#debugging), [**Bash**](/docs/shell#debugging),
[**Elixir**](/docs/elixir#debugging), [**PowerShell**](/docs/powershell#debugging),
[**Dart and Flutter**](/docs/dart#debugging) and [**Lua**](/docs/lua#debugging) through the Debug Adapter Protocol, with an IntelliJ-style Debug tool window (`M-g d`). Debugging
is **on by default**, and each language's debugger starts once its adapter is
found ([Installing adapters](#installing-adapters)); the switch is **Enable
debugging (DAP)** in **Settings → Debugging**. A project's
`.vscode/launch.json` configurations start as VS Code starts them
([launch.json configurations](#launchjson-configurations)), and any other
debugger that speaks the protocol can be added
([Debug adapters of your own](#debug-adapters-of-your-own)). Several programs
can be debugged at once, each in a session of its own
([Several sessions](#several-sessions)), and a [compound](#compounds) starts
several together. Scripts of shells other than Bash, such as zsh or fish,
can't be debugged; [Run with Trace](/docs/run-debug#run-with-trace) shows each line of a
script as it runs.

This page covers what every language shares. Each language's own setup, test
debugging and quirks are on its page under **Languages** in the sidebar.

## Start debugging

1. **Set a breakpoint** on the line where the program should stop: click the
   strip at the left of the line numbers, or use **Debug: Toggle Breakpoint**
   (`debug.toggleBreakpoint`) on the caret's line. Its key is F9 in the VS
   Code, CUA and Sublime Text keymaps, Ctrl+F8 (⌘F8 on macOS) in the IntelliJ
   IDEA keymap, and `C-c C-b` in Emacs. A dot marks the line.
2. **Start the program** under the debugger with **Debug: Start / Continue**
   (`debug.start`): F5 in the VS Code, CUA and Sublime Text keymaps, Shift+F9
   (⌃D on macOS) in the IntelliJ IDEA keymap, and `C-c C-d d` in Emacs. It
   debugs the current file, as **Debug File** in the editor's right-click menu
   does. In a Maven or Gradle project, **Debug: Debug Main Class…**
   (`debug.mainClass`) picks a main class instead.
3. When the program reaches the breakpoint, it stops there: the line is marked,
   and the [Debug window](#the-debug-window) opens with the call stack, the
   variables and the console. Step through the code with Step Over, Step Into
   and Step Out, run on to the next breakpoint with Start / Continue, and end
   the session with Stop. The keys are in
   [Commands and menus](#commands-and-menus).

What Start / Continue runs for a file, such as a test, a web app's server or a
script, is on each language's page under **Languages**. To start a session
another way, from a launch.json configuration, a saved run configuration or a
program that is already running, see [Starting a session](#starting-a-session).

When a file cannot be debugged, the status bar says why and what to do: Python
or JavaScript debugging is turned off, debugpy was not found for the Python
interpreter, or vscode-js-debug or Node.js was not found.

## The Debug window

![A Java program paused at a breakpoint in Shelf.java with the Debug tool window below: the call stack, a variables tree with the items list unfolded, and the console](/screenshots/features/debugging.png "Paused at a breakpoint: call stack, variables and console in the Debug window.")

The Debug window has a threads and call-stack view, a lazy variables tree with
**set-value**, **watches**, an [evaluate field](#evaluating-expressions) and a
console. While suspended, **inline
values** appear after each line and hovering a variable shows its value.
Hovering an attribute shows the whole expression: the `x` of `p.x` shows
`p.x`, not a variable named `x`. Hovering stops at a call or an index, so it
never runs code.

In Variables and watches, each row's name, value and type are coloured like
code in the current editor theme: strings, numbers, constants such as `true`,
`null` and `None`, and type names, including each element of a list or dict
value. A watch that cannot be evaluated shows the debugger's message in red
italics instead.

When the panel is wide enough, the call stack, variables, and console sit side
by side; they stack when it is narrow. A deep call stack lists its top 50
frames first, and more when you ask ([The call stack](#the-call-stack)). The
console wraps long lines.
It is cleared at each launch and kept after the session ends, and its
right-click menu has **Clear Console**. Your keymap's Find key in the console,
or **Debug: Find in Console** (`debug.findInConsole`) from anywhere, opens a
find bar over it that searches as you type: Enter goes to the next match,
Shift+Enter to the previous one, and running the command again goes to the
next match too. It keeps the last 200,000 characters;
when older output was dropped, the Debug window's state line says so. **Restart** is disabled for a debugger attached to a test or build
run (its tooltip says why); a session started with *Debug → Attach* can still be
restarted.

A spinner beside the Debug window's status turns while the before-launch build,
the session start, or the program is running, and stops while the program is
paused (at a breakpoint, a step, or Pause). Pause works only on a running
program, so it is greyed out while the program is paused, starting or being
built. In Java, Pause stops every thread of the program, and Continue resumes
them all (see [Threads in a Java program](/docs/java#threads-in-a-java-program)).

### Which runtime a session runs under

Beside that status, the Debug window names the runtime the debugged program
runs under, as the [Run window does](/docs/run-debug#which-runtime-a-program-runs-under): the
JDK of a Java launch, the Python that debugpy runs the script with, or the
Node.js of a JavaScript launch. The name clears when the session ends. A
session started with *Debug → Attach* shows none, because the program was
already running.

### Keys in the Debug window

Starting a session or stopping at a breakpoint never moves the keyboard focus
into the Debug window: focus stays in the editor, with the caret on the
stopped line. A stop opens the Debug window again if you closed it.

When you click into the Debug window, single keys drive the session, as in
gdb:

| Key | Action |
| --- | --- |
| `c` | Start or continue |
| `p` | Pause |
| `n` | Step over |
| `s` | Step into |
| `f` | Step out (finish) |
| `u` | Run to cursor |
| `b` | [Step back](#stepping-backward) |
| Shift+C | [Reverse continue](#stepping-backward) |
| Shift+K | Stop |
| Shift+R | Restart |

Stop and Restart need Shift, so a stray `k` or `r` typed in a list does nothing
to the program. The keys don't apply while you type in the evaluate field or
the console's input field.
Stepping keeps the focus in the Debug window, so you can step again and again.
A toolbar button's tooltip shows both its key in your keymap and its key in the
Debug window, for example "Step Over (F10 · in this window: N)".

### Program input in the Debug console

A debugged **Java**, **Python**, **JavaScript** or **Rust** program can read its
standard input from the Debug console, so a Python program that calls `input()` waits
for a line typed there.
The field does one of two things, and its prompt says which:

- While the program **runs**, Enter sends the line to the program (an empty
  line counts).
- While it is **paused**, Enter evaluates an expression.

The field takes your keymap's editing keys, as the
[evaluate fields](#typing-an-expression) do.

End the input with `Ctrl+D` in the field, the console's right-click menu, or
*Debug: End Program Input (EOF)* (`debug.endProgramInput`).

In this mode Editora starts the program itself rather than leaving it to
the debugger, and stops it on Stop, Restart, a new launch, a failed launch, or
closing the window. The session ends when the program exits, after its last
output, and the console reports the exit code. A Python program's output is
unbuffered, so a prompt shows before the program waits. Lines written to stdout
and stderr at almost the same moment can appear in either order.

It is **on by default**. The switch, **Run the debugged program in the Debug
console (program input)**, is on the **Debugged programs** card of
**Settings → Debugging** (or use
*Debug: Toggle Program Input in the Debug Console*,
`debug.toggleProgramConsole`) and takes effect at the next launch. It applies
to a launched Java or Rust program, and to a Python or JavaScript program
started on this computer; a Python or JavaScript file on a remote host, in WSL or in a
container is unaffected, and so are attached sessions.

**Run the program in a Terminal tab instead**, just below it, runs that program
in a tab of the [Terminal](/docs/terminal#run-and-debug-programs-in-a-terminal)
tool window rather than the Debug console. Use it for a program that draws on
the screen, reads single keys or asks for a password. Its output and input are
then in that tab, which is titled *Debug: <file>* and stays when the program
ends. The Debug console's first line names the tab. The program's exit still
ends the session, and Stop ends the program. It is off by default and needs the
program console switch on.

With the switch off, the debugger starts a Java program itself. A program that
prints a lot then gets its output to the console well after it writes it; the
session ends, and the exit line shows, once the last of it has arrived. Until
then the Debug window's state reads *Program ended, output still arriving*. The
console still reports the program's exit code on Linux and macOS when the
program runs on this machine. On Windows, and on a remote host, in WSL or in a container, it
says "The program ended." instead. A JavaScript program's output, including
`process.stdout.write`, still reaches the console, but the program cannot read
input.

## Commands and menus

Every debugger action is a command, so it is in the palette and can be given a
key under Settings → Keymaps:

| Action | Command | Emacs key | Other keymaps |
| --- | --- | --- | --- |
| Start / continue | `debug.start` | `C-c C-d d` | CUA, Sublime Text, VS Code: F5; IntelliJ: Shift+F9 |
| Continue | `debug.continue` | `C-c C-d c` | IntelliJ: F9 |
| Pause | `debug.pause` | `C-c C-d p` | CUA, VS Code: F6 |
| Stop | `debug.stop` | `C-c C-d k` | CUA, Sublime Text, VS Code: Shift+F5; IntelliJ: Ctrl+F2 |
| Stop every session ([Several sessions](#several-sessions)) | `debug.stopAll` | (palette) | — |
| Restart | `debug.restart` | `C-c C-d r` | CUA, Sublime Text, VS Code: Ctrl+Shift+F5; IntelliJ: Ctrl+F5 |
| Step over | `debug.stepOver` | `C-c C-d n` | CUA, Sublime Text, VS Code: F10; IntelliJ: F8 |
| Step into | `debug.stepInto` | `C-c C-d i` | VS Code: F11; IntelliJ: F7 |
| Step out | `debug.stepOut` | `C-c C-d o` | CUA, Sublime Text, VS Code: Shift+F11; IntelliJ: Shift+F8 |
| Step over / into one machine instruction ([Disassembly](#disassembly-and-memory)) | `debug.stepOverInstruction`, `debug.stepIntoInstruction` | (palette) | — |
| Run to cursor | `debug.runToCursor` | `C-c C-d u` | IntelliJ: Alt+F9 |
| Run to cursor past every breakpoint | `debug.forceRunToCursor` | `C-c C-d S-u` | IntelliJ: Ctrl+Alt+F9 |
| Jump to line | `debug.jumpToLine` | `C-c C-d j` | — |
| Have Step Into go over a frame's code ([Don't step into](#dont-step-into)) | `debug.dontStepIntoFrame` | (palette) | — |
| [Step back or reverse continue](#stepping-backward) | `debug.stepBack`, `debug.reverseContinue` | (palette) | — |
| Have GDB record the program ([Record Execution](#stepping-backward)) | `debug.toggleRecording` | (palette) | — |
| Toggle breakpoint | `debug.toggleBreakpoint` | `C-c C-b` | CUA, Sublime Text, VS Code: F9; IntelliJ: Ctrl+F8 |
| Toggle temporary breakpoint | `debug.toggleTemporaryBreakpoint` | `C-c C-d 1` | IntelliJ: Ctrl+Alt+Shift+F8 |
| Toggle [recording breakpoint](#recording-breakpoints) | `debug.toggleRecordingBreakpoint` | (palette) | — |
| Toggle method breakpoint (Java) | `debug.toggleMethodBreakpoint` | `C-c C-d S-m` | — |
| Toggle field watchpoint (Java) | `debug.toggleFieldWatchpoint` | `C-c C-d S-w` | — |
| Toggle [lambda breakpoint](#lambda-breakpoints) (Java, JavaScript, TypeScript) | `debug.toggleLambdaBreakpoint` | `C-c C-d S-l` | CUA, Sublime Text, VS Code: Shift+F9 |
| Edit breakpoint | `debug.editBreakpoint` | `C-c C-d e` | — |
| Add a [logpoint](#logpoints) | `debug.addLogpoint` | `C-c C-d g` | — |
| Add a [triggered breakpoint](#triggered-breakpoints) that waits for another | `debug.addTriggeredBreakpoint` | (palette) | — |
| Put the caret line's breakpoint in a [group](#breakpoint-groups) | `debug.moveBreakpointToGroup` | (palette) | — |
| Turn a group's breakpoints on or off | `debug.toggleBreakpointGroup` | (palette) | — |
| [Export or import breakpoints](#exporting-and-importing-breakpoints) | `debug.exportBreakpoints`, `debug.importBreakpoints` | (palette) | — |
| Choose exception breakpoints | `debug.toggleExceptionBreakpoints` | (palette) | — |
| Stop on an exception [of a type you name](#exception-breakpoints-by-type) | `debug.addExceptionBreakpoint` | (palette) | — |
| Show the [exception card](#exception-card) again | `debug.showException` | (palette) | — |
| Debug the current file on its own, as **Debug File** does | `debug.debugFile` | (palette) | — |
| Debug a project main class | `debug.mainClass` | (palette) | — |
| Debug the main class the caret is on | `debug.mainClassAtCaret` | (palette) | — |
| Debug a saved run configuration | `debug.config` | (palette) | — |
| Start a [launch.json configuration](#launchjson-configurations) | `debug.startConfiguration` | (palette) | — |
| Start a [compound](#compounds) | `debug.startCompound` | (palette) | — |
| Make or change the project's compounds | `debug.editCompounds` | (palette) | — |
| Open [`debug-adapters.json`](#debug-adapters-of-your-own) | `debug.editCustomAdapters` | (palette) | — |
| Debug via build tool | `debug.viaBuild` | (palette) | — |
| Attach to a running JVM or Node.js | `debug.attach` | `C-c C-d a` | — |
| Attach to a program on this machine ([Attach to Process](#attach-to-process)) | `debug.attachToProcess` | (palette) | — |
| Attach to a program under gdbserver elsewhere ([Attach to Remote Program](#attach-to-a-remote-program)) | `debug.attachRemote` | (palette) | — |
| Attach again to the last address | `debug.reattach` | `C-c C-d S-a` | — |
| Open a [core dump](#opening-a-core-dump) | `debug.openCoreDump` | (palette) | — |
| [Listen for Xdebug](/docs/php#debugging) | `debug.listenXdebug` | (palette) | — |
| Open the [Evaluate window](#the-evaluate-window) | `debug.evaluate` | `C-c C-d x` | IntelliJ: Alt+F8 |
| Go to the [evaluate field](#the-evaluate-field-in-variables) in Variables | `debug.focusEvaluate` | (palette) | — |
| Pin a value to the caret's line ([pinned values](#pinned-values)) | `debug.pinValue` | (palette) | — |
| Unpin the values on the caret's line | `debug.unpinLine` | (palette) | — |
| Show numbers as hexadecimal | `debug.toggleHex` | (palette) | — |
| Show one number in another base ([Show Value As](#show-value-as)) | `debug.showSelectedAsHex`, `debug.showSelectedAsDecimal`, `debug.showSelectedAsBinary`, `debug.showSelectedAsOctal`, `debug.showSelectedAsCharacter`, `debug.showSelectedAsDefault` | (palette) | — |
| Show values without your [type renderers](#type-renderers) | `debug.muteRenderers` | (palette) | — |
| Show every thread ([Threads view](#threads-view)) | `debug.threads` | (palette) | — |
| Hide or list the Go runtime's goroutines, or filter them ([Goroutines](/docs/go#goroutines)) | `debug.toggleRuntimeGoroutines`, `debug.filterGoroutines` | (palette) | — |
| Take a [thread dump](#thread-dump) | `debug.threadDump` | (palette) | — |
| Draw every thread's stack as one tree ([Parallel Stacks](#parallel-stacks)) | `debug.parallelStacks` | (palette) | — |
| Go back or forward through earlier stops ([Debug History](#debug-history)) | `debug.historyBack`, `debug.historyForward` | (palette) | — |
| List the stops of the session | `debug.showHistory` | (palette) | — |
| Show the [Disassembly or Memory view](#disassembly-and-memory) | `debug.showDisassembly`, `debug.showMemory` | (palette) | — |
| List the program's [loaded sources or modules](#loaded-sources-and-modules) | `debug.loadedSources`, `debug.modules` | (palette) | — |
| Turn a debugger on or off: any language's but Java's, or Firefox's | `debug.toggleAdapter` | (palette) | — |
| Set an adapter's path | `debug.setAdapterPath` | (palette) | — |
| Change a setting of **Settings → Debugging** | `debug.changeSetting` | (palette) | — |

The **Emacs key** column is the default Emacs keymap; **Other keymaps** gives
the chords the CUA, Sublime Text, VS Code and IntelliJ IDEA keymaps bind (— where
none does). On macOS those mostly use ⌘ for Ctrl; the
[keybindings reference](/keybindings) lists every keymap.

The keys are the Emacs keymap's; the other keymaps' keys are listed under
[Debugger keys](/docs/keymaps#debugger-keys). The step commands are also
buttons in the Debug window, and most debugger actions are in the **Run** menu.
Its top level has Start / Continue, Debug Main Class, Attach, Stop, Stop All
and Restart. The rest is in four submenus:

- **Launch and Attach**: Run and Debug Selected Configuration (`run.selectedConfig`, `debug.selectedConfig`), Start
  Configuration, Start Compound, Edit Compounds, Attach to Process, Attach to
  Remote Program, Reattach, Open Core Dump, Listen for
  Xdebug, Debug in Browser and Attach to Browser.
- **Stepping**: Pause, stepping by line or by instruction, Smart Step Into
  ([Java](/docs/java#stepping-through-java-code)), Run to Cursor, Force Run to
  Cursor, Restart Frame, Don't Step Into This, and Record Execution, Step Back
  and Reverse Continue.
- **Breakpoints**: Toggle Breakpoint and the other kinds of breakpoint (with
  logpoints and triggered breakpoints), Edit Breakpoint, the breakpoint list,
  groups, export and import, Mute Breakpoints, the exception breakpoints and
  Add Exception Breakpoint.
- **Debugger**: Apply Code Changes, evaluate, watches, pinned values,
  hexadecimal values, Mute Type Renderers, Show Exception, Debug History, the
  Threads view, Thread Dump, Parallel Stacks, the Loaded Sources, Modules,
  Disassembly and Memory views, and Edit Debug Adapters.

## Starting a session

Besides Start / Continue and **Debug File** on the current file, a session can
start from a launch.json configuration, a compound, a program that is already
running, a remote debug server or a core dump.

### Several sessions

Editora can debug several programs at once, each in a session of its own: a
server and its client, or a program and one of its tests. Start debugging
another file while a session runs, with F5, **Debug File** or **Debug Test at
Caret**, and Editora asks what to do:

- **Debug Both** debugs the new file in a session of its own, beside the one
  that runs.
- **Stop and Debug** stops the session and debugs the new file instead.
- **Cancel** leaves things as they are.

![The question Debug Another File?: main.go is being debugged. Debug shelf_test.go as well, in a session of its own, or stop main.go and debug shelf_test.go instead? with Stop and Debug, Cancel and Debug Both buttons](/screenshots/docs/debug-both-question.png "Debugging another file while a session runs asks first.")

While a session is paused, F5 continues it instead of asking, so start another
file then with **Debug File** or **Debug Test at Caret**. **Debug Both** is
offered beside a session that has started: while one is still getting ready
(its build, say), the question has **Stop and Debug** and **Cancel** only.
Asking to debug a file that a session already debugs starts nothing: that
session is shown, and the status bar says so.

With two sessions or more, a row of tabs at the top of the Debug window has one
tab per session, with its file (or its test) and its state, such as *main.go ·
suspended*. Click a tab to show that session. Each session has its own console,
call stack, variables and [Debug History](#debug-history). The toolbar, the
debugger keys, the execution line, inline values and hovers follow the session
shown. A session that stops is shown by itself, so you see where it stopped;
a stop on a [recording breakpoint](#recording-breakpoints) doesn't switch.
Breakpoints, Mute, exception breakpoints and hexadecimal values apply to every
session.

![The Debug window with two Go sessions: tabs main.go · suspended and TestCheckoutTotals · suspended above the toolbar, Stop All at the right, and the test's session shown, paused at line 8 of shelf_test.go with lines and total in Variables](/screenshots/docs/debug-sessions.png "A program and one of its tests, each in a session of its own.")

**Stop** stops the session shown. **Stop All**, at the right of the tabs while
two or more sessions run, ends them all; it is also in the **Run** menu and in
the palette as **Debug: Stop All Sessions** (`debug.stopAll`). A session that
ends keeps its tab and its output, marked *ended*, until you close the tab with
its ×, or with **Debug: Close Ended Session** (`debug.closeEndedSession`) while
it is shown. **Debug: Switch Debug Session…** (`debug.switchSession`) picks the
session to show from the keyboard. A window keeps up to eight sessions: past
that, a new one takes the place of the oldest ended one.

A [launch.json configuration](#launchjson-configurations) started while a
session runs is debugged beside it, as in VS Code, without asking. A
[compound](#compounds) starts several programs together, and a Python
program's [subprocesses](/docs/python#debugging) get sessions of their own.

### launch.json configurations

**Debug: Start Configuration…** (`debug.startConfiguration`, in **Run ▸ Launch
and Attach**) lists the configurations of the project's `.vscode/launch.json`, or
of the nearest one above the active file, and its [compounds](#compounds), and
starts the one you pick as VS Code would. A configuration started while a
session runs is debugged beside it, in a session of its own
([Several sessions](#several-sessions)). Type to filter the list; the configuration you started last is
listed first. With no `launch.json`, the status bar says so.

| `type` | Debugger |
| --- | --- |
| `lldb`, `codelldb` | CodeLLDB, for Rust, C or C++. Its `cargo` field runs Cargo first and debugs the program Cargo built |
| `gdb` | GDB's own debug adapter mode (GDB 14 or later); attaches by `pid` |
| `cppdbg` | GDB, or CodeLLDB when `MIMode` is `lldb`; attaches by `processId` |
| `go` | Delve, with `exec` for a program you built yourself; `mode: remote` isn't supported |
| `debugpy`, `python` | debugpy; an attach connects to its `connect` address |
| `node`, `pwa-node` | vscode-js-debug; an attach connects to its `address` and `port` |
| `java` | the Java debugger: `mainClass`, `projectName`, `args`, `vmArgs`, `cwd` and `env` |
| `coreclr` | netcoredbg, `program` being the built assembly; attaches by `processId` |
| `rdbg` | rdbg, with `useBundler` for `bundle exec`; an attach connects to a local `debugPort` |
| `php` | PHP Debug; without a `program`, it listens for Xdebug |
| `bashdb` | Bash Debug, which launches a script under bashdb; it can't attach |
| `lua-local` | [Local Lua Debugger](/docs/lua#debugging), which launches its `program` (`lua` and `file`) with its `args`; it can't attach |
| `PowerShell` | [PowerShell Editor Services](/docs/powershell#debugging), which launches a `script` (a file or a command) with its `args`; it can't attach |
| `chrome`, `msedge`, `pwa-chrome`, `pwa-msedge` | vscode-js-debug driving a [browser](/docs/javascript-typescript#debugging-in-a-browser): a launch needs a `url` or a `file`; an attach connects to its `port` |
| `firefox` | vscode-firefox-debug driving [Firefox](/docs/javascript-typescript#debugging-in-a-browser), launched or attached to |
| `mix_task` | [ElixirLS](/docs/elixir#debugging), which runs a Mix `task` with its `taskArgs`; it can't attach |

A [debug adapter of your own](#debug-adapters-of-your-own) is used by its
`type` too. Any other type, such as C# Dev Kit's `dotnet`, is refused with a
message.

The file is read as VS Code reads it: comments and trailing commas are allowed,
and a configuration's `linux`, `osx` and `windows` parts apply on that system.

- **Variables:** `${workspaceFolder}`, `${file}` and the rest of the `${file…}`
  family, `${relativeFile}`, `${lineNumber}`, `${selectedText}`, `${env:NAME}`,
  `${userHome}` and `${pathSeparator}` are filled in. `${input:id}` asks for
  its value once per start, as a list to pick from (`pickString`) or a prompt
  (`promptString`), before anything runs. `${command:…}` and `${config:…}` name
  VS Code's own commands and settings, so they are refused with a message.
- **`preLaunchTask`:** a task of `.vscode/tasks.json` that is a `shell` or
  `process` command runs first, in the Debug console, after Editora saves the
  folder's edited files. The command is the folder's own code, so in a folder
  you have not trusted it first asks you to
  [trust the folder](/docs/workspace#trusted-folders), naming the command;
  declining runs nothing. **Restart** runs the task again. Any other kind of
  task, or one with `dependsOn`, isn't run: the status bar says to run it
  yourself first.
- **`postDebugTask`:** a task of `tasks.json`, of the same kinds, that runs
  when the session ends, in the Debug console after the program's output.
  **Restart** runs it before the `preLaunchTask` and the new launch, as VS Code
  does.
- **`serverReadyAction`:** Editora watches the program's output for the
  server's address, and the first time it sees it, opens the address or
  debugs it:

  ```json
  {
    "name": "Server", "type": "node", "request": "launch",
    "program": "${workspaceFolder}/server.js",
    "serverReadyAction": { "action": "debugWithChrome", "killOnServerStop": true }
  }
  ```

  - `pattern`: a regular expression looked for in each line of output, in
    any case, with colour codes taken out. It is `listening on.* (https?://\S+|[0-9]+)`
    when you leave it out, which matches "listening on port 3000" and
    "Now listening on: https://localhost:5001".
  - `uriFormat`: the address made of what the pattern's first group caught,
    with `%s` standing for it. A port number gives `http://localhost:%s` and
    an address gives itself. A pattern with no group uses `uriFormat` as it is.
  - `action`: `openExternally` (the default) opens the address in your
    browser. `debugWithChrome` and `debugWithEdge` open it in Chrome or Edge
    under the debugger, in a session beside the server's, with `webRoot`
    (the workspace folder unless you set it) for the page's scripts.
    `startDebugging` starts another configuration: the one called `name`, or
    the one given as `config`.
  - `killOnServerStop`: when the server's session ends, the session the
    address started stops too.

  The Debug console says what was done with the address. The program's output
  is watched in the Debug console only: a program you run in a Terminal tab
  ([program input](#program-input-in-the-debug-console)) isn't watched.

### Compounds

A compound starts several configurations together, each in a session of its
own ([Several sessions](#several-sessions)), in the order it lists them: a
server and its client, say. It comes from the project's `.vscode/launch.json`,
or from the project's own saved run configurations.

A **launch.json compound** is a `compounds` entry, read as VS Code reads it.
**Debug: Start Configuration…** lists it beside the configurations, as
*compound · 2 configurations*:

```json
{
  "version": "0.2.0",
  "configurations": [
    { "name": "Server", "type": "debugpy", "request": "launch", "program": "${workspaceFolder}/server.py" },
    { "name": "Client", "type": "debugpy", "request": "launch", "program": "${workspaceFolder}/client.py" }
  ],
  "compounds": [
    { "name": "Server and client", "configurations": ["Server", "Client"], "stopAll": true }
  ]
}
```

- `configurations`: the configurations it starts, by name.
- `preLaunchTask`: a task that runs once, before any of them. Each
  configuration's own `preLaunchTask` still runs before that configuration.
- `stopAll`: stopping one session of the compound stops them all.

An `${input:…}` that several of its configurations use is asked once. When a
compound names a configuration that isn't in the file, nothing starts, and the
status bar names the missing one.

A **compound of run configurations** debugs the project's saved
[run configurations](/docs/run-debug#run-configurations) together. **Debug: Edit
Compounds…** (`debug.editCompounds`, in **Run ▸ Launch and Attach**) opens the
**Compounds** window: the project's compounds on the left and, for the one
selected, its **Name**, the **Run configurations to debug together** to tick,
and **Stopping one stops them all**. **Add** (`debug.addCompound`) makes a compound, **Remove**
deletes it, and **Debug** starts it. Every change is saved as you make it, with
the project. The ticked configurations start in the order the run
configurations are listed, each debugged as **Debug a saved run
configuration** debugs it, so each must be a kind that can be debugged (see
[What a configuration can launch](/docs/run-debug#what-a-configuration-can-launch)).

![The Compounds window: a compound named Server and worker on the left; on the right its Name, the list Run configurations to debug together with API server and Restock worker ticked and Web client and Price check not, Stopping one stops them all ticked, and Add, Remove, Debug and Close buttons](/screenshots/docs/debug-compounds.png "A compound of two saved run configurations, made in Debug: Edit Compounds….")

**Debug: Start Compound…** (`debug.startCompound`, in **Run ▸ Launch and Attach**) lists
both kinds, the project's own first. Type to filter; **New Compound…**, last in
the list, opens the Compounds window on a new one.

The sessions of a compound build at the same time. When the compound stops
them all, **Stop** in any of its sessions stops every one that still runs.
**Stop All** stops every session, compound or not, and a program that ends by
itself ends only its own session.

### Attach to Process

**Debug: Attach to Process…** (`debug.attachToProcess`, in **Run ▸ Launch and
Attach**) lists the Java, Node.js, Python, Go, Rust, C and C++ programs
running on this machine under your user, so you can attach to one without typing its address.
It is also the last row, *A program on this machine…*, of the
[Attach list](/docs/java#starting-a-java-session) a Java file shows once the project has
attached before. Type to filter the list. Programs listening for a
debugger come first:

- a **JVM** started with the JDWP agent as a server on a fixed port, such as
  `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005` (or
  `-Xrunjdwp:…`). A JVM started with `server=n` or `address=0` can't be
  attached to;
- a **Node.js** started with `--inspect`, `--inspect-brk` or `--inspect-wait`
  (port 9229 unless it names another);
- a **Python** program started under debugpy with
  `python -m debugpy --listen 5678 app.py`;
- any **Go** program, on Linux and macOS. Delve attaches to it by its process
  id, so it needs no flag; Editora tells a Go program by reading its
  executable;
- a **Rust**, **C** or **C++** program built with debug information, on
  Linux. CodeLLDB attaches to it by its process id. Editora tells a Rust
  program from a C or C++ one, listed as *Native (C, C++)*, by reading its
  executable. A program built without debug information isn't listed.

Each row names the main class, jar, script or program and the process id, and
below them the kind of program and where it listens, such as *Java · listening
on localhost:5005*, *Go · Delve attaches by process id* or *Native (C, C++) ·
CodeLLDB attaches by process id*. A program of these kinds that was started without debugging is
listed too, with what to start it with instead: *not started for debugging
(start it with --inspect)*. Picking such a row says the same in the status bar
and attaches nothing. With none of these programs running, the list says so.

![The Attach to Process list over Main.java: com.example.inventory.InventoryServer (pid 11), Java · listening on localhost:5005; server.js (pid 13), Node.js · listening on localhost:9229; worker.py (pid 15), Python · listening on localhost:5678; then com.example.inventory.ReportJob (pid 12) and watch.js (pid 14), not started for debugging, each with what to start it with](/screenshots/docs/debug-attach-to-process.png "Attach to Process: programs listening for a debugger come first, then those started without debugging.")

Picking a listening program attaches the right debugger to it and opens the
Debug window. **Stop** detaches: the program keeps running, and you can attach
again.

- **Java** attaches the way [Attach to Running Program](/docs/java#starting-a-java-session)
  does, through a Java file of the program's project: the active tab if it is
  one, else the first open Java tab. With no Java file open, the status bar
  asks you to open one, since the debugger finds the program's classes through
  it. The address is added to the project's Attach list, so
  **Debug: Reattach** goes back to it.
- **Node.js** attaches through vscode-js-debug, as
  [Attach to a running program](/docs/javascript-typescript#debugging) does.
- **Python** connects to debugpy inside the program, so Editora doesn't need
  a debugpy of its own. A program listening only on another network address of
  this machine, not on `localhost`, can't be attached to yet.
- **Go** attaches [Delve](/docs/go#debugging) to the process, with the breakpoints
  of the project's Go files. On Linux, a debugger may attach to a program it
  didn't start only when `kernel.yama.ptrace_scope` is 0, which many
  distributions don't make the default; `sudo sysctl kernel.yama.ptrace_scope=0`
  sets it until the next restart. Not on Windows.
- **Rust, C and C++** attach [CodeLLDB](/docs/rust#debugging) to the process by
  its process id. The same `ptrace_scope` setting applies. A macOS program keeps its debug information
  outside the executable, so it isn't listed there.

### Attach to a remote program

**Debug: Attach to Remote Program…** (`debug.attachRemote`, also in **Run ▸
Launch and Attach**) debugs a C, C++ or Rust program that runs on another
machine, in a container or on a board under a debug server: start it there
with `gdbserver :2345 ./app` (or `lldb-server gdbserver :2345 ./app`). Editora
asks for the server's address, such as `board:2345` (a port alone means this
machine), then for the program here: the same build, with its debug symbols,
so the debugger knows its functions and source lines. Both answers are offered
again the next time.

C and C++ attach with the debugger chosen for them on **Settings →
Debugging** (CodeLLDB or GDB); Rust with CodeLLDB. The server holds the
program before its first instruction: Editora lets it run on to your
breakpoints instead of showing that stop. **Stop** ends the program, and the
server with it.

A [launch.json](#launchjson-configurations) `cppdbg` configuration with
`miDebuggerServerAddress`, or a `gdb` attach with a `target`, attaches the
same way, as in VS Code.

### Opening a core dump

**Debug: Open Core Dump…** (`debug.openCoreDump`, also in **Run ▸ Launch and
Attach**) opens the core file a crashed **Go**, **C**, **C++** or **Rust**
program left behind, or one made with `gcore`, as GoLand and CLion do. Pick
the core file; on Linux the program it is the memory of is named inside the
core, so Editora finds it. Otherwise, or when the program has moved since,
Editora asks for the program too. A Go core opens in [Delve](/docs/go#debugging),
the others in [CodeLLDB](/docs/rust#debugging).

The session opens stopped where the program crashed, with the signal on the
Debug window's state line, such as *Core dump: SIGSEGV…*. The stop shows the
first frame in the program's own code, not the runtime's or the C library's
frame that raised the signal, so the line drawn as the crash is your line. Every
thread is there with its call stack and variables, and
[evaluating](#evaluating-expressions), [watches](#watches), the
[Threads view](#threads-view) and [Thread Dump](#thread-dump) work as at any
stop.

A core is the memory of a program that has ended, so it can't run: Continue,
the steps, Run to Cursor, Jump to Line, Restart Frame and Resume Thread are
disabled, and their tooltips and the command palette say why. A
[launch.json configuration](#launchjson-configurations) that opens a core, such
as Delve's `"mode": "core"`, is treated the same way.

## Breakpoints

Breakpoints live in a leftmost gutter strip and are saved per project. Click
the strip to set or remove one; each kind below has its own command and glyph.

### Edit Breakpoint

**Edit Breakpoint** (`debug.editBreakpoint`, `C-c C-d e` in the Emacs keymap,
also in a breakpoint's right-click menu) is a form with a **condition**, a **hit count**, a **log
message** (which turns it into a [logpoint](#logpoints) that logs and never suspends), an
**Enabled** toggle, **Remove once hit**, which makes it a
[temporary breakpoint](#temporary-breakpoints), **Record and continue**,
which makes it a [recording breakpoint](#recording-breakpoints), **Wait for**,
which makes it a [triggered breakpoint](#triggered-breakpoints),
**Group**, which puts it in a [group](#breakpoint-groups), and three
[filters](#breakpoint-filters) that limit it to some threads, callers or one
object. Its title names the file and line, such as *Edit
Breakpoint — Main.java:27*, and the line's code is shown above the fields, since
the form can cover that line. The hit count makes it stop only on that hit: `5`
stops on the fifth. Python also takes forms such as `>= 3` or `% 2`; the other
debuggers take only a number, and the field's hint says which. A breakpoint
with a condition or a hit count is drawn as conditional. Breakpoints in closed
files are honored too.

![The Edit Breakpoint form for Shelf.java line 50 over the editor: the line's code above the fields, a Condition field with item.price() > 1.0 typed, an empty Log message, a Hit count field whose hint reads Every hit, or a number: 5 stops on the fifth hit, Enabled ticked, Remove once hit, and Cancel and OK buttons](/screenshots/docs/edit-breakpoint.png "Edit Breakpoint: a condition, a hit count, or a log message that turns it into a logpoint.")

### Moved and unverified breakpoints

The debugger may set a breakpoint on another line than the one you chose: in
Python, a breakpoint on a blank or comment line goes to a nearby line with code.
The breakpoint then moves to that line, and the status bar says so. If that line
already has a breakpoint, the moved one is removed and the one already there,
with its condition, is kept.

While a session runs, a breakpoint the debugger has **not verified** (its code
is not loaded yet, or the line has no code) is drawn as a hollow ring, with the
debugger's explanation on hover. Every breakpoint is hollow from the start of
the session until the debugger answers, and the markers return to normal when
the session ends.

### Managing breakpoints

A breakpoint's glyph shows its kind by shape as well as colour: a dot for a
plain breakpoint, a diamond for a logpoint, two "=" bars for a breakpoint with
a condition or a hit count, a "1" for a
[temporary breakpoint](#temporary-breakpoints), a λ for a
[lambda breakpoint](#lambda-breakpoints), purple with a ring for a
[recording breakpoint](#recording-breakpoints), an hourglass for a
[triggered breakpoint](#triggered-breakpoints), and grey for a disabled one. Java
[method breakpoints and field watchpoints](#method-breakpoints-and-field-watchpoints)
have marks of their own on their member's line.
Its hover names the condition, log message, hit count and group, and says when it is
disabled, removed once hit, recorded in Debug History, or waiting for another
breakpoint. A hit
count stops on that hit in Java too: `3` stops the third time the line runs.

Right-click a glyph for **Edit Breakpoint…**, **Enable Breakpoint** or
**Disable Breakpoint**, **Remove Breakpoint**, **Add Triggered Breakpoint…**,
**Move Breakpoint to Group…** and **Breakpoints…**; on a line that starts a
lambda or, in JavaScript and TypeScript, a function, the menu also has
**Stop on the Line** and **Stop in Lambda n** (see
[Lambda breakpoints](#lambda-breakpoints)). The editor's right-click menu has a
**Breakpoints** submenu with every breakpoint action for the line: Toggle
Breakpoint, Toggle Temporary Breakpoint, Toggle Recording Breakpoint, Toggle
Lambda Breakpoint on a line that starts a lambda, Add Logpoint, Add Triggered
Breakpoint, Edit Breakpoint, Move Breakpoint to Group, Enable / Disable
Breakpoint, then Breakpoints… and Mute Breakpoints. While the program is
paused, the editor's menu also has Run to Cursor,
[Force Run to Cursor](#force-run-to-cursor), Show Value at Caret,
[Evaluate Expression](#the-evaluate-window) and Add to Watches.

Click the gutter strip to toggle a breakpoint; Shift+click it to add a
[logpoint](#logpoints). A breakpoint can't be set on a blank line or on a line that holds only a
comment; the status bar says so. Removing one is always possible. A line with
only a closing `}` takes a breakpoint.

Removing a breakpoint that you set up asks nothing: one with a condition, a log
message, a hit count, a group or a filter, a method breakpoint or field
watchpoint with a condition, or an
[exception breakpoint by type](#exception-breakpoints-by-type). The status bar
says it was removed, and
[**Edit: Undo Last Removal**](/docs/undo-history#undo-last-removal)
(`edit.undoRemoval`, in the Edit menu; no key until you give it one) puts the
last one back as it was, with its condition, group and filters. It does the
same for a [watch](#watches) and a [pinned value](#pinned-values). When the breakpoint
can't go back where it was (the file was closed, the line reads something else,
or a new breakpoint is on it), it says so and changes nothing. A plain
breakpoint isn't recorded: a click sets it again.

**Debug: Breakpoints…** (`C-c C-d l`; Ctrl+Shift+F8 in the IntelliJ keymap,
⇧⌘F8 on macOS) lists every breakpoint. Each row has the gutter's glyph for its kind, its file,
line and code, and below them what it does (its condition or log message) and
its folder, relative to the project (none for a file at the project root), or with
your home folder shortened to `~`. With no breakpoints, the list says how to set
one. A breakpoint that waits for another says which, such as *Waits until
shelf.js:12 is hit*, and one in a [group](#breakpoint-groups) names it, such as
*Group: checkout*. Type to filter it, by file, code or group name; Enter jumps
to the selected breakpoint, Alt+E enables or disables it, Alt+D removes it,
Alt+G turns its whole group on or off, and Alt+M mutes them all. Java
[method breakpoints and field watchpoints](#method-breakpoints-and-field-watchpoints)
are listed after the line breakpoints.

**Debug: Mute Breakpoints** (`C-c C-d m`) turns every breakpoint off without
removing it, until you run it again. It also stops exception breakpoints from
stopping the program; your caught and uncaught choices come back when you turn
it off. While breakpoints are muted, every glyph is faded, a glyph's hover says
that none of them stops the program, Mute Breakpoints is checked in
Run ▸ Breakpoints, and the Debug window's state line says "breakpoints muted".
Editora keeps the choice for each project, so a muted project is still muted
when you open it again. **Debug: Enable / Disable Breakpoint** (`C-c C-d t`)
switches the breakpoint on the caret line. These commands are in
**Run ▸ Breakpoints**.

**Debug: Break on Caught Exceptions** stops wherever an exception is thrown,
even one the program catches. **Debug: Break on Uncaught Exceptions** stops on
an exception that nothing catches. Both are in Run ▸ Breakpoints, checked when on,
and in the menu of the ⚠ button in the Debug window's toolbar. While either is
on, the ⚠ is drawn in the accent colour, and its tooltip says which are on.
They act on the running session's language, or else on the active file's, and
Editora keeps your choice for each project. To stop only on some Java exception
classes, list them, separated by commas, in **Exception breakpoints: only these
classes** on the Java card of **Settings → Debugging** (for example
java.lang.IllegalStateException). Left blank, both stop on every exception.

At an exception stop, the line that threw is drawn red, the
[exception card](#exception-card) opens under it, and the exception's type and
message are the top row of Variables.

### Logpoints

A logpoint logs a message each time its line runs, and the program goes on
without stopping, like a `print` you don't have to add to the code and remove
again. IntelliJ IDEA calls these non-suspending breakpoints with a log message,
and VS Code calls them logpoints.

To add one in a single step, **Shift+click** the breakpoint gutter on the line,
as in IntelliJ, or run **Debug: Add Logpoint…** (`debug.addLogpoint`;
`C-c C-d g` in the Emacs keymap) on the caret line. It is also in the editor's
right-click **Breakpoints** submenu and in **Run ▸ Breakpoints**. A small form,
*Add Logpoint* with the file and line in its title, asks only for the
**Log message**. Put an expression in braces to include its value:
`{item.name}: total = {total}`. While the program is paused, the field completes
the `{expression}` at the caret, as the full
[Edit Breakpoint](#edit-breakpoint) form does.

![The Add Logpoint — shelf.js:13 form over the editor: the line's code total += subtotal; above a Log message field holding {item.name}: total = {total}, a hint that it is logged each time the line runs and the program does not suspend, and Cancel and OK buttons](/screenshots/docs/debug-add-logpoint.png "Add Logpoint asks only for the message; braces include a value.")

OK makes the line a logpoint, drawn as a blue diamond, and the status bar says
it logs and never suspends. OK is greyed out while the message is blank, so the
form never leaves a breakpoint that stops the program; Cancel adds nothing.
On a line that already has a breakpoint, the form opens with its current
message, and OK changes only the message: its condition and hit count stay.
A line with no code is refused, as for a breakpoint. A plain click on the
gutter still toggles an ordinary breakpoint.

Each time the line runs, the message, with the values filled in, appears in
the Debug window's console. A condition or a hit count, set in
**Edit Breakpoint**, decides which runs of the line log.

A message can also say where it ran, with Visual Studio's tracepoint tokens:
`$FUNCTION` for the function or method, `$CALLER` for the one that called it,
`$CALLSTACK` for the whole call stack (one frame per line, below the message),
and `$TID` and `$TNAME` for the thread's id and name, as in
`total = {total} in $FUNCTION, called from $CALLER`. No debugger knows these
tokens, so a logpoint that uses one pauses the program briefly at each hit
while Editora writes the message, then lets it go on.

![A JavaScript session paused on line 20 of shelf.js, the logpoint's blue diamond on line 13, and the Debug window's console showing four logged lines: 'Pencil': total = 0, 'Notebook': total = 4.800000000000001, 'Stapler': total = 13.05 and 'Marker': total = 21.950000000000003](/screenshots/docs/debug-logpoint-console.png "Each pass through the loop logs a line in the console; the program never stopped there.")

Editora's own JVM debugger, which debugs [Groovy](/docs/groovy#debugging),
[Kotlin](/docs/kotlin#debugging), [Clojure](/docs/clojure#debugging) and
[Scala scripts](/docs/scala#debugging), doesn't support logpoints yet: a
logpoint there isn't set, and its hover says why. netcoredbg, the C# debugger, doesn't log them itself, so
Editora does: at each hit the program pauses briefly, each `{expression}` is
evaluated where it stopped, the message is written in the Debug console, and
the program goes on. The editor isn't taken to the line, and the stop isn't
kept in [Debug History](#debug-history).

### Temporary breakpoints

A temporary breakpoint is removed once the program stops on it, so it stops
the program only once. **Debug: Toggle Temporary Breakpoint**
(`C-c C-d 1`; Ctrl+Alt+Shift+F8 in the IntelliJ keymap, ⌥⇧⌘F8 on macOS) works
on the caret line:

- on a line without a breakpoint, it adds a temporary one;
- on a line with an ordinary breakpoint, it makes that breakpoint temporary,
  keeping its condition and hit count;
- on a line with a temporary breakpoint, it removes it.

The command is also in **Run ▸ Breakpoints** and the editor's right-click
**Breakpoints** submenu. In **Edit Breakpoint**, **Remove once hit** turns any breakpoint into a
temporary one, or back.

A temporary breakpoint's dot carries a "1" (one with a condition or a hit count
shows the "=" bars instead), and its hover and its row in **Debug: Breakpoints…**
say *Removed once hit*. When the program stops on it, Editora removes it, also
from the running session, and the status bar says *Temporary breakpoint on line
N hit and removed.* A temporary breakpoint with a condition or a hit count is
removed only when it actually stops the program. A logpoint never stops the
program, so it is never removed.

### Recording breakpoints

A recording breakpoint keeps a stop instead of staying stopped. Each time it
is hit, [Debug History](#debug-history) keeps that stop, with its call stack,
the stopped frame's local values, your watches and the line's inline values,
and the program goes on at once. The editor does not jump to the line, and the
Debug console notes each one as *Recorded stop n:* followed by where it was.
Afterwards, Back, Forward and Show Value History read the recorded stops as
any other, so you can follow how a value changed over many passes of a loop
without stepping through each one. This is what Visual Studio's tracepoints and
IntelliJ's non-suspending breakpoints do, with the values kept instead of
logged.

**Debug: Toggle Recording Breakpoint** (`debug.toggleRecordingBreakpoint`, in
**Run ▸ Breakpoints** and the editor's right-click **Breakpoints** submenu) works
on the caret line:

- on a line without a breakpoint, it adds a recording one;
- on a line with an ordinary breakpoint, it makes that breakpoint record,
  keeping its condition and hit count;
- on a line with a recording breakpoint, it removes it.

In **Edit Breakpoint**, **Record and continue** turns any breakpoint into a
recording one, or back. A recording breakpoint is drawn purple with a ring,
and its hover says *Recorded in Debug History; the program goes on*. A
condition or a hit count still decides when it records. A logpoint never stops
the program, so it records nothing.

A session records at most 500 stops. After that, a recording breakpoint stops
the program as an ordinary one does, and the status bar says why. It also
stops as usual when Debug History is turned off in **Settings → Debugging →
History**, since there is nowhere to keep the stop.

### Triggered breakpoints

A triggered breakpoint waits until another breakpoint is hit. Until then it
does not stop the program; from then on it stops like any other breakpoint,
for the rest of that debug session. The next session it waits again. Use one
when a line runs often but only matters after something else has happened: a
breakpoint in a loop that should stop only once a particular request has
come in, for example. This is VS Code's triggered breakpoint, IntelliJ IDEA's
*Disable until hitting the following breakpoint* and Visual Studio's *Only
enable when the following breakpoint is hit*.

**Debug: Add Triggered Breakpoint…** (`debug.addTriggeredBreakpoint`) works on
the caret line. It lists the other line breakpoints, with their file, line and
code; choose the one to wait for. The line gets a breakpoint if it has none,
and an ordinary one keeps its condition and hit count. The breakpoint it waits
for already is ticked, and choosing it again makes the breakpoint stop as
usual. The list leaves out any breakpoint that waits, directly or through
others, for this one, so two breakpoints never wait for each other. With no
other breakpoint to wait for, the status bar says to set that one first. The
command is in the palette, **Run ▸ Breakpoints**, the editor's right-click
**Breakpoints** submenu and a breakpoint's gutter menu. In **Edit
Breakpoint**, the **Wait for** field does the same; choose *No other
breakpoint* to stop waiting.

![shelf.js with breakpoints on lines 12 and 13 and, on line 21, a breakpoint carrying an hourglass; over the editor, the Debug: Add Triggered Breakpoint… list for line 21 with shelf.js:12 const subtotal = item.price * item.qty; ticked and shelf.js:13 total += subtotal; below it](/screenshots/docs/debug-triggered-breakpoint.png "A triggered breakpoint on line 21 waits for the one on line 12.")

A triggered breakpoint carries an hourglass on its dot, and its hover says
*Waits until shelf.js:12 is hit*. While a session runs and its trigger has not
been hit, it is drawn hollow and its hover says *Waiting: shelf.js:12 has not
been hit in this session*. When the trigger stops the program, the status bar
and the Debug console say so, such as *shelf.js:12 was hit: the breakpoint that
waits for it now stops*. A [recording breakpoint](#recording-breakpoints) can
be a trigger too: it records the stop and lets the waiting breakpoints stop.

The waiting is done by Editora, not by the debugger, so triggered breakpoints
work with every debugger Editora runs, and in files that are not open. They are
saved with the project's breakpoints, and the link holds when you edit the file
and the lines move. If you remove the breakpoint it waits for, a triggered
breakpoint becomes an ordinary one and stops at once. Only line breakpoints can
be triggers; a [method breakpoint or field
watchpoint](#method-breakpoints-and-field-watchpoints) can't.

### Breakpoint filters

A breakpoint on a line many threads or callers pass through can be told to stop
only for the ones you care about, as IntelliJ IDEA's breakpoint filters do.
[Edit Breakpoint](#edit-breakpoint) has three fields for it:

- **Only in threads:** stops only in threads whose name matches, such as
  `worker-*`.
- **Only when called from:** stops only when the function that called this one
  matches, such as `OrderService.save`.
- **Only on the object marked:** stops only when `this` (`self` in Python, Ruby
  and Rust, `$this` in PHP) is the object you gave that label with
  [Mark Object](#marking-objects).

Patterns are separated by commas, `*` and `?` are wildcards, `!` before a
pattern excludes it, and case is ignored: `worker-*, !worker-0` is every
worker but the first. A breakpoint with filters is drawn as conditional, and
its hover and the breakpoints list name them, such as *Only in threads:
`worker-*`*.

The filters are checked by Editora, not by the debugger, so they work with
every language. At each hit Editora looks at the thread, the caller and the
object, and when one doesn't match, lets the program go on without showing the
stop. A filtered breakpoint's [hit count](#edit-breakpoint) and
[log message](#logpoints) count only the hits that pass the filters.

To stop on one object, mark it at the breakpoint's first stop: while no object
carries the label, the instance filter lets every hit through, and once one
does, the next stops are that object's.

### Breakpoint groups

Breakpoints can be put in named groups, as in IntelliJ IDEA, and a whole group
turned on or off at once: the breakpoints for one bug, or for one part of the
program, can stay set while you work on something else.

**Debug: Move Breakpoint to Group…** (`debug.moveBreakpointToGroup`) asks for
the group of the caret line's breakpoint: pick one of the groups there are,
type a new name, or leave it blank for none. It is in **Run ▸ Breakpoints**, the
editor's right-click **Breakpoints** submenu and a breakpoint's gutter menu. On
a line without a breakpoint, the status bar says so. In **Edit Breakpoint**,
the **Group** field does the same.

**Debug: Enable / Disable Breakpoint Group…** (`debug.toggleBreakpointGroup`)
lists the groups, each with how many breakpoints it has, ticked (✓) when all of
them are on and marked – when only some are. Choosing a group turns all its
breakpoints on, or all off when they are all on already. A running session is
told at once. Breakpoints in files that are not open follow too, and so do
method breakpoints and field watchpoints that came into a group from
[an imported file](#exporting-and-importing-breakpoints).

In **Debug: Breakpoints…**, each row names its group, typing a group's name
lists its breakpoints, and Alt+G turns the selected row's group on or off.

![The Breakpoints list over shelf.js: shelf.js:12 and shelf.js:13, hollow, marked Disabled · Group: checkout, and shelf.js:21 marked Waits until shelf.js:12 is hit · Group: report; the key line ends with Alt+G group on/off and Alt+M mute all](/screenshots/docs/debug-breakpoint-groups.png "The Breakpoints list with the checkout group turned off.")

### Exporting and importing breakpoints

**Debug: Export Breakpoints…** (`debug.exportBreakpoints`) writes your
breakpoints to a file you can keep, or share with a teammate, as Visual
Studio's Export Breakpoints does. When some breakpoints are in groups, it first
asks whether to write all of them or one group's. The file keeps each
breakpoint's condition, log message, hit count, lambdas, whether it records or
is removed once hit, its group, and the breakpoint it waits for, along with
method breakpoints and field watchpoints. Files inside the project are written
relative to it, so the file works in another copy of the project. The code of a
line in an [encrypted file](/docs/encrypted-files) is left out of it.

**Debug: Import Breakpoints…** (`debug.importBreakpoints`) sets the breakpoints
of such a file again. Each one goes on the line its code is on now, so edits
since the export don't put it on the wrong line. A line that already has a
breakpoint keeps its own, and breakpoints in files that aren't there are
skipped. The status bar counts them, such as *Imported 5 breakpoints; 1 already
set, 0 in files not found.* A running session gets the imported breakpoints at
once, in open files and closed ones. A file that isn't an Editora breakpoints
file, or one written by a newer Editora, is refused, and nothing is imported.

Both commands are in **Run ▸ Breakpoints** and the palette.

### Lambda breakpoints

In Java, a line breakpoint stops once, when its line runs, and never inside a
lambda on that line. On `items.forEach(x -> System.out.println(x));` it
stops before `forEach` is called, not each time the lambda's body runs, because the body
runs later, as code of its own. A lambda breakpoint stops there. In JavaScript
and TypeScript the same goes for arrow functions and function expressions:
on `items.filter((item) => item.price < 1)`, a lambda breakpoint stops in
`item.price < 1` once for each item.

**Debug: Toggle Lambda Breakpoint** (`debug.toggleLambdaBreakpoint`) sets a
breakpoint that stops inside the lambda the caret is in, or the first one on
the caret's line, each time its body runs, with the lambda's parameters in
Variables. Its keys are Shift+F9 (⇧F9 on macOS) in the VS Code, CUA and Sublime
Text keymaps and `C-c C-d S-l` in the Emacs keymap. It is in
**Run ▸ Breakpoints**, and in the editor's right-click **Breakpoints** submenu on
a line that starts a lambda.

- On a line without a breakpoint, it adds one that stops only in that lambda.
- On a line with a breakpoint, it adds that lambda to the places it stops.
- On a lambda the breakpoint already stops in, it takes the lambda away, and
  removes the breakpoint when it stops nowhere else.

The status bar says which, such as *Breakpoint in lambda 1 on line 12.* On a
line that starts no lambda, it says so, and in other languages it says lambda
breakpoints are for Java, JavaScript and TypeScript files.

Right-click a breakpoint's dot on a line with lambdas to choose where it
stops, in any combination: **Stop on the Line** (**Debug: Toggle Stop on the
Line**, `debug.toggleStopOnLine`, on the caret's line), and **Stop in Lambda 1**,
**Stop in Lambda 2**… for each lambda the line starts, numbered from the left,
each followed by the start of its lambda.
At least one stays on: with no lambda checked, the breakpoint stops on the line
again.

![Shelf.java with a breakpoint on line 56, .filter(item -> item.price() < 1.0), its dot carrying a λ, and the dot's right-click menu: Edit Breakpoint…, Disable Breakpoint, Remove Breakpoint, Stop on the Line unchecked, Stop in Lambda 1: item -> item.price() < 1.0) checked, and Breakpoints…](/screenshots/docs/debug-lambda-breakpoint.png "A lambda breakpoint: the λ on its dot, and the menu that chooses where it stops.")

Such a breakpoint carries a λ on its dot, and its hover says *Also stops in
lambda 1* or *Stops only in lambda 1*. It keeps its condition, hit count and
log message for every place it stops, and is saved with the project's
breakpoints. Lambdas are kept by their number on the line, so editing earlier
in the line doesn't lose them; when the line no longer has that lambda, the
breakpoint stops on the line instead.

In JavaScript and TypeScript, the lambdas of a line are its arrow functions
(`async` ones and those with a TypeScript return type, such as
`async (id): Promise<Item> => …`, included) and the `function` expressions
that don't begin the line, such as a callback passed as
`function (item) { … }`. A function declaration that begins its line needs no
lambda breakpoint: a line breakpoint there already stops in it. Arrows inside
strings, template literals and comments don't count. The breakpoint stops where
the function's body starts: the expression after `=>`, or the first statement
inside its braces.

Java, JavaScript and TypeScript have lambda breakpoints. The other JVM
languages and the other debuggers use line breakpoints.

### Method breakpoints and field watchpoints

In Java, a breakpoint can be on a method or a field instead of a line.

**Debug: Toggle Method Breakpoint** (`debug.toggleMethodBreakpoint`,
`C-c C-d S-m`) stops the program when the method the caret is in is entered,
before its body runs. It covers every overload of that name in that class, but
not the method in a subclass. **Debug: Toggle Field Watchpoint**
(`debug.toggleFieldWatchpoint`, `C-c C-d S-w`) stops the program when the
field declared on the caret line is assigned, in any instance of the class.
Run either again on the same method or field to remove it; the status bar says
what was added or removed. Both are in **Run ▸ Breakpoints**. The IntelliJ IDEA
keymap has no keys for them.

Away from a method or field, they ask for one by name: the class, `#`, and the
member, such as `com.example.Order#total` or `com.example.Order#count`. The
prompt starts with the caret's class filled in. A nested class is written with
`$`, as in `com.example.Order$Line#price`. `com.example.Order::total` and
`com.example.Order.total()` are read the same way.

While the program is paused, **Break When Value Changes** in the Variables
tree's right-click menu (`debug.watchSelectedField`) sets a field watchpoint on the field a row shows, such
as a field of `this` or of an object you unfolded. A local variable can't be
watched, and the status bar says so.

Method breakpoints and field watchpoints are saved per project with the line
breakpoints, and every Java session gets them. **Debug: Breakpoints…** lists
them after the line breakpoints, as *Method breakpoint* or *Field watchpoint,
stops on assignment*. There Alt+E enables or disables one, Alt+D removes it,
and Enter opens the method or field, found through the Java language server.
**Mute Breakpoints** mutes them with the others, and
[Force Run to Cursor](#force-run-to-cursor) runs past them. When one stops the program,
the Debug window's state line says *Suspended on entering a method* or
*Suspended on a field access*.

Each one is marked in the gutter on its member's declaration line, in an open
Java file: a dot with an arrow into it for a method breakpoint, on the line of
every overload it stops in, and a dot with an eye for a field watchpoint. A
mark is gold when the breakpoint has a condition or a hit count, grey when it
is disabled, and faded while breakpoints are muted. Hover it to see what it is,
such as the method's name and *Method breakpoint*; click it to remove it.
Right-click it for **Disable Breakpoint** or **Enable Breakpoint**,
**Remove Method Breakpoint** or **Remove Field Watchpoint**, and
**Breakpoints…**. From the keyboard, **Debug: Enable or Disable Method
Breakpoints on Line** (`debug.toggleMarkedMembersEnabled`) and **Debug: Remove
Method Breakpoints on Line** (`debug.removeMarkedMembers`) act on the marks of
the caret's line. The mark follows its member as you edit. When a line has a
line breakpoint too, the gutter shows the line breakpoint, and a click on an
unmarked line still sets a line breakpoint.

![Shelf.java with a field watchpoint's mark, a dot with an eye, beside line 13, private final List<Item> items;, and a method breakpoint's mark, a dot with an arrow into it, beside line 39, Receipt checkout(int limit) {](/screenshots/docs/debug-member-marks.png "A field watchpoint marked on its field and a method breakpoint on its method.")

The marks are placed from the Java language server's outline of the file, so
without it there are none; the breakpoints still stop the program. Only the
Java debugger has method breakpoints and field watchpoints.

### Exception breakpoints

**Debug: Exception Breakpoints…** (`debug.toggleExceptionBreakpoints`) lists
the exception filters of the debugger for the current session's language (or
the active file's, when no session runs). A checkmark marks the ones that are
on; choosing one turns it on or off, also during a session.

| Language | Filters | On at the start |
| --- | --- | --- |
| Python | Raised Exceptions, Uncaught Exceptions, User Uncaught Exceptions | Uncaught Exceptions |
| Java | Caught Exceptions, Uncaught Exceptions | none |
| JavaScript | Caught Exceptions, Uncaught Exceptions | none |
| Groovy, Kotlin, Clojure, Scala | Caught Exceptions, Uncaught Exceptions | Uncaught Exceptions |
| Rust | Rust: on panic | Rust: on panic |
| C and C++ (CodeLLDB) | C++: on throw, C++: on catch | C++: on throw |
| C# | C#: user-unhandled exceptions, C#: all exceptions | C#: user-unhandled exceptions |
| Ruby | Ruby: any raised exception, Ruby: RuntimeError | none |
| PHP | PHP: exceptions, errors, warnings, notices, deprecations, everything | none |
| Dart and Flutter | All Exceptions, Uncaught Exceptions | Uncaught Exceptions |
| JavaScript in Firefox | All Exceptions, Uncaught Exceptions, Debugger Statements | Debugger Statements |

Go has none to choose: Delve stops on a panic by itself. Lua's debugger stops
on an uncaught error by itself too. Elixir and PowerShell have none, and Bash
has no exceptions.

A session starts with the filters its debugger turns on by default, so a
Python program stops where an uncaught exception is raised, with the
exception's type and message in the Debug window and the status bar. In
Python, `sys.exit()` with a non-zero code also counts as an uncaught exception;
Continue then ends the program. Editora keeps your choice for each language and
each project, as it does for **Break on Caught Exceptions** and **Break on
Uncaught Exceptions** (see [Managing breakpoints](#managing-breakpoints)).

### Exception breakpoints by type

To stop only on some exceptions, name their type, as IntelliJ IDEA's exception
breakpoints do. **Debug: Add Exception Breakpoint…**
(`debug.addExceptionBreakpoint`, also in **Run ▸ Breakpoints**) opens a form
with:

- **Exception type**: a type, or patterns separated by commas. `*` and `?` are
  wildcards and `!` before a pattern leaves it out, so `java.io.*`, `*Error`
  and `!InterruptedException` all work. A pattern matches the full type or its
  last part, so `KeyError` matches Python's `KeyError`. Blank is any type.
- **Language**: the language whose exceptions it is for.
- **Condition** (optional): an expression evaluated where the exception was
  thrown. `KeyError` with `order.id == 42` stops on that one order's error and
  lets the rest go by.
- **Enabled**.

When the program is stopped on an exception, the form offers that exception's
type. Subclasses are not matched by themselves: name them, or use a pattern
such as `*IOException`.

The program then stops only on an exception that matches; the others go by.
An exception nothing catches always stops, and while **Break on Caught
Exceptions** is on, every exception stops as before. If the condition can't be
evaluated, the program stops and the status bar says why.

Exception breakpoints are listed in **Debug: Exception Breakpoints…**, after
the debugger's own filters. Choose one to edit it, turn it off or remove it.
They are kept per project, with the other breakpoints, and work with every
language whose debugger stops on exceptions.

### Exception card

When the program stops on an exception, a card opens under the line that threw
it, once per stop. Its title says what kind of stop it is:

- **Uncaught exception**, in red, for an exception that nothing catches;
- **Exception (caught)** for one the program catches;
- **Exception** when the debugger doesn't say which. The Java debugger says
  which.

Below the title are the exception's type and its message. When the debugger
sends them, the card also lists the exceptions that caused it, each on a
**Caused by** line, and the stack trace. The Python and JavaScript debuggers
send them; the Java debugger sends only the type and the message.

- **Copy** copies the type, the message, the causes and the stack trace; so
  does **Debug: Copy Exception** (`debug.copyException`).
- **Don’t Stop on Caught Exceptions** appears on a caught exception while
  **Break on Caught Exceptions** is on, and turns that off, so the program no
  longer stops on exceptions it catches.

The card never takes focus, so typing stays in the editor. Esc in the editor,
the card's ✕ or a click anywhere else closes it, and so do Continue, a step,
picking another frame in the call stack, or the session ending. It follows its
line when you scroll, and closes when the line leaves the screen.

**Debug: Show Exception** (`debug.showException`, in **Run ▸ Debugger** while
the program is paused) opens the card again. If another frame is shown, it goes
back to the frame that threw first. When the program did not stop on an
exception, the status bar says so.

## Stepping

Step Over, Step Into and Step Out move the program a line at a time, Continue
runs it to the next breakpoint, and Pause stops it where it is; their keys are
in [Commands and menus](#commands-and-menus).

### Force Run to Cursor

**Run to Cursor** (`debug.runToCursor`) runs the program to the caret's line
and stops there, unless a breakpoint on the way stops it first.
**Force Run to Cursor** (`debug.forceRunToCursor`) runs to the caret line as Run to Cursor does, but no
breakpoint or logpoint on the way stops it, in any file. Exception breakpoints
still stop the program. The breakpoints are back at the next stop, wherever
that is: the caret line, an exception, or Pause. Its keys are `C-c C-d S-u` in
the Emacs keymap and Ctrl+Alt+F9 (⌥⌘F9 on macOS) in the IntelliJ IDEA keymap;
it is also in **Run ▸ Stepping** and, while the program is paused, in the
editor's right-click menu.

While the program is paused, pointing at a line number turns it into an arrow,
and its tooltip names the line: click it to run to that line, as Run to Cursor
does, or Shift+click it to get there past every breakpoint on the way, as Force
Run to Cursor does.

### Jump to Line

**Jump to Line** moves the program to another line without running the code in
between. It needs a debugger that can do that: Python's debugpy can. The Java
and JavaScript debuggers cannot, and Jump to Line then says it is not supported.

### Step timing

After a step or Continue, the time the program ran until it stopped appears
faintly after the line it stopped on, as Visual Studio's PerfTips show it.
**Show how long each step took**, at the top of **Settings → Debugging** (on by
default), turns it off.

### Don't step into

**Don't step into**, in **Settings → Debugging**, lists code that Step Into
goes over: a step that lands there leaves at once. An entry can name:

- a class or package: `com.shop.util.*`
- a module: `requests`
- a function: `fmt.*`, `std::*`
- a file: `**/vendor/**`

`*` stands for any text. Each entry is for one language or every language, and
has its own on/off switch. **Add…**, **Edit…** and **Remove** change the list.

To add the code you are in, right-click a frame in the call stack and choose
**Don't Step Into This…** (`debug.dontStepIntoFrame`, also in
**Run ▸ Stepping**). It offers the frame's code as its language names it: the
class in Java, the file in Python (or the npm package in JavaScript), the
package in Go and C#, or the outer namespace in C++ and Rust.

A breakpoint inside code on the list still stops. The Java, Python and
JavaScript debuggers, and the other JVM languages', skip the code themselves;
with Go, Rust, C and C++ and the rest, Editora steps back out when a step
lands there.

### Stepping backward

In a session that can run the program backward, the Debug window has two more
buttons: **Step Back** (`debug.stepBack`) goes back over the last statement, with the
variables as they were before it, and **Reverse Continue**
(`debug.reverseContinue`) runs backward to the previous breakpoint, or as far
back as the program was recorded. Both are also in **Run ▸ Stepping** and,
in the Debug window, on the keys `b` and Shift+C. Unlike
[Debug History](#debug-history), which shows a stop as it was without touching
the program, these move the program itself back, and you can step forward
again from there.

For C and C++ debugged with **GDB** (**C and C++ debugger** on the
[Rust, C and C++ card](/docs/c-cpp#debugging) of **Settings → Debugging**), turn
on **Record Execution** (`debug.toggleRecording`, a toggle button in the Debug
window and in **Run ▸ Stepping**) while the program is paused. GDB records the program from there, the Debug window's
state line says *recording*, and Step Back and Reverse Continue go back over
what ran since. Run it again to stop recording. Recording slows the program
down a great deal, so turn it on just before the part you want to replay, not
at the start. GDB's recorder needs nothing installed, and it records up to
200,000 machine instructions by default. In a GDB session, Step Back and
Reverse Continue are there from the start but disabled until Record Execution
is on.

A debugger that says it can step back by itself is used as it is: the buttons
show in its sessions, and Record Execution is not needed. In a session that
can't run backward, the commands are greyed out in the command palette and say
why.

## Inspecting values

The line the program is stopped on is amber, with an arrow in the gutter, and
it is scrolled into view below any sticky header. Inline values appear only on
the lines up to the stopped line. Hovering a name shows its value once the
mouse rests; names in comments and strings are skipped. **Debug: Show Value at
Caret** (`C-c C-d v`) does the same from the keyboard.

A plain value, such as a number, shows on one line. An object, array or
collection shows as a tree that opens with its first level unfolded; unfold a
row to see its fields or elements. A Java `String` shows its whole text,
wrapped, under a line that gives its length, with a **Copy Value** button. A
big array comes a page at a time: its last row is "Show N more…". Right-click a
row for **Copy Value**, **Add to Watches** and **Pin to Line**, which
[pins](#pinned-values) its expression to the line the popup opened for; a
one-line value's menu has Copy Value and Pin to Line. The popup closes with Esc or when the mouse leaves it. When you open
it with Show Value at Caret, the keyboard works it while it is open:

| Key | Action |
| --- | --- |
| Up / Down | Move between rows |
| Right | Unfold the row |
| Left | Fold the row, or move to its parent |
| Enter | Unfold or fold the row, or load the next page on "Show N more…" |
| Shift+Enter | Add the row's expression to Watches |
| Ctrl+C (⌘C on macOS) | Copy the row's value |
| Esc | Close the popup |

Any other key closes the popup and does its usual thing in the editor. When the
popup was opened by the mouse, any key closes it.

After a step or any other stop, a value in Variables or Watches that changed
since the previous stop is **highlighted**: its row is tinted and the value is
bold, keeping its colours. Each frame is compared with what that same frame
showed at the previous stop, so after you step out of a call, the caller's
variables are compared with what they were before the call. A variable that
was not there before, such as one just declared, is not highlighted, and a new
session starts with nothing to compare. Choosing another frame and coming back,
or setting a value, keeps the highlights of the current stop.

### The Variables tree

In the Variables tree, the right-click menu has **Copy Value** (a string is
copied without its quotes and escapes), **Copy Name**, **Copy as Expression**,
**Add to Watches**, which adds the row's whole expression, such as
`order.items`, **Set Value…**, **Jump to Type Source**,
[Go to Index…](#long-lists-and-arrays) on a long list or array, in Java
[Break When Value Changes](#method-breakpoints-and-field-watchpoints) on a
field, [Mark Object…](#marking-objects) on an object,
[View As](#viewing-a-string-as-json-xml-or-text) on a string, and **Show
Numbers as Hexadecimal**. Ctrl+C (⌘C on macOS) copies the selected value, and Delete removes the selected
[watch](#watches) or evaluate result.

**Copy as Expression** copies what gives the value in the stopped code, as the
debugger itself names it: `items.get(0)` in Java or `d['k']` in Python, rather
than `items[0]`. Add to Watches, Set Value and View As use the same
expression. **Jump to Type Source** opens the class the value really has, which
may be a subclass of the variable's type, in your code or the JDK's; it is
offered where the file's language server can find a type by name.

Each of these rows is also a command that acts on the selected row, so it can
be given a key under Settings → Keymaps: **Debug: Copy Selected Value**
(`debug.copySelectedValue`), **Debug: Copy Selected Name**
(`debug.copySelectedName`), **Debug: Copy Selected Variable as Expression**
(`debug.copySelectedExpression`), **Debug: Add Selected to Watches…**
(`debug.watchSelected`) and **Debug: Jump to Type Source of Selected Value**
(`debug.jumpToSelectedType`); the other rows' commands are named in their
sections below.

In Java, **Set Value…** sets a number, boolean, character or String directly,
and an `Integer`, a record or an enum variable by assigning what you type as an
expression, such as `Color.RED`.

In Java, an object whose class has its own `toString()`, such as a record, an
enum or a date, shows that text after its type, for example
`Main$Point@17 "Point[x=3, y=4]"`, and still unfolds to its fields. A boxed
number, such as an `Integer`, shows just its value: `42`. A lambda reads
`lambda@19`. Variables, watches, inline values and the hover popup all show
values this way. A Variables row shows at most 200 characters of a value; Show
Value and Copy Value give the whole text.

Each `toString()` is your program's own code, run once per object at each stop,
so a stop gives them 1.5 seconds in all. A row whose `toString()` has not
answered by then reads *(toString() not loaded yet: click to load)*, and loads
when you click it, press Enter on it or unfold it. After Pause, objects read
`Type@id` with *(toString() not available after Pause)*. While a `toString()`
has not returned, the debugger takes no other request, and the status bar says
so: wait, or press Stop. **Show toString() values** on the Java card of
**Settings → Debugging** (on by default) turns these calls off: objects then
read `Type@id`, from the next debug session.

While the program is paused, **completion** works in the
[evaluate field](#the-evaluate-field-in-variables) over Variables, the
[Evaluate window](#the-evaluate-window), the Debug console's input field, the
Add Watch and Edit Watch prompts, and a breakpoint's condition and log message.
Press Ctrl+Space, or type `.`, to list what the debugger can complete in the
stopped frame. Keep typing to narrow the list, use Up and Down to move in it,
Enter or Tab to insert the selected item, and Esc to close it. In a field that
evaluates, Enter inserts the item and does not evaluate yet. In a log message, only the `{expression}` part at the caret is completed.
When the program is not paused, Ctrl+Space says under the field that
completion needs a pause. A breakpoint's condition is completed against the
frame the program is stopped in, not the breakpoint's own line.

Emoji and other characters outside the Basic Multilingual Plane show as
written in Java string values: in Variables, watches, the hover popup and
inline values. One limit remains, from a bug in the Java language server's
debugger: a string that an evaluation builds, such as `name + "!"`, and the
text inside a `toString()` result can still show such characters garbled.

### Autos

At each stop, the end of the Variables view has an **Autos** list, as Visual
Studio has: open it to see the values of the variables and fields used on the
line the program stopped at and on the line before. Once you open it, it stays
open from stop to stop. It works with every debugger Editora runs.

### Return values

When a step leaves a function, Variables shows what the function returned,
first in the caller's locals, as `(return) name`. This works in Python, and in
Kotlin, Groovy and Clojure on Editora's own JVM debugger, for the function you
step out of. JavaScript's debugger shows a "Return value" of its own.

### The call stack

The thread list leaves out the JVM's own housekeeping threads and puts the
stopped thread first, marked "(paused)". The [Threads view](#threads-view)
shows every thread at once. After the program ends, the state line
keeps "Exited with code N".

A deep stack lists its top 50 frames first, then a row such as
*… 250 more frames (click to list them)*: choosing it lists the frames below,
up to 1,000 in all. Stepping stays quick in a deep recursion or framework stack.

**Copy Call Stack**, in the call stack's right-click menu, copies the shown
thread's whole stack as text (the frames not listed yet are fetched first), ready to paste into a bug report or a chat.
Ctrl+C (⌘C on macOS) in the call stack does the same. Frames folded into one
row are copied too. The text reads like a Java stack trace: the thread's name,
then one line per frame, top frame first:

```text
Thread "main"
    at Main.total(Main.java:12)
    at Main.main(Main.java:5)
```

A frame without a source file is copied with its name only.

### Evaluating expressions

While the program is paused, an expression can be evaluated in the frame
selected in the call stack, as in IntelliJ IDEA: in the field at the top of
Variables, in an Evaluate window, or with Alt+click in the editor. An
expression may call a method, such as `items.add(7)`, and the call runs in
your program. After an evaluation, Variables, watches and inline values are
fetched again, so an assignment such as `count = 5` shows at once. An
expression that throws says what it threw, such as *The expression threw
NullPointerException in length()*.

Expressions are evaluated only at a stop. While you look at an earlier stop in
[Debug History](#debug-history), the fields say to go back to the live stop
first. A step pressed while an evaluation runs is refused, and the status bar
says why.

#### The evaluate field in Variables

The Debug window's Variables start with an evaluate field. Type an expression
and press Enter: its result is the first row of the tree, and an object, list
or map opens under it, a row per field or element. Those rows work like any
other Variables row, with **Set Value…**, **Copy Value**, **Copy Name** and
**Add to Watches** in their right-click menu. The result stays until the
program moves on; **Clear Result**, in the result row's menu, or Delete on it,
removes it sooner (`debug.clearResult`).

The button beside the field adds the expression to [Watches](#watches), so you
can follow it at every stop (`debug.watchEvaluated`). **Debug: Focus Evaluate Field**
(`debug.focusEvaluate`) puts the keyboard in the field from anywhere.

#### The Evaluate window

**Debug: Evaluate Expression** (`debug.evaluate`: Alt+F8 in the IntelliJ IDEA
keymap, `C-c C-d x` in Emacs, and in the editor's right-click menu while the
program is paused) opens an **Evaluate** window. It opens with the selection,
or the expression at the caret, ready to evaluate or to type over. The window
is not modal: keep it beside the editor while you step, or open several at
once, each with its own expression.

Enter, or the **Evaluate** button, shows the result below as a tree, which
opens like the one in Variables, with Copy Value, Copy Name and Add to Watches
on its rows. A failure shows the debugger's reason in its place. The line
beside the buttons, *Evaluates in …*, names the frame the window evaluates in,
with its line and file. While the program runs, the line says it is not paused and Evaluate is
greyed out. **Add to Watches** adds the expression to Watches, and the
History button beside the field (`debug.evaluateHistory`) lists your last expressions; pick one to put it
back in the field. Esc or **Close** closes the window. After the program moves
on, the last result can still be read but not unfolded; evaluate again for the
new stop.

To type in the evaluate field in Variables instead, use
[Focus Evaluate Field](#the-evaluate-field-in-variables).

#### Typing an expression

The evaluate field and the Evaluate window's field are small code editors:

- They are **highlighted** in the language the program is stopped in.
- They take your **keymap's** motion and editing keys. In the Emacs keymap
  that is `C-a` and `C-e`, `M-f` and `M-b`, `C-d`, `C-k`, `M-d`, `M-DEL`,
  `C-w`, `M-w` and `C-y`, and `C-/` to undo.
- **Completion** lists what the debugger can complete: press Ctrl+Space, or
  type `.` (see [completion](#the-variables-tree)).
- They share one **history**, kept between sessions. Up and Down bring back
  the expressions you evaluated before; in Emacs, `C-p` and `C-n` do too at the
  first and last line, and so do `M-p` and `M-n`.
- **Shift+Enter**, or the expand button beside the field, turns it into a code
  fragment of several lines, where Enter starts a new line and **Ctrl+Enter**
  evaluates.

Java and JavaScript give a fragment's value: `int x = 2; return x * 3;` in
Java, or `let x = 2; x * 3` in JavaScript, shows 6. Python runs a fragment but
shows no value; evaluate an expression on its own to see one.

#### Quick Evaluate

While the program is paused, **Alt+click** an expression in the editor to
evaluate it at once. Clicking a name evaluates it with the names before it, as
hovering does: `order.total` for the `total` of `order.total`, and `order`
alone for `order`. Clicking a method's name includes the call and its
arguments, so on `get` in `list.get(i + 1)` it runs `list.get(i + 1)`, which
hovering never does. Alt+click inside a selection evaluates the selection
instead. The value shows in the same
popup as a [hover](#inspecting-values), as a tree for an object, and an
expression that fails shows the debugger's reason. Unlike hovering, Quick
Evaluate runs code, so a call it evaluates has its effects. **Debug: Quick
Evaluate at Caret** (`debug.quickEvaluate`) does the same from the keyboard,
for the selection or the expression at the caret.

#### Time limit

An expression that calls a function which never returns, in a watch, the
Debug console or a hover, would hold the paused program for good. With GDB and
Python's debugger, the call is stopped after a time limit: the program stays
paused on the same line, and the next expression is evaluated there. Set it
with **Time limit** under **Evaluating expressions** in
**Settings → Debugging**: 10 s by default, or 5 s, 30 s, 60 s, 300 s, or
**Each debugger's own**. It applies from the next session on.

CodeLLDB stops such a call in a watch or a hover by itself. The Java,
JavaScript and Go debuggers have no limit to set: after 30 seconds Editora
stops waiting and says the program is still running the expression.

### Watches

A watch is an expression evaluated at every stop and listed under **Watches**
in Variables. Add one with **Add to Watches** in a Variables row's menu, the
button beside the [evaluate field](#the-evaluate-field-in-variables), the
Evaluate window, the editor's right-click menu while paused (**Debug: Add
Expression at Caret to Watches…**, `debug.addWatchAtCaret`), or
*Debug: Add Watch* (`C-c C-d w`). Delete, or **Debug: Remove Selected Watch**
(`debug.removeSelectedWatch`), removes the selected watch, and
[Undo Last Removal](#managing-breakpoints) puts it back in its place, paused
or not. **Edit Watch…** (`debug.editSelectedWatch`) changes its expression.

A watch row's right-click menu has:

- **Pause Watch**: the watch stays listed, marked *(paused)*, but is not
  evaluated at each stop. Use it for an expression that is slow or that you
  need only now and then. **Resume Watch** evaluates it again at every stop.
  A paused watch stays paused in later sessions. Both are
  `debug.pauseSelectedWatch`.
- **Evaluate Once** (`debug.evaluateSelectedWatchOnce`), on a paused watch
  while the program is paused: evaluates it for the stop shown, and it stays
  paused.
- **Set Value…**, while the program is paused and the watch has a value: gives
  the watch's expression a new value, such as a variable, a field or an
  element. The prompt starts with the current value, and every watch and value
  shown is evaluated again afterwards. Python and JavaScript set the expression
  itself; Java, Go and C and C++ set the variable the watch names, and Java
  evaluates an assignment for a field such as `o.n`.
- **Remove All Watches**, also on the Watches heading: removes every watch.

### Pinned values

A pinned value is an expression kept on a line and shown at the end of that
line at every stop, as Visual Studio's pinned DataTips and IntelliJ's inline
watches are, so you can follow it next to the code instead of in Watches.

**Debug: Pin Value to Line** (`debug.pinValue`, in **Run ▸ Debugger**) pins the
selection, or the expression at the caret, to the caret's line. With neither,
it asks for an expression. Running it again on the same expression unpins it.
**Pin to Line** in the [value popup](#inspecting-values)'s right-click menu,
shown as *Pin `total` to Line 12*, pins the popup's expression to the line it
opened for (`debug.pinShownValue`). A line holds at most four pinned values. **Debug: Unpin Values on
Line** (`debug.unpinLine`) removes every value pinned to the caret's line. The
status bar says what was pinned or unpinned, and
[Undo Last Removal](#managing-breakpoints) puts back a value, or a line's
values, that you unpinned.

At each stop, and when you pick another frame, every pinned value in the open
files is evaluated in the selected frame and painted after the values the
editor shows by itself, in the info colour:

```
◆ total = 3  ·  items.size() = 2
```

A value is cut to 60 characters. One that can't be evaluated there, such as a
variable not in scope, shows *unavailable:* and the debugger's message. The
values are cleared while the program runs and when the session ends.

![Shelf.java paused on line 52 in checkout, with total, subtotal and item.name() pinned to line 54, the loop's closing brace, which shows ◆ total = 4.560000 · subtotal = 8.250000 · item.name() = "Notebook", and the Debug window below with the call stack and Variables](/screenshots/docs/debug-pinned-values.png "Three values pinned to one line, evaluated at the stop.")

Pins are kept when no session runs, though they show only while the program is
paused. They follow their line as you edit above it, are saved with the
project's breakpoints, and come back on their line when the file opens again.

### Filtering Variables

The **Filter** field beside the Variables heading narrows each scope to the
variables whose name contains what you type, ignoring case: `id` keeps `id`,
`userId` and `ID_PREFIX`. Rows that arrive later, such as the next page of a
scope, are filtered as they come. Watches are never filtered. In the field, Esc
clears it and Down moves into the Variables tree.

### Long lists and arrays

A list or array opens 100 elements at a time in Variables and Watches; the last
row reads *Show N more… (M remaining)*. Once a list or array with more than
100 elements is open, its right-click menu has **Go to Index…**
(`debug.goToSelectedIndex`), which asks for
an index from 0 to the last one and shows the elements from there, still 100 at
a time. The index can be written `250`, `[250]`, `1_000` or `1,000`. Go to
Index 0 goes back to the start. While you look at a part of a list this way,
its other children, such as a Java list's fields, are left out.

### Hexadecimal values

**Debug: Show Numbers as Hexadecimal** (`debug.toggleHex`), also in
**Run ▸ Debugger** and as a check item in the Variables tree's right-click
menu, shows
integer values in hexadecimal in Variables, watches, the hover popup and inline
values, so `255` reads `0xff`. Run it again to go back to decimal. The values
are fetched again at once, and the switch holds for later sessions until you
turn it off or quit Editora. Switching does not mark every number as
[changed](#inspecting-values).

The debugger formats the numbers: the Java and Python debuggers do,
JavaScript's does not, so a JavaScript program's numbers stay decimal. CodeLLDB
and Delve ignore it too; [Show Value As](#show-value-as) works with every
debugger.

### Show Value As

Right-click a whole number in Variables or a watch and choose **Show Value As ▸
Decimal**, **Hexadecimal**, **Binary**, **Octal** or **Character** to see that
one value that way, while the others stay as they are. **As the Debugger Shows
It** goes back.

- A negative number shows as its two's complement for its type: `-42` as an
  `int` is `0xffffffd6`. A `char` shows its code.
- Chosen on an array or an object, it applies to the numbers inside, except
  one with its own choice.
- The choice holds at the next stop and in the next session, until the window
  closes.
- **Copy Value** copies what is shown.
- On a value that is not a number, the submenu is greyed out and says why.

It works with every debugger. GDB and Delve don't give a value's type, so with
them a negative number keeps its sign, such as `-0x2a`.

### Marking objects

**Mark Object…**, in the right-click menu of a Variables, watch or
[evaluate result](#evaluating-expressions) row that shows an object, gives
that object a label of your own, as IntelliJ IDEA's Mark Object does. The
prompt starts with the row's name; type another, of up to 40 characters, and
press Enter. The label then shows in brackets before the object's value
wherever that object appears in the session: in Variables, watches, evaluate
results, the value popup and [Debug History](#debug-history). So when the same
object turns up in another variable, a field or a collection, you can tell it
is the one you marked. On a marked row the menu has **Edit Mark…** and
**Unmark Object**, and once anything is marked, **Unmark All Objects**. **Debug:
Mark Selected Object…** (`debug.markSelectedObject`) and **Debug: Unmark
Selected Object** (`debug.unmarkSelectedObject`) do it for the selected row.

![Shelf.java paused on line 48 in checkout, and the Debug window's Variables below: the watch items.get(0) and the local item both read [pencil] Item@11 "Item[sku=A-1, name=Pencil, quantity=12, price=0.4]"](/screenshots/docs/debug-mark-object.png "The object marked as pencil, recognised in a watch and a variable.")

An object is recognised by the identity its debugger shows for it, so Mark
Object is offered where the debugger shows one: Java objects (`Item@11`),
Python objects with an address (`<Box object at 0x7f…>`), and pointers in
native code, such as C and C++. The JavaScript debugger shows no identity for
an object, so JavaScript and TypeScript objects can't be marked, and neither
can a number or a string.

Marks belong to the debug session. They stay after Stop, so Debug History
still shows them, and are cleared when the program is debugged again, since an
object of one run is not the same object in the next. A label only shows the
object; it can't be used in an expression. IntelliJ IDEA's *Referring
Objects* is not offered: the Java debugger has no way to find which objects
refer to another.

### Viewing a string as JSON, XML or text

**View As ▸ JSON**, **View As ▸ XML** and **View As ▸ Text**, in the
right-click menu of a Variables, watch or evaluate result row that holds a
string, open the string's whole text in a read-only tab, so you can fold,
search and copy it. JSON and XML are laid out and highlighted; Text opens it as
it is. The tab is named after the row, such as *order (JSON)*. A string that
isn't valid JSON or XML opens as text, and the status line says why, such as
*Not valid JSON, so shown as text: …*. The three are also
`debug.viewSelectedAsJson`, `debug.viewSelectedAsXml` and
`debug.viewSelectedAsText`, for the selected row.

![A read-only tab named order (JSON) showing the string laid out as indented JSON, with lines, sku, qty and total, and the Debug window below with Variables filtered to order, whose row reads '{"customer":"Ada","lines":[{"sku":"A-1","qty":12},…'](/screenshots/docs/debug-view-as-json.png "A JavaScript string opened with View As ▸ JSON.")

The text is the string's own, with the quotes and escapes the debugger shows
taken off, and it is fetched again whole while the program is paused, so a long
string that a row cuts short (JavaScript shows about 1,000 characters) opens
complete. At an earlier stop in Debug History, it opens what that stop kept.
XML is read without its DTD and without loading anything outside the text.

### Viewing a collection as a table

**View as Table** (`debug.viewSelectedAsTable`), in the right-click menu of a
Variables, watch or evaluate result row that holds a list, array, slice, set
or map, opens the collection in
a window of its own, as IntelliJ IDEA and Visual Studio do: a row for each
element and a column for each of its fields, so a list of orders reads as a
table of their ids, customers and totals. The first column is the element's
index, or a map's key; an element with no fields, such as a number, shows its
value. The debugger's own rows under a collection, such as Python's *special
variables* or an array's `length`, are left out.

- Click a column's heading to sort by it. Numbers sort by value, so `10` comes
  after `9`.
- The **Filter rows** field keeps the rows that mention what you type in any
  cell.
- **Copy** (Ctrl+C) puts the selected rows, or every row shown, on the
  clipboard as tab-separated text, ready to paste into a spreadsheet.
- **Open as CSV** (`debug.openTableAsCsv`) opens the rows shown in a
  [CSV](/docs/csv) tab.
- **Refresh** (`debug.refreshTable`) evaluates the collection again at the
  current stop and shows what it holds now; it needs the program paused.

The table keeps the values of the stop it was opened at, and its note says so,
such as *12 elements, as at this stop*, so you can step on and compare. A large
collection shows its first 1,000 elements, and the note says how many there
are. Each collection opens its own window. [Type renderers](#type-renderers)
apply to the cells. Checked with Python, Java and Go.

#### DataFrames and numpy arrays

In a Python program, the row is named **View as DataFrame** on a pandas
DataFrame or Series, and **View as Array** on a numpy array, as PyCharm's data
viewer does. It opens the data's rows, not the object's attributes:

- Columns keep their names, and hovering a column's heading shows its dtype.
  The heading of the window says the kind and the shape.
- A Series, or an array of one dimension, is a single column of values. A
  structured array's fields are its columns.
- An array of more dimensions is laid out as rows of its last one, each row
  named by the indexes before it, such as `(1, 2)`.
- The first 1,000 rows show; **Load More** brings the next 1,000.

Filter, sort, Copy and Open as CSV work as for any table, and Refresh reads
the first rows again while the program is paused.

### Type renderers

A type renderer chooses how the debugger shows a value of one type, as IntelliJ
IDEA's type renderers and Visual Studio's natvis do: a `Money` as `12.50 EUR`
rather than `Money@1f3a`, and, when unfolded, the fields or expressions that
matter instead of every field. The debugger's own children stay under a last
row, **[Raw View]**.

Right-click a value in Variables and choose **Customize Rendering…**
(`debug.customizeSelectedRendering`) to make one for that value's type, or to change the one it has. The form has:

- **Type:** the type's name, filled in from the row, such as `com.shop.Money`
  or `Money`.
- **Language:** the debugged language it applies to, or **Any language**.
- **Value:** an expression for the value shown on the row, such as
  `this.amount + " " + this.currency`. Empty keeps the debugger's own.
- **Children:** one `name = expression` per line, such as
  `owner = this.owner.name`. Empty keeps the debugger's own children.
- **Enabled**, to keep a renderer without using it.

Expressions are written in the debugged program's language and evaluated where
it stopped, with `this` (or `self`) for the value. A value of a subclass has its
own type, so a renderer for `Item` does not apply to a `SpecialItem`. A value
or child that can't be evaluated keeps the debugger's value, or shows its
message.

Renderers are listed under **Settings → Debugging → Type renderers**, where
**Add…**, **Edit…** and **Remove** manage them. They apply in Variables,
[watches](#watches), the [Evaluate window](#the-evaluate-window), the hover
popup and [View as Table](#viewing-a-collection-as-a-table). A watch or
evaluation whose expression calls a method or assigns is not evaluated a second
time for its renderer, so it never runs twice. Setting the value of a
renderer's child assigns to its expression.

**Mute Type Renderers** (`debug.muteRenderers`), in the Variables tree's
right-click menu and **Run ▸ Debugger**, shows every value as the debugger gives
it; run it again to turn the renderers back on. Checked with Java, Python and
Go.

## History, threads and code views

These views show a session from other sides: its earlier stops, every thread
at once, and the code and memory under the source.

### Debug History

The Debug window keeps each stop of a session as it was shown: the call stack,
the values you looked at in Variables, your watches, the inline values and the
pinned values. After the program has run on, you can go back to an earlier
stop and read them again. Editora keeps the stops itself and asks the debugger
for nothing, so this works with every language's debugger.

- **Back** and **Forward**, the ‹ and › buttons in the Debug window's toolbar
  (or **Debug: Back** and **Debug: Forward**), go to the stop before or after
  the one shown.
- **Debug: Show History…** (`debug.showHistory`) lists the stops, each with
  its number, time, why it stopped and where. Pick one to go back to it.

![The Debug History list: Stop 7, breakpoint, main.checkout — shelf.go:46, then Stops 6 to 1, each a breakpoint in main.checkout at shelf.go:42, with the time of each stop](/screenshots/docs/debug-history-list.png "Debug: Show History… lists the stops of the session, newest first.")

An earlier stop is shown read-only. A banner above the Debug window says which
stop it is, when and where it stopped, and that the values are as they were
then; its **Back to the live stop** button returns to the program as it is now.
The stop's call stack and values fill the Debug window, values that changed
since the stop before are [highlighted](#inspecting-values) as usual, and the
editor marks the stop's line grey, with its inline and pinned values. Hovering
a name evaluates nothing there. Forward from the last stop, a step, Continue or
a new stop also return to the live stop.

![shelf.go during a Go debug session, shown at stop 4 of 7: line 42, total += subtotal, is marked grey with its inline values, total: 19.919 and subtotal: 4.8; the Debug window below has the banner Stop 4 at 22:33:59, breakpoint: main.checkout (shelf.go:42). Values as they were then; the program has run on, with a Back to the live stop link, the call stack at main.checkout, Variables with the values that changed since stop 3 highlighted, and the console listing Recorded stop 1 to Recorded stop 6](/screenshots/docs/debug-history.png "An earlier stop, read back from Debug History: a recording breakpoint kept six stops while the program ran on.")

Only values that were fetched while the stop was live are kept. A row nobody
unfolded at that stop reads *(not opened at that stop)*, since its children
were never asked for and the program has moved on.

**Show Value History**, in the right-click menu of a Variables or Watches row
(`debug.selectedValueHistory`), lists that row's value at every kept stop of the same frame and marks the ones
that changed. Pick one to go back to that stop. At an earlier stop, the menu
also has Copy Value and Copy Name, since nothing there can be changed.

The stops are kept in memory only, never on disk. A session keeps 200 by
default and forgets the oldest past that; **Stops kept** under **Settings →
Debugging → History** sets it to Off, 50, 200, 1,000 or 5,000. A new session
starts an empty history, but after the program ends you can still read the
last session's stops until you start the next one.
[Recording breakpoints](#recording-breakpoints) add stops to the history
without stopping the program.

### Threads view

The call stack shows one thread at a time, the one picked in the thread
dropdown above it (**Debug: Choose Thread…**, `debug.chooseThread`, opens it
from the keyboard). The **Threads** switch beside that dropdown
(`debug.toggleThreadsView`) replaces the frames and the dropdown with every
thread of the program. **Debug: Threads**
(`debug.threads`, also in **Run ▸ Debugger**) opens the Debug window on it.
Turn the switch off to see the frames again.

Each row names a thread and its state: **Running**, or suspended and why:

- **Suspended at a breakpoint**
- **Suspended after a step**
- **Suspended on an exception**
- **Paused**
- **Suspended at its start**
- **Suspended on entering a method**
- **Suspended on a field access**

The thread on screen, whose frames and variables the Debug window shows, is in
bold and marked ▶. The JVM's own housekeeping threads are left out, unless one
of them is suspended.

Right-click a thread to act on it alone:

- **Pause Thread** (`debug.pauseSelectedThread`), on a running thread, suspends that thread alone; the others
  keep running. Its stop is shown, as any stop is.
- **Resume Thread** (`debug.resumeSelectedThread`), on a suspended thread, resumes that thread alone; the
  others stay suspended. If it was the thread on screen, the next suspended
  thread is shown.
- **Show Frames** (`debug.showSelectedThreadFrames`), on a suspended thread, turns the switch off and shows that
  thread's call stack and variables. Enter or a double-click on the row does the
  same.

The list follows each stop and Continue, and threads that start or end while
the program runs update it.

With Python (debugpy), Pause Thread and Resume Thread pause or resume the whole
program, not one thread.

### Thread Dump

**Debug: Thread Dump** (`debug.threadDump`, also in **Run ▸ Debugger** and the
right-click menus of the [Threads view](#threads-view) and the call stack)
shows every thread of the program with its call stack, in a window of its own,
as IntelliJ IDEA's Get Thread Dump does. The threads are listed on the left
with their state, the thread on screen first; the selected one's call stack is
on the right.

It works on a running program too: Editora pauses it for a moment, takes the
dump and lets it go on, without moving the editor to where it was. That is the
way to see what a program that hangs is doing. On a paused program, the dump is
of the stop.

- **Merge identical stacks**, on by default, puts threads whose stacks are the
  same in one row, such as *worker-0 and 19 others*, so twenty pool workers
  waiting in one place read as one.
- The **Filter threads and frames** field finds a thread by its name or by a
  frame's text.
- Enter or a double-click on a frame opens its code at its line, even after the
  program went on: the dump is a snapshot.
- **Copy** (`debug.copyDump`) puts the dump on the clipboard as text, laid
  out as `jstack` prints one; **Open as Text** (`debug.openDumpAsText`) opens
  it in a read-only tab; **Take Again** takes a new one.

A thread that was running when the dump was taken has no call stack, and the
window says so and suggests Pause, then Take Again. That happens with Java when
a breakpoint stops only its own thread (see
[Threads in a Java program](/docs/java#threads-in-a-java-program)). Thread Dump works
with Java, Python, Go and every other debugger that lists threads; Go's
goroutines are listed as threads.

### Parallel Stacks

**Debug: Parallel Stacks** (`debug.parallelStacks`, beside Thread Dump in
**Run ▸ Debugger** and the Threads view's and call stack's menus) draws every
thread's call stack as one tree, as Visual Studio's Parallel Stacks window
does. The outermost calls are at the bottom. Threads that went through the same
calls share a box, which names how many threads it holds, and the tree branches
where they went different ways, so twenty workers waiting in one place are one
box of twenty threads. A frame whose threads are on different lines of the same
function says *several lines*.

The thread on screen is highlighted all the way up, through each box it passes
and its own top frame. A click on a frame, or Enter on it, opens its line.
**Copy** (`debug.copyDump` too) puts the tree on the clipboard as indented
text, and **Take Again** draws it anew. As with Thread Dump, a running program is paused for a moment
and goes on, and threads that were running at a stop, with no stack to draw,
are counted in a note. Checked with Python, Java and Go.

### Disassembly and Memory

For C, C++, Rust and Go, the Debug window's third area shows the machine code
and the memory of the program as well as its console, as VS Code, Visual
Studio and IntelliJ do. Three toggles in the area's header switch between
**Console** (`debug.showConsoleArea`), **Disassembly** and **Memory**. **Debug: Show Disassembly**
(`debug.showDisassembly`) and **Debug: Show Memory** (`debug.showMemory`), also
in **Run ▸ Debugger**, open the Debug window on them. Both views need a
debugger that offers them: CodeLLDB, which debugs Rust, C and C++, GDB, which
debugs C and C++, and Delve, which debugs Go, do. With another debugger, the view says it can't.

**Disassembly** shows the machine instructions around the selected frame,
about 120 of them, with their addresses. The instruction the frame is at is
marked and selected, and each run of instructions is labelled with the source
line it came from. Double-click an instruction to open its source line. The
list is filled again at each stop and when you pick another frame in the call
stack.

![shelf.go paused on line 39 in a Go program, with the Debug window's Disassembly view open beside Variables: machine instructions such as CALL main.discountFor(SB) and MOVSD_XMM X0, 0x58(SP) with their addresses, runs labelled shelf.go:37, shelf.go:38 and shelf.go:39, and the instruction the frame is at highlighted](/screenshots/docs/debug-disassembly.png "The Disassembly view: the instructions around the stopped line, each run labelled with its source line.")

While the Disassembly list has the keyboard, **Step Over** and **Step Into**
step one instruction at a time instead of one line, as in VS Code's
Disassembly view. **Debug: Step Over Instruction**
(`debug.stepOverInstruction`) and **Debug: Step Into Instruction**
(`debug.stepIntoInstruction`), in **Run ▸ Stepping**, do it from anywhere; Step
Into Instruction follows a call into the called function. With a debugger that
steps only by line, they say so in the status bar.

**Memory** shows the program's memory as rows of 16 bytes: the address, the
bytes in hexadecimal, and the same bytes as text. It reads 256 bytes at a time;
the ◀ and ▶ buttons (`debug.memoryPreviousPage`, `debug.memoryNextPage`) show
the 256 bytes before or after. Type an address
(`0x…`) or an expression, such as a variable's name, in its field and press
Enter: an expression shows the memory where its value is. **View Memory**
(`debug.viewSelectedMemory`), in a variable's right-click menu in Variables,
opens the view at that variable.
Memory that can't be read, or an expression with no address, is said in the
view.

To change memory while the program is paused, double-click a row of the
Memory view, press F2, or choose **Edit Bytes…** in its right-click menu, and
type the new bytes, in hexadecimal or as "quoted text". Only the bytes you
changed are written, and the Memory view and Variables show the new values.
This works on CodeLLDB, GDB and Delve.

#### Instruction breakpoints

In the Disassembly view, click the margin before an instruction's address,
press F9, or use the row's right-click menu to put a breakpoint on that one
machine instruction. The program stops there, and the Disassembly view opens
at it. **Edit Breakpoint…** on the row gives it a condition and a hit count,
as a line breakpoint has. The Breakpoints list shows the session's
instruction breakpoints too: Enter opens the Disassembly view at one, and
Alt+D removes it. An address belongs to one run of the program, so instruction
breakpoints last for the debug session. They work on CodeLLDB, GDB and Delve.

### Loaded Sources and Modules

While a session runs, two lists show what the program has loaded. Both are in
**Run ▸ Debugger** and the palette, where they are greyed out when no session
runs.

**Debug: Loaded Sources…** (`debug.loadedSources`), like VS Code's Loaded
Scripts, lists the scripts and source files the program has loaded: your
project's files first, then other files, then code the debugger has only in
memory, such as Node.js's own modules (`<node_internals>/…`) and code run
through `eval`. Type to filter it. Enter on a file opens it in its tab, where
breakpoints work as anywhere. Enter on code in memory opens it read-only, with
the text the debugger gives, as [Code without a file](#code-without-a-file)
describes.

![The Debug: Loaded Sources… list over a paused JavaScript session: shelf.js marked project folder first, then <node_internals>/_http_agent, _http_client, async_hooks, buffer and others, each marked read-only, from the debugger · Skipped by skipFiles](/screenshots/docs/debug-loaded-sources.png "Loaded Sources in a Node.js session: the project's file first, then Node.js's own modules.")

**Debug: Modules…** (`debug.modules`), like Visual Studio's Modules window,
lists the libraries, assemblies or modules the program has loaded, your code
first. Each row shows what the debugger says of it: whether its symbols are
loaded, its version, whether it is your code or optimized, its address range,
and its path. Enter or Alt+C copies the module's path.

![The Debug: Modules… list over a C# session paused in Checkout.cs: Shelf.dll first with Symbols loaded. and its path in the project's bin/Debug/net10.0 folder, then System.Collections.dll, System.Console.dll, System.Linq.dll, System.Private.CoreLib.dll, System.Runtime.dll and System.Runtime.InteropServices.dll, each with Symbols not found. and its path in the .NET shared runtime](/screenshots/docs/debug-modules.png "Modules in a C# session: the program's own assembly first, with its symbols loaded, then the .NET assemblies it uses.")

Which list a debugger fills:

| Debugger | Loaded Sources | Modules |
| --- | --- | --- |
| JavaScript and TypeScript (vscode-js-debug) | yes | no |
| Rust, C and C++ (CodeLLDB) | no | yes, with symbol status |
| C# (netcoredbg) | no | yes |
| Python (debugpy) | no | the modules whose code has run under the debugger, such as your script as `__main__`; not every import |
| Java, Go | no | no |

When the debugger doesn't fill a list, as Java's and Go's fill neither, the
command says so in the status bar. The lists follow the program as
it runs: a source or module it unloads drops out.

### Code without a file

When a JavaScript or TypeScript program stops in, or steps into, code the
debugger holds only in memory, such as code run through `eval`, that code opens in a read-only tab, named as the debugger names it
(such as `<eval>/VM46947589`), with the current line marked, as VS Code shows
it. The tab opens once per
session; a later stop in the same code brings it to the front. The call stack
shows such frames like any other, and clicking one opens its code.

![A read-only tab named <eval>/VM46947589 holding the five lines of a report function, line 3 debugger; marked as the current line, and the Debug window with the call stack global.report:3, VM46947589 above <anonymous>:26, shelf.js](/screenshots/docs/debug-eval-source.png "Code run through eval, shown read-only where it stopped.")

Breakpoints can be set in that tab. They last for that debug session only:
they are not saved, and they go when the session ends, because the code exists
only in that run of the program.

A pause while the program waits inside Node.js's own code, in a timer for
example, still shows your own line, not Node.js's internals. Python's debugger
leaves code run through `exec` out of the call stack by default, so in practice
this is for JavaScript and TypeScript. An earlier stop in such code, seen in
[Debug History](#debug-history), still says there is no source.

## Adapters and remote debugging

Where each debugger comes from, how to add one of your own, and debugging a
program on another machine, in a container or in WSL.

### Installing adapters

Adapters are not bundled. Most languages' cards on **Settings → Debugging**
have an **Install…** button that downloads what is missing into your config folder:
for Java and Python, the adapter together with its language server. The same
installs are three palette commands:

| Command | Installs | Needs |
| --- | --- | --- |
| **Install: Java Language Support** (`install.javaSupport`) | jdtls and the Microsoft java-debug plugin | `tar`, and a JDK to run `jdtls` |
| **Install: Python Language Support (Pyright, debugpy)** (`install.pythonSupport`) | Pyright and debugpy | Python with `pip` for debugpy; Node.js with `npm` for Pyright |
| **Install: TypeScript / JavaScript (Node.js) Language Support** (`install.jsSupport`) | `typescript-language-server` 5.3.0 with TypeScript 5.9.3, and vscode-js-debug v1.117.0 | Node.js with `npm`, and `tar` |

The Java and Python cards on the **Settings → Debugging** page have the same
**Install…** button; the JavaScript card's **Install…** installs only
vscode-js-debug. The **Go (Delve)** card's **Install…** runs `go install` for
Delve, the **Rust, C and C++ (CodeLLDB)** card's downloads CodeLLDB, the **C#
(netcoredbg)** card's downloads netcoredbg, the **Ruby (rdbg)** card's runs
`gem install` for the debug gem, the **PHP (Xdebug)** card's downloads PHP
Debug, and the **Bash (bashdb)** card's downloads Bash Debug with its own bashdb, the
**Lua (local-lua-debugger)** card's downloads Local Lua Debugger, the
**Firefox (vscode-firefox-debug)** card's downloads vscode-firefox-debug, and
the **Elixir (ElixirLS)** card's downloads ElixirLS. Dart's debugger comes with
the Dart SDK, and PowerShell's (PowerShell Editor Services) you install
yourself, so those two cards have no Install…. See
[Debugging Go](/docs/go#debugging), [Debugging Rust](/docs/rust#debugging),
[Debugging C#](/docs/csharp#debugging), [Debugging Ruby](/docs/ruby#debugging),
[Debugging PHP](/docs/php#debugging), [Debugging Bash](/docs/shell#debugging),
[Debugging Lua](/docs/lua#debugging), [Debugging Dart](/docs/dart#debugging),
[Debugging in Firefox](/docs/javascript-typescript#debugging-in-a-browser),
[Debugging Elixir](/docs/elixir#debugging) and
[Debugging PowerShell](/docs/powershell#debugging). An install fetches only what is missing. When one tool's
runtime is missing, the others still install and the status bar names the one
that waits (debugpy installs without Node.js, for example). When Python has no
`pip`, the error says so. After a debugger installs while debugging is off, the
status bar says where to turn it on.

Editora does not install those runtimes for you. A downloaded server or adapter
is unpacked beside the installed version and replaces it only once it is
complete, so a failed install leaves the working one in place.

To install an adapter by hand instead, the Editora repository has a helper
script for each:

- **Java**: the Microsoft java-debug plugin (layered onto `jdtls`),
  `scripts/install-java-debug.sh`. If your `jdtls` distribution already bundles
  and loads java-debug itself (Homebrew's does), Editora detects that and needs
  no second copy.
- **Python**: `debugpy`, `scripts/install-debugpy.sh`.
- **JavaScript**: vscode-js-debug, `scripts/install-js-debug.sh`.

**Settings → Debugging** shows one language's card at a time: pick it in
**Language**, under **Enable debugging (DAP)**. The cards every language
shares follow it. A search in Settings ignores the pick and shows the
matching settings of every language.

Each language's card has a **Detected** line, for example
*debugpy: found (/usr/bin/python3)* (the interpreter that has debugpy) or
*js-debug: not found*, also while that language's debugging is switched off.
Every card but Python's also has the adapter's path field with **Browse…** (C
and C++ use Rust's CodeLLDB path, or the **GDB path**), and every card but
Java's a switch that turns that language's
debugging on or off. While **Enable debugging (DAP)** is off, the cards are greyed and
a note says to turn it on. Python has no path field there: debugpy runs under
a Python interpreter, and the **Python interpreter (fallback for Run, Debug and
code intelligence)** is on the Python card of **Settings → LSP**, which stays
editable with debugging off. It is the Python used when a project has no
interpreter of its own, and the one that runs debugpy when the project's
interpreter cannot import it. A link at the top of the Debugging page leads
there.

![The Language picker of Settings → Debugging set to Go (Delve), and under it the Go (Delve) card: Enable Go debugging (Delve) on, an empty Delve (dlv) path field with Browse…, and Detected dlv: found (/home/ada/go/bin/dlv) with the Install button reading Installed](/screenshots/docs/debug-settings-go.png "The Go card: Delve found where go install puts it.")

![The Language picker of Settings → Debugging open, listing Java, Python, JavaScript (Node.js), Go (Delve), Rust, C and C++ (CodeLLDB), C# (netcoredbg), Ruby (rdbg), PHP (Xdebug), Bash (bashdb), Dart and Flutter, Lua (local-lua-debugger), Firefox, Elixir (ElixirLS) and PowerShell (PowerShell Editor Services), with C# picked; behind it the C# (netcoredbg) card with its Enable switch, netcoredbg path field and an Install… button](/screenshots/docs/debug-settings-languages.png "One language at a time: the Language picker lists every language with a debugger.")

### Debug adapters of your own

Any program that speaks the Debug Adapter Protocol can debug in Editora, such
as `lldb-dap` or a language's own debugger. **Debug: Edit Debug Adapters**
(`debug.editCustomAdapters`, in **Run ▸ Debugger**) opens
`debug-adapters.json` in your [config folder](/docs/configuration#config-directory),
written with a commented example the first time. Each adapter says:

- `type`: the name a launch.json configuration uses for it. It can't be one of
  the types Editora handles itself, such as `lldb`, `node`, `chrome` or
  `bashdb`; for an adapter that names one, the status bar says so.
- `command`: the program and its arguments, as a list.
- `transport`: how Editora talks to it: `stdio` (the default), `port` (Editora
  picks a free port and puts it where the command says `{port}`), or `announce`
  (the adapter prints the port it listens on; `announce` is a pattern whose
  first group is the port).
- `languages`: the file types F5 debugs with it, such as `["zig"]`.
- `name` and `adapterID`, optional.
- `launch`: what F5 sends it, with VS Code's variables filled in. An adapter
  without one is started only from a launch.json configuration.

```json
{
  "adapters": [
    {
      "type": "lldb-dap",
      "name": "LLDB (lldb-dap)",
      "command": ["lldb-dap"],
      "transport": "stdio",
      "languages": ["zig"],
      "launch": { "program": "${fileDirname}/${fileBasenameNoExtension}", "cwd": "${fileDirname}" }
    }
  ]
}
```

A [trusted](/docs/workspace#trusted-folders) project can bring its own in
`.editora/debug-adapters.json`; they replace yours of the same type. An entry
that can't be read is named in the status bar, and the others still work.

### Debugging on a remote host

A file in a [remote folder](/docs/remote) is debugged on its host, when that
host [may run commands](/docs/remote#allowing-a-host-to-run-commands), with
breakpoints, stepping, variables and the call stack as for a local file.
Breakpoints of a remote file are kept across reconnects. Remote is in Beta.

- **Python** runs debugpy on the host, which needs its own
  (`pip install debugpy`). The program itself runs as Run runs it there: under
  the project's virtual environment on the host, else the host's **Python for
  Run and tests**, with the project root and `src/` on `PYTHONPATH`. debugpy
  runs under the host's **Python for debugging**. The program's output reaches
  the Debug console, but it can't read typed input there.
- **JavaScript** runs vscode-js-debug and `node` on the host, from the
  script's package folder there, as Run does. The program's output reaches the
  Debug console, but it can't read typed input there.
- **Java** in a Maven or Gradle project goes through the host's `jdtls`: Debug,
  Debug Main Class, Attach (where `localhost` is the host), Debug Test, and a
  saved configuration. Editora copies its java-debug plugin to
  `~/.cache/editora` on the host. A Java file outside a project is compiled on
  the host for the debugger.

The adapters' locations can be set per host in Settings → Remote (**Python for
debugging**, **JavaScript debugger (vscode-js-debug)**, **Node.js**,
**java-debug plugin jar**). Other
languages cannot be debugged on a host yet.

A Java configuration that runs on a [deployment](/docs/deployments#debugging-on-a-deployment)
is debugged on its host too: the program starts there and the debugger attaches
through a tunnel, with breakpoints in the local files.

### Debugging in a container or WSL

A project whose tools [run in a container](/docs/remote#tools-in-a-container)
or [in a WSL distribution](/docs/remote#tools-in-wsl-windows) is debugged there,
as on a remote host: Python with the debugpy installed there (the program under
the project's virtual environment there, as Run uses it), JavaScript with
vscode-js-debug and `node` there, and Java through the jdtls running there, with
Editora's java-debug plugin copied into `~/.cache/editora`. A Java file outside
a project is compiled there for the debugger. The debugger's port inside is
reached from here whatever the container's network, through a relay that needs
`nc`, `bash` or `python3` inside (see
[Ports and debuggers](/docs/remote#ports-and-debuggers)). Containers and WSL
are part of [Remote](/docs/remote), which is in Beta.

## Related pages

For sending HTTP requests from `.http` files, see the
[HTTP client](/docs/http-client). For Maven, Gradle, npm, Cargo, Go, sbt,
Mill, Scala CLI, Clojure CLI, Leiningen and Babashka tasks, see
[Build tools](/docs/build-tools). The first build in a folder, wrapper or
not, asks you to [trust the folder](/docs/workspace#trusted-folders) first,
because the build runs code the repository supplies; only Go builds are not
asked about, since `go build` never runs a module's code.
