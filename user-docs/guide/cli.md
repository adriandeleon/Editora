---
title: Command line
description: Command-line flags for opening files, projects, config folders, and the focus modes.
category: Help
order: 2
---

Editora takes a few command-line arguments. With the native installers the
launcher binary accepts them; from source, build the jar and pass them to it:
`java -jar target/Editora-*.jar <flags>`.

## Where is the launcher?

The examples on this page write the launcher as `editora`. Whether that command
exists depends on how you installed:

| Package | Launcher |
| --- | --- |
| **Linux `.deb`** | `editora` on your `PATH` (a `/usr/bin/editora` link to `/opt/editora/bin/Editora`) |
| **Linux `.tar.gz`** | `editora` on your `PATH` after `./install.sh` (`~/.local/bin/editora`, or `/usr/local/bin/editora` with `sudo`); or run `./Editora/bin/Editora` in place |
| **Linux `.rpm`** | `/opt/editora/bin/Editora`; no `editora` command is added, so add your own symlink if you want one |
| **Linux `.AppImage`** | The `.AppImage` file itself; nothing is added to your `PATH` |
| **macOS `.dmg`**, **Windows `.msi`** | The installed application's own launcher; no `editora` command is added to your `PATH` |
| **Fat jar** | `java -jar Editora-<version>-<platform>.jar` (needs JDK 25) |

Where there is no `editora` command, substitute the launcher in the examples.

## Flags

