---
title: Troubleshooting
description: A blocked first launch, finding external tools, Doctor, plugins that fail, the application log, recovering after a crash, performance, and resetting config.
category: Help
order: 2
---

## A launcher is blocked on first run

The Windows installer is unsigned, so Windows stops it until you allow it. You
only do this once.

- **Windows**: when you run the downloaded `.msi`, SmartScreen shows *Windows
  protected your PC*. Click **More info**, then **Run anyway**. The installer
  installs Editora for every user of the computer, so Windows then asks for
  administrator rights, and its prompt names the publisher as *Unknown*.
  Once installed, Editora starts without these prompts.
- **macOS**: the app is signed and notarized by Apple. The first time you open
  it, macOS asks once whether to open an app downloaded from the Internet;
  click **Open**.

To check that a download is the one the release workflow built, see
[Checking a download](/docs/getting-started#checking-a-download).

## External tools aren't found

Language servers, debug adapters, `git`, `mmdc`, and the file runner are
external programs Editora launches. A GUI-launched app inherits a stripped
`PATH` that often misses Homebrew, npm, and version-manager directories, so a
tool that works in your terminal may look "not found" in the app.

Editora rebuilds the `PATH` from your login shell plus the usual install
locations before launching anything, which recovers most setups (including
nvm/fnm/asdf per-version bins). A Python environment you activated before
starting Editora (`$VIRTUAL_ENV`, `$CONDA_PREFIX`) stays first on that `PATH`.
[Doctor](#doctor) shows the command each feature found, or that it found none.
If a tool still isn't found:

- Set its absolute path in the relevant Settings page (LSP, Debugging, Mermaid).
- Confirm it runs from a plain login shell, since that's the `PATH` Editora reads.
- On Windows there's no login-shell convention, so prefer setting absolute paths.

## Doctor

**View: Doctor** (`view.doctor`, in the Tools menu) opens a full-tab health
report of every external command Editora's features rely on: whether each tool
is installed, which version, and whether Editora is looking at the right copy,
with an **Install…**, **Turn on** or **Settings…** fix on the rows that need one.
Start there when something that should work doesn't, and use **Copy Report** to
paste the whole report into a bug report. Every row and button is described in
[Doctor](/docs/doctor).

### Update… for servers older than Editora's

A server or tool that Editora installs at the release it is tested with gets an
**Update…** button in Doctor when your copy is older. See
[Update… for servers older than Editora's](/docs/doctor#update-for-servers-older-than-editoras).

## A language server starts but does nothing

The status bar shows an **LSP: \<server\>** segment and a loading bar while a
server initializes. If it never settles, restart servers with *LSP: Restart
Language Servers* (`lsp.restartServers`). For Java specifically, a stale `jdtls` process from a
previous run can hold its workspace lock; the loading bar clearing is the sign
the handshake completed. A server that starts but then fails its requests is
announced in the status bar (`lemminx-maven: internal error on hover — see the
log`), stopped after three failures in a row, and shown as a warning in Doctor,
with **Install…** for a server Editora installs;
the Maven `pom.xml` server whose folder is missing jars is reported as
*incomplete* in Settings → LSP and Doctor, and its **Install…** repairs it.

A server whose requests time out twice in a row is reported once, with its
name: *Not responding: its requests time out. Run “Restart Language Servers” to
start it again.* When it answers again, the status bar says *Responding again*.
Timeouts do not count while the server is busy: while jdtls is still importing
a project, or while a server reports work in progress. Requests sent before
the server finished starting do not count either.

**Format Document** says why it changed nothing. *Already formatted* means the
server answered with no edits, or with edits that change nothing together
(an HTML page that already ends the way its EditorConfig
`insert_final_newline` asks, for example); no undo step is left. Otherwise it says *Format failed: the language
server did not answer in time*, *Format failed: the language server stopped
before it answered*, or *Format failed:* with the server's own error.

## Java shows errors on every import, or a server never starts

In a folder you have not [trusted](/docs/workspace#trusted-folders), language
servers that would run the project's build to load it are held back. Java's
jdtls starts without the Maven or Gradle build, so the project's dependencies
and other modules don't resolve; the Kotlin, Scala, Rust, Ruby, C# and Clojure
servers don't start at all. The file says so, with **Trust Folder**. Trusting
the folder restarts the server with the build. See
[Servers that load a project's build](/docs/lsp#servers-that-load-a-projects-build).

## JavaScript in an HTML page completes nothing

If completion after `document.` in a `<script>` offers nothing and there is no
parameter help, the HTML server cannot find TypeScript's libraries (its log
says *Unable to load library*). That happens with a copy of
vscode-langservers-extracted installed with npm on its own, such as
`npm install -g`. The first HTML page a window opens says so on a banner, and
so does Doctor's HTML row: click **Update** on either, or **Install…** on the
HTML card in Settings → LSP, to install Editora's own copy, and leave the HTML
command blank so it is used. See
[Installing it](/docs/html#installing-it).

## Language servers run the wrong Node.js

The Node.js chosen on the **Node.js** card under **Settings → Build Tools**
runs every language server installed with npm, not only TypeScript's: the
HTML, CSS, JSON, YAML and Bash servers, Pyright and the others. Left blank,
they run on `node` on the `PATH`. A project's `.nvmrc` or `.node-version` does
not change them, so an old Node.js pinned for the project cannot break them;
only the TypeScript server follows the project's version file (from nvm), as
[Run](/docs/javascript-typescript#running) does. A server row that would warn about
a missing Node.js does not, when that setting names one that exists.

Install uses the npm beside that Node.js when there is one, so npm-installed
servers install even with nothing on the `PATH`. A server installed with
`npm install -g` into that Node.js (Bash, YAML, Pyright and the other npm
servers) is found in its folder even when that folder is not on the
`PATH`: Settings, Doctor and the banner's install check look there after the
`PATH`. If npm is set up to put global packages somewhere else, Install fails
and says where they went: *npm installed it, but Editora does not find
yaml-language-server — npm put it in …/bin. Add that folder to PATH, or choose
the Node.js there in Settings → Build Tools → Node.js, then try again*.

Doctor's **Installer Prerequisites** row counts that Node.js and the npm beside
it too, so a machine with no `node` or `npm` on the `PATH` is not reported as
missing them; they are listed as *npm, node (from Settings → Build Tools →
Node.js)*.

## TypeScript features are off or show false errors

JavaScript and TypeScript need a TypeScript with a `tsserver`, and Editora
installs its own: **Install…** on the TypeScript / JavaScript card in
**Settings → LSP** puts typescript-language-server 5.3.0 and TypeScript 5.9.3
in Editora's config folder, not in your global npm folder. Do not install a
newer TypeScript globally to fix the server:

- **TypeScript 6** leaves out `@types/node` and the other installed `@types`
  packages unless a tsconfig lists them. Node.js code then shows false errors
  such as *Cannot find name 'process'*, and JavaScript hovers Node's APIs as
  `any`. Doctor's TypeScript / JavaScript row and the card's **TypeScript**
  line warn: *TypeScript 6.0.3: leaves out @types packages*.
- **TypeScript 7** has no `tsserver`, so the server cannot start. The status
  bar says *JavaScript and TypeScript features are off: TypeScript 7.0.2 has no
  tsserver*, and Doctor's row warns too.

In both cases, click **Update…** on Doctor's row, or **Install…** on the card.
Editora's own TypeScript is then used even by a server you installed yourself,
except in a trusted folder that has its own. Which TypeScript runs, and in
what order, is under [TypeScript and JavaScript](/docs/javascript-typescript#language-server).

When the status bar says *the only TypeScript here is this folder's own, and
the folder is not trusted*, either trust the folder with *Project: Trust This
Project's Settings* (`lsp.trustProjectSettings`) or install Editora's own
TypeScript.

## Too many open files in a big JavaScript project

In a large JavaScript or TypeScript project, the TypeScript server can run out
of file watchers: it watches each `node_modules` folder it reads. On Linux this
shows as *too many open files*, or as a file-watcher limit reached, and changes
on disk may stop reaching the server. Editora cannot pass watch options to
`tsserver`, so set them in the project. Add this to `tsconfig.json` (or
`jsconfig.json`):

```json
"watchOptions": {
  "excludeDirectories": ["**/node_modules"]
}
```

Or raise the system's limit, for example with
`sudo sysctl fs.inotify.max_user_watches=524288`, and put
`fs.inotify.max_user_watches=524288` in a file under `/etc/sysctl.d/` to keep
it. Then run **Restart Language Servers**.

## Python imports show as errors

`Import "requests" could not be resolved` on a package you have installed
means Pyright is looking at a different Python than the one the package is in.

- Look at the interpreter item in the status bar, for example
  `Python 3.13.5 (PATH)`. `(PATH)` means no project environment was found.
- Click it, or run **Python: Select Interpreter** (`python.selectInterpreter`),
  and pick the project's environment, or **Browse…** to the environment's
  `python`. The server picks the change up at once. The status bar shows the
  interpreter Pyright analyses with.
- A `.venv` created after the project was opened, and packages added with
  `pip install`, reach Pyright within a few seconds. There is no need to
  restart the server.
- A folder with its own `pyproject.toml`, `setup.py`, `setup.cfg` or
  `pyrightconfig.json` gets its own Pyright, which uses that folder's `.venv`.
  A uv workspace member uses its workspace's `.venv`, with or without a project
  open.
- An environment named something other than `.venv`, `venv`, `env` or `.env`, or
  one outside the project, is not found by itself: pick it once, and the project
  keeps it.
- A `pyrightconfig.json` or `[tool.pyright]` section that sets `venv` wins over
  the pick inside Pyright.
- Doctor's **Python interpreter** row shows the same interpreter, its version
  and why it was chosen. Its **Settings…** opens the Python card on
  **Settings → LSP**, where the fallback interpreter is set.

See [the Python interpreter](/docs/python#the-python-interpreter).

## Python Format Document changes nothing

Format Document on a Python file runs Ruff or Black, from the project's
environment first, then from your `PATH`. When it leaves the text alone, the
status bar says why:

- **No Python formatter found**: install `ruff` or `black` in the project's
  environment or on your `PATH`.
- **printed nothing, so the text was left as it was**: the formatter skipped the
  file, usually because it matches Black's or Ruff's `force-exclude` setting.
- **the result is larger than 10 MB**: the output was cut off, so it is not
  applied.
- **Widen the buffer to format it**: the buffer is narrowed.
- **Ruff and Black cannot format a file on another machine yet**: the file is on
  a remote host, in WSL or in a container. A Python language server that
  formats still formats it.

A read-only file is not formatted either, and the status bar says so.

## A project's tools don't run in its container

A project's tools [run in a container](/docs/remote#tools-in-a-container) only
when all of these hold:

- **Run commands on the remote host** is on in Settings → Remote.
- The container is running, and one of its bind mounts covers the project
  folder. A dev container is found only when the project folder itself has a
  `.devcontainer` folder or a `.devcontainer.json` file.
- You allowed that container. **Not Now** holds until you restart Editora, and
  so does going back with *Containers: Run This Project's Tools Here*. Picking
  the container with *Containers: Run This Project's Tools in a Container…*
  allows it at once.

Editora looks for a dev container when the project opens and when its window
comes back to the front, at most every 30 seconds, so one started meanwhile is
picked up once you switch back to the window. If the picker says no containers
are running, check that the **Container engine** in Settings → Remote names the
engine you use (`docker` or `podman`) and that it lists your container in a
terminal.

A debug session that cannot reach its port in the container or WSL distribution
needs `nc`, `bash` or `python3` in it; see
[Ports and debuggers](/docs/remote#ports-and-debuggers).

## A WSL project misses outside changes

Windows cannot watch a folder on the `\\wsl.localhost` share. Editora watches a
WSL project from inside the distribution, but only while the project's
[tools run there](/docs/remote#tools-in-wsl-windows). Allow the distribution
when asked, and install `inotify-tools` in it for changes to show at once
rather than within a few seconds. See
[Outside changes in WSL](/docs/remote#outside-changes-in-wsl).

## Shell scripts show no problems or don't format

The Bash language server needs two helper programs: **ShellCheck** for
diagnostics and **shfmt** for Format Document. Without them a script looks
clean and Format Document changes nothing. A banner above the script says so,
with **Install**, and the **Bash / Shell** card in **Settings → LSP** and the
Doctor show whether each is found. Install them with the Install… buttons
there, or with **Install: ShellCheck and shfmt** (`install.shellTools`) in the
palette. If you turned **Lint with ShellCheck** or **Format with shfmt** off on
the Bash / Shell card, turn it back on. See [ShellCheck and shfmt](/docs/shell#shellcheck-and-shfmt).

If ShellCheck runs but ignores a `.shellcheckrc`, the file is probably in a
subfolder of the project; ShellCheck reads it only from the project folder or
above. See [Where ShellCheck finds .shellcheckrc](/docs/shell#where-shellcheck-finds-shellcheckrc).

A script whose ShellCheck problems seem to be missing, while others show
theirs, may be a zsh script: ShellCheck cannot check zsh, and the problem
counter says **Not checked**. A `PKGBUILD` shows no SC2034 or SC2154 by design.

The server's own warnings, such as a helper it could not find, are in the
Application Log (below).

## Java debugging does not start or apply changes

When the Debug window's state line reads *Not started: the import failed*, the
Java language server could not import the Maven or Gradle project, so nothing
was built or launched. The Debug console lists the import's errors. Fix the
build file and debug again. When the errors name a Java version, choose
another **Default JDK** in **Settings → Build Tools**.

*Not started: the build failed* means the project has compile errors; the
Debug console lists them, with a link to each line.

When a save during a session says *Code changes not applied: the program runs
Java N, but the code was built for Java M*, the program runs on another JDK
than the one the language server builds for. Choose the program's JDK as the
**Default JDK**, or restart the debug session. See
[Build tools](/docs/build-tools) for which JDK a program runs on.

## A plugin does nothing or stops Editora starting

A plugin's row in **Settings → Plugins** says why it did not load or start, and
[`--disable-plugins`](/docs/cli#flags) starts Editora without any plugin. See
[Plugins: Troubleshooting](/docs/plugins#troubleshooting).

## Seeing errors in a packaged build

A packaged app has no visible console. Editora captures its logging and uncaught
exceptions into an **Application Log**. It is Editora's own log, not the output
of a program you debug, which is in the Debug console.

- Open it with **Settings → Advanced → Show Application Log…** or
  **View: Application Log** (`view.debugLog`).
- It also mirrors to `editora-session.log` in your config folder, so it survives
  a crash for a bug report. The session before keeps its file as
  `editora-session.prev.log`, so reopening Editora after a crash does not erase
  the log that recorded it. Neither file goes into a configuration export.

The **message log** (click the status-bar message, or `view.messageLog`) keeps a
session history of the transient status-bar messages. A message's right-click
menu has **Copy** (*Message Log: Copy*, `messageLog.copySelected`); Ctrl+C (⌘C),
or your keymap's copy chord (`M-w` in Emacs), copies too. Esc, or your keymap's
cancel chord (`C-g` in Emacs), closes it. *Message Log: Clear* empties it.

## Reporting a problem

**Help → Report a Problem…** (`help.reportProblem`, also a button in the
Application Log window) prepares a bug report from the Application Log of this session and the one
before. It also lists Editora's version, the Java it runs on, your operating
system and your plugins, and ends that block with one *Settings:* line saying how
Editora is set up: the theme, the language, the keymap, the interface zoom, the
font size, which bars show, and which features are on (language servers,
debugging, Git, plugins, AI and so on). That line holds no path, address or
key. Before you see it, Editora replaces what looks private:
paths in your home folder, your user name, e-mail addresses, credentials in URLs,
authentication headers and values shaped like tokens. The window says how many it
replaced.

The report is shown, and you can edit it, before anything leaves your computer.
Editora sends nothing by itself:

- **Open GitHub Issue** copies the report and opens a new issue in your browser
  with it filled in. A report too long for the link is left on your clipboard
  instead, for you to paste into the issue's *Problem report* box. Describe what
  happened, then submit the issue yourself.
- **Copy** and **Save…** are there for any other way of sending it.

The issue is public, so read the report first and delete anything you would
rather not share.

When Editora will not start, run `editora --report` instead (see
[command-line flags](/docs/cli#flags)). It writes the same report to the current
folder, prints its path and the link of a new issue, and opens both. Its
*Settings:* line comes from the settings file, and says so when that file
cannot be read.

## After a crash or an interrupted save

- **Unsaved edits.** If Editora did not close normally (a crash, a kill, a
  logout, a power cut), the next launch opens **Recover Unsaved Edits** with the
  text of every tab that had unsaved changes. See
  [crash recovery](/docs/undo-history#crash-recovery). Reopen the list any time
  with `file.recoverUnsavedEdits`.
- **A save that did not finish.** Some files are overwritten in place rather
  than replaced. While that happens their previous contents are held in
  `save-backups/` in the config folder. If Editora stops in the middle, the next
  launch shows an **Unfinished Save** dialog that names the backup and offers
  **Restore Previous Contents**, **Keep Current File**, or **Decide Later**.
  Keep Current File deletes the backup. When the file itself is gone, the
  backup is the only copy left and that button reads **Delete Backup**.
- **Save as Administrator.** The file is backed up beside itself
  (`<name>.editora-backup`) before it is overwritten, and put back if the write
  fails. If it cannot be put back either, the status bar names the backup, and
  the next administrator save of that file is refused until you restore or
  remove it.
- **A refactoring that moves or deletes files.** If Editora is interrupted in
  the middle of one, opening the project again shows an **Interrupted
  Refactoring** dialog that lists the files that were moved aside and offers to
  put them back.
- **A damaged config file.** A config file that is empty or unreadable at
  startup is copied to `<name>.corrupt.bak` and reported in the status bar
  rather than reset silently. See
  [Configuration](/docs/configuration#safety-copies).

## Two editors show different settings

Two Editora processes on the same config folder (the second started with
`--new-instance`) do not overwrite each other's data, but each one reads the
other's changes only when it starts. Restart the one that looks stale. See
[Configuration](/docs/configuration#two-editors-on-one-config-folder).

## Settings sync shows "Sync ⚠"

The status-bar segment means the last automatic sync failed; click it to open
**Settings → Sync**, where the Status row gives the reason. Automatic syncs
never prompt for credentials, so a repository that needs a password or an SSH
passphrase fails until a credential helper or an SSH agent can supply it. Run
**Settings Sync: Sync Now** (`sync.now`) to try with your credential helper. See
[Settings sync](/docs/settings-sync#credentials-and-failures).

## A file shows the wrong characters

Editora reads a file as UTF-8 unless a byte-order mark or an `.editorconfig`
`charset` rule says otherwise. A file in some other encoding still opens without
losing any bytes, but its accented or non-Latin text can look garbled, and the
encoding in the status bar is drawn in the warning colour. Click it and choose **Reopen with Encoding…** with the file's
real encoding (Shift_JIS, windows-1251, GBK and so on). Editora remembers the
choice for that file. See [File encodings](/docs/workspace#file-encodings).

## Performance

Editora aims to stay responsive on large files. It gives up the costly
features on a file past these sizes, and says so:

| A file with | Opens |
| --- | --- |
| 50,000 lines or more | With everything but its language server, unless the language keeps it longer (see [Language servers](/docs/lsp)); **Settings → Editor → Performance** sets that line count |
| 200,000 lines or more, or 16 MB or more | In large-file mode and [View Mode](/docs/editing#read-only-and-view-mode), without highlighting, folding, Structure, spell check, the minimap, undo and the language server; the status line lists what is off, and the Structure window says why it is empty |
| A line of a million characters or more, or minified code | In [View Mode](/docs/editing#read-only-and-view-mode), with the reason |
| About 50 MB or more | Read-only, with a capped load |
| More than the memory that is left (typically a second or third very large file) | Read-only, as a slice of its first part; close other large tabs and reopen it to edit it |

A file in View Mode for one of these reasons is edited with **Enable Editing**; the status
bar then says **Editable, No Undo** while undo is off.

Within a file:

| What | Effect |
| --- | --- |
| A line longer than 20,000 characters | Not syntax-highlighted; the rest of the file is |
| A line of 2,000 characters or more with more than 400 tokens, or one that takes too long to colour | Shown plain |
| A line of 10,000 characters, or a long run without spaces (minified code, an embedded image) | The file opens unwrapped even where [word wrap](/docs/editing#word-wrap) is on; *View: Toggle Word Wrap* wraps it anyway |
| Find in a very large file | Searches in the background and holds at most 100,000 matches around the current one, so the count can read "N of 100,000+"; Next, Previous, Replace and Replace All still reach every match |

If editing or scrolling feels heavy:

- Check whether a language server is busy (the LSP loading bar).
- Try **Simple UI mode** (`view.toggleSimpleMode`), which drops the gutter,
  minimap, and the heavier features.
- The native builds set conservative heap and GC options and bake in an AOT
  class cache for faster startup; a missing cache just falls back to a normal
  start.

## Reset or isolate your config

- **Reset to Defaults** in **Settings → Advanced** resets every preference,
  including the keymap choice, which goes back to Emacs. Text zoom is kept, and
  your key rebinds stay stored with the keymap you made them in, so they apply
  again when you switch back to it.
  The previous preferences are first saved as
  `settings.json.before-reset-<date>.bak` in the config folder, and the status
  bar names the file; copy it back over `settings.json` while Editora is closed
  to undo the reset.
- **Export Configuration…** zips the active config folder for a backup or bug
  report.
- Run with `--dev` or `--config-dir <path>` to use a separate config without
  touching your everyday one. See [Configuration](/docs/configuration) and the
  [command-line reference](/docs/cli).
