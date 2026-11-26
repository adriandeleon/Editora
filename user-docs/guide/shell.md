---
title: "Shell & Bash"
description: "The Bash language server, ShellCheck and shfmt, running shell scripts, and debugging Bash."
category: Languages
order: 20
---

Shell scripts get code intelligence from `bash-language-server` with ShellCheck and shfmt, run from the gutter, and Bash scripts debug with bashdb. What every language shares is on the [language servers](/docs/lsp), [running](/docs/run-debug) and [debugging](/docs/debugging) pages.

## Language server

Shell scripts use **bash-language-server** (`bash-language-server start`).
It serves `.sh`, `.bash`, `.zsh`, `.ksh`, `.mksh`, `.command` and `.bats`
files, shell dotfiles such as `.bashrc` and `.profile`, files whose shebang
names a shell, and any file with a `# shellcheck shell=bash` (or `sh`, `dash`,
`ksh`) line near the top, such as a sourced `.inc` file. A `PKGBUILD` is a
shell script too; its `pkgname`, `pkgver` and other makepkg variables are not
reported as unused (SC2034) or unset (SC2154).

If language servers are off (Settings → LSP), the first shell script a window
opens asks **Turn on shell script code intelligence?** with **Turn on** and
**Not now**, once per window, like the [Python offer](/docs/python#language-server).

zsh startup files (`.zshrc`, `.zshenv`, `.zprofile`, `.zlogin`, `.zlogout`)
are not opened on the Bash server. ShellCheck has no zsh mode and would report
errors in valid zsh, so these files get highlighting and folding but no
language server. A zsh script (a `.zsh` file, a zsh shebang, or a
`# shellcheck shell=zsh` line) stays on the server for completion, hover and
symbols, but nothing lints it: ShellCheck's "only supports sh/bash" error is
hidden, and the problem counter says **Not checked: ShellCheck has no zsh
mode**.

Scripts outside any Git repository (in `~/bin`, `/etc` or a loose folder)
share one Bash server per window, which reads no other scripts in the
background.

### Installing the Bash server

**Install** on the banner, in Settings → LSP or in Doctor installs
bash-language-server **5.8.1** with npm, the version Editora is tested with,
together with ShellCheck and shfmt when they are missing. It needs **Node.js
20 or newer**; with an older Node.js the install stops and says which version
it found. Doctor's **Bash / Shell** row shows the installed version and warns,
with **Update**, when it is older than 5.7. A Bash command you set in Settings
that is not found gets a banner that opens Settings rather than an install.

bash-language-server is a Node.js program. When Editora starts where `node` is
not on the `PATH` (from a desktop launcher, without nvm's setup), the server
cannot start: Doctor says the server needs Node.js, and its **Restart Language
Servers** button tries again once that is fixed.

### ShellCheck and shfmt

The Bash server finds problems with **ShellCheck** and formats with **shfmt**.
Without ShellCheck a script gets no diagnostics; without shfmt **Format
Document** changes nothing. Editora shows whether each one is there:

- The **Bash / Shell** card in **Settings → LSP** has a command field and a
  Detected line for each, for example *ShellCheck: found (/usr/bin/shellcheck)*
  or *shfmt: not found*.
- [Doctor](/docs/doctor) has a **ShellCheck** and an **shfmt**
  row under the Bash / Shell server.
- Opening a script when the server is installed but ShellCheck or shfmt is not
  shows a banner that says what is lost (*ShellCheck and shfmt aren't
  installed — shell scripts get no problems or formatting*) with **Install**
  and **Not now**. Not now holds for the window. Format Document without shfmt
  says that shfmt was not found.

To install them, use the **Install…** button beside either line in Settings,
the Install… button in Doctor, or **Install: ShellCheck and shfmt**
(`install.shellTools`) in the palette, which installs whichever is missing.
Editora downloads the official release for your system (ShellCheck 0.11.0 and
shfmt 3.14.1, for Linux x64 and arm64, macOS Intel and Apple silicon, and
Windows x64). It checks each download against a SHA-256 hash built into
Editora and refuses one that does not match, so nothing is installed from a
changed or damaged file. The programs go into `plugins/tools` in your config
folder.

The **ShellCheck command** and **shfmt command** fields on the Bash card choose
which copy the server runs. Left empty, they use the copy Editora installed,
else the program of that name on your `PATH`. A path you enter always wins.
On a [remote host](/docs/lsp#language-servers-on-a-remote-host), in a container and in
WSL, ShellCheck and shfmt are the ones that side's `PATH` finds.

### Bash server options

The Bash / Shell card also holds the server's own settings:

| Setting | What it does |
| --- | --- |
| Lint with ShellCheck | On by default. Off: scripts get no ShellCheck problems and Editora stops saying ShellCheck is missing; completion, hover and navigation keep working |
| Format with shfmt | On by default. Off: Format Document leaves scripts as they are and Editora stops saying shfmt is missing |
| ShellCheck arguments | Added to every ShellCheck run, for example `--severity=warning` to hide notes and style hints or `--exclude=SC2034`. Quote an argument that contains spaces. Sourced files are already followed, so `-x` is not needed |
| Explain source lines the server cannot follow | On by default. A `source` line whose path is computed, such as `source "$(dirname "$0")/lib.sh"` or `source "${BASH_SOURCE%/*}/lib.sh"`, gets a hint. Add `# shellcheck source=lib.sh` above it, and Go to Definition, hover and completion reach that file |
| shfmt dialect | The shell syntax Format Document reads: Automatic (from the shebang), Bash, POSIX sh, mksh or Bats |
| Indent case branches, Put && and \|\| at the start of a line, Put a function's opening brace on its own line, Space after redirections, Simplify code when formatting, Keep column alignment | shfmt's style switches, all off by default |
| Ignore .editorconfig shfmt options | If an `.editorconfig` sets **any** shfmt property (such as `switch_case_indent`), all the options above, the dialect included, are ignored for that file unless this is on |
| Scripts read in the background | How many workspace scripts the server reads for Go to Symbol and cross-file completion (500 by default; 0 turns it off) |
| Symbols from every workspace script | Complete, find and rename functions and variables from all scripts, not only from sourced ones |
| explainshell server | The address of an [explainshell](https://github.com/idank/explainshell) server, for hover help on a command's options. Hover sends it the **whole command line** under the caret, including any password or token in it. Use `https://`; plain `http://` is accepted only for this machine (`localhost`). A hover that waits more than 5 seconds gives up with *explainshell did not answer*. Empty (the default) turns it off |

A project can set the same options for everyone who opens it, with a `bashIde`
object in its `.editora/settings.json` (see
[Per-project overrides](/docs/lsp#per-project-overrides)). It uses the server's own key
names, the same ones VS Code's Bash IDE extension uses:

```json
{
  "bashIde": {
    "shellcheckArguments": ["--severity=warning"],
    "backgroundAnalysisMaxFiles": 2000,
    "globPattern": "**/*@(.sh|.bash|.inc)",
    "shfmt": { "caseIndent": true, "languageDialect": "bash" }
  }
}
```

The keys are `shellcheckPath`, `shellcheckArguments` (a string or a list),
`explainshellEndpoint`, `backgroundAnalysisMaxFiles`,
`includeAllWorkspaceSymbols`, `enableSourceErrorDiagnostics`, `globPattern`,
`logLevel`, `shellcheckExternalSources`, `backgroundAnalysisIgnore`, and a
`shfmt` object with `path`, `languageDialect`, `simplifyCode`,
`binaryNextLine`, `caseIndent`, `funcNextLine`, `spaceRedirects`,
`keepPadding`, `ignoreEditorconfig` and `additionalArguments`.
`shellcheckExternalSources` and `backgroundAnalysisIgnore` need
bash-language-server 5.7 or newer. A key the project leaves out keeps your own
setting, and a value of the wrong type is ignored. `"shellcheckPath": ""` or
`"shfmt": {"path": ""}` turns that tool off for the project.

A project's `bashIde` applies only to the scripts in that project. A script
from another folder opened in the same window keeps your own settings. The
ShellCheck path, the shfmt path, the explainshell server and any key Editora
does not know name a program to run or a place to send your code, so they
apply only in a trusted folder, like a project's server commands; the other
keys always apply. When a project overrides options, the Bash / Shell card
says which.

### Formatting, renaming and finding references in scripts

- **Format Document** says why shfmt could not format a script, with the line:
  *Could not format: line 5: `for` statement must end with `done`*. A
  `#!/bin/sh` script that uses arrays or `[[ ]]` is read as POSIX sh, and the
  message suggests changing the shebang to bash or setting the shfmt dialect.
  The server cannot format a selection, so Format Selection formats the whole
  file and says so.
- **Rename** works on your functions and variables, across the files that
  source each other. On a command such as `echo`, `cd` or `ls` it is refused:
  *echo is a command, not a function or variable defined in your scripts*. The
  new name must be a valid shell name; a function name may also contain `-`,
  `.` and `:`.
- **Find References** on a call lists every call, the one you are on included,
  and leaves out the definition.
- **Quick fixes** say what they do: *Fix SC2006: Use $(...) notation instead of
  legacy backticks*. *Disable SC2086 for this command* adds a
  `# shellcheck disable=SC2086` line above the command (bash-language-server 5.8
  also offers it for the whole file); these suppressions are listed under
  **Suppress**, after the fixes.
- **Completion** lists keywords and snippets first, then your script's
  functions and variables, then builtins and commands, each labelled. After `$`
  or `${` it offers only variables.
- A library you `source` that changes on disk (a `git pull`, another editor) is
  read again, so Go to Definition and completion follow it without a restart.

Positions on a line with emoji or other characters outside the Basic
Multilingual Plane are correct: Editora converts ShellCheck's columns.

bash-language-server cannot follow `source` into a folder whose path contains
a space or another character that needs escaping in a URL (`My Scripts`,
`Proyectos 2026`). Cross-file Go to Definition, hover and completion do not
work for scripts there; diagnostics and everything within one file do.

### Where ShellCheck finds .shellcheckrc

ShellCheck looks for a `.shellcheckrc` file in the server's workspace root (the
project or repository folder) and the folders above it. Scripts outside any
repository share one server whose root is in Editora's config folder, so a
`.shellcheckrc` beside such a script is not read; use `# shellcheck` directives
in the script, or a `~/.shellcheckrc`. A `.shellcheckrc` in a subfolder of the project is not
read, because the server hands ShellCheck the script's text rather than its
path. Put shared settings in the root
`.shellcheckrc`, and use `# shellcheck disable=SC2086` (or other
`# shellcheck` directives) in the script itself for anything that applies to
one folder or file.

## Running

<a id="running-scripts"></a>A shell script runs under the interpreter its **shebang** names, as `./script`
would in a terminal, with the shebang's options:

| First line | Runs |
| --- | --- |
| `#!/bin/bash -eu` | `/bin/bash -eu script` |
| `#!/usr/bin/env -S bash -e` | `bash -e script` |
| `#!/bin/sh` | `/bin/sh script` |
| `#!/usr/bin/env zsh` | `zsh script` |
| `#!/usr/bin/env python3` | `python3 script` |
| none | `bash script`, or `zsh` for a `.zsh` file |

The `env` forms are read as `env` reads them: `env -u NAME` runs the script
without `NAME` set, and `env -C DIR` runs it in that folder (Run reports a
folder that does not exist). `#!/bin/busybox sh` runs busybox's shell. When an
interpreter path in the shebang does not exist on this machine, Run uses the
command of that name on your `PATH` instead. A
[run configuration](/docs/run-debug#run-configurations) can set its own interpreter.

A shell run gets `TERM=xterm-256color`, so scripts that ask `tput` for colours
get them. When a bash script asks a question with `read -p "Name: "`, the
prompt shows in the console, although bash prints such prompts only to a
terminal; type the answer in the input field.

If the script has Windows (CRLF) line endings or starts with a byte-order mark,
Run warns first, because bash then fails with errors that never mention the
cause. **Convert and Run** saves the file with LF line endings (and UTF-8
without a byte-order mark) and runs it; **Run Anyway** runs it as it is. When
`.editorconfig` requires CRLF for the file, only Run Anyway is offered.

On **Windows**, `bash` and `sh` come from **Git Bash**: the one beside the
`git.exe` on your `PATH`, then a standard Git for Windows install, then any other
`bash` on your `PATH` (MSYS2, Cygwin). The WSL launcher in `System32` is never
used, because it cannot open a Windows path. Lines typed into the console
reach the script with Unix line endings.

A Bash script can be debugged too: see [Debugging Bash](#debugging). A
script of another shell, such as zsh or fish, can't be; use
[Run with Trace](/docs/run-debug#run-with-trace) to see each line as it runs.

## Debugging

A Bash script is debugged with bashdb, through Bash Debug (vscode-bash-debug),
the adapter of VS Code's Bash Debug extension. **Debug: Start / Continue**
(`debug.start`: `C-c C-d d` in the Emacs keymap, F5 in the VS Code, CUA and
Sublime Text keymaps, Shift+F9 (⌃D on macOS) in the IntelliJ IDEA keymap) or **Debug File** runs the script under the bashdb that
Bash Debug ships, in the script's folder, with the arguments set by **File: Run
with Arguments…**, and runs to your first breakpoint. Step Over, Step Into and
Step Out go line by line, and the script's output reaches the Debug console.

A script is debugged when its shebang names `bash` or `sh`, or when it has no
shebang. A script of another shell, such as a `.zsh` or `.fish` file or a
`#!/usr/bin/env zsh` script, can't be: Debug says so and points to
[Run with Trace](/docs/run-debug#run-with-trace). A shell
[run configuration](/docs/run-debug#run-configurations) can't be debugged either; open its
script and debug that.

Variables lists only what bashdb gives, such as `$PWD` and `$?`. Read a
variable in a [watch](/docs/debugging#watches), the Debug console or by hovering it: `total`,
`$total` and `${total}` all give its value. Bash has no exceptions, so there
are no exception breakpoints. A [launch.json](/docs/debugging#launchjson-configurations)
`bashdb` configuration launches a script as VS Code does; Bash Debug can't
attach to a script that is already running.

Bash Debug runs on Node.js and uses the `bash`, `cat`, `mkfifo` and `pkill`
on your `PATH`. Editora finds Bash Debug
as set on the **Bash (bashdb)** card of **Settings → Debugging** (its
`bashDebug.js`), else in its own install, else in VS Code's Bash Debug
extension (`rogalmic.bash-debug`). The card's **Install…** downloads Bash Debug
0.3.9 from Open VSX, with its own bashdb; Bash itself isn't installed by
Editora.
