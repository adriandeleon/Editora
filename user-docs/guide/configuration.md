---
title: Configuration
description: The config folder, settings.json, session state, and how to export or reset it.
category: Customization
order: 1
---

Editora keeps preferences and session state in a config folder. Most settings
have a UI in the Settings window; everything underneath is plain text you can
edit by hand.

## The Settings window

Open it with **View: Settings** (`view.settings`: `Ctrl+,` in the CUA, Sublime
and VS Code keymaps, `Ctrl+Alt+S` in IntelliJ, `⌘,` in all four on macOS). The
sidebar groups the pages, each with an icon before its name (pages with an icon elsewhere in Editora, such as Git or Terminal,
use the same one); a page whose feature is still in beta (AI, AI Agent, AI Actions, Remote, Sync, MCP
Server) carries a **Beta** pill in the sidebar and beside its title. Every
page title has a **?** link that opens that page's documentation for the
version of Editora you are running. The window fits its 900 px minimum width in
every language.

![The Settings window on its Appearance page: User and Project scope buttons, a search field, a sidebar of pages with icons, and the page's language, font, theme and editor theme settings above a live preview](/screenshots/docs/settings.png "The Settings window: pages in the sidebar, each with its icon.")

- Changes apply as you make them. There is no OK or Save button, and the footer
  says so. Every setting is also a palette command; see
  [Settings from the palette](#settings-from-the-palette).
- The window opens centred on the editor window the first time. After that it
  stays where you put it for the rest of the session.
- A text field applies when you press Enter, when you leave it, and when you
  close the window.
- A number field takes a whole number in its range. A number out of range, or
  any other text, stays in the field, marked red, with a tooltip that gives the
  range; the setting keeps its value until you fix the text or use the arrows.
- Other input that cannot be used, such as a ruler list, a TODO pattern, a
  Remote host or port, a plugin registry URL or a sync branch, also turns its
  field red with the reason and keeps what you typed.
- Rows that only matter while a switch is on are greyed out while it is off,
  with the note "Turn on “…” to change these settings." This holds on every
  page. A switch that is off is drawn so that it shows in every theme, and a
  disabled one is grey.
- The list editors (Snippets, Templates, Abbreviations, External Tools and
  Remote sites) save each change as you make it too; only Macros keeps Save
  and Revert. Deleting an entry from a list asks "Delete “…”?" first, with
  Cancel as the default button.
- Every list on a page (Abbreviations, External Tools, Macros and their steps,
  Templates, Snippets, the personal dictionary, Trusted Folders, the toolbar
  lists, Remote sites, type renderers and the rest) has a right-click menu with
  what its buttons do to a row: Edit…, Rename…, Move Up and Down, Remove,
  Disable or Enable, and Copy where the row has text. The Menu key or Shift+F10
  opens it at the selected row. The **List** commands (*List: Open*,
  *List: Copy*, *List: Rename…*, *List: Turn On or Off*, *List: Remove*,
  *List: Move Up*, *List: Move Down*) do the same from the palette or a key of
  your own, bringing the Settings window forward first.
- The window's buttons and links that act are commands too, such as
  **Settings: Reset All to Defaults…**, **Settings: Restore Settings Backup…**,
  **Settings: Open Help for Shown Page** and **Settings: Toggle Modified Only**.
- The Editor page is grouped into Display, Editing, Clipboard, Indentation and
  Saving, followed by Performance, Logs, CSV, File previews and Export & Print.
  Updates, Trusted folders and
  [Show Application Log…](/docs/troubleshooting#seeing-errors-in-a-packaged-build)
  are on the Advanced page.
- A few settings apply only when Editora starts: the **Language** on the
  Appearance page, merging the menu bar into the title bar, and turning plugins
  on or off. Once you change one, its row says it applies after a restart, and
  the footer says "Restart Editora to apply N changes." with a **Restart Now**
  button. Restart Now, or **Application:
  Restart** (`app.restart`) in the palette, quits the usual way, asking about
  unsaved files, and starts Editora again; cancelling the quit cancels the
  restart.

### Settings from the palette

Every setting can be changed without opening the window. Most pages have a
command that lists the page's settings by name with their values: pick one to
flip it, choose from its choices, or type its value.

| Command | Covers |
| --- | --- |
| **Terminal: Change a Setting…** | The shell, theme, text size, cursor, desktop notifications and the rest of the Terminal page |
| **Debug: Change a Setting…** | The Debugging page |
| **LSP: Change a Language Setting…** | ShellCheck and shfmt, XML and HTML formatting, ESLint and the formatters, the actions on save |
| **Build: Change a Setting…** | Each build tool's command, Maven's default JDK, Node.js, Build before Run |
| **Encryption: Change a Setting…** | The Encryption page |
| **Settings Sync: Change a Setting…** | The Sync page |
| **Web: Change a Setting…** | The HTTP client's scripts and the browser it opens |
| **Editor: Change a PDF Setting…** | The orientation, margins and code font size of exported and printed PDFs |

Settings that stand alone have a command of their own, among them **View:
Toggle a File Preview…**, **View: Toggle Word Wrap in Prose**, **View: Toggle
Quick Fix Lightbulb**, **View: Toggle Problem Counter**, **GitHub: Set gh
Command…**, **Remote: Set Container Engine…**, **Spell Check: Set Default
Language…**, **AI: Set API Key…** (typed into a field of dots), **AI Agent: Set
a Client's Command…** and **Markdown Lint: Set Rule Severity…**.

Each keeps what its Settings row would keep, and an open Settings window shows
the change at once. The question before pasting several lines into a terminal
has no command: it protects you, so only its row turns it off.

### Settings you changed

A row whose value differs from the default has a marker at its leading edge,
and a screen reader reads it as "Modified". The sidebar shows beside each page
how many of its settings differ from the default.

A modified row has a **Reset to default** link under its text, and the same
item on its right-click menu. It puts that row's settings back to their
defaults and leaves every other row alone. API keys and the Local History
limits are never marked or reset row by row. A row reset by mistake comes back
with [Edit: Undo Last Removal](/docs/undo-history#undo-last-removal), run from
the editor window.

The **Modified** toggle beside the search box lists only the settings that
differ from their defaults; typing `@modified` in the search does the same. A
row you put back to its default leaves the list.

In a project window, the **Edit settings for All projects | This project: name**
switch at the top of the window chooses whose settings the pages edit; see
[Inside a project](#inside-a-project).

### Search

The search box at the top of the sidebar finds a setting by its title, its
description, its card and page names, the choices of a list, and extra
keywords. Every word you type must appear, in any order. Pages without a match
are greyed out in the sidebar, and rows whose title or description matches are
tinted.

The page with the best match opens. A match in a row's title counts more than
one in its description or keywords, and a whole word more than part of one;
words that name a page (*keymaps*, *build tools*) open that page. Some settings
are found by other names too: *zoom* finds Font size, *line endings* and *crlf*
the EditorConfig row (end of line), *encoding* its charset, *hotkey* and
*keybinding* the shortcuts, *colour scheme* the themes, *soft wrap* the wrapping
rows, and *gutter* the line numbers. A command name finds the Keymaps page and
puts your words in its shortcut filter.

- `Ctrl+F` (`Cmd+F` on macOS) goes to the search box from anywhere in the
  window. Typing a letter or digit while the sidebar has the focus starts a
  search, and Down in the search box goes back to the sidebar.
- Escape clears the search; a second Escape closes the window.
- `@modified` in the search box shows only the settings you changed; see
  [Settings you changed](#settings-you-changed).
- A command that opens Settings on a page, such as **Snippet: Manage…** or
  **Settings Sync: Set Up…**, clears the search first. Opening the window again
  keeps the last search, selected so that typing replaces it.

## Config directory

The folder is chosen by this precedence:

| Source | Location |
| --- | --- |
| `--config-dir <path>` (CLI) | that path |
| `--dev` flag | `~/.editora-dev/` |
| `EDITORA_CONFIG_DIR` (env var) | that path |
| default | `~/.editora/` |

`--dev` wins over the variable, so a development launch never uses your everyday
folder; to run a development instance on another folder, name it with
`--config-dir`.

`~` is your home directory; on Windows that is `%USERPROFILE%`, so the default
folder is `%USERPROFILE%\.editora`.

Use `--dev` to run a development instance that never touches your everyday
settings or session. The live path is shown in **About Editora**. See the
[command-line reference](/docs/cli) for all the flags.

### Two editors on one config folder

A second launch normally goes to the editor that is already running on the same
config folder, so one folder has one process. Starting another with
[`--new-instance`](/docs/cli#one-editor-not-two) gives two processes that share
the folder. That is safe: each one writes only what it changed, so settings,
sessions, projects, notes, bookmarks, breakpoints, macros, the dictionary, and
Local History from both are kept.

The second process shows a one-time notice in its status bar. A change made in
one process appears in the other only after that one restarts, and when both
change the same setting or entry, the one saved last wins.
[Settings sync](/docs/settings-sync) runs only in the first process.

## Files

| File | Holds |
| --- | --- |
| `settings.json` | Preferences: font, theme, keymap, tab size, view options, auto-save, keybinding overrides. AI API keys only where no system keychain is available (see [where API keys are kept](/docs/ai#where-api-keys-are-kept)) |
| `workspace-state.json` | Session of the no-project window: open files, folds, tool-window layout, window geometry, and the [Python interpreter](/docs/lsp#the-python-interpreter) picked for loose files |
| `windows/<id>.json` | Session of each extra no-project window |
| `recent-files.json` | Recent files list |
| `search-history.json` | Your recent [Find in Files](/docs/navigation#find-in-files) queries |
| `agent-sessions.json` | Your recent [AI Agent](/docs/ai#ai-agent) chats, so you can resume them. Each one's title is taken from its first prompt |
| `projects.json` + `projects/<id>.json` | Project index and each project's session, including the [Python interpreter](/docs/lsp#the-python-interpreter) picked for it |
| `bookmarks.json` | Bookmarks (per project) |
| `notes.json` | Personal notes (per project) |
| `breakpoints.json` | Debugger breakpoints (per project) |
| `debug-adapters.json` | Your own [debug adapters](/docs/run-debug#debug-adapters-of-your-own), opened by **Debug: Edit Debug Adapters** |
| `connections.json` | Saved SFTP connections (no secrets), with whether each host may [run commands](/docs/remote) and its tool commands, and which containers and WSL distributions may run a project's tools |
| `plugins.json` + `plugins/<id>/` | Enabled plugins and their folders |
| `dictionary.txt` | Your added spell-check words |
| `macros.json` | Saved keyboard macros (format version 2; an older file is converted when it is read, and its key bindings are kept) |
| `abbreviations.json` | Your abbreviations |
| `trusted-folders.json` | Folders you have trusted: their builds, project settings, JavaScript tools and HTTP scripts may run, and language servers may load their builds ([what trust allows](/docs/workspace#trusted-folders)) |
| `snippets/<lang>.json`, `templates/*.json` | Your snippets and file templates |
| `themes/*.css`, `editor-themes/*.css` | Your [own themes](/docs/themes-fonts) |
| `history/` | [Local History](/docs/workspace#local-file-history) |
| `http-history/` | The [HTTP client](/docs/http-client)'s last responses and chosen environment for each `.http` file |
| `cache/` | Data Editora downloads or builds and can rebuild: XML schemas, the Maven archetype catalog, compiled Kotlin scripts, the HTTP client's script runner. Settings export leaves it out |

Preferences, sessions, and list files are **JSON**. If `settings.json` is absent,
Editora converts an existing `settings.toml` automatically. Once the JSON
replacement has been written safely, the old file is renamed to
`settings.toml.migrated` and kept, because the JSON file does not carry over its
comments.

### Safety copies

These folders and files hold copies Editora makes before it replaces something.
They are created only when needed.

| Path | Holds |
| --- | --- |
| `recovery/` | The text of tabs with unsaved changes, for [crash recovery](/docs/undo-history#crash-recovery). Removed as you save or close |
| `save-backups/` | The previous contents of a file that is being overwritten in place, until that save finishes. One left by an interrupted save is offered at the next launch |
| `line-ending-originals/` | The original bytes of the last 30 files whose [mixed line endings](/docs/editorconfig#files-with-mixed-line-endings) a save rewrote |
| `sync/` | The [Settings sync](/docs/settings-sync) working copy (`sync/repo/`) and copies of files a sync replaced (`sync/backups/`) |
| `settings.json.before-reset-<date>.bak` | The preferences as they were before **Reset to Defaults** or **Restore from Backup…** |
| `backups/config.before-import-<date>.zip` | The whole configuration as it was before **Import Configuration…**, API keys and HTTP history included, readable only by you |
| `<name>.v<n>.bak` | A file written by a newer Editora, set aside by an older one (see [Schema versioning](#schema-versioning)) |
| `<name>.corrupt.bak` | A config file that was empty, damaged, or not valid UTF-8 when it was read |

A config file that cannot be read is never silently replaced by defaults: the
original is copied to `<name>.corrupt.bak` and the problem is reported in the
status bar at startup.

### Inside a project

These files live in the project itself rather than in your config directory, so
they can be committed and shared:

| File | Holds |
| --- | --- |
| `.editora/settings.json` | This project's own values for how its files are edited, its build commands, debugger programs and language servers (the list follows) |
| `.editora/run-configurations.json` | [Run configurations](/docs/run-debug) exported for the team |
| `.editora/debug-adapters.json` | The project's own [debug adapters](/docs/run-debug#debug-adapters-of-your-own), used once the folder is trusted |

A project's `.editora/settings.json` can set only these:

| Setting | In the file |
| --- | --- |
| Indent style (detect, spaces or tabs) and tab size (1 to 16) | `editor` → `indentStyle`, `tabSize` |
| Rulers, and rulers by language (columns 1 to 1000) | `editor` → `rulers`, `languageRulers` |
| Wrap long lines in code, and in Markdown and plain text | `editor` → `wordWrap`, `wordWrapProse` |
| Trim trailing whitespace on save, and end files with a newline on save | `editor` → `trimTrailingWhitespace`, `insertFinalNewline` |
| Spell-check language (`en_US`, `en_GB`, `es`, `es_MX` or `fr`) | `editor` → `spellLanguage` |
| The command each build tool runs (Maven, npm, Cargo, Go, Gradle, sbt, Mill, Scala CLI, Clojure, Leiningen, Babashka) | `buildCommands`, by tool |
| The program of the Java, Python, JavaScript, Go, Rust, C#, Ruby and PHP debuggers, and GDB | `debugTools`, by debugger |
| Which language server to run for a language, and whether to run it | `lspCommands`, `lspEnabled`, by server |
| The [Bash server's options](/docs/lsp#bash-server-options) | `bashIde` |
| The [HTML server's options](/docs/html#settings--lsp--html) (VS Code's `html.*` names) | `html`, or VS Code's dotted `html.…` keys at the top level |

```json
{
  "version": 2,
  "editor": { "tabSize": 2, "rulers": [100], "trimTrailingWhitespace": true },
  "buildCommands": { "maven": "./mvnw" },
  "lspEnabled": { "rust": false }
}
```

Appearance, keymap and fonts stay personal, because checking out a repository
should not rearrange somebody else's editor. Trimming trailing whitespace and
the final newline exist only per project: on your own they are left to
`.editorconfig`. A value out of range is ignored rather than changed to fit.

- **Trust.** The project's values apply only once you trust the folder
  (*Project: Trust This Project's Settings*, `lsp.trustProjectSettings`, or
  **Trust Folder…** in Settings). Without trust, a project can still turn a
  language server off, since that only runs less, set the Bash server's
  options other than its program paths and explainshell server, and set the
  HTML server's options, none of which runs a program.
- **`.editorconfig` wins** where it says something: the project's values take
  the place of yours, and a file's `.editorconfig` rules still come first.
- **In Settings.** In a project window, the top of Settings reads **Edit
  settings for All projects | This project: name**. *This project* names the
  project (the name the window's title shows, with the folder in its tooltip);
  the tooltip of *All projects* says it is used in every window unless a project
  sets its own. While **This project** is chosen the bar is tinted, the window
  is titled *Settings — Project: name*, and the note under the bar names the
  project and the `.editora/settings.json` the changes go to. In **All
  projects**, every row edits your own settings, and a row
  the project sets says "Overridden by this project". In **This project**, only the
  rows listed above can be changed; each shows the project's value, or yours
  as a starting point, and a change is written to the project's file, keeping
  the rest of it. A row the project sets says "Set for this project" and has
  **Use my setting**, which removes the project's value. While the folder is
  not trusted, the bar says so and has **Trust Folder…**, and the rows say the
  value is not applied until the folder is trusted.
- A file written by a newer Editora is not changed from Settings; edit it as a
  file instead. A remote project's file is not read.

**Project: Edit Project Settings…** (`project.editSettings`) creates the file
with an example. An existing project TOML file remains readable and is
converted when you open **Edit Project Settings**. See
[projects](/docs/workspace#settings-a-project-can-commit).

## Preferences (settings.json)

Edit in the Settings window, or directly:

```json
{
  "fontFamily": "JetBrains Mono",
  "fontSize": 14,
  "theme": "Editora Dark",
  "tabSize": 4,
  "autoSave": "afterDelay"
}
```

`autoSave` accepts `off`, `afterDelay`, or `onFocusChange`. Text zoom is stored
separately as `fontZoom` (adjusted with `C-=` / `C--` / `C-0` or Ctrl+wheel).
While it is not 100%, the Appearance page says so, with a
**Reset zoom** link.

### Editing settings.json by hand

The **Path** link on the Advanced page opens `settings.json` in an editor tab.
Saving it there applies it at once in every window, Settings windows included,
and the status bar says "Applied the saved settings.json." A file that is not
valid JSON applies nothing; fix it and save again. Changes made with another
program apply the next time Editora starts.

Under the path, the Advanced page lists the values that are not used as
written:

- a key that is not a setting: it is ignored and removed at the next save;
- a value of the wrong type: the default is used;
- a value that was changed to fit, such as a font size of -5, used as 6.

### Containers

`containerEngine` is the command Editora runs for a project whose
[tools run in a container](/docs/remote#tools-in-a-container): blank (the
default) means `docker`; `podman` works too, and so does a command line such as
`flatpak-spawn --host docker`. It is the **Container engine**
field in Settings → Remote. `containerBindings` remembers the container you
picked for each project folder with *Containers: Run This Project's Tools in a
Container…*, from folder path to container name; *Containers: Run This
Project's Tools Here* removes the folder's entry. Whether a container or WSL
distribution may run a project's tools is kept in `connections.json`, with the
hosts' answers.

### JavaScript and TypeScript tools

`nodePath` is the Node.js that Run, run configurations, Debug, Package Scripts,
the language servers and HTTP scripts use. It is the **Node.js** field on its
own card under **Settings → Build Tools**, beside npm. Blank (the default) means
automatic: a project's `.nvmrc` or `.node-version` version when nvm has it,
else `node` on the PATH.

ESLint and formatting are the **ESLint and formatting** rows of the TypeScript
card under **Settings → LSP**. ESLint, Prettier and Biome run only in a trusted
folder:

| Key | Settings row | Default |
| --- | --- | --- |
| `eslintEnabled` | Check with ESLint | `true` |
| `jsFormatter` | Formatter: `auto` (Automatic), `prettier`, `biome` or `server` (TypeScript server) | `auto` |
| `jsOrganizeImportsOnSave` | Organize imports on save | `false` |
| `eslintFixOnSave` | Apply ESLint fixes on save | `false` |
| `jsFormatOnSave` | Format on save | `false` |

**Automatic** formats with the project's own Prettier or Biome when it has one,
else with the TypeScript server. The three on-save actions run in that order
after a save you make yourself, never after auto-save. When they change the
file it is saved again, and their changes undo as one step. See
[Language servers](/docs/lsp).

### Python

The Python settings are on the **Python** card under **Settings → LSP**. The
card stays editable with language servers and debugging switched off, since Run
and tests use the interpreter too:

| Key | Settings row | Default |
| --- | --- | --- |
| `pythonDebugCommand` | Python interpreter (fallback for Run, Debug and code intelligence) | blank (the first Python on the `PATH`) |
| `pythonFormatOnSave` | Format on save (Ruff/Black) | `false` |

The interpreter is the fallback: it is used when a project has no virtual
environment (`.venv` or `venv`) and none was picked for it. A path or a bare
name such as `python3.12` works; a bare name is looked up on the `PATH`, for
Run and Debug and for Pyright alike. The card's **Choose…** button runs
**Python: Select Interpreter** (`python.selectInterpreter`), which picks the
interpreter for the window's project; that pick is kept with the project's
session, not in `settings.json`. See
[The Python interpreter](/docs/lsp#the-python-interpreter).

**Format on save (Ruff/Black)** formats a Python file with Ruff or Black (the
project's own first) after a save you make yourself, and saves it again when
that changed it. Auto-save does not format.

### Java debugging

The Java debugger's settings are on the **Java** card under **Settings →
Debugging**:

| Key | Settings row | Default |
| --- | --- | --- |
| `debugHotCodeReplace` | Apply code changes to a running Java program: `auto` (On save), `manual` (Only with Debug: Apply Code Changes) or `off` (Never) | `auto` |
| `debugStepFilters` | Step Into skips JDK classes and synthetic methods | `true` |
| `debugShowStatics` | Show static fields in Variables | `true` |
| `debugShowToString` | Show toString() values | `true` |
| `debugSuspendAllThreads` | Suspend all threads when one stops | `false` |
| `debugExceptionTypes` | Exception breakpoints: only these classes (separated by commas) | blank (every exception) |

**Show toString() values** shows what each object's `toString()` returns,
which runs your program's code at each stop; off, objects read `Type@id`. It
takes effect at the next debug session. See
[Inspecting values](/docs/run-debug#inspecting-values).

**Suspend all threads when one stops** makes a breakpoint, an exception or a
step stop every thread of the program, and Continue resume them all; off, only
the thread that hit it stops. It takes effect at the next debug session. See
[Threads in a Java program](/docs/run-debug#threads-in-a-java-program).

### Python debugging

The **Python** card under **Settings → Debugging** has:

| Key | Settings row | Default |
| --- | --- | --- |
| `debugPythonSubprocesses` | Debug Python subprocesses | `true` |
| `debugPythonTemplates` | Template breakpoints: `auto` (Automatic), `on` (Django and Jinja) or `off` (Off) | `auto` |

On, a process that a debugged Python program starts (`multiprocessing`, a
Python subprocess) is debugged too, in a session of its own; off, it runs
without the debugger. **Template breakpoints** makes a breakpoint in a Django
or Jinja template stop on its line; Automatic turns it on for a project that
uses Django or Jinja. See
[Debugging Python](/docs/run-debug#debugging-python).

### Go debugging

The **Go goroutines** rows under **Settings → Debugging** choose which
goroutines the Threads view lists:

| Key | Settings row | Default |
| --- | --- | --- |
| `debugGoHideRuntimeGoroutines` | Hide runtime goroutines | `true` |
| `debugGoPprofLabels` | pprof labels in names (`*` for every label, or keys such as `job, region`) | `*` |
| `debugGoGoroutineFilter` | Goroutine filter, in Delve's syntax (`-with label job=*`) | blank (every goroutine) |

See [Goroutines](/docs/run-debug#goroutines).

### Evaluating expressions

**Evaluating expressions** under **Settings → Debugging** has:

| Key | Settings row | Default |
| --- | --- | --- |
| `debugEvaluationTimeLimit` | Time limit, in seconds (`0` is each debugger's own) | `10` |

GDB and Python's debugger stop an expression's call after this long, and the
program stays paused where it was. See
[Time limit](/docs/run-debug#time-limit).

### Rulers and indent guides

These are the rows under **Settings → Editor → Display**:

| Key | Settings row | Default |
| --- | --- | --- |
| `showColumnRuler` | Show rulers | `true` |
| `rulers` | Rulers, such as `80, 120` (blank for none) | `[80]` |
| `languageRulers` | Rulers by language, such as `java: 100; python: 79, 88` | none |
| `showIndentGuides` | Show indent guides | `true` |

`languageRulers` maps a language to its own columns, used instead of `rulers`
for files in that language; an empty list means that language has no rulers.
Columns run from 1 to 1000. An `.editorconfig` `max_line_length` replaces the
first ruler (see [EditorConfig](/docs/editorconfig#rulers-and-max_line_length)).

The palette commands that set them, and how rulers and indent guides are
drawn, are under
[Whitespace and the column ruler](/docs/editing#whitespace-and-the-column-ruler).

```json
{
  "rulers": [80, 120],
  "languageRulers": { "java": [100], "python": [79, 88], "markdown": [] },
  "showIndentGuides": true
}
```

### Word wrap

Two rows under **Settings → Editor → Display**, one for code and one for prose
(see [Word wrap](/docs/editing#word-wrap)):

| Key | Settings row | Default |
| --- | --- | --- |
| `wordWrap` | Wrap long lines in code | `false` |
| `wordWrapProse` | Wrap long lines in Markdown and plain text | `true` |

*View: Toggle Word Wrap* (`view.toggleWordWrap`) flips whichever of the two
governs the file in front.

## Schema versioning

Each structured config file carries a `schemaVersion`. Editora migrates older files forward
automatically on read. A file written by a **newer** Editora than you're running
is backed up to `<name>.v<n>.bak` and defaults are loaded, so an older build
never clobbers a newer config. When you return to the newer build, it puts that
copy back, provided the file left in its place still holds only defaults.

## Export and reset

- **Export Configuration…** (Settings → Advanced, or `config.export`) zips the
  active config folder into a timestamped archive in your home directory.
  `.bak` backups and installed plugins are included. Local History is left out,
  because it holds the text of your files rather than configuration. A dialog
  asks before exporting when there is something sensitive to leave out:
  - AI API keys kept in the system keychain are never exported. Keys kept in
    `settings.json`, where there is no keychain, are blank in the exported
    `settings.json` and its backups unless you tick **Include AI API keys**. A
    key is a billable credential. When keys were left out, the status bar says
    so.
  - The HTTP client's response history is left out unless you tick **Include
    HTTP response history**: saved responses hold response bodies and full
    request URLs, which can contain tokens or keys.

  A password or token in the [Settings sync](/docs/settings-sync) repository URL
  is always removed from the export. Runtime files are never included:
  downloaded language servers and debug adapters, the installed ShellCheck and
  shfmt, the Typst CLI, `jdtls` workspaces, the `sync/` folder, the lock files,
  the MCP endpoint file with its live token, and the session log.
- **Reset to Defaults** (the window's footer, or Settings → Advanced) asks
  first, and **Cancel** is the default button. The dialog lists what is reset
  and what is kept, and names the backup it is about to write.
  - Reset: appearance, editor and language settings, the keymap choice (back to
    Emacs), tool and server commands, AI and language-server options, external
    tools, TODO patterns, the toolbar layout, and plugins (turned off).
  - Kept: AI API keys, the Sync connection, saved Remote sites, text zoom, your
    custom shortcuts (stored with the keymap you made them in; shortcuts of
    macros, external tools and run configurations come along to the default
    keymap), snippets, templates, abbreviations, macros, and the personal
    dictionary. A Local History limit that is higher than the default is kept
    too, so a reset never deletes revisions.

  Before anything is reset, the current preferences are saved beside
  `settings.json` as `settings.json.before-reset-<date>.bak`, and the status bar
  names that file. If the copy cannot be written, nothing is reset.
- **Import Configuration…** (Settings → Advanced, or *Configuration: Import
  from Zip*, `config.import`) reads a zip made by Export Configuration. Its
  dialog lists the parts: preferences, the keymap and key bindings, external
  tools, snippets, file templates, abbreviations, the personal dictionary,
  macros, your own themes, Remote sites, bookmarks, notes and breakpoints, and
  plugins. Each part says whether the zip has it and what it would replace,
  add and remove here. Untick a part to keep yours. Bookmarks, notes and
  breakpoints (they name files by the other computer's paths) and plugins
  (they run code) start unticked.
  - A part you import replaces yours as a whole. A blank AI API key in the zip
    keeps the key you have.
  - Window layouts, recent files, projects, trusted folders and Local History
    belong to the other computer and are never imported; the dialog says how
    many files it leaves out.
  - Before anything is replaced, your current configuration is copied to
    `backups/` in the config folder.
  - Preferences, key bindings, external tools, snippets, templates,
    abbreviations and the dictionary apply in every open window at once.
    Macros, themes, Remote sites, bookmarks and plugins apply after a restart,
    which the last dialog offers with **Restart Now**.
  - A file that is not a zip, a zip with no `settings.json` at its top, an
    entry outside the config folder, a zip larger than an export would be, or
    an export from a newer Editora is refused, and nothing is imported.
- **Restore from Backup…** (Settings → Advanced, beside **Reset to Defaults**)
  lists those backups, newest first, in a picker (type to filter, Enter to
  choose, Esc to cancel), and puts the one you pick back. The
  settings in force are saved as a new backup first, so a restore can be undone
  the same way.

## More

- Keymaps and rebinding: [Keymaps & keybindings](/docs/keymaps).
- Themes, fonts, and zoom: [Themes & fonts](/docs/themes-fonts).
- Snippets and templates: [Snippets & templates](/docs/snippets-templates).
- The same snippets, abbreviations, templates, dictionary, and shortcuts on
  every computer: [Settings sync](/docs/settings-sync).

Full internals live in the
[README](https://github.com/adriandeleon/Editora/blob/master/README.md).