| Flag | Effect |
| --- | --- |
| `--version`, `-V`, `-v` | Print the version and exit (no GUI) |
| `--help`, `-h` | Print usage and exit (no GUI) |
| `--config-dir <path>` | Use this config folder |
| `--dev` | Use an isolated `~/.editora-dev/` config |
| `--project <dir>` | Open this folder as a project (if projects are enabled) |
| `--new-file[=name]` | Open a new untitled buffer (optionally named) |
| `--zen` | Start in Zen mode (session-only) |
| `--expert` | Start in Expert mode, a lighter focus mode (session-only) |
| `--simple` | Start in Simple UI mode (session-only) |
| `--disable-plugins` | Start with no plugin loaded (session-only) |
| `--single-window[=project]` | Open just one window, not the whole saved set (session-only) |
| `--no-session` | Open only the files given here; don't restore the saved session |
| `--new-instance` | Start a separate editor process instead of handing the launch to the one already running on the same config folder |
| `--diff-ui LEFT RIGHT` | Open files or folders in an isolated comparison workspace |
| `--report` | Write a redacted problem report (system details and the last two session logs) to the current folder and open a GitHub issue for it, without starting the editor (see [Reporting a problem](/docs/troubleshooting#reporting-a-problem)) |
| `--` | Every argument after it is a file (see [Opening files](#opening-files)) |

An option Editora does not define stops the launch: `editora --config-dri /tmp/x`
prints `Editora: unknown option: --config-dri` and how to get help, and exits
with status 2 before any window opens, rather than starting on your real
settings. This holds for an option with one dash too. The JVM options a
launcher can leave among the arguments (`--add-exports`, `--add-opens`, `-p`)
are passed over, with their values.

`--single-window` opens exactly one window instead of restoring every window
that was open at last quit: bare, it opens the no-project window; with a name
(`--single-window=MyProject`), that project's window (falling back to no-project
if no project matches). It's session-only, so your saved multi-window layout is
untouched and the next normal launch restores everything.

`--no-session` skips the saved session's files entirely and opens only what you
named on the command line. It's for launching from a file manager or a script,
where restoring the last session only costs time: every restored file is a
buffer to load and, once shown, a file to highlight and a language server to
run, for files you didn't ask to see. It is also session-only, so your saved tabs are
left as they were and are not replaced by the files you opened.

`--disable-plugins` loads no plugin at all for the session, whatever Settings
→ Plugins says, and saves nothing: it is the way out of a plugin that hangs or
breaks Editora at startup. Settings → Plugins and the Doctor say when it is on.
Switch the plugin off or remove it there; **Restart Now** starts Editora again
without the flag. See [Plugins](/docs/plugins#if-a-plugin-breaks-startup).

## Opening files

Pass one or more file targets, each optionally with a line and column:

```
editora path/to/file.txt
editora src/Main.java:42
editora notes.md:10:5
```

Each target opens in its own focused tab and jumps to the given position. File
targets, `--project`, and `--new-file` combine, so you can open a project and
jump into a file in one command.

Every argument after `--` is a file, so `editora -- -notes.txt` opens a file
whose name starts with a dash. A lone `-` is a file name too.

## One editor, not two

If Editora is already running on the same config folder, a second launch **goes
to the running editor and exits**. The running process is reused, with its
language servers and its memory, and no second editor is started. What the
running editor does depends on what the launch asked for:

| Launch | Result |
| --- | --- |
| Files (`editora notes.md`) | The files open in a new window |
| `--project <dir>` | That project's window opens, or comes forward if it is already open; files named with it open as tabs in that window |
| `--new-file` | The new buffer opens in a new window |
| `--diff-ui LEFT RIGHT` | The comparison opens in its own window |
| Nothing to open (`editora`, or the application icon) | The editor's current window is brought to the front |

Files open in a **new window**, brought to the front, with any focus mode
you asked for (`--expert`, say) applied to it, so a desktop entry like "Editora
Expert Mode" still opens in Expert Mode. The window you were working in is left
alone: the files do not arrive as tabs in it, and its chrome is not restyled.

The exception is a file that is **already open**. The window holding it is
brought forward instead of opening it twice, because two independent buffers
over one file lose edits: saving one leaves the other stale.

These windows are left out of your saved layout, so a file opened from the file
manager doesn't come back as an empty window on the next launch.

The handoff exists because on Linux and Windows a file manager passes the path
as a command-line argument, which starts a new process. Without the handoff,
clicking a file pays a full cold start and leaves a second editor resident.
macOS does not have the problem, because Finder delivers an event to the running
app, and the handoff routes into that same code path.

Two kinds of launch always start their own process:

- anything passing **`--new-instance`**
- `--disable-plugins`, since the running editor has its plugins loaded

An instance is scoped to its **config directory**. `--dev` and `--config-dir`
do not opt out of the handoff; they choose which folder, and so which running
editor, a launch belongs to. A `--dev` launch goes to the editor already running
on `~/.editora-dev/`, never to your everyday one, and two `--config-dir`
sessions stay independent. If the handoff fails for any reason, the launch
starts its own editor.

`--new-instance` gives two processes on one config folder. Neither overwrites
the other's settings or data, but each sees the other's changes only after a
restart; see
[Configuration](/docs/configuration#two-editors-on-one-config-folder).

## Opening files from the file manager

Editora registers itself as a text editor with the desktop, so it shows up where
your file manager offers a choice of application. What the installer can claim
differs by platform:

| Package | Registration |
| --- | --- |
| **Windows `.msi`** | Editora appears under Explorer's *Open with* for the text and source types it edits |
| **Linux `.deb`** | An *Open With* entry and an `editora` command on your `PATH`; it also sets *Editora Expert Mode* as the system default text editor, which your own per-user choice still overrides |
| **macOS `.dmg`** | Finder's *Open With*, via the app bundle's declared document types |
| **Linux `.rpm`** | No association — run `/opt/editora/bin/Editora`, or open files from inside Editora |
| **Linux `.tar.gz`** | `install.sh` adds an application-menu entry and the `editora` command |
| **Linux `.AppImage`** | Nothing is registered — the file runs in place |

Windows hands a file manager's chosen file to an application as a command-line
argument, and nothing maps an extension to Editora unless the installer says
so. The MSI registers Editora for those types, which is what lets a file
manager hand it a file.

**It doesn't take over your existing defaults.** On Windows 8+ the shell's
per-user choice wins over anything an installer writes, so a type you already
open with something else keeps opening with it and Editora is offered
alongside. An extension nothing else claims does open with Editora, which is the
intended result for types such as `.tfvars`.

The list is every extension Editora resolves to a language, minus `.html`,
`.htm`, `.xhtml` and `.svg`, which are left to the browser and the image viewer.
The Linux package makes the same choice, so the two installers claim the same
types.

## Examples

```bash
# A throwaway instance that won't touch your real config
editora --dev

# Open a folder as a project and jump to a line
editora --project ~/code/app src/main/java/App.java:88

# Start a quick scratch buffer in Zen mode
editora --new-file=scratch.md --zen

# Open a file in a second editor rather than the running one
editora --new-instance notes.md

# Bring the running editor to the front
editora

# What a desktop "Open With" entry passes: just these files, no saved session
editora --expert --single-window --no-session README.md
```

`--zen`, `--expert` and `--simple` only affect the current session; they don't change your
saved preferences. The config-folder flags are documented in
[Configuration](/docs/configuration).
