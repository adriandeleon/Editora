---
title: Terminal
description: Shells in a Terminal tool window, on this machine, a remote host, a container or WSL, with shell integration, find in the scrollback, clickable links, type-ahead over slow connections, the kitty keyboard protocol, desktop notifications, and Run and Debug in a terminal tab.
category: Workspace
order: 8
---

The **Terminal** tool window runs shells inside Editora, one per tab, at the
bottom of the window: on your computer, or where a project's commands run,
on a [remote host](#terminals-on-a-host-in-a-container-or-in-wsl), in a dev
container or in a WSL distribution. A focused terminal owns the keyboard, so
Ctrl+C, Escape, and your shell's own shortcuts work as they do in any terminal.
Its colors follow the editor theme unless you
[pick a terminal theme](#colors-and-font), its font is the editor's, and a
Ctrl+click (⌘-click on macOS) on a file name in the output opens that file in
the editor.

## Open a terminal

**Tool Window: Terminal** (`tool.terminal`) shows the tool window and starts a
shell if none is running. When the Terminal is open but the keyboard is
elsewhere, it moves the keyboard into the terminal; run it from inside the
terminal to hide the window. A hidden Terminal's shells keep running, and the
next toggle brings them back. The Window menu has **Terminal** and **New
Terminal**, and the File menu has **Open Terminal Here**.

![The Terminal tool window under the editor, running git status --short and git log --oneline in the project folder](/screenshots/docs/terminal.png "The Terminal tool window, started in the project folder.")

| Action | Command | Emacs | IntelliJ IDEA | VS Code, CUA, Sublime Text |
| --- | --- | --- | --- | --- |
| Show, focus or hide the Terminal | `tool.terminal` | `M-0` | Alt+F12 (⌥F12 on macOS) | Ctrl+\` |
| Open another terminal | `terminal.new` | (palette) | (palette) | Ctrl+Shift+\` |
| Back to the editor, leaving the Terminal open | `terminal.focusEditor` | Shift+Escape | Shift+Escape | Shift+Escape |
| Close the selected terminal | `terminal.close` | (palette) | (palette) | (palette) |
| Find in the selected terminal | `terminal.find` | (palette) | (palette) | (palette) |
| Next or previous terminal tab | `terminal.next`, `terminal.previous` | (palette) | (palette) | (palette) |
| Rename the selected tab | `terminal.rename` | (palette) | (palette) | (palette) |
| Move the selected tab left or right | `terminal.moveTabLeft`, `terminal.moveTabRight` | (palette) | (palette) | (palette) |

Ctrl+\` is the Control key on macOS too, as in VS Code. Run the commands
marked (palette) from the command palette, which also works from inside a
terminal (see [Keys](#keys)). Inside a terminal, Find, switching tabs and
moving tabs also have keys of their own, the same in every keymap: see
[The terminal's own keys](#the-terminals-own-keys). The **+** at the right end
of the tab strip opens another terminal as well.

A Terminal left open when Editora quits is open again at the next start, with
a new shell; it starts without taking the keyboard from the editor.

## Terminal tabs

Each tab carries a chip saying where its shell runs (**Local**, or the host's,
container's or distribution's name). Its title is the title the shell or its
program sets; else, while the shell is running something, that program's name
(`vim`, `npm`, `ssh`); else the name of its current folder. Pointing at the tab
shows the whole folder. Tabs with the same title are numbered: `project`,
`project (2)`. The program's name is read on this computer, so a tab on a host,
in a container or in WSL shows its folder instead.

A tab's right-click menu has **Rename Tab…**, **New Terminal**, **Close**,
**Close Other Tabs**, **Close Tabs to the Right**, **Move Tab Left**,
**Move Tab Right** and [**Move to Editor**](#a-terminal-in-an-editor-tab).
**Terminal: Close Other Terminals** (`terminal.closeOthers`) and **Terminal:
Close Terminals to the Right** (`terminal.closeRight`) do the same from the
palette. A renamed tab keeps its name whatever its shell sets;
renaming it to an empty name gives it back the shell's title. Drag a tab by
its header to move it.

When a program rings the bell in a tab you can't see, the tab is marked until
you look at it. A [notification](#notifications-and-the-clipboard) from a
program marks it too, in the accent color.

A tab closes when you click its **✕**, run **Terminal: Close Terminal**, or the
shell exits (`exit`, or Ctrl+D at an empty prompt). When the last shell exits
or its tab closes, the Terminal tool window hides and the editor has the
keyboard again. Closing the window ends its shells. Closing a tab, a window or
Editora while a shell is running a program
[asks first](#closing-a-busy-terminal).

## A terminal in an editor tab

**Move to Editor**, in a terminal tab's right-click menu, in the Window menu
or as **Terminal: Move to Editor** (`terminal.moveToEditor`), shows the
selected terminal as an editor tab beside your files, as IntelliJ's terminal
does. Its shell keeps running and keeps what it printed. With no terminal open,
Move to Editor opens a new one in the editor.

The editor tab's right-click menu renames it, closes it, or puts it back with
**Move to Terminal Tool Window** (`terminal.moveToToolWindow`), which opens the
Terminal with it selected. Closing it asks first when its shell is running
something, as in the tool window, and closing the window lists it with the
others.

## Open Terminal Here

**Open Terminal Here** opens a new terminal tab in a particular folder. It is in
a tab's right-click menu, the Project tree's right-click menu, the
breadcrumb's menu, and the File menu, and it is the **File: Open Terminal
Here** (`file.openTerminal`) command, which uses the active file's folder. On a
folder in the Project tree it uses that folder; on a file, the file's folder.
If the Terminal tool window had no shell yet, it opens with just that tab, not
with a second shell in the project folder beside it.

To use your system's terminal app instead, turn on **Settings → Terminal →
Open Terminal Here uses the system terminal**. Open Terminal Here then starts
your system's terminal application, and the Terminal tool window is left alone.

On a file or folder of a [remote project](/docs/remote), Open Terminal Here
opens a terminal on that host, in that folder, whatever the system-terminal
setting says. In [Simple UI mode](/docs/workspace#focus-modes) the trimmed
right-click menus leave it out; the command still works.

For a [deployment](/docs/deployments), **Run → Deployment → Open Terminal in
Deployment** (`deploy.terminal`) opens a tab on the host, in the deployment's
folder.

## Where a shell starts

A new shell started from the Terminal tool window, rather than by Open
Terminal Here, starts in the first of these that applies:

1. The project's folder: on this computer, or on its host for a
   [remote project](/docs/remote).
2. The folder of the active file, when it is a file on this computer.
3. Your home folder.

On this computer the shell is the one **Settings → Terminal → Shell** names,
or else your own:

- **macOS and Linux:** your login shell (`$SHELL`), started as a login shell
  so it reads your profile. Without `$SHELL` it is zsh on macOS and bash on
  Linux, else `sh`.
- **Windows:** PowerShell 7 (`pwsh`) when it is installed, else Windows
  PowerShell, else the command prompt (`%COMSPEC%`, `cmd.exe`).

The setting takes a path or a program on your `PATH`, followed by any
arguments, such as `/bin/bash --norc` or `pwsh -NoLogo`. A shell named without
arguments starts as a login shell (on Windows, only bash and zsh do). When
the shell it names can't be found or run, the terminal starts your own shell
instead and the status bar says why.

The shell runs with the same `PATH` additions as programs Editora runs, and
with `TERM=xterm-256color`, `COLORTERM=truecolor`, `TERM_PROGRAM=Editora` and
`TERM_PROGRAM_VERSION` set.

## Terminals on a host, in a container or in WSL

A folder whose commands run somewhere else gets its terminal there too: a
remote project's terminal is a shell on its SSH host, and a folder whose tools
run in a [dev container](/docs/remote#tools-in-a-container) or a
[WSL distribution](/docs/remote#tools-in-wsl-windows) gets a shell inside it.
The tab's chip names the host, container or distribution, so you can tell at a
glance where a command will run. These are part of [Remote](/docs/remote),
which is in Beta.

- **It is a real terminal.** vim, htop, less and resizing work, as in `ssh`.
- **It is the account's login shell there**: `$SHELL`, else bash, else sh.
  Settings → Terminal → Shell names a program on this computer, so it is never
  sent to a host.
- **Allowed places only.** A terminal runs commands there, so it opens only
  where Editora may run commands: a host you
  [allowed](/docs/remote#allowing-a-host-to-run-commands), or an allowed
  [container](/docs/remote#allowing-a-container) or
  [distribution](/docs/remote#allowing-a-distribution). It's the same permission
  Run, Git and language servers use.
- **It opens without freezing the window.** The tab says *Connecting to …*
  until the shell is there.
- **Each terminal on an SSH host uses one of the connection's channels for
  long-running programs**, as a Run or a language server does. When all are in
  use, opening another says so; close a terminal or stop a program first.

**When the connection drops**, the host ends the shell and the programs in its
foreground, as it does when an `ssh` session drops. Programs started with
`nohup` or inside tmux or screen survive. The tab keeps what it printed under a
**Connection lost** banner with **Reconnect**, which starts a new shell in the
same folder once the connection is back.

In a container or WSL there is no connection to lose: when the container stops,
the shell ends and its tab closes. Closing a terminal ends the shell inside the
container too.

## Shell integration

With bash or zsh, the terminal knows where the shell is and where each command
starts and ends. Editora starts the shell with a small script of its own that
first runs your usual startup files (`~/.bash_profile`, `~/.zshrc` and the
rest), so your prompt, aliases and `PATH` are as always, and none of your
files are changed. It works the same on a host, in a container and in WSL,
where the script is kept in `~/.cache/editora`.

What it gives you:

- **The tab follows the shell's folder**, on any machine, and relative paths
  in the output open the right file (see
  [Open files and links](#open-files-and-links-from-the-output)).
- **New Terminal Here** in the terminal's right-click menu opens another tab in
  the shell's current folder.
- **Jump between prompts** with **Ctrl+Shift+Up** and **Ctrl+Shift+Down**
  (**⌘↑** and **⌘↓** on macOS), to scroll back to where each command started.
- **Copy Last Command Output** in the right-click menu copies what the last
  command printed. The item is there only when the shell has marked its
  prompts.

It is on by default; turn it off with **Settings → Terminal → Shell
integration**, which applies from the next terminal you open. A terminal
starts without it:

- with other shells, such as fish or PowerShell;
- with a bash or zsh that runs on Windows itself, such as Git Bash (a WSL
  terminal has it);
- when **Settings → Terminal → Shell** gives the shell arguments of its own,
  such as `/bin/bash --norc`. Only a shell named alone, or with just `-l`,
  gets it.

## Find in the terminal

**Ctrl+Shift+F** (**⌘F** on macOS), **Terminal: Find** (`terminal.find`), or
**Find…** in the terminal's right-click menu opens a find bar over the
terminal. Every match in the scrollback and on the screen is highlighted, and
the current one starts at the most recent match:

- **Enter** goes to the next match up, toward older output, and **Shift+Enter**
  down. Both wrap around.
- **Aa** matches case.
- **Escape** closes the bar and gives the keyboard back to the shell.

The bar counts the matches, up to 1,000; past that it says *1000+* and keeps
the newest. Matches stay on their text as more output scrolls in. Text
selected on one line, when there is some, is what the bar starts with.

![The Terminal tool window after git log --oneline, with the find bar open over it: map typed, every match highlighted in the commit subjects, 4 of 4, an Aa toggle, up and down arrows and a close button](/screenshots/docs/terminal-find.png "Find in the terminal highlights every match in the scrollback and counts them.")

## Keys

While a terminal has focus, keys go to the shell, not to Editora:

- **Ctrl+C** interrupts the running program, **Ctrl+W** deletes a word, and
  **Ctrl+R** searches the shell's history.
- **Escape** reaches vim, less, and other full-screen programs. **Shift+Escape**
  (`terminal.focusEditor`) is how you get back to the editor without closing
  the Terminal.
- Key sequences belong to the shell: in the Emacs keymap, `C-x` and the
  `M-g …` sequences inside the terminal are Emacs's or readline's, not
  Editora's.

Only a few commands still reach Editora from a terminal. By default they are:

- the tool-window toggles (`tool.*`), so you can open the Project tree or hide
  the Terminal without leaving it;
- the command palette (`palette.show`);
- Search Everywhere (`search.everywhere`);
- the terminal commands (`terminal.*`).

Even those reach Editora only on a key a shell has no use for, such as a
Ctrl+Shift or Alt+Shift chord, Ctrl+\`, or Alt+F12. A key a shell uses goes to
the shell whatever the keymap binds to it: a plain Ctrl+letter, Ctrl+digit,
Alt+letter or Alt+digit, and any key without Ctrl or Alt, F-keys included. So
IntelliJ's Ctrl+K, VS Code's Ctrl+B and F1, the Emacs keymap's `M-1` … `M-9`
and IntelliJ's Alt+1 … Alt+9 tool windows go to the shell. A command gets
through only when its shortcut is a single chord.

Of the commands on the list, two kinds pass on any key:

- the Terminal's own commands (`tool.terminal` and `terminal.*`), so there is
  always a way out: `M-0` in the Emacs keymap and Shift+Escape in every
  keymap work from inside a terminal;
- the Emacs keymap's `M-x`, which opens the command palette from a terminal.

On macOS, every ⌘ shortcut reaches Editora too, listed or not, since a shell
has no use for ⌘. The terminal's own ⌘ keys, below, stay with the terminal.

Change the list in **Settings → Terminal → Commands that reach Editora**:
entries separated by commas, where an entry is a command id, or ends in `.*`
to cover every command that starts with it. An entry can also be a key,
written as Editora's keymaps write it, such as `M-1`: that key then reaches
Editora whatever command it is bound to. Every other key stays with the shell.
Changes apply to open terminals.

### The terminal's own keys

These belong to the terminal itself, in every keymap, and reach neither the
shell nor Editora's keymap:

| Action | Linux and Windows | macOS |
| --- | --- | --- |
| Copy the selection | Ctrl+Shift+C, Ctrl+Insert | ⌘C |
| Paste | Ctrl+Shift+V, Shift+Insert | ⌘V |
| [Find](#find-in-the-terminal) | Ctrl+Shift+F | ⌘F |
| [Jump to the previous or next prompt](#shell-integration) | Ctrl+Shift+Up, Ctrl+Shift+Down | ⌘↑, ⌘↓ |
| Next or previous terminal tab | Ctrl+PageDown, Ctrl+PageUp | Ctrl+PageDown, Ctrl+PageUp |
| Move the selected tab right or left | Ctrl+Shift+PageDown, Ctrl+Shift+PageUp | Ctrl+Shift+PageDown, Ctrl+Shift+PageUp |
| Scroll the history a page up or down | Shift+PageUp, Shift+PageDown | Shift+PageUp, Shift+PageDown |
| Scroll to the top of the history, or back to the prompt | Shift+Home, Shift+End | Shift+Home, Shift+End |

The history keys scroll the terminal's scrollback. In a full-screen program
such as vim or less, which has no scrollback, they go to the program.

### Programs that ask for more keys

A terminal normally can't tell some keys apart: Ctrl+I is Tab, Ctrl+M is Enter,
and Escape is the start of every Alt key. Programs that know the **kitty
keyboard protocol**, such as Neovim, Helix, fish and nushell, ask the terminal
for unambiguous keys, and Editora's terminal gives them: Escape on its own,
Ctrl+I apart from Tab, Shift+Enter as a key of its own, and, when the program
asks, key repeats and releases. Nothing needs turning on. A program that
doesn't ask, and your shell at its prompt, get the usual terminal keys, and a
full-screen program's request ends with it.

### Alt and Option

On Linux and Windows, Alt is the shell's Meta key: Alt+B and Alt+F move by
words in readline, as `M-b` and `M-f`. On macOS, Option types the characters
your keyboard layout puts on it, such as @ [ ] { } on many layouts. Turn on
**Settings → Terminal → Option key as Meta (macOS)** to make Option the
shell's Meta key instead.

### Copy and paste

The terminal's own Copy and Paste are **Ctrl+Shift+C** and **Ctrl+Shift+V**
(**⌘C** and **⌘V** on macOS), and **Ctrl+Insert** and **Shift+Insert** as in
other terminals. Plain Ctrl+C and Ctrl+V belong to the shell. In a program that
reads the mouse, such as htop, vim or tmux, hold **Shift** to select text
instead of clicking in the program.

Selecting text:

- **Drag** to select. The selection ends at the edge between characters
  nearest the pointer, and what is highlighted is exactly what is copied.
- **Double-click** selects a word and **triple-click** a line.
- **Drag past the top or bottom** of the terminal to scroll the history while
  you select; the further out the pointer is, the faster it scrolls.
- **Alt+drag** (**Option+drag** on macOS) selects a block of columns, such as
  one column of `ls -l`; it is copied one row per line. Where your desktop
  moves windows with Alt+drag, as GNOME and KDE do, use **Ctrl+Alt+drag**.
- Typing doesn't clear the selection, so you can type a command under a
  selection of the output above it. The selection goes when the text under it
  is redrawn, and stays on its text when you resize the terminal.
- **Settings → Terminal → Copy on select** copies a selection to the clipboard
  as soon as you make it.

On Linux, a selection is also the *primary selection* other Linux apps paste
with the middle mouse button, and a **middle-click** in the terminal pastes the
primary selection. This uses `wl-copy` and `wl-paste` on Wayland, or `xclip` or
`xsel` on X11; without them, and on macOS and Windows, a middle-click pastes
the clipboard.

The terminal's right-click menu has Copy, Paste, Select All, Copy Last Command
Output, Clear Scrollback, New Terminal Here and Find…, and, on a link, Open
Link and Copy Link Address (see
[Open files and links](#open-files-and-links-from-the-output)). Clear
Scrollback drops the history and keeps what is on the screen.

A paste is text, never keystrokes: control characters in the clipboard are
dropped, so a paste can't run a command on its own or end bash's bracketed
paste early. Pasting several lines into a program that would run each line as
it arrives, such as `sh`, a REPL or an older remote shell, asks first, with
**Cancel** as the default. bash and zsh mark a paste and never ask. **Don't ask
again** in the question turns off **Settings → Terminal → Confirm a paste of
several lines**.

## Open files and links from the output

Hold **Ctrl** (**⌘** on macOS) and point at a file name, a path, or a URL in the
terminal's output: it is underlined when it can be opened. Click to open it:

- **A file** opens in the editor. A position after it, as compilers, test
  runners and stack traces print it (`src/Main.java:42` or
  `src/Main.java:42:7`), puts the caret on that line and column.
- **A folder** is revealed in the Project tree.
- **A URL** (`http`, `https`, `ftp`, `ftps`, or `mailto`) opens in your browser
  or mail app. A `file://` URL is treated as the path it names, including one
  that names this computer, as `ls --hyperlink` writes them.

A relative path is read against the shell's current folder, so a bare name from
`ls` or a path from `grep -n` works as printed. On a host, it is a path on the
host, and the file opens from there. Only a path that exists is
underlined, so ordinary words never turn into links. On a link, the terminal's
right-click menu has **Open Link**, which does the same as the click, and
**Copy Link Address**.

**Hyperlinks programs print** work the same way. Tools such as
`ls --hyperlink`, gcc, and git can mark text as a link to an address of their
choosing (OSC 8 hyperlinks); Ctrl+click opens that address. When the text
isn't the address itself, pointing at it with Ctrl held shows the real address
first. Only `http`, `https`, `ftp`, `mailto` and `file` links open.

A plain click never opens a link: it starts a selection, or goes to a program
such as vim or tmux that reads the mouse.

## Notifications and the clipboard

**Notifications.** A program can ask for a desktop notification, as a long
build or test run does when it finishes; Editora understands the two common
forms, OSC 9 and OSC 777. For example:

```sh
make; printf '\e]777;notify;make;finished\a'
```

The notification's tab is marked if you can't see it, and the notification
reaches your desktop unless you are looking at that terminal in the focused
window. It is shown with `notify-send` on Linux (or straight through the
desktop's notification service when that tool isn't installed), with
Notification Center on macOS, and as a toast on Windows. A terminal posts at
most one notification every two seconds; the rest only mark its tab. Turn the
desktop part off with **Settings → Terminal → Desktop notifications**.

**The clipboard.** A program can put text on your clipboard (OSC 52), which is
how tmux and terminal editors copy over SSH. A program can never read the
clipboard: such a request goes unanswered. Turn writes off with **Settings →
Terminal → Let programs copy to the clipboard**.

## Typing over a slow connection

On a slow link to a host, what you type appears at once, dimmed, before the
shell echoes it back, and is replaced by the real echo when it arrives. This is
**type-ahead**. It starts only once echoes take longer than 100 ms, so a shell on
this computer is never affected, and it stays out of full-screen programs such
as vim. Turn it off with **Settings → Terminal → Type-ahead**.

## Run and debug programs in a terminal

Programs that draw on the screen, read single keys or ask for a password need
a real terminal, which the Run and Debug consoles are not. For those:

- A [run configuration](/docs/run-debug#run-configurations)'s **Console:
  Run in a Terminal tab** runs its program in the Terminal tool window. A
  before-launch step still runs in the Run console.
- **Run the program in a Terminal tab instead**, on the **Debugged programs**
  card of **Settings → Debugging**, does the same for a Java, Python or
  JavaScript program you debug. It needs **Run the debugged program in the
  Debug console (program input)**, beside it, on as well (see
  [Program input](/docs/run-debug#program-input-in-the-debug-console)). The
  Debug console's first line names the tab the program runs in.

The program runs where its configuration or file runs: on this computer, on
the project's host, or in its container or WSL distribution. Its tab is named
after the configuration or file, and the Run tool window's Stop button and
stripe follow it. The configuration's environment variables win over those the
login profile sets, and a working folder that doesn't exist fails the run, as
in the console.

When the program ends, the tab stays, with what it printed and its exit code in
the corner. The next run of the same configuration takes that tab's place
rather than opening another; a run still going keeps its tab. Closing the tab
while the program is still running asks first.

## Colors and font

The terminal takes its colors from the active
[editor theme](/docs/themes-fonts): the theme's keyword color becomes the
terminal's red, its number color the blue, and so on, with the editor's
background and text colors behind and in front. Switching themes recolors
open terminals at once.

To give the terminal colors of its own, pick a scheme in **Settings →
Terminal → Theme**. **Editor theme**, the default, is the behavior above. The
other choices are fixed terminal color schemes, listed under **Light** and
**Dark** headings:

- **Editora Dark** and **Editora Light**: the flagship editor themes' terminal
  colors, kept whatever the editor shows
- **Solarized Dark** and **Solarized Light**
- **Dracula**
- **Nord**
- **Gruvbox Dark** and **Gruvbox Light**
- **One Half Dark** and **One Half Light**
- **Tango Dark** and **Tango Light**
- **Campbell**, the Windows Terminal default

A scheme uses the sixteen colors its authors publish, so a shell looks as it
does in other terminals with that scheme. It stays the same when you switch
editor themes, so a light editor can sit beside a dark terminal. Picking a
scheme recolors open terminals at once. Text too faint against a scheme's
background is darkened or lightened just enough to stay readable.

The font is always the editor's. **Settings → Terminal → Text size** sets the
terminal's size on its own; 0 keeps the editor's size. Either way the
terminal follows the editor's text zoom.

The icons tools such as `eza`, `lsd` and powerline prompts print are drawn in a
[Nerd Font](https://www.nerdfonts.com): the Nerd Font version of the editor's
font when you have it installed, else Symbols Nerd Font, which comes with
Editora, so the icons show without installing anything. Its icons are drawn a
little wider than a character, as Nerd Font versions of text fonts draw them;
powerline separators stay at text size.

## Closing a busy terminal

Closing a terminal whose shell is running a program, such as a build or a
server, asks first and names the program. **Cancel** is the default; **Close**
ends the program with the shell. On a host, a container or WSL, Editora asks
that machine which programs the shell is running; when the machine doesn't
answer within a few seconds, the tab closes without asking. A tab running a
program from Run or Debug asks the same way while the program runs.

Closing a window, or quitting Editora, asks once for all the terminals that
are running programs, listing each tab and what it runs. Turn both questions
off with **Settings → Terminal → Ask before closing a terminal that is running
a program**.

## Settings

**Settings → Terminal** (under Languages & Tools), or **Terminal: Change a
Setting…** (`terminal.changeSetting`) to pick one from the palette:

| Setting | Default | What it does |
| --- | --- | --- |
| Shell | (blank) | The shell to start on this computer, with any arguments. Blank uses your own shell; see [Where a shell starts](#where-a-shell-starts). |
| Shell integration | On | Start bash and zsh with Editora's [shell integration](#shell-integration). |
| Type-ahead | On | Show typing before a slow shell echoes it; see [Typing over a slow connection](#typing-over-a-slow-connection). |
| Open Terminal Here uses the system terminal | Off | Off: [Open Terminal Here](#open-terminal-here) opens a tab in the Terminal tool window. On: it starts your system's terminal application. |
| Theme | Editor theme | The terminal's colors. Editor theme follows the editor theme; any other choice is a fixed color scheme. See [Colors and font](#colors-and-font). |
| Text size | 0 | The terminal's text size, 6 to 72. 0 uses the editor's size. |
| Cursor | Block | Block, Underline, or Bar. |
| Flash on the bell character | On | Flash the terminal when a program rings the bell, and mark its tab when it is out of sight. |
| Scrollback lines | 5,000 | Lines of history kept above the screen, up to 50,000. Each terminal can use about 1 MB per 1,000 lines. |
| Commands that reach Editora | `tool.*, palette.show, search.everywhere, terminal.*` | See [Keys](#keys). |
| Option key as Meta (macOS) | Off | On: Option+key is the shell's Meta key. Off: Option types your layout's characters. See [Alt and Option](#alt-and-option). |
| Confirm a paste of several lines | On | Ask before pasting several lines into a program that would run each at once; see [Copy and paste](#copy-and-paste). |
| Copy on select | Off | Copy a selection to the clipboard as soon as it is made; see [Copy and paste](#copy-and-paste). |
| Let programs copy to the clipboard | On | Programs may put text on the clipboard (OSC 52); see [Notifications and the clipboard](#notifications-and-the-clipboard). |
| Desktop notifications | On | A program's notification reaches the desktop as well as marking its tab; see [Notifications and the clipboard](#notifications-and-the-clipboard). |
| Ask before closing a terminal that is running a program | On | See [Closing a busy terminal](#closing-a-busy-terminal). |

Theme, text size, cursor, bell, type-ahead, the command list, the Option key, the
paste question, copy on select, the clipboard and notification switches and the
close question apply to open terminals. Shell, shell
integration, and scrollback apply from the next terminal you open.

## Known limitations

- **Prompts built for dark terminals are hard to read on light themes.** Some
  prompt themes, such as Oh My Bash's *agnoster*, draw text in the default
  color on a black segment, which is dark on dark under a light editor theme.
  No terminal palette fixes that; use a prompt theme that suits light
  backgrounds, a dark editor theme, or a dark
  [terminal theme](#colors-and-font).
- Without [shell integration](#shell-integration) (another shell, a bash or
  zsh on Windows such as Git Bash, a Shell setting with arguments of its own,
  or the setting off), Editora reads a shell's current folder on Linux and macOS only.
  On Windows, tab titles then don't follow the folder, and only absolute paths
  and URLs in the output become links, not relative paths. A terminal on a host
  needs shell integration for the folder at all.
- macOS's own `/bin/bash` is version 3.2, which can't mark where a command's
  output starts: Copy Last Command Output needs zsh or bash 4.4 or later.
- Find matches within one line of the screen. Text that wrapped onto the next
  line is two lines to it.
- Shells don't outlive Editora: a Terminal open again at the next start has new
  shells, without the old ones' output.
