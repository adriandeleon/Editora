# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Performance

- **Opening the first file of a language no longer freezes the editor for a beat.** Starting a language server
  (forking the jdtls/pyright/tsserver process, scanning `PATH`, writing the process ledger) ran on the UI thread.
  It now happens on a background thread — the editor stays responsive while the server comes up, and the loading
  indicator already covers the wait. (#407)
- **Language-server updates aren't sent twice per keystroke.** After a trigger character (e.g. `.`), the editor
  materialized and pushed the whole document to the server both on the completion trigger and again on the
  debounced edit pulse — the same version twice. It now skips a resend when the document hasn't changed since the
  last one. (#553)
- **Editing above a bookmark, note, or breakpoint no longer stutters.** When an edit shifted a marker's line
  (e.g. holding Enter above a bookmark), the editor did a blocking `bookmarks.json`/`notes.json` write plus a
  full tool-window tree rebuild on the UI thread — once per newline. That persist is now coalesced to a single
  write after editing settles; explicit actions (toggle/add/reorder) still save immediately. (#551)
- **The Structure outline no longer rebuilds while it's closed.** It re-split the whole document and rebuilt its
  tree on every edit (debounced) even when the tool window was hidden. It now defers the rebuild while closed and
  does it once when the window is reopened. (#549)
- **The File Information panel no longer slows down typing.** It re-counted words (a whole-document scan) on
  every caret move and re-materialized the whole document on every keystroke — even when the panel was closed.
  It now rides the debounced change stream, counts words only on an actual edit, reads only the character under
  the caret (not the whole file), and does nothing at all while its tool window is hidden. (#547)
- **Zooming text is much smoother.** A text zoom (`Ctrl`+wheel, `Ctrl`+`=`/`-`/`0`, the status-bar `−/+`) reran
  the entire settings-apply cascade per notch — re-swapping the editor-theme stylesheet (a full-scene CSS
  reapply) and re-running ~20 feature `applySupport()` calls (Git, LSP, previews, …), none of which change on a
  font zoom. Zoom now re-applies only the fonts and per-buffer view, so a Ctrl+wheel gesture no longer churns
  the scene stylesheet or re-detects every language server. (#545)
- **Moving the caret or typing in a large file is much lighter.** The status bar recomputed the file size — which
  scans and copies the whole document — on every caret move and keystroke (and re-materialized the whole
  document per keystroke besides). The size now updates only when the file actually changes, debounced, so
  arrow-keying and typing no longer allocate the entire document on the UI thread. (#542)

### Changed

- **Bookmarks and Personal Notes now group by project, so they don't appear/disappear when you switch projects.**
  Each panel always shows the **General** (no-project) group and the **current project**, and a new **"Show all
  projects"** toggle reveals every other project's bookmarks/notes too — grouped by project, and you can jump to
  any of them. File rows now use the real file-type icons and project headers are colored. (#565)
- **The Git Log tool window is colored and uses real file icons.** Each commit's short hash reads in the accent
  color, and the changed-files list colors each row by its status (added/modified/deleted/renamed) with the
  actual file-type icon — matching the Commit tool window and Project tree — instead of flat text and a generic
  sheet icon. (#562)
- **The Run, External Tools, and Debug consoles now color error output.** A program's stderr (e.g. a Python
  traceback) is tinted so it stands out from normal output, instead of appearing in the same plain color.
  (#558, #560)
- **The command palette now shows commands that can't run in the current context, grayed out**, instead of
  hiding them. A command for a disabled feature (e.g. Git commands while Git is off) is listed with its
  keybinding but dimmed and non-actionable, so you can see it exists. (#532)

### Fixed

- **Two windows connected to different SFTP servers no longer strand each other's remote files.** With more than
  one window open, the last one to connect took over remote-path resolution, so the first window's remote recent
  files and session paths stopped resolving — and closing the second window broke them for the first even while it
  was still connected. Each window's SFTP engine is now registered independently, so every open connection keeps
  resolving its own paths. (#436)
- **Undo inside a snippet no longer half-reverts a repeated field.** When a snippet field appears more than once
  (e.g. `$1 = $1;`), typing into it mirrored the text to every occurrence — but each mirror was a separate change,
  so one Ctrl-Z reverted only the mirror and left the field half-reverted (`x = ;`), cancelling the snippet. The
  field edit and its mirrors are now applied as a single change, so one Ctrl-Z reverts them together and the
  document stays consistent. (#415)
- **A keyboard config synced between macOS and Windows/Linux no longer double-binds your custom shortcuts.**
  Custom keybinding overrides were kept in a single map, but a chord is modifier-specific (Cmd on macOS, Ctrl
  elsewhere) — so a shortcut rebound on a Mac and then opened on Windows stayed bound to *both* your new chord and
  the original default. Overrides are now stored per platform, so each OS uses (and only sees) its own. Existing
  configs migrate in place to the platform they're opened on. (#439)
- **Taking your time over an SFTP host-key fingerprint no longer times the connection out.** When connecting to a
  new server, the trust-on-first-use dialog asks you to compare the host-key fingerprint — but the connection's
  handshake timeout kept counting while the dialog was open, so a careful comparison could time out from under you.
  The time the dialog is on screen is now excluded from the handshake deadline; a genuinely unreachable host still
  fails at the normal timeout. (#486)
- **The completion popup now offers snippets whose trigger isn't a plain word.** Typing `#in` in C/C++ (or `?xml`,
  `[PSCustomObject]`, …) never surfaced the matching `#include`/`#ifndef`/… snippet in the autocomplete popup — the
  popup only ever looked up the identifier run after the last symbol (`in`), which no snippet is registered under, so
  those had to be expanded with Tab. The popup now matches snippets on the whole token before the caret while words
  and language-server items keep the identifier prefix, and accepting one replaces the full token (never `##include`).
  (#446)
- **Local File History keeps garbage-collecting after a failed snapshot.** If writing a history snapshot to disk
  failed, the internal completion callback was dropped, which left history GC disabled for the rest of the session
  (old revision blobs then accumulated on disk). Failures are now handled and logged, and GC keeps running. A few
  related background tasks (the log tail, the project file-watcher, a starting language server) were also hardened
  so an unexpected error can't silently stop them. (#556)
- **Text zoom now works inside Diff views.** `Ctrl`+`=`/`-`/`0`, the status-bar `−/+`, and `Ctrl`+mouse-wheel
  resize the font when comparing two files or a file against Git, the same as in a normal editor. (#533)
- **Text zoom now scales the Welcome page too.** (#540)
- **The Commit panel now appears when a file changes outside Editora.** If another program (a terminal `git`
  command, another editor, a build) modified files under the repo while Editora was focused, the Commit stripe,
  Git status, and gutter change bars didn't update until you clicked away and back. They now refresh as soon as
  the project tree sees the change. (#529)

### Changed

- **The Maven / Gradle / npm / Cargo / Go task panels now open on the right by default** instead of the bottom
  (a better fit for a vertical task list). A panel you've already moved keeps its side. (#531)
- **The gutter fold arrow is 35% larger** — easier to see and click. (#530)

## [0.9.6] - 2026-07-17

### Security

- **Your AI API key is no longer sent in cleartext to a remote server.** If you pointed an AI provider at a
  plain-`http://` endpoint on another machine, Editora attached your key (an `Authorization: Bearer` token, or
  Anthropic's `x-api-key`) with no check on the address, so the credential crossed the network unencrypted.
  Editora now refuses — before connecting — to attach a key to a non-loopback `http://` host, with a clear
  message to use an `https` endpoint or a loopback address. Plain-http *loopback* (LM Studio, Ollama, vLLM on
  `127.0.0.1`) is untouched, since that is the intended local-inference path and never leaves the machine, and
  a keyless local server is unaffected. (From the deferred backlog.)

- **Switching AI provider no longer leaks the previous provider's API key to the new endpoint.** The API key
  was a single field shared by both AI providers, so configuring Anthropic and then switching to the
  OpenAI-compatible provider (a local model, or a hosted host like OpenRouter/Together/Groq) sent your
  Anthropic key — over plain http in that provider's default configuration — to a server that had no business
  seeing it, unprompted on every inline-completion pause. The reverse leaked too. Keys are now stored
  per-provider: the Settings key field shows and edits only the currently-selected provider's key, and a key
  configured for one provider is never sent to another's endpoint. An existing key is migrated onto the
  provider that was active when it was saved. (From the deferred backlog.)

- **An SFTP password no longer lingers in memory for the whole session.** After authenticating, Editora kept
  the password on the SSH session's identity list until you disconnected — potentially hours — so a memory dump
  of a long-running session could recover it. It is now dropped as soon as the handshake completes, since a
  password is only needed to authenticate. (A password only needs one string copy at the SSH library's
  boundary, which its API doesn't let us zero; this shrinks how long that copy is reachable from "until
  disconnect" to "during the handshake".)

- **Opening a terminal in an untrusted repo can no longer run its commands (Windows).** "Open Terminal Here"
  built a `cmd.exe` command line with the folder path spliced in (`cmd /k cd /d <dir>`), so a repository
  shipping a directory named with a shell metacharacter — `&`, `|`, `^`, all legal in Windows folder names —
  executed arbitrary commands the moment you opened a terminal there. The folder is now handed to the child
  process as its working directory, where no shell parses it, instead of being placed in any command line.
  (macOS and Linux were never affected — they exec the terminal launcher directly, with no shell. "Reveal in
  Explorer" was never affected either: `explorer.exe` is launched directly and runs no subcommand.)

- **SFTP connections now verify the server's host key.** Editora accepted *any* key, so anyone positioned
  between you and your server — hostile Wi-Fi, a compromised router, DNS spoofing — could impersonate it
  silently, and with password auth Editora simply handed them the password, along with the contents of every
  file you opened or saved. Editora now checks the key against `~/.ssh/known_hosts` (the same file `ssh` uses,
  so a host you have already accepted at the terminal connects without a prompt). A host you have never
  connected to asks you first, showing its fingerprint to compare — and a host whose key has *changed* is
  refused outright, without offering a button that waves it through.


- **The exported configuration archive is no longer readable by other users.** Settings → Advanced → "Export
  Configuration" writes a zip of the config directory into your home folder — including `settings.toml`, and so
  the AI provider's API key, plus your private notes. The archive was created world-readable, which undid the
  lock-down of the files it copies. The session log (`editora-session.log`), which records your file paths and
  error text, was the same. Both are now owner-only, from one shared helper. (From a per-feature audit of the
  Appearance / Interface / Tool Windows / Advanced pages.)


- **Your Anthropic API key is no longer sent to a local or third-party AI endpoint.** `ANTHROPIC_API_KEY` is
  exported on most developer machines, and Editora fell back to it for *any* provider — so selecting the
  OpenAI-compatible provider (the local LM Studio / Ollama path, which needs no key at all) attached that key
  as a bearer token to whatever endpoint was configured. The Settings key field was empty, so nothing on
  screen suggested a credential was in play, and inline completion sent it on every idle pause while typing.
  The environment fallback is now only used for the provider the variable belongs to. (From a per-feature
  audit of the AI pages.)

- **Config files are no longer readable by other users on the machine.** They were written with the default
  umask — mode 0644 in a world-traversable config directory — and `settings.toml` holds the AI provider's API
  key, a billable credential, while `notes.json` holds the user's private notes. Every config file is now
  owner-only, applied when the file is created so it is never briefly readable, and rewriting a file left
  world-readable by an earlier version tightens it. (From a per-feature audit of the AI pages.)


- **The MCP server's access token is no longer readable by other users on the machine.** It was written with
  default permissions (world-readable) into a world-traversable config directory, and that token is the only
  thing protecting the MCP tools — which can run any editor command and read or write any file. It is now
  owner-only, and the token is compared in constant time. (From a per-feature audit of Typst + MCP.)

- **The HTML live-preview server no longer serves files outside the previewed file's folder via a symbolic
  link.** Its path-traversal guard was purely lexical, so a symlink inside the folder that pointed elsewhere
  (common in real project trees) could be fetched by the previewed page — an arbitrary-file read. The guard now
  resolves symlinks and rejects anything whose real target leaves the folder. (From a per-feature audit of the
  Web feature.)

- **Declining a plugin's capability prompt now disables it.** When updating an already-installed plugin, the
  new code is written to disk before the "here's what it can do" prompt; declining used to leave the plugin
  enabled, so the rejected update ran on the next launch. Declining now disables the plugin. (From a per-feature
  audit of the Plugins feature.)

### Fixed

- **Undoing a Format Document (or an auto-import) is now a single step.** A multi-line reformat applied each of
  the language server's edits separately, so it took one Ctrl-Z per edit to undo. The whole set is now one undo
  step. (#415)
- **Previewing a Typst document no longer churns the project tree.** Each render writes and deletes a
  throwaway `.typ` file beside your document; on some systems the project tree's file watcher reacted to that
  by rebuilding itself on every render. It now ignores Editora's own temporary render files. (#465)
- **Error squiggles no longer briefly underline the wrong line while you type.** After an edit, the diagnostic
  underlines, scrollbar ticks, and minimap marks stayed on their old line numbers until the language server
  re-reported (a fraction of a second later), so inserting a line above an error moved the squiggle onto
  unrelated text. They now clear on the edit and reappear, correctly placed, on the next update. (#417)
- **A PlantUML file with several `@startuml` diagrams no longer hides the extras silently.** Only the first
  diagram was shown, with no sign the others existed; the preview now shows the first plus a "N more
  diagram(s) not shown" note. (#459)
- **Find References is faster on symbols with many references.** Building the results list re-scanned the
  whole file once per reference; on a heavily-used symbol in a large open file that caused a visible stall.
  Each file is now scanned once. (#471)
- **Closing the window that started the MCP server no longer stops MCP for the other windows.** Ownership now
  moves to a surviving window (keeping the same port and token, so a connected agent stays connected); the
  server is stopped only when the last window closes. (#463)
- **A stale MCP endpoint file left by a crash is now cleaned up at launch.** If Editora exited hard while the
  MCP server was running, `mcp-endpoint.json` lingered — pointing an MCP client at a dead port with a
  live-looking token. Editora now stamps the file with its process identity and removes a stale one on the
  next launch. (#464)
- **Editing a Mermaid diagram no longer piles up background renders.** Each pause while typing in a `.mmd`
  file queued another headless-Chromium render (~4 s each) even though only the latest result is shown, so a
  burst of edits could back up many seconds of wasted work. Superseded renders are now skipped, so only the
  diagram you're actually editing is rendered. (#458)
- **The Typst preview no longer retains gigabytes of page images.** Its render cache was bounded by document
  count, but each entry is a whole multi-page document — so a 40-page document could retain ~570 MB, and
  editing it (each keystroke a new render) accumulated several times that. The cache is now bounded by total
  pages, keeping memory in check. (#461)
- **A Typst compile error now shows your file name, not an internal temp filename.** Previously every typo in
  the Typst preview reported an error against `.editora-typst-<uuid>.typ` (the throwaway file Editora renders
  through). The buffer's name is now shown instead; line and column were already correct. (#462)
- **A failed settings/session save is now surfaced instead of vanishing silently.** If Editora couldn't write
  `settings.toml` or the session state (full disk, read-only config directory, permissions), a setting change
  or the session on quit was lost with no indication. Editora now shows a status message on the failure. (#418)
- **The JavaScript debug adapter you install through Editora is now the one that's used.** After installing
  vscode-js-debug via the in-app installer (or `install-js-debug.sh`), Editora could silently keep using an
  older VS Code extension copy instead. Editora's own installed adapter now takes precedence. (#474)
- **Breakpoints in closed files are now hit.** Setting a breakpoint, closing the tab, then starting a debug
  session left the breakpoint inert — the program ran straight past it. Editora now sends the breakpoints of
  files with no open tab to the debugger too (re-anchored against the file's current text), matching VS Code
  and IntelliJ. (#473)
- **Accepting a completion that begins with its trigger character no longer duplicates it.** For a language
  server that advertises a trigger character which is part of its own insert text (phpactor's `$`, shell
  variables), typing `$` and accepting `$user` inserted `$$user`. The accept now honors the server's
  `textEdit.range` when present, and otherwise consumes the overlapping trigger character, so it yields
  `$user`. Ordinary identifier completions are unaffected. (#445)
- **Format Document now works for JSON, CSS, and HTML.** The language servers for these files implement
  formatting but only advertise it when asked with a specific option, which Editora wasn't sending — so Format
  Document reported "not supported" even though the server would have formatted the file. Editora now sends that
  option, and formatting works on `.json`, `.css`, and `.html`. (From the deferred backlog.)

- **A deleted project no longer reappears if a session write was in flight at exactly the wrong moment.**
  Deleting a project deletes its session file and cancels any queued write of it — but if the background writer
  had already picked up that write, the cancel couldn't stop it, and the file was re-created (and the "deleted"
  project came back the next time its folder was opened). The cancel now reliably suppresses an
  already-in-flight write. (From the deferred backlog.)

- **"Add to Dictionary" now clears the misspelled squiggle in every open window, not just the current one.** The
  personal dictionary is shared across windows, but adding a word only refreshed the spell check in the window
  you added it from — other windows kept underlining the word until you touched a setting there. The change now
  fans out to every window immediately. (From the deferred backlog.)

- **A whole-line personal note re-attaches to the right line after an external edit.** A note added to a line
  (rather than a selection) stored no surrounding context, so after the file changed outside the editor it
  latched onto whichever identical line was nearest — exactly wrong for the lines these notes usually mark
  (`    }`, `});`, a repeated `import`). Line notes now capture the surrounding lines as context and the
  relocator scores partial context matches, so the note follows its real line. (From the deferred backlog.)

- **A personal note whose text is extremely common no longer jumps to the wrong place after an edit.** In a
  very large file where a note's text occurs thousands of times, an edit could relocate the note far up-file to
  the wrong occurrence instead of admitting it couldn't be placed. Such a note is now marked orphaned (which is
  honest and recoverable) rather than silently moved somewhere wrong. (From the deferred backlog.)

- **A personal note on a long selection now highlights the whole selection, not just the first 200
  characters.** Notes cap the amount of selected text they store, and the highlight was drawn over that capped
  text — so a note taken on a 500-character selection was highlighted over only its first 200. Notes now record
  the full selection length and highlight the complete span. (From the deferred backlog.)

- **CSV files using classic-Mac (CR-only) line endings are parsed into rows correctly.** A file whose lines end
  with a lone carriage return had its records fused together (`a,b⏎c,d` became one row `a, bc, d`, with the `b`
  and `c` glued into a cell that exists nowhere in the file). The CSV parser now treats a lone CR — as well as
  LF and CRLF — as a record separator, matching RFC 4180 and other tools. (From the deferred backlog.)

- **The crontab preview now shows all the requested next-run times for infrequent schedules.** A leap-day job
  (`0 0 29 2 *`) or a yearly one showed only its first upcoming run — the forward scan shared one ~4-year
  budget across every result, so it ran out before finding a schedule's second occurrence. The scan budget is
  now per result, so all three next-run times appear (an impossible schedule like Feb 31 still stops rather
  than looping). (From the deferred backlog.)

- **The SSH config preview now reflects overrides from earlier `Host` blocks.** SSH uses the *first* value it
  finds for each option, so an earlier `Host *` (or matching wildcard) block can fix `User`/`Port`/etc. before
  a later host-specific block does. The preview summarized each block from its own lines only, so it could
  state the exact opposite of what `ssh` will do. For a block that names a single concrete host, the summary
  now resolves each value across the whole file (matching `ssh -G`); a wildcard block still shows its own
  declared values. (From the deferred backlog.)

- **A folder named like a build file no longer triggers a spurious "can't read the build file" error.** The
  build-tool detector walked up looking for markers like `pom.xml`/`package.json`/`go.mod` and accepted a
  *directory* of that name as a match, then failed trying to parse it — so a project that merely had a folder
  called `pom.xml` somewhere up the tree showed a build stripe and a parse error for a build file it doesn't
  have. Build markers are now matched only as files. (From the deferred backlog.)

- **Disabling a language server now clears its errors from the Problems window.** Turning a server off in
  Settings (e.g. unchecking Java) stopped the server but left its already-reported errors listed forever — no
  server remained to retract them, and closing the tab missed them too. Disabling a server now clears its
  files' diagnostics (Problems entries and the editor squiggles) as it shuts down. (From the deferred backlog.)

- **The Problems window now sorts the active file to the top even when it's reached through a symlink.** The
  diagnostics were stored under the path the language server reported (often a symlink path like `/tmp/…`),
  while the active-file marker used the resolved real path (`/private/tmp/…`), so on macOS — and for any
  symlinked project directory — the two never matched and the file you were editing never floated to the top.
  Diagnostics are now keyed by the resolved real path, so the sort (and the tab-close clear) line up. (From
  the deferred backlog.)

- **An HTTP request whose body is missing its blank line now warns instead of silently sending nothing.** In a
  `.http` file the body must be separated from the headers by a blank line; if you forgot it, a JSON body like
  `{"a": 1}` was parsed as a header (it has a colon) and the request went out with no body — a failure that
  looked like the server's fault. Editora now recognizes that a line in the header block isn't a real header
  and shows a clear warning atop the response ("a request body must be separated from the headers by a blank
  line") rather than guessing where the body starts. (From the deferred backlog.)

- **A hung AI endpoint no longer wedges every later AI request.** If an endpoint returned response headers and
  then stopped writing (a stalled proxy, a crashed local server that keeps the socket open), the streaming read
  blocked forever — and because AI requests run one at a time, every subsequent commit-message, explain, or
  inline completion queued behind it permanently until you restarted. The stream now gives up if no data
  arrives for the response-timeout window and frees the worker; a healthy slow generation keeps streaming, since
  any activity resets the clock. (From the deferred backlog.)

- **The diff viewer no longer garbles non-UTF-8 files.** When comparing a Latin-1 or UTF-16 file against its
  committed version (Compare with HEAD / a branch / a commit, or the Commit window's staged/unstaged rows), the
  git side was force-decoded as UTF-8 — so every accented character came out as mojibake, and because the
  working side read correctly, the whole file showed as one giant spurious change. Both sides now decode a git
  blob the same way the editor reads the file (a byte-order mark wins, else the file's `.editorconfig` `charset`,
  else UTF-8), so the text is right and only real edits show. (From the deferred backlog.)

- **Rebinding a prefix chord no longer silently breaks every shortcut under it.** Recording a lone `C-x` in
  Settings → Keymaps drew no warning yet dead-ended `C-x C-s` (save), `C-x C-f` (find file), `C-x g` (commit),
  `C-x C-c` (quit) and the rest — the editor only checked for an exact-chord clash, and `C-x` alone matched
  nothing. The conflict check now also finds prefix collisions in both directions (a new chord that would
  shadow existing multi-key ones, and a shorter existing chord that would make the new one unreachable) and
  lists the affected shortcuts in the confirm dialog. (From the deferred backlog.)

- **The HTTP client no longer silently drops a request header — a puzzling `401` becomes diagnosable.** A
  header value the JDK client rejects (a token pasted from a terminal with a trailing newline, or a value
  with an embedded control character) was skipped without a word, so the request went out missing that header.
  `Authorization` was already trimmed, but any *custom* auth header (`X-Api-Key`, `X-Auth-Token`) was not, so a
  pasted key with a stray newline dropped it and the request went out unauthenticated. Every header's value is
  now trimmed of surrounding whitespace (spec-safe — servers strip it anyway), and any header still un-sendable
  (a restricted name, or an embedded control character — a header-injection attempt) is shown as a warning atop
  the response report instead of vanishing. (From the deferred backlog.)


- **Git now works for a project opened through a symlink.** `git rev-parse --show-toplevel` reports the repo's
  real path, while an open file keeps its as-opened (symlinked) path — so for a repo reached through a symlink
  (a macOS `/tmp` project, or a symlinked work directory) the two never matched, and Compare-with-HEAD, stage,
  unstage, revert, and file history all reported "not in repo" or did nothing. Both paths are now
  symlink-resolved before comparing. (From the deferred backlog.)


- **A user theme that defines its colours by reference is no longer read as light.** A theme file can set up a
  palette and point the standard tokens at it (`-color-bg-default: -color-dark-1;`) — ordinary CSS authoring.
  Editora only looked for a literal hex value, found none, and fell back to defaults: a dark theme got dark
  chrome around a glaring white editor with the light syntax palette. It now follows the reference. Relatedly,
  a token is no longer confused with a longer one that merely starts with it (`-color-bg-default-hover` was
  being read as the background).

- **A stray or half-written `.css` file is no longer offered as a theme.** Anything ending in `.css` in the
  themes folder appeared in the picker, so an empty or unfinished file became a selectable theme that stripped
  the app's styling when chosen. A theme file now has to actually declare something.

- **"Reset to Defaults" now actually resets your settings.** It restored 23 of 181 preferences — a
  hand-written list of setters that stopped being updated years of features ago — so it silently left roughly
  87% of your settings exactly as they were, including every AI, language-server, debugger, Mermaid, TODO,
  preview and build-tool option, plus Simple mode, the keymap and the UI language. It even left the AI API key
  in place. It is now driven off the same properties the settings file is written from, so a new setting is
  covered the day it's added; text zoom and your keybindings are still deliberately preserved. (From a
  per-feature audit of the Appearance / Interface / Tool Windows / Advanced pages.)


- **The crontab preview described the day-of-month/day-of-week rule backwards.** `0 0 13 * 5` was explained as
  "on day 13 of the month **and** on Friday" — i.e. Friday the 13th. It actually runs on *every* Friday and
  *every* 13th, which is cron's most famous gotcha and the reason the preview exists. The next-run times shown
  in the same row already said so, so the preview contradicted itself on screen. A day field covering every
  value made it worse: `0 0 1-31 * 5` claimed "on Friday" while running daily. The English is now derived from
  the same rule the matcher uses, and verified against croniter. (From a per-feature audit of the previews.)

- **A weekend crontab was reported as a syntax error.** `0 0 * * 6-7` (Saturday–Sunday) and `0 0 * * 5-7` were
  rejected with "range start after end", because 7 was folded to Sunday's 0 before the range was read, turning
  6–7 into 6–0. Real cron expands the range first and folds afterwards; Editora now does too. (From a
  per-feature audit of the previews.)

- **Turning a preview off could strand a file in a preview it couldn't leave — or evict one that still
  worked.** Each preview asked "is this file my format?" rather than "does this file still have a preview?".
  So switching off structured data stranded an XML file, whose tree rides that same setting, in a preview with
  nothing left to render and no toggle to escape with; and it also bounced a GitHub Actions workflow — which is
  YAML, but has its own preview — out of a view mode that is remembered per file. (From a per-feature audit of
  the previews.)


- **A breakpoint (or a bookmark) on one of two adjacent identical lines could vanish for good.** Both are kept
  in a line-keyed map and are re-anchored to their saved line text when a file is opened, so when an edit made
  outside the editor shifted two same-text lines — two `});` in a row, routine in real code — the upper one
  re-anchored down onto the line the lower one still matched exactly, and the second silently overwrote the
  first. Both lines still existed; one marker just disappeared. The self-heal then wrote the collapsed set
  back to disk, so it never came back — and for a bookmark, the note went with it. Each marker now resolves
  against the lines still free, so two never collapse onto one. (From a per-feature audit of Debugging.)

- **Debugging a Java program with a quoted argument passed the wrong arguments.** Program arguments are shared
  with Run, which passes `"hello world" second` as two arguments; the Java debug launch re-joined them into one
  space-separated string, so the program received three. Java only — Python and JavaScript were always correct,
  and the fix is what those already did. (From a per-feature audit of Debugging.)

- **Toggling exception breakpoints during a debug session did nothing, while reporting that it worked.** The
  filters were only read once, when the session started, so a later change updated a field nothing would read
  again: the program ran straight through an uncaught exception without suspending, even though the status bar
  said exception breakpoints were on. Confirmed against a real debugpy. (From a per-feature audit of Debugging.)

- **The debugger's execution-line highlight could land on a file that has nothing to do with the stack frame.**
  When a frame's source isn't on disk — a frame inside a dependency, or a path baked in by a build on another
  machine — opening it fails quietly, and the "you are here" line was then painted onto whatever file happened
  to be in front, scrolling it to an arbitrary line. (From a per-feature audit of Debugging.)

### Added

- **Logpoints and disabled breakpoints can now actually be created.** Both were fully built — they persisted,
  re-anchored, reached the adapter, and had their own gutter glyphs — but nothing in the app could set them:
  Edit Breakpoint only ever asked for a condition, despite its own description promising "condition, log
  message". It is now a form with all three (condition, log message, enabled). A log message turns a breakpoint
  into a logpoint: the debugger logs it and never suspends. (From a per-feature audit of Debugging.)


- **Turning a language server off in Settings now actually stops it, and changing its command now takes
  effect.** Neither did: the code that shuts down one server's sessions could never match them, so a disabled
  server kept running, a re-pointed command kept using the old binary (while its Settings row turned green as
  if it had applied) and leaked the old process, and toggling Debug never reloaded the Java server with its
  debug plugin — the thing that makes Java debugging work at all. (From a per-feature audit of LSP.)

- **A language server that crashes no longer takes every language feature down silently.** Its session stayed
  cached and looked healthy, so completion, hover, go-to-definition and diagnostics all quietly did nothing
  while the status bar still named the server, and nothing ever restarted it. A dead server is now detected and
  dropped, so the next file you open starts a fresh one. (From a per-feature audit of LSP.)

- **A language server that never finishes starting no longer leaks memory or spins forever.** The handshake had
  no timeout, so the loading indicator never stopped, and every keystroke queued another full copy of the
  document — about a gigabyte after a few minutes of typing in a large file, against a 2 GB heap. (From a
  per-feature audit of LSP.)

- **Installing Typst now actually fixes the preview.** A failed render was cached against the document's text
  forever, so after installing the tool — which re-renders every open preview — the old "typst not found" was
  served straight back. Two documents with identical text in different folders also showed each other's
  render, and editing a file they `#import`ed never refreshed the preview. (From a per-feature audit of Typst +
  MCP.)

- **Typst works when its path contains a space** — `/Users/John Smith/…` was split into two arguments and
  every render failed, which also broke the installer for anyone whose home directory has a space. And the
  preview is no longer reported as ready for a command that can't actually render. (From a per-feature audit
  of Typst + MCP.)

- **Exporting a Typst document now reports the file it actually wrote.** Typst renames `report.png` to
  `report-1.png` — even for a single page — so the status bar named a file that didn't exist; an export that
  produced nothing also reported success. (From a per-feature audit of Typst + MCP.)

- **Pressing Enter after a Typst list item numbered beyond ~19 digits no longer throws.** (From a per-feature
  audit of Typst + MCP.)

- **A named PlantUML diagram now renders — and exports.** PlantUML names its output after the diagram
  (`@startuml myclassdiagram` writes `myclassdiagram.png`), but Editora looked for a fixed file name, so any
  named diagram showed a bare "render failed" in the preview — indistinguishable from a broken one — and
  exporting it reported "Exported to …" while writing **no file at all**. Naming a diagram is idiomatic
  PlantUML and required for multi-diagram files. (From a per-feature audit of Mermaid + Diagrams.)

- **Installing Mermaid no longer leaves your diagrams showing "render failed".** A failed render was cached
  against the diagram's text forever, so after installing `mmdc` — which re-renders every open preview — the
  old error was served straight back. The diagram stayed broken until you edited it or restarted. The same
  applied to changing a tool's path in Settings, and to DOT/PlantUML. (From a per-feature audit of Mermaid +
  Diagrams.)

- **Mermaid's linter is no longer reported as installed when it isn't.** The default maid command runs through
  `npx`, which always launches — so on any machine with Node, maid showed as found, and every pause in typing
  fired a multi-second `npx` that could never produce a diagnostic. (From a per-feature audit of Mermaid +
  Diagrams.)

- **A diagram tool whose path contains a space now works** — `C:\Program Files\Graphviz\bin\dot.exe`, or
  anything under `Application Support`, was split into two arguments and failed with "No such file or
  directory", even though Settings offers a Browse… button. (From a per-feature audit of Mermaid + Diagrams.)

- **Toggling a setting no longer stalls diagram linting and export for several seconds.** Every settings or
  theme change re-probed for the tools — including the ~6.5-second `npx` probe — on the same single thread
  that serves live linting. Detection is now only re-run when a path actually changes, and a failed render no
  longer spawns the linter when it isn't installed. Mermaid warnings also appear now, instead of being
  dropped. (From a per-feature audit of Mermaid + Diagrams.)

- **An external tool's output no longer lands in the wrong file.** The result was applied to whatever tab was
  active when the tool *finished*, not the one it ran on — so switching tabs during a format run replaced the
  entire contents of an unrelated file with the formatted output of the first, and reported success. The
  target file is now captured when the tool starts, and its output is dropped if the file changed meanwhile.
  (From a per-feature audit of External Tools.)

- **A command that waits for input no longer freezes external tools for the rest of the session.** A tool like
  `grep`/`jq`/`sort` configured without a stdin source waited forever for input that never came, and its
  timeout could never fire — blocking every later tool run. Timeouts are now genuinely enforced (they were
  unreachable for *any* command that outlived them), and a runaway tool's output can no longer exhaust memory.
  (From a per-feature audit of External Tools.)

- **File paths and selections with spaces or apostrophes now reach the tool intact.** `$FilePath$` for
  `~/My Docs/a.txt` arrived as two separate arguments, and no amount of quoting fixed it — an apostrophe in
  the path (`~/Bob's Files/`) split it anyway *and* deleted the apostrophe. An empty `$SelectedText$` used to
  vanish entirely, shifting every argument after it. (From a per-feature audit of External Tools.)

- **"Run last tool again" no longer runs a tool you deleted, disabled, or edited** — it re-ran a stale copy,
  including the old command after you'd fixed it. (From a per-feature audit of External Tools.)

- **Editing external tools in one window no longer deletes tools added in another.** (From a per-feature audit
  of External Tools.)

- **Two tools whose names differ only in punctuation no longer collide**, silently making one unreachable and
  stealing its shortcut; and a tool that legitimately produces no output is no longer reported as having
  failed. (From a per-feature audit of External Tools.)

- **Opening a file that happens to contain the same text as another no longer steals that file's personal
  notes.** Notes were re-homed onto a file whose *content hash* matched — which is not identity: a
  `cp config.yaml config.backup.yaml`, a duplicated LICENSE, or the boilerplate `index.ts` in each package of a
  monorepo is enough. Merely opening the second file moved the first one's notes onto it and deleted them from
  the original, permanently. A content match is now only trusted when the original file is gone from disk —
  i.e. an actual rename, which is the case this exists for. (From a per-feature audit of Workspace.)

- **Local History no longer deletes revisions you named.** "Put Label" marks a deliberate restore point, but
  retention treated it as cache: past the 30-day age limit, or once 50 automatic revisions had accumulated
  (a few hours of editing with autosave on), your labelled revision was silently dropped and its content
  deleted. Labels — and the copy captured just before a file is deleted — are now exempt from the age limit,
  the per-file cap and the project size budget. (From a per-feature audit of Workspace.)

- **Local History no longer loses a revision, or deletes the content of one it just recorded.** Saving two
  files at once (or labelling during a slow save) could overwrite one revision with a list that never
  contained it, and the blob cleanup could run between a revision's content being written and it reaching the
  index — deleting the content of a revision that was about to be listed, which then displayed as an empty
  file. (From a per-feature audit of Workspace.)

- **A note you marked resolved no longer comes back as open work.** If the note's text was temporarily absent
  — a branch switch, a colleague's edit — its resolved mark was overwritten with "orphaned" and saved, and
  when the text returned it came back as active. (From a per-feature audit of Workspace.)

- **Release-candidate builds are now told when the final version ships.** `1.0.0` was considered *older* than
  `1.0.0-rc1`, so anyone running an RC never saw the update notice for the release it was a candidate for.
  (From a per-feature audit of Workspace.)

- **Gradle's "Load all tasks…" no longer misses tasks that have no description**, and no longer throws the
  list away the next time you save. Any task registered without a description — common in real build scripts,
  and the norm for Gradle's own "Other tasks" — was silently dropped from the enumeration, while the status
  line still reported the (short) count as a success. And because the task list was rebuilt from scratch on
  every save, tab switch and window focus, the ~90-second enumeration you just waited for vanished on the next
  Ctrl+S. (From a per-feature audit of Build Tools.)

- **Loading Gradle's task list no longer leaks it into another project.** Switching projects while the
  enumeration was running merged the first project's tasks into the second one's tree, where running one fails
  with "task not found". (From a per-feature audit of Build Tools.)

- **A build wrapper that isn't executable no longer breaks every build.** `mvnw`/`gradlew` are preferred over
  everything, including your configured command — but a wrapper checked out without its execute bit (a Windows
  clone, an unzip that dropped modes) was preferred anyway, and every run died with "Permission denied"
  instead of falling back to `mvn`/`gradle`. On Windows the wrapper is also found now: it was being launched
  by bare name, which is neither on PATH nor resolved against the project directory. (From a per-feature audit
  of Build Tools.)

- **A Bun project is no longer run with npm.** Only the legacy binary lockfile (`bun.lockb`) was recognized,
  so any project using Bun 1.2's default `bun.lock` fell through to npm. (From a per-feature audit of Build
  Tools.)

- **A build file that fails to parse now says so.** The tasks window showed "No build tool detected" — the
  opposite of the truth, since the tool's stripe is only there *because* the marker was found — and nothing
  reported the parse error. (From a per-feature audit of Build Tools.)

- **"Run custom…" no longer throws** if the build file is deleted or renamed while its prompt is open. (From a
  per-feature audit of Build Tools.)

- **The TODO tool window's edit actions no longer corrupt the line they rewrite.** Marking a TODO done (or
  setting its priority, or editing its text) re-emitted the whole comment, which could damage it two ways: a
  block comment's terminator was treated as part of the TODO's text, so editing the text dropped the closing
  `*/` and silently commented out everything below it; and a Markdown link right after the keyword was read
  as a `[tag]`, so re-emitting it inserted a space and broke the link — in a `TODO.md`, exactly where such
  lines live. HTML `-->` comments were affected too. (From a per-feature audit of TODO highlighting.)

- **Reopening a completed FIXME no longer turns it into a TODO.** Marking an item done overwrites its
  keyword, so nothing remembers whether it started as a FIXME, HACK or XXX — Reopen simply assumed TODO and
  silently downgraded it. Reopen now asks which keyword to restore. (From a per-feature audit of TODO
  highlighting.)

- **A TODO edit on a read-only file now says why nothing happened**, instead of appearing to do nothing at
  all. Log files and anything not writable on disk open in View mode — which is exactly the generated and
  vendored code a TODO scan turns up. (From a per-feature audit of TODO highlighting.)

- **Quick-adding a TODO pattern now matches case like the built-in ones do**, so a new `REVIEW` pattern no
  longer also flags the word "review" in ordinary prose. (From a per-feature audit of TODO highlighting.)

- **Jumping to the next/previous TODO no longer scans a file whose highlighting is deliberately off** (a
  large file), and a single stale mark can no longer blank every TODO highlight for a frame after a big
  delete. (From a per-feature audit of TODO highlighting.)

- **Keyboard macros now record Backspace, Delete and the arrow keys.** They were invisible to the recorder —
  the editor handles them itself and no keymap binds them — so they were silently dropped: recording "type x,
  Backspace, type y" replayed as `xy`, and the classic "go to line start, insert something, move down" macro
  replayed without ever moving. Home/End, Page Up/Down and the selection-extending variants (Shift-Down,
  Ctrl-Left) are captured too, with their modifiers. (From a per-feature audit of Macros.)

- **Recording a macro no longer captures what you type into the command palette or the find bar.** The
  capture hook saw every keystroke in the window regardless of what was focused, so recording a command via
  the palette also recorded the letters used to search for it — and replaying then typed them into your
  document. Only keys aimed at the editor are recorded now. (From a per-feature audit of Macros.)

- **A macro can invoke another saved macro again.** Doing so while recording was silently dropped, because
  the filter meant to exclude the record/replay commands also excluded every saved macro's own command.
  (From a per-feature audit of Macros.)

- **Renaming a macro no longer loses its keyboard shortcut** when the new name is a close variant of the old
  one (`build` → `Build`). (From a per-feature audit of Macros.)

- **Two macros can no longer end up sharing one command**, which made the older one unreachable from the
  palette and stole its shortcut. Names that differ only in punctuation (`my macro` / `my-macro`), or that
  are all punctuation, are now refused with an explanation instead of silently shadowing. (From a per-feature
  audit of Macros.)

- **"Replay N times" no longer freezes the window** when given an absurd count, and a macro reached from
  inside another replay no longer reports success while doing nothing. (From a per-feature audit of Macros.)

- **Pressing Enter at the very start of a Markdown file that begins with a blank line no longer does
  nothing.** The table-block finder threw an out-of-bounds exception there, and it runs on every Enter (and
  every Tab) in a Markdown buffer — so the keystroke was swallowed and an error went to the debug log. No
  table needed; any document starting with a blank line was enough. (From a per-feature audit of Markdown.)

- **A table cell containing a pipe is no longer destroyed by the next Tab.** A `\|` in a cell was treated as
  a column separator, so the cell was split in two and the table grew a phantom column — which bit hardest
  right after pasting CSV as a table, since that's exactly what produces `\|` cells. Reflow, Tab navigation,
  the row/column operations and the CSV round-trip all keep escaped pipes intact now, and such a cell is
  padded to its real width so the columns still line up. (From a per-feature audit of Markdown.)

- **Exporting Markdown to ODF (`.odt`) no longer produces a file LibreOffice and Word refuse to open.** A
  control character in the source — a form feed used as a page marker, or a stray escape/bell out of pasted
  terminal output — was written straight into the document's XML, which forbids those characters outright.
  The export reported success and the file was unopenable. (From a per-feature audit of Markdown.)

- **Making bold text italic no longer removes the bold.** Selecting `**bold**` and pressing italic (the
  format bar, `markdown.italic`, or `C-c C-s i`) matched the inner asterisk of the bold markers and stripped
  them, leaving `*bold*` instead of `***bold***`. (From a per-feature audit of Markdown.)

- **The Markdown linter no longer flags — and its auto-fix no longer deletes — hard line breaks.** Two
  trailing spaces are Markdown's `<br>`, allowed by markdownlint's own defaults; "Fix all" stripped them,
  silently reflowing addresses, poems and signature blocks into a single paragraph. Three or more trailing
  spaces are still flagged. (From a per-feature audit of Markdown.)

- **A `---` under a list item is a horizontal rule again, not a heading.** It was read as a setext heading
  titled with the list's last item, which then appeared in the Structure outline and in generated tables of
  contents. (From a per-feature audit of Markdown.)

- **Deleting a table's last data row no longer leaves the cursor on the `---` divider**, where typing broke
  the table. (From a per-feature audit of Markdown.)

- **Snippets whose prefix isn't a plain word now expand with Tab.** Expansion scanned back over letters,
  digits and `_` only, so at `#inc` it stopped on the `#` and looked up `inc` — which nothing is registered
  under. That left 42 bundled snippets unreachable from the keyboard: the c/cpp `#include`/`#ifndef`/`#define`
  family, the emmet `!` HTML skeleton, `?xml`, yaml `---`, ruby `->`, typst `=`, and PowerShell's
  `[PSCustomObject]`. Tab now tries the whole token before falling back to the word before the caret. (From a
  per-feature audit of Completion.)

- **Accepting a completion no longer immediately offers another one on the word you just completed.** The
  guard against re-triggering was cleared on the next frame — ~264 ms before the trigger it was meant to
  suppress — so it never suppressed anything: accepting `apple` grew a fresh `s` ghost, and accepting a
  language-server item re-opened the popup. Suppression is now tied to the accept's own edit, so it lasts until
  you type something. (From a per-feature audit of Completion.)

- **Inline completion no longer mangles the capitalization of what you typed.** Ghost text matched
  case-insensitively but appended the dictionary's own spelling to what you had typed, so an acronym at the end
  of a line (`APP`) was offered — and on Tab accepted — as `APPle`. A word is now only suggested when it would
  complete to a correctly-cased word. (From a per-feature audit of Completion.)

- **Completion ranking no longer depends on the order snippets happen to be declared in.** The comparator
  contradicted itself (two entries could each sort before the other), so identical candidates ranked
  differently depending on input order, and a snippet file with enough same-prefix entries could throw
  mid-keystroke. (From a per-feature audit of Completion.)

- **The completion popup no longer mis-highlights — or crashes on — a label containing a dotted capital İ.**
  The matched characters were located in a lowercased copy of the label, and lowercasing is not always
  length-preserving, so the emphasis shifted onto the wrong characters and could run past the label's end.
  (From a per-feature audit of Completion.)

- **A completion's auto-import is dropped when the file changed while it was being resolved.** Resolving an
  import is a round-trip to the language server, and its result used to be applied blind — so undoing the
  completion (or editing above it) meanwhile inserted the import in the wrong place, or for a symbol that was
  no longer there. Language-server completion results are likewise dropped now when an edit lands the caret
  back on the offset the request was made at. (From a per-feature audit of Completion.)

- **Your personal spell dictionary can no longer be silently lost.** `dictionary.txt` was rewritten by
  truncating it first (a crash or full disk mid-write left it empty), and a single malformed byte anywhere in
  it made Editora discard *every* word without a word — after which removing a word rewrote the file from that
  empty set, making the loss permanent. It's now written atomically and read leniently (a stray byte or a
  byte-order mark no longer costs you the file). (From a per-feature audit of Spell Check.)

- **Adding or removing a word in Settings → Spell Check now takes effect immediately** instead of only after
  toggling something else or restarting.

- **"Add to Dictionary" now clears the squiggle in every open tab**, not just the one you right-clicked in.

- **Restored tabs get their squiggles without needing a scroll or keystroke.** When several files asked for
  the same dictionary at once (session restore, a language switch), only the first was told when it finished
  loading — the rest silently showed no spell check at all.


- **Spell check no longer squiggles inside Markdown fenced code blocks.** The "skip ``` code fences" rule was
  never actually switched on (it was decided before the file's language was known), so opening a README put red
  squiggles on `sudo`, `cd`, `xzf` and friends inside its code blocks. (From a per-feature audit of Spell
  Check.)

- **Spell check no longer skips whole phrases joined by typographic punctuation.** A sentence using an em dash
  (`results—surprising—showed`), a smart apostrophe (`world’s`), or a non-breaking space had *every* word in
  that run silently left unchecked — and since macOS/iOS auto-substitute `--`→`—` and `'`→`’`, that's ordinary
  typed prose. Those characters are now understood as part of the word, and `don’t` is matched against the
  dictionary's `don't` rather than being reported as a misspelling. Non-breaking spaces (pervasive in pasted
  text, and required by French typography) now separate words properly.

- **Editora no longer builds the spell-check dictionary at startup when spell check is turned off** — it cost
  ~200 ms of CPU and ~1 MB of memory per language on every launch regardless of the setting.


- **A running plugin-defined shell command no longer delays quitting Editora** — it ran on a non-daemon thread
  that could hold the JVM open until the command (or its 2-minute timeout) finished; it's now a daemon thread.

- **HTML preview assets whose filename contains `+`, `%`, or a space now load.** The server decoded the URL
  path twice (and form-decoded `+` to a space), so `<img src="my+file.png">` 404'd; it now decodes once.

- **The live-preview server uses a bounded thread pool**, so a flood of long-poll connections from another
  local process can't grow threads without limit.

- **A `.http` `>>` response-save operator can no longer write outside the request file's folder** (a `../`
  path is now rejected).

- **macOS Option-key text input works again.** Typing an accented or special character with Option (é, ç, ∞,
  dead keys) was being swallowed by the editor even when Option wasn't a bound shortcut; unbound Option
  characters now reach the document, while bound Meta chords still don't type a stray glyph. (From a
  per-feature audit of the keymap subsystem.)

- **Two default Emacs shortcuts that never worked now do.** *Find References* (`M-?`) and *AceJump to line*
  (`M-g L`) were written with key tokens the dispatcher can't produce, so they silently did nothing; they're
  now `M-S-/` and `M-g Shift-L`. A test guards the whole class of unreachable binding.

- **Numeric-keypad digits with a modifier now match the `M-1`…`M-9` shortcuts** instead of producing an
  unmatchable token.

- **A snippet or template containing a plain dollar amount no longer fails silently.** A body with text like
  `$12345678901` (a `$` followed by a very long number) threw internally, so the snippet didn't expand / the
  template didn't create — the number is now treated as ordinary text. A body ending in a stray `${` is
  likewise handled instead of erroring. (From a per-feature audit of Snippets & Templates.)

- **Multi-line snippets with a choice field now show their dropdown when expanded on an indented line.** The
  `${1|a,b,c|}` choice list was being dropped during re-indentation, so the field became a plain placeholder;
  the dropdown is preserved now.

- **Running an External Tool on a remote (SFTP) file no longer hangs the app.** The tool tried to launch a
  local process in the remote file's directory, which can't work; the "Running…" status hung forever with no
  output. External Tools are now disabled for remote buffers with a clear message. (From a per-feature audit of
  the Remote/SFTP feature.)

- **A remote file that was open when you quit no longer reopens the wrong local file on restart.** The session
  stored the bare path (dropping the `sftp://` host), so on the next launch a same-named local file could be
  opened in its place; remote entries are now stored as their full URI and skipped on restart until you
  reconnect (matching Recent Files).

- **Failed SSH logins and reconnects no longer leak connections.** A wrong password left the SSH session open,
  and reconnecting the same host stranded the previous filesystem; both are now closed.

- **Remote hosts given as bracketed IPv6 literals** (`sftp://[::1]:2222/…`) now parse and round-trip correctly.

- **Replace All in Find-in-Files now changes exactly what the search preview showed.** The results list matches
  line by line, but Replace All applied a regex over the *whole file* — so `;$` previewed a hit on every line
  but only replaced the last one, and a cross-line pattern like `foo\nbar` previewed nothing yet rewrote across
  the newline. Replace is now line-oriented like the preview (line endings preserved). (From a per-feature
  audit of Search.)

- **Case-insensitive regex search now matches accented and other non-ASCII letters.** A regex search for
  `café` (case-insensitive) missed `CAFÉ` — the regex engine was folding only ASCII, unlike the plain-text
  search and ripgrep. All three now agree.

- **Whole-word search for a term that starts or ends with punctuation now behaves like a word-boundary
  match.** e.g. `+foo` now matches inside `a+foo`, matching the regex/ripgrep whole-word rule; ordinary words
  are unaffected.

- **Files with accented or non-ASCII names now work with Git.** A file like `café.txt` was left with Git's
  raw quoted-and-escaped name (`"caf\303\251.txt"`) throughout the integration, so it got no color in the
  Project tree and staging / unstaging / discarding / diffing it failed with "pathspec did not match". Editora
  now decodes Git's quoting, so non-ASCII filenames behave like any other. (From a per-feature audit of the Git
  integration.)

- **Diffing a renamed file from the Git Log no longer shows the whole file as newly added.** For a commit that
  renamed a file, the "before" side was fetched under the new name (which didn't exist yet at the parent), so
  the diff looked like a 100%-added file; it now uses the original name and shows the real change.

- **Pushing while on a detached HEAD no longer builds an invalid command.** It used to try
  `push --set-upstream origin (detached)`, which Git rejects with a confusing refname error; it now falls back
  to a plain `push` (Git then gives its own clear "detached HEAD" message).

- **Staging/unstaging/discarding the current file works for files in subfolders on Windows.** Those three
  actions built the path with backslashes, which Git treats as escapes; they now use forward slashes like the
  Project-tree actions already did.

- **Backspace on an empty auto-inserted bracket pair no longer eats the line's indentation.** With the caret
  between a just-typed `()`/`[]`/`{}` sitting in a line's leading whitespace, a single Backspace removed the
  pair *and* the indentation (and the newline above), jumping up to the previous line — because two Backspace
  handlers on the editor both ran even though the first had already handled the key (a JavaFX quirk: `consume()`
  stops the event reaching other nodes, not other handlers on the same node). Now the second handler yields, so
  Backspace removes only the pair. (First finding from a per-feature audit pass over the editor's core editing
  mechanics.)

- **Pressing Enter on a Markdown ordered-list item with an absurdly long number no longer throws.** A list
  marker whose number overflowed a 64-bit integer (20+ digits) raised an uncaught error on Enter; it now falls
  back to a plain newline.

### Added

- **Editora now tells you when a new version is available.** On startup (at most once a day) it checks GitHub
  for a newer release; when one exists, a subtle "Update: X.Y.Z" indicator appears in the status bar — click it
  to open the release page (which dismisses that version's notice). The About dialog shows an "Update available"
  link too, and *Check for Updates* in the command palette checks on demand. Automatic checks are on by default
  and can be turned off in Settings → Workspace → Updates (or *View: Toggle Automatic Update Checks*); the check
  contacts GitHub's API over HTTPS and sends no data.

- **Copy or Cut with nothing selected now acts on the whole current line** (VS Code's
  `editor.emptySelectionClipboard`). Put the caret anywhere on a line, press Copy and the entire line
  (with its newline) lands on the clipboard; press Cut and the line is removed in one undoable step. It's on
  by default and can be turned off in Settings → Editor ("Copy/cut the whole line when nothing is selected")
  or via the command palette (*View: Toggle Copy Line When No Selection*).

### Changed

- **The five build tools now share one tabbed "Build Output" window** instead of a separate output window
  each. Maven, npm, Cargo, Go, and Gradle previously registered five distinct output tool windows (five stripe
  buttons, five rows in Settings → Tool Windows); they now stream into a single window with **one tab per
  tool** (created on first run, titled with the tool name, and auto-selected while it runs). Two concurrent
  builds stream into their own tabs instead of interleaving. The per-tool **tasks** windows are unchanged.

## [0.9.5] - 2026-07-15

### Fixed

- **Joining two lines no longer silently loses a breakpoint or bookmark on the lower line.** Pressing
  Backspace at the start of a line (or Delete at the end of the line above) merges the two lines — the code
  survives, but a breakpoint or bookmark on the merged-up line was being dropped, so you'd set a breakpoint,
  tidy up the line above, and then debug without it. The marker now follows its code to the joined line.

- **French and Italian error/status messages no longer show a literal `{0}` instead of the filename or
  error.** In those languages an apostrophe (`l'`, `d'`, `dell'`) sits right before the value placeholder, and
  an unescaped apostrophe is a MessageFormat escape that swallowed it — so "save failed: /path" came out as
  "save failed: {0}", the actual path gone. 60 messages across French and Italian are fixed (save/open
  failures, theme changes, plugin/agent/build/export errors), and a test now guards every locale so it can't
  come back.

### Security

- **Previewing an untrusted Markdown file can no longer probe your internal network or leak credentials.**
  An image reference in the source (`![](…)`) was fetched with no restriction the moment the preview rendered,
  so a malicious document could reach cloud-metadata endpoints, internal hosts, or a Windows file share (an
  SMB credential leak) with no consent. The preview now refuses to fetch loopback, link-local, private, and
  UNC targets, re-checking each redirect hop; public images and local files still load.
- **A malicious file template can no longer write outside the target folder.** The single-file "New from
  Template" path didn't contain its file-name pattern, so a `../…` or absolute name could create a file
  anywhere writable. It now applies the same containment the multi-file path already used. (Templates aren't
  loaded from the opened workspace, so this is defense-in-depth.)

- **Format Document no longer corrupts the file if you edit while it's working.** A language server computes
  formatting against the file as it was when asked; if you typed during the round-trip, those edits landed at
  the wrong offsets, silently mangling lines. Editora now detects that the file changed and skips the stale
  result, telling you to run it again.
- **Semantic highlighting no longer briefly mis-colors code while you type.** A slow server's colors,
  computed against an older version of the file, could paint onto shifted text (a variable colored as a
  function, the whole overlay off by a line) until the next response caught up. Stale responses are now
  dropped.

- **A pathological regex in Find no longer freezes the editor.** Typing a valid-but-catastrophic pattern
  (the classic ReDoS case) with regex search on could send the find bar backtracking for many seconds — on the
  UI thread, so the whole editor locked up with no way out. Regex search is now time-bounded and abandons a
  runaway match instead.
- **A hostile or fat-fingered `.editorconfig` no longer throws when you open a file.** An enormous numeric
  glob range like `[{1..99999999999999999999}]` crashed the read of any file it governed. It now degrades to
  matching any number.
- **The systemd unit preview no longer throws on an out-of-range time value.** A directive like
  `RestartSec=99999999999999999999` (or one that overflows when converted to seconds) now shows the raw value
  instead of failing to render.

- **Closing an image, hex, or PDF tab now actually releases it.** Those tabs were only cleaned up when closed
  by clicking the ✕ — closing them with Ctrl-W, "Close All"/"Close Others", or by closing the window leaked a
  live thread and (for a PDF) kept the file open for as long as Editora was running. Open and close a few
  dozen PDFs and it added up.
- **Closing a window now shuts down everything it started.** The diff, external-tool and HTTP-client workers
  were left running, and an SFTP connection stayed open on the remote host with its SSH client pinned in
  memory for the rest of the session.
- **Stopping (or quitting during) a run or a build now kills the whole process tree.** `npm run dev`, `mvn`
  and `./gradlew` all launch a child process; only the wrapper was being killed, so a dev server kept holding
  its port and a build JVM kept running. Those processes are now tracked, so they're also cleaned up if
  Editora exits unexpectedly.

- **Auto-save can no longer undo a manual save.** Hitting Ctrl/Cmd-S just as a queued auto-save fired let the
  auto-save land afterwards and put the *older* text back on disk — while the editor showed the file as saved.
  Writes to a file are now serialized, and a stale auto-save is dropped.
- **Auto-save no longer overwrites a file that changed on disk.** A dirty buffer in a *background* tab was
  never checked for outside modification (that check only looks at the tab you're on), so a timer could
  silently overwrite a file that a `git checkout` or a generator had just rewritten. Auto-save now skips such a
  file and leaves the usual "changed on disk" prompt to handle it.
- **Saving a file is now crash-safe.** Files were written by truncating them first, so an interruption
  (a crash, a full disk) could leave the document truncated or half-written. The new content is written to a
  temporary file and moved into place — and the file keeps its permissions (an executable script stays
  executable) and stays a symlink if it was one.
- **A character your file's encoding can't represent is no longer silently turned into `?`.** With an
  `.editorconfig` setting `charset = latin1`, an em dash, a curly quote, `€`, or an emoji was written to disk
  as a question mark — and the editor kept showing the real character until you reopened the file. Editora now
  saves as UTF-8 instead and tells you why.
- **Trimming trailing whitespace no longer flips a whole file to Windows line endings.** A mostly-LF file
  containing a single stray CRLF was rewritten *entirely* as CRLF the first time it was saved with
  `trim_trailing_whitespace` on. The file's dominant line ending is now what's preserved.
- **Save-As now applies the destination's `.editorconfig`**, instead of writing with the previous location's
  charset, line endings, and trim rules (and continuing to do so on every later save).

- **A crash or a full disk while saving can no longer wipe out your bookmarks, notes, breakpoints, or the
  project list.** Those files were written by truncating them first and then streaming the new contents in, so
  an interruption left a half-written file — which Editora then treated as *empty* on the next launch and
  promptly overwrote. Every config store is now written to a temporary file and moved into place, so the real
  file is only ever replaced by a complete one. A config file that still can't be read is kept as
  `<name>.corrupt.bak` instead of being silently replaced.
- **Running an older Editora twice no longer destroys a newer config.** A settings/session file from a newer
  build is moved aside so the older build can't clobber it — but if a backup with that name already existed,
  the move was skipped and the newer file was overwritten anyway, leaving only a stale backup. Backups are now
  numbered.
- **Deleting a project no longer risks it coming back.** A save queued moments earlier could re-create the
  deleted project's session file, and because a project's identity is derived from its folder, re-adding that
  folder later picked the old session right back up.

- **Pressing Ctrl/Cmd-S on a very large file no longer destroys it.** A file of 50 MB or more is deliberately
  loaded only in part (the first 50 MB — or, for a log, the *last* 50 MB) and opened read-only. But read-only
  only blocks *typing*: Save needs neither an edit nor unsaved changes, and it wrote the buffer straight back
  over the file — truncating it on disk to whichever slice happened to be loaded, irreversibly. Every write
  path now refuses a partially-loaded file and says why.
- **Quitting no longer discards the unsaved work and the session of every window except the one you quit
  from.** Quitting from inside the app never ran the other windows' close handling, so their dirty buffers
  were dropped with no save prompt and their sessions (open tabs, carets, window bounds) reverted to whatever
  they were at launch. Quit now prompts and persists each window in turn, and any window's prompt can cancel
  the quit. Each window's services and subprocesses are shut down properly too.

- **`--zen` and `--expert` are now genuinely session-only, like `--simple`.** Launching once with either flag
  wrote the mode into the saved session, so *every* later launch came up in it — with no hint as to why. The
  flags now apply the mode for the current run only and leave the saved session untouched (including the
  docked tool windows the mode closes). Turning the mode on from inside the app still sticks, as before.
- **Dragging a folder together with something inside it no longer pops an error dialog.** Selecting a folder
  *and* a file within it and dropping both on a target moved the folder first (contents and all), then failed
  trying to move the file from a path that no longer existed. Nested entries are now dropped from the move —
  the folder carries them along, as it always did.
- **A build-tool toggle that vanished from the build file no longer silently poisons every run.** Ticking a
  Maven profile (or Cargo's `--release`) and then deleting it from the `pom.xml` — or pointing the window at
  a different project — left the id active with no checkbox to untick, so `-P<profile>` kept getting appended
  to every build, invisibly. Stale toggles are now dropped when the build file is re-parsed.
- **The build-tool tasks tree no longer loses your selection and scroll position** every time a file is
  saved, the tab changes, or the window regains focus (each of those re-parses the build file, which was
  rebuilding the whole tree from scratch).
- **The build-tool tasks tree's Stop button no longer looks live before anything has run.**
- **In the Commit tool window, a file's status letter and its color can no longer disagree** — a file staged
  as modified but deleted in the working tree showed "M" in the deleted (grey) color. Both now come from the
  same classification.
- Closing a project window can no longer throw on the filesystem-watcher thread (a race between `dispose()`
  and the watch loop).

### Changed

- The Project tree builds a row's right-click menu on demand rather than on every cell render, so scrolling a
  large tree (or any Git-status refresh) no longer allocates a ~20-node menu per visible row.
- **The Project tool window now shows a single-letter Git status on each changed file (M / A / D / R / U),
  matching the Commit tool window.** The colors it already had stay (modified = blue, added = green,
  deleted = gray, renamed = violet, untracked = olive), and the letter is prefixed like the Commit window's
  rows. The Commit window's file rows are now **colored** with the same palette too, so the two read
  identically (an unsaved-in-editor file still shows its amber "•" instead of a Git letter).

### Added

- **The Project tool window now works like a mini file manager.** Multi-select files/folders with
  Ctrl/Cmd- and Shift-click, then **drag them onto a folder to move them** (drop onto any folder or the
  project root; a name conflict in the target is skipped rather than overwritten, and a folder can't be
  moved into its own subtree). Open editor tabs follow a moved file — or the files under a moved folder — to
  their new path. **Delete** now acts on the whole multi-file selection at once (one confirm), alongside the
  existing per-file Rename/Delete.

## [0.9.4] - 2026-07-13

### Added

- **Expert mode — a lighter focus mode than Zen that strips only the window chrome and keeps the whole editor
  view.** A new per-window distraction-free overlay that hides the surrounding chrome like Zen — toolbar, tab
  bar, breadcrumb, tool stripes, and the whitespace guides — but **keeps the full editor view**: line numbers,
  the status bar, the minimap, the column ruler, and the current-line highlight. So you get a focused coding
  surface that still shows where you are. Toggle it from the palette (**View: Toggle Expert Mode**), `C-c C-e`,
  *Settings → Interface → Modes*, the `--expert` launch flag, or the floating **"E"** button (top-right) to
  exit — mirroring Zen's "Z". Expert and Zen are mutually exclusive; like Zen it's per-window state and never
  mutates your saved preferences, so leaving it restores them exactly.

- **File Templates can write into a chosen folder.** The template creation wizard gained an optional
  **Folder** field (with a Browse button): leave it blank to create an unsaved buffer (as before), or pick
  a folder to write the file(s) into. Invoking "New From Template…" from a project folder's right-click
  menu pre-fills that folder.

- **Project tool window: create folders and act on the root.** Folder right-click menus gained a
  **New Folder…** item, and the **project root now has its own right-click menu** (New Folder / New From
  Template / Reveal / Open Terminal / Local History / Git stage·revert) — with Rename omitted on the root
  so it can't move the whole project folder.

### Changed

- **Build tools (Maven / npm / Cargo / Go / Gradle) now have IntelliJ-style tasks tool windows instead of
  main-toolbar icons.** Each detected build tool gets its own tool-window stripe (shown when the tool's
  marker file is found) whose panel is a browsable tree of the tool's goals / scripts / targets with a mini
  toolbar (Run / Reload / Stop / Run custom…); double-click or Enter runs a task, and the streaming output
  goes to a separate per-tool console window (auto-opens on a run). The per-tool icons were removed from the
  main toolbar; the searchable actions popup is still available from the command palette (`<tool>.showActions`
  / *Maven: Show Actions*, …). Everything else — marker detection, wrapper/executable selection, profiles and
  `--release`-style toggles, Gradle's on-demand "Load all tasks…" — is unchanged. `tool.<tool>` now opens the
  tasks window; the output console is `tool.<tool>Output`.

- The editor install banner for a missing **language server** now says "*X* language server isn't
  installed" instead of "*X* language support isn't installed" — clearer that it offers the optional LSP
  (code intelligence), not the language's render/run tool, which may already work (e.g. the Typst preview
  renders via the typst CLI even when the tinymist language server isn't installed).

### Fixed

- **The Cargo, Go, and Gradle build-tool tool-window icons showed blank.** Their SVG logos (vendored from
  Simple Icons) used SVGO's compact *packed elliptical-arc flags* (e.g. `a1 1 0 000-.5`), which JavaFX's
  `SVGPath` parser can't read — it fails with "invalid boolean flag" and renders an empty shape. Replaced the
  three with clean Material Design glyphs (a box for Cargo, a running figure for Go, stacked layers for
  Gradle) that parse and render correctly. A new `IconsFxTest` guards that every build-tool glyph produces a
  non-empty shape, so a future un-parseable path is caught. (Maven and npm were unaffected — their paths have
  no packed arc flags.)

- **Installing the Typst language server (tinymist) from the banner/Settings now actually activates it.**
  The extracted `tinymist` binary's path was never written to `Settings.typstLspCommand`, so detection kept
  failing on the PATH default and the install banner reappeared right after the install "succeeded". Added
  the missing persist case (`InstallCoordinator.applyServerCommand`) + a regression test covering every
  binary-archive server (clangd/kotlin/lua/xml/terraform/typst).

## [0.9.3] - 2026-07-10

### Added

- **GitHub Actions workflow preview.** A workflow YAML (detected by content — a top-level `on:` + `jobs:` —
  so it works regardless of path and keeps YAML highlighting) gets the same 3-mode view, rendering a
  plain-English digest instead of the generic YAML tree: the triggers ("push to main, develop", "pull
  request to main", `workflow_dispatch` → "manual dispatch", and a `schedule:` cron **decoded via the cron
  engine** → "on a schedule (…Monday through Friday)"), then each job with its runner, `needs`/`if`, and the
  ordered step list (an `actions/checkout@v4` step shown as "checkout", a `run:` as its command). Pure,
  unit-tested `ghactions/` core (`GithubActions` sniff + `Workflow` parser, reusing the existing YAML +
  cron support). On by default (`Settings.githubActionsPreview`); Settings → Editor → GitHub Actions, palette
  `view.toggleGithubActionsPreview` (turning it off falls back to the YAML tree). No new dependency.
- **In-app typst-CLI installer.** *Settings → Languages & Tools → Typst* gains an **Install…** button (and
  the `install.typstCli` command) that downloads the `typst` renderer binary for your OS/arch from typst's
  GitHub releases, extracts it (adds `.tar.xz` support — typst ships xz tarballs), and points
  `Settings.typstPath` at it, so the document preview lights up without a restart. Distinct from the
  tinymist LSP-server installer. The button shows **Installed** when `typst` is already detected.

- **Typst editing conveniences.** For `.typ` files: **snippets** (a bundled `typst.json` — figure, table,
  grid, equation, import, outline, bibliography, …); **Insert Table** (a grid size-picker that drops a
  `#table(…)` skeleton with the caret in the first cell — on the format bar, right-click menu, and
  `typst.insertTable`); **Insert Table of Contents** (`#outline()`, `typst.outline`); **Insert Image**
  (`typst.insertImage` / right-click — pick or paste/drop/drag an image, copied into the doc's `assets/`
  and inserted as `#image("assets/…")` — the Markdown image-paste paths now handle Typst too); and
  **Export the preview to PNG / SVG** from the preview right-click menu (native `typst -f png`/`-f svg`,
  alongside the existing PDF), plus `typst.exportPng`/`typst.exportSvg` commands. Typst-specific bits live
  in the pure, unit-tested `com.editora.typst.TypstMarkup`. i18n ×6.

- **Typst language server (tinymist).** `.typ` files now get full LSP code intelligence via
  [tinymist](https://github.com/Myriad-Dreamin/tinymist) — completion, hover docs, go-to-definition,
  find-references, a precise document-symbol outline, diagnostics (squiggles + the Problems window), and
  **Format Document** — the 22nd server in Editora's LSP layer (one `ServerDef`, root markers `typst.toml`).
  It complements the existing Typst rendered preview. Off until the global LSP feature is on; auto-detected
  on `PATH` (`tinymist lsp`), with a per-server enable + command field and a one-click **Install…** button
  (downloads the tinymist binary from GitHub releases) under *Settings → LSP*. `Settings.typstLspEnabled`/
  `typstLspCommand` (schema 72→73).
- **Three more config-file previews — systemd units, SSH config, and Dockerfiles** — each gets the same
  3-mode Editor/Split/Preview view that decodes the file into plain English:
  - **systemd** (`.service`/`.timer`/`.socket`/…): each directive glossed ("`ExecStart` → runs: …",
    "`Restart=on-failure`", "`WantedBy=multi-user.target` → enabled for: …"), and a `.timer`'s
    `OnCalendar=` is decoded into English + the next trigger times (e.g. `Mon..Fri *-*-* 02:30:00` → "At
    02:30, Monday through Friday" + upcoming runs), including the `daily`/`weekly`/`hourly`/… shorthands and
    `On*Sec=` monotonic timers via time-span decoding ("15min" → "15 minutes after boot").
  - **SSH config** (`~/.ssh/config`, `ssh_config`): one connection summary per `Host` block — "Connects to
    example.com on port 2222 as deploy, key ~/.ssh/id_ed25519, via jump host bastion" — plus per-option
    glosses (agent forwarding, keepalive, proxy jump, …).
  - **Dockerfile**: a per-build-stage digest — base image, exposed ports, workdir, user, env count,
    entrypoint/command, health check, and a build-step count — so you can see what an image does at a glance.

  All three are pure, unit-tested (`systemd`/`sshconfig`/`dockerfile` packages); on by default
  (`Settings.systemdPreview`/`sshConfigPreview`/`dockerfilePreview`); Settings → Editor + palette
  `view.toggleSystemdPreview`/`toggleSshConfigPreview`/`toggleDockerfilePreview`. No new dependency.

- **fstab mount preview.** An `/etc/fstab` file (already syntax-highlighted) gains the same 3-mode
  Editor/Split/Preview view. The preview decodes each mount line into plain English — the device spec
  (`UUID=…` → "the filesystem with UUID …", `LABEL=`, `//nas/share` → SMB/CIFS, `host:/export` → NFS), the
  mount point + filesystem, the comma-separated **options** (`noatime` → "access times not updated",
  `nofail` → "boot continues even if the device is missing", `uid=1000`, …), and the fsck/dump columns
  ("fsck-checked first (root) · not backed up"). A malformed line (too few columns, non-numeric dump/pass)
  turns red. Pure, unit-tested `fstab/` core (`Fstab` parser + `FstabDescribe`); no new dependency. On by
  default (`Settings.fstabPreview`); Settings → Editor → fstab, palette `view.toggleFstabPreview`.
- **Typst document preview.** Standalone `.typ` files get the same 3-mode preview (Editor / Split /
  Preview) as Markdown, rendered off-thread via the external **`typst`** CLI as a **multi-page** stack —
  one image per page. Editing updates in place with no flicker (the last good render stays visible while
  the new one runs; a compile error keeps it visible under a small banner). The document is compiled with
  `--root` set to the file's folder, so relative `#image`/`#import` references resolve. Export to **PDF**
  (native single file) / **PNG** / **SVG** (`typst.export`), and print paginates the pages. Syntax
  highlighting via a bundled `source.typst` grammar; `//` + `/* */` comments and brace folding. **On by
  default** — self-gating on detection, inert until `typst` is found (install via your package manager,
  e.g. `brew install typst` or `cargo install typst-cli`). Toggle (`view.toggleTypstSupport`) + command
  path under *Settings → Languages & Tools → Typst*.
  - **Editing ergonomics on par with Markdown:** pressing **Enter** in a Typst list continues the marker
    (`-`/`+`/`N.`) on the next line and ends the list on an empty item (Backspace clears an empty marker);
    selecting text pops the same floating **format bar** (Bold `*`, Emphasis `_`, Raw `` ` ``, Link, Bullet
    + a heading dropdown), with matching right-click **Format** items and palette commands (`typst.bold`,
    `typst.emph`, `typst.raw`, `typst.link`, `typst.bulletList`, `typst.headingPromote`/`headingDemote`).
    Headings use `=`/`==`, links insert `#link("url")[…]` — the pure `com.editora.typst.TypstMarkup` core
    (unit-tested) supplies the Typst-specific pieces; the generic inline-wrap and bullet toggle are shared
    with Markdown.
  - **Multi-file projects resolve.** The preview compiles with `--root` set to the nearest `typst.toml`
    ancestor (or the active project root when the file is inside it), so a document deep in a project can
    `#import`/`#image` files above its own folder; relative refs still resolve because the throwaway input
    is written in the file's own directory. The preview also caps at 40 stacked pages (a "… more pages"
    note; export/print use every page), and remote/SFTP `.typ` files fall back to an isolated root so a
    self-contained one still renders.
- **Crontab schedule preview.** A `crontab` / `*.cron` / `cron.d/*` file (already syntax-highlighted) gains
  the same 3-mode Editor/Split/Preview view as Markdown. The preview decodes each job's terse `* * * * *`
  schedule into plain English (`30 2 * * 1-5` → "At 02:30, Monday through Friday"), lists the next few
  fire times computed from now, handles the `@reboot`/`@daily`/… macros, and red-flags a malformed line
  with the exact field error (e.g. minute 99 out of range). Because every field is parsed to describe it,
  the whole file's validity is visible at a glance. Pure, unit-tested `cron/` core (`CronExpression`
  with the Vixie day-of-month/day-of-week OR-rule + `Crontab` file parser); no new dependency. On by
  default (`Settings.crontabPreview`); Settings → Editor → Crontab, palette `view.toggleCrontabPreview`.

- **Cargo, Go, and Gradle build-tool support** — completing the build-tool set (Maven + npm shipped
  earlier). Each detected tool gets its own toolbar icon, actions popup, and streaming console:
  - **Cargo** (`Cargo.toml`) — the standard subcommands plus `[[bin]]`/`[[example]]` targets and a
    `--release` toggle.
  - **Go** (`go.mod`/`go.work`) — the standard `go` subcommands over the whole module.
  - **Gradle** (`build.gradle[.kts]`) — the common tasks plus an on-demand **Load all tasks…** that
    enumerates `gradle tasks`; prefers the project's `./gradlew` wrapper. Each parses its marker file with
    existing dependencies (no new libraries); toggle under *Settings → Languages & Tools → Build Tools*.
- **npm build-tool support, plus a generalized build-tool framework.** The Maven integration is now one
  tool-agnostic `com.editora.build` framework, so every build tool gets the same first-class treatment: its
  own toolbar icon (shown only when its marker file is detected), an IntelliJ-style actions popup, and a
  streaming per-tool console. **npm** is the first new tool — a `package.json` toolbar icon whose popup lists
  every `scripts` entry (run portably as `<pm> run <name>`) plus common tasks (`install`, and `ci` for npm),
  using the package manager detected from the `packageManager` field or the lockfile (npm/yarn/pnpm/bun).
  Discovery parses `package.json` directly (no new dependency). Maven is unchanged in behavior. Toggle each
  tool under *Settings → Languages & Tools → Build Tools* (both on by default, inert until their marker file
  is found). *Cargo, Go, and Gradle will follow.*
- **Every preview can now export to PDF from its right-click menu.** Previously only Markdown, CSV, and the
  Mermaid diagram had a working preview → PDF; the newer previews either had no context menu or silently
  exported garbage (their content was run through the Markdown renderer). Now:
  - **SVG** rasterizes to a PDF page; **DOT/PlantUML** render via their CLI to PDF (both were misrouted to
    Markdown before);
  - **JSON/YAML/TOML** and **XML** tree previews gained a right-click **Export to PDF** that snapshots the
    **whole** tree (rendered in bounded row-chunks so a large document can't build a giant image) and lays
    it across pages;
  - **Markwhen** timelines gained Export to PDF (a snapshot of the timeline/calendar), no longer disabled.

  Every preview also gained a matching **Print** (right-click) — the snapshot previews (SVG, Markwhen, the
  trees) and DOT/PlantUML paginate their rendered image across pages, mirroring the PDF path (previously
  only Markdown/CSV/Mermaid could print; the rest silently printed the source through the Markdown
  renderer). And a snapshot export/print is now **forced to a light theme** (Primer Light) regardless of
  the app theme, so a dark-theme user still gets an ink-friendly page. New `pdf/ImagePdfWriter`
  (scale-to-width + slice-tall-across-pages, shared with `print/PrintService.prepareImages`) +
  `PdfExportService.exportImages`, with `EditorBuffer.snapshotPreviewChunks(lightUa)` doing the off-screen
  render/snapshot. No new dependency (PDFBox was already bundled). *Note: a tree/timeline/SVG/diagram PDF or
  print is a light raster snapshot; Markdown/CSV stay native-vector.*

- **Portable Linux install tarball** (`Editora-<version>-linux-<arch>.tar.gz`, x64 + arm64). A new release
  artifact for systems without `.deb`/`.rpm`: it bundles the same self-contained, AOT-trained app image the
  installers are built from (a jlink'd Java runtime + the native `bin/Editora` launcher — no system Java
  needed) plus a POSIX `install.sh` that installs to **`/opt/editora`** when run as root or
  **`~/.local/editora`** as a regular user, wiring up an `editora` command on `PATH` and an
  application-menu entry (`StartupWMClass=com.editora.App` so the running window inherits the icon).
  Supports `--system`/`--user`/`--prefix DIR`/`--uninstall`; the image also runs in place
  (`./Editora/bin/Editora`) without installing. Built by the new `scripts/build-tarball.sh` from the
  existing `target/aot-image/Editora` — the extract-and-install counterpart to the `.AppImage`, reusing the
  jpackage `APP_IMAGE` output (jlink under the hood), so **no `pom.xml` change and no second build**.
- **XML tree preview.** `.xml` files (xsd/xsl/fxml/pom/wsdl/rss/…) join the structured-data preview with a
  collapsible **DOM tree** in the 3-mode view — each element shows its tag + inline attributes, children in
  document order, and text-only elements inlined as `tag = "value"`. A *faithful* DOM model
  (elements/attributes/text/comments), **not** a lossy XML→JSON shoehorn. Parsed off-thread with the JDK's
  own DOM parser (**no new dependency**), hardened against XXE exactly like the Maven pom parser (DOCTYPE
  disallowed, external entities/DTD off). New pure `structured/XmlNode` + `structured/XmlParser`
  (unit-tested, incl. an XXE-blocked case) + `editor/XmlTree`; reuses the existing structured-preview
  hosting **and the same `Settings.structuredPreview` toggle** (relabelled JSON/YAML/TOML → JSON/YAML/TOML/
  XML). `.svg` still renders as an image (excluded from the tree); `.xhtml` still uses the browser preview.
  *Deferred: specialized dialect renderers (RSS/Atom feed view, like OpenAPI for JSON), attribute/text
  search.*
- **PDF viewer.** `.pdf` files now open in a read-only **page viewer** (rasterized via Apache **PDFBox** —
  already a dependency, for PDF export) instead of the hex viewer. A top bar navigates pages
  (`◀ Page N of M ▶`) and zooms (out / in / fit-to-window / actual-size; `Ctrl`+wheel too). Pages are
  rasterized **one at a time, off the FX thread** on a single daemon thread (PDFBox's `PDDocument` isn't
  thread-safe, so all document access is serialized there), so only the current page's image is held —
  bounding the Prism texture pool. New `ui/PdfViewerPane implements TabContent` (mirrors `ImageViewerPane`);
  routed in `openPath` before the hex fallback and round-tripped through session restore + `tabPath` like
  the image/hex viewers. Works for local **and** remote (SFTP) PDFs; refused above 128 MB. **No new
  dependency.** *Deferred: continuous page scroll, a page-jump field, text selection/search, password-
  protected PDFs.*
- **SVG image preview.** `.svg` files stay editable XML (with XML highlighting + LSP) but now gain a
  rendered-image preview in the same 3-mode view (Editor / Editor + Preview / Preview) as Markdown — edit
  the source, see the image re-render live. Rasterized off-thread via **JSVG** (already bundled for
  Markdown badge images, so **no new dependency**) and cached by source hash; the preview zoom resizes the
  image. New `editor/SvgImages` (async render cache, mirroring `MermaidImages`/`DiagramImages` but
  in-process — no external tool); `EditorBuffer.isSvg()`/`hasSvgPreview()` + a `scheduleRenderPreview()`
  branch. **On by default**, `Settings.svgPreview` (schema 64→65) — Settings → Editor → SVG + palette
  `view.toggleSvgPreview`. *Deferred: export-as-PNG from the preview, a checkerboard backdrop for
  transparent SVGs.*
- **Structured-data preview for JSON / YAML / TOML, with OpenAPI/Swagger docs.** `.json`, `.yaml`/`.yml`,
  and `.toml` files now get the same 3-mode preview (Editor / Editor + Preview / Preview) as Markdown: a
  collapsible, type-colored **data tree** rendered off-thread. A JSON/YAML document detected as an
  **OpenAPI 3 / Swagger 2** spec instead renders as **browsable API docs** (title, servers, endpoints with
  colored method badges + params + responses, schemas), with a tree ⇄ API-docs toggle
  (`structured.toggleView`). New pure package `com.editora.structured`
  (`StructuredNode`/`StructuredParser`/`OpenApiModel`/`OpenApiParser` — all Jackson stays here, unit-tested)
  + `editor/StructuredTree` + `editor/OpenApiDoc` (self-scrolling nodes hosted the CSV-grid way, so the
  `TreeView` keeps virtualization). Render-branch model (no coordinator, like Markwhen); a 50k-node cap
  guards pathological files. **On by default**, `Settings.structuredPreview` (schema 63→64) — Settings →
  Editor → Structured data + palette `view.toggleStructuredPreview`. Adds a dependency:
  **jackson-dataformat-yaml** (+ snakeyaml 2.4; both real JPMS modules, no moditect). *Deferred: GeoJSON
  map, jump-from-tree-to-source-line, per-file view persistence, expand/collapse-all, editing.*
- **Diagram-as-code preview for Graphviz DOT and PlantUML.** Standalone `.dot`/`.gv` and
  `.puml`/`.plantuml`/`.pu`/`.iuml` files now get the same IntelliJ-style 3-mode preview (Editor /
  Editor + Preview / Preview) as Markdown and Mermaid: the whole buffer renders to an image via an
  external CLI (`dot` / `plantuml`), off the FX thread, cached by a hash of the source so an unchanged
  re-render is a cheap re-fit; the preview zoom resizes the image and a diagram exports to **SVG / PNG /
  PDF** (`diagram.export`). Both tools rasterize to PNG natively (no headless browser, unlike Mermaid's
  mmdc). New generic, Mermaid-independent seam: pure package `com.editora.diagram`
  (`DiagramKind`/`DiagramRenderer`/`DiagramService`, the CLI facade + per-kind argv builders, unit-tested)
  + `editor/DiagramImages` (the async render cache, mirroring `MermaidImages`) + `ui/DiagramCoordinator`
  (the `CoordinatorHost` feature-coordinator pattern). Authored compact `grammars/dot.tmLanguage.json` +
  `grammars/plantuml.tmLanguage.json` for highlighting, plus `LanguageRegistry`/`GrammarRegistry`/
  `Commenter`/`FoldRegions` entries. **On by default** — self-gating on detection, so it stays inert
  until `dot`/`plantuml` are found (install via your package manager, e.g.
  `brew install graphviz plantuml`); toggle + tool paths under *Settings → Languages & Tools → Diagrams*.
  `Settings.diagramSupport`/`dotPath`/`plantumlPath` (schema 62→63, additive). Commands
  `diagram.export`, `view.toggleDiagramSupport`, `diagram.setDotCommand`, `diagram.setPlantumlCommand`.
  Mermaid's shipped path is untouched (the two share only the `editor/DiagramImages` render-cache
  convention). *Deferred: PlantUML `@start/@end` block folding, a themed (dark) render, live linting,
  and the browser-raster diagram tools (D2, WaveDrom).*

### Changed

- **CSV/TSV grid preview is now an in-editor Editor/Split/Preview view, mirroring the Markdown preview.**
  The spreadsheet grid for `.csv`/`.tsv` files moved out of the bottom tool window into the same
  IntelliJ-style floating Editor / Split / Preview toggle that Markdown uses (top-right of the editor):
  Split shows the source and the live grid side by side, Preview shows the grid alone. The view mode is
  remembered per file. The grid keeps everything it had — the spreadsheet rendering, the "First row is a
  header" toggle, the row/column type profiler, sort/filter, ragged-row diagnostics, in-cell editing
  (writes back to the source line), click-a-cell → jump to the field, and the PDF / Excel (`.xlsx`) / ODF
  (`.ods`) / print exports. The old `csvGrid` bottom tool-window stripe and its `tool.csvGrid` command
  were retired; the Settings → Editor → CSV toggle and the `view.toggleCsvGrid` palette command now gate
  the in-editor preview.

### Fixed

- **Structured / XML tree preview no longer recolors its text when you select a row.** The JSON/YAML/TOML
  and XML preview rows are `TextFlow`s of colored `Text` runs, whose fill AtlantaFX flips to the selection
  foreground on a selected cell — so clicking a line made the syntax-colored tokens visibly flash to the
  selection color and back (a "redraw"). Added selected-state CSS rules pinning each token's `-fx-fill` to
  its normal color (the same fix `.completion-list` already uses for its `TextFlow` labels), so selection
  only changes the row background now. CSS-only.
- **Spell-check and Personal-Notes overlays no longer pin an idle full-viewport GPU texture.** Both
  `SpellCheckOverlay` and `NoteHighlightOverlay` kept their `Canvas` sized to the whole viewport whenever
  the feature was on (the default), even on a buffer with zero visible misspellings/notes — retaining a
  viewport-sized RTTexture that other overlays (Todo/Search/LSP/…) already release. They now shrink the
  canvas to 1×1 when there's nothing visible to paint and grow it back on demand (with no per-scroll size
  flapping), matching the established overlay convention — trimming idle Prism-texture footprint on every
  spell-checked/annotated buffer. Purely a resource fix; no visible behavior change.
- **Structured / XML tree preview no longer blanks silently on a render error.** The off-thread
  `PREVIEW_POOL.submit(...)` tasks that parse a JSON/YAML/TOML/XML buffer now catch `Throwable` (not just
  the parser's `Exception`), so an `Error` such as a `NoClassDefFoundError`/`LinkageError` while loading a
  Jackson format factory on the pool thread is surfaced as a visible error label in the preview + a
  `WARNING` in the Debug Log, instead of being swallowed into the task's `Future` and leaving a blank pane.
  Aligns these two branches with the project's "catch `Throwable` in `exec.submit` tasks" convention.

## [0.9.2] - 2026-07-08

### Added

- **Maven support — a toolbar icon + actions popup that reads the active project's pom.xml, IntelliJ-style.**
  A new Maven toolbar button (hidden until a pom.xml is actually detected for the active file/project;
  refreshed on tab switch, window focus-regain, and save) opens a searchable popup listing the standard
  lifecycle phases (clean, validate, compile, test, package, verify, install, site, deploy), the pom's
  declared `<profiles>` (checkable — composing with a run via `-P<id>`, without closing the popup — and
  marking any `activeByDefault` one), and each declared `<build>/<plugins>` entry's explicitly-bound
  `<executions>` goals as `<prefix>:<goal>` rows (e.g. `spotless:check`, `jacoco:report`) using Maven's
  own plugin-prefix naming convention; profile-scoped plugins (`<profiles>/<profile>/<build>/<plugins>`)
  are nested under their own profile once checked. Discovery is pom.xml-only — parsed directly with the
  JDK's own hardened DOM parser (`java.xml`, XXE disallowed outright; no third-party XML dependency, no
  shelling out to `mvn help:effective-pom`) — so it's instant and offline; a plugin with no `<executions>`
  is omitted from the goal list (the "Run custom goal(s)…" freeform prompt covers those). Runs prefer the
  project's own `./mvnw`/`mvnw.cmd` wrapper when present, falling back to `mvn` resolved on PATH (or a
  Settings override), streaming output to a dedicated Maven console tool window (default-hidden, auto-opens
  on a run). New pure package `com.editora.maven` (`PomModel`/`PomParser`/`MavenPluginPrefix`/
  `MavenLifecycle`/`MavenExecutable`/`MavenArgs`) + `MavenService` (mirrors `RunService`'s streaming shape)
  + `ui/MavenPanel`/`ui/MavenActionsPopup`/`ui/MavenCoordinator`. Gated by `Settings.mavenSupport` (default
  on — inert until a pom.xml is found) + `Settings.mavenCommand` override (schema 61→62), with a Settings →
  Languages & Tools → Maven page; off in Simple UI mode and for remote (SFTP) files. Palette commands
  `view.toggleMavenSupport`, `maven.showActions`, `maven.runCustom`, `maven.stop`, `maven.rerunLast`,
  `maven.refresh`, `tool.maven`. Also generalizes `OverlayHost` with a `showBelow(...)` variant (mirroring
  the existing `positionAbove`) so a popup can drop below a top-of-window anchor like a toolbar button,
  rather than only above a status-bar-style anchor.
- **A dedicated "AI" group in Settings, with a master Enable/Disable AI switch (off by default).**
  The AI Agent and AI Actions pages — previously scattered under separate groups — now live together
  under a new **AI** sidebar group, alongside a new landing page holding a single master checkbox
  (`Settings.aiEnabled`, schema 60→61 additive). Turning it off disables *every* AI feature regardless
  of the AI Agent / AI Actions pages' own settings below it: the AI Agent chat window, commit-message
  generation, explain/rewrite selection, and inline ghost-text completion — `AgentCoordinator.isEnabled()`
  and `AiCoordinator.isEnabled()` both now gate on it. It does **not** affect the MCP server (a separate
  feature). Also a palette command, `view.toggleAiEnabled` ("Toggle AI"). Defaults to off, so a fresh
  install has no AI usage until explicitly turned on.
- **Clickable links in the Markdown preview.** A rendered link now shows a hand cursor and opens in the
  system default browser on click (the same `Consumer<String>` already used by Ctrl/Cmd-click-in-source
  and the Welcome page's links), rather than being inert. `MarkdownRenderer.renderDocument` gained an
  overload taking an optional click handler; the print/PDF writers and the hover/completion-doc popups
  keep using the existing no-handler path, so only the interactive live preview is affected.
- **Loading indicator while an AI explanation streams into the Markdown preview.** `ai.explainSelection`
  opens a new `explanation.md` buffer in Preview mode and streams the response in, but the preview's
  re-render is debounced ~250ms after the text stops changing — a fast, continuous stream can go the
  whole generation without a quiet gap, leaving the preview blank with no feedback until it finally
  finishes. `EditorBuffer` gained a centered spinner + message overlay (`setPreviewLoading`), shown from
  just before the request starts until it ends (success or error).

### Changed

- **The gutter Run play glyph is 20% bigger** (scale 1.014 → 1.217), making the clickable green run
  target easier to see and click.

### Fixed

- **Windows installs failed to launch: "Failed to find JVM in ...\runtime directory".** The AOT-cache
  build step (`scripts/aot_build.java`) strips the bundled runtime's `bin/` after training to reclaim
  footprint — safe on macOS/Linux, where the JVM shared library lives in `runtime/lib/server/` and `bin/`
  holds only launcher executables. But the Windows JDK layout puts the JVM itself *inside* `bin/`
  (`runtime\bin\server\jvm.dll` plus the bootstrap `jli.dll`/`java.dll`/`jvm.cfg`), which `Editora.exe`
  loads from there — so deleting `bin/` removed the JVM, and every Windows install since the AOT cache
  landed failed to start. The `bin/` strip is now guarded to non-Windows only; the Windows `bin/` is kept
  intact (its launcher exes are a negligible footprint).

- **AI Agent header buttons (History / New Session / Stop) took up too much width.** They're now
  icon-only (matching the Debug panel's icon-only toolbar buttons), with the translated label moved to
  a tooltip instead of being lost — hover to see what each does.

- **CSV grid preview could stay open on a non-CSV file after a restart.** If the CSV/TSV grid tool
  window was left open in the previous session, it was force-reopened at startup (session restore) before
  the first real buffer loaded — at that point every buffer-gated tool window is provisionally marked
  "unavailable", so the reopened window's tracked availability and its actual open state diverged. The
  very next gating pass (on the restored tab) saw the tracked flag as already matching and skipped
  closing it, leaving the grid visibly open over a file that wasn't CSV until some other state change
  happened to flip it. `ToolWindowManager.setAvailable` now always reconciles the actual open/closed +
  stripe-button state instead of short-circuiting when its tracked flag already matches, so it self-heals
  regardless of how it got out of sync (mirrors the same gating used by the Commit/Markdown-Lint/TODO
  windows).

## [0.9.1] - 2026-07-06

First tagged release. (`v0.9.0` was cut and pushed first, but a JReleaser misconfiguration published it
as an immutable GitHub Release before the asset uploads failed; GitHub permanently reserves a tag once
it has backed an immutable release, even after both the release and the tag are deleted, so `v0.9.0`
itself can never be reused on this repository — see the fixes below. This release ships as `0.9.1`
instead.)

### Fixed

- **macOS release build failure on a pre-1.0 version.** jpackage rejects an `--app-version` whose first
  number is zero or negative, so a `0.x.y` `pom.xml` version (like this release's `0.9.1`) failed the
  macOS legs of the release build with "The first number in an app-version cannot be zero or negative" —
  on both the app-image build *and* the DMG wrap step, since jpackage enforces the rule on every
  invocation. The macOS build now passes jpackage a bumped placeholder (a leading `0.` to `1.`, e.g.
  `1.9.1`) purely to satisfy that CLI check, then immediately rewrites the built app's `Info.plist`
  (`CFBundleVersion`/`CFBundleShortVersionString`) back to the true version before wrapping the DMG — so
  the placeholder never reaches the delivered app: Finder "Get Info", `mdls`, System Settings, and the
  app's own `--version`/About dialog all correctly show `0.9.1`. Linux and Windows are untouched (jpackage
  accepts a leading-zero version there).

- **Release job failed to check out branch `main`.** JReleaser defaults to a branch named `main` when none
  is configured; this repo's default branch is `master`, so the final "Release" job failed with
  "Could not checkout branch main" even after every platform's installer build succeeded.
  `jreleaser.yml`'s `release.github` now sets `branch: master` explicitly.

- **Release job failed with "Cannot upload assets to an immutable release."** GitHub's Immutable Releases
  feature (GA October 2025) rejects any asset upload once a release is published, but JReleaser's default
  flow publishes the release *before* uploading each installer — so every asset upload 422'd and the whole
  release failed after all five platform builds had already succeeded. `jreleaser.yml`'s `release.github`
  now sets `immutableRelease: true`, which makes JReleaser create the release as a draft, upload every
  asset while it's still a mutable draft, then publish — the flow GitHub's own docs prescribe. This is
  mutually exclusive with the previous `overwrite: true` (removed), so re-running on the same tag now
  needs the prior release deleted first (`gh release delete v<version>`) rather than overwritten in place.

- **AI connection check no longer hangs on a slow endpoint.** The Settings → AI Actions health check is
  now a **non-streaming** request whose 30 s timeout bounds the *entire* exchange — so a local server
  that sends response headers immediately but is slow to produce the first token (LM Studio warming up a
  model) can no longer leave the status stuck on "Checking connection…". It resolves to green
  **Connected** or a red **Not working: …** (with the HTTP error / "timed out"). Real streaming AI
  requests also gained a 120 s time-to-first-response cap (unchanged for a healthy generation).

### Added

- **Open a `.patch`/`.diff` file in the structured diff viewer.** A right-click "Open in Diff Viewer"
  item (shown only for a `.patch`/`.diff` file, plus the palette-only `diff.openPatchFile` command) parses
  the active buffer's unified diff and opens its first file-section as a read-only side-by-side diff — with
  word-level highlighting and prev/next-change navigation — instead of just plain syntax-highlighted diff
  text. Works on the buffer's own (possibly unsaved) text; a multi-file patch shows a status note and just
  the first file.

- **Configurable TODO per-part colors.** The highlight color for a structured TODO comment's `[tag]` and
  each `(priority)` level (critical/high/medium/low) is now user-configurable — a **TODO Part Colors**
  section in Settings → Editor → TODO with five color pickers, plus a palette command `todo.setPartColor`
  (pick a part → `#RRGGBB`). Applies live to every buffer.

- **AceJump line-mode (avy-goto-line).** A new `nav.aceJumpLine` command (`M-g L`) labels every visible
  line at once — type a label to jump the caret to that line's first non-whitespace character. Complements
  the existing character-mode AceJump (`M-g j`).

- **MCP: `list_tabs` and `todo_scan` tools.** The MCP bridge gains two read tools — `list_tabs` (every
  open tab including non-editor tabs — Welcome/image/hex/diff — with each tab's type + which is active)
  and `todo_scan` (scans the project + open buffers for the configured TODO/FIXME highlight patterns,
  returning file/line/col/keyword/tag/priority/text) — bringing the tool count to fourteen.

- **External Tools: editor right-click submenu.** The editor context menu now shows an **External Tools**
  submenu listing every enabled tool, so you can run one on the active file without the palette or a
  keybinding (hidden when no tool is enabled or in Simple UI mode).

- **Find-in-Files popup: include/exclude globs.** The keyboard-first *Find in Files* popup now has the same
  comma-separated **include / exclude glob** fields as the tool window (e.g. `*.java, src/**` / `**/test/**`),
  narrowing the search live as you type.

- **Remote-file (SFTP) status indicator.** When the active file lives on a remote host, the status bar now
  shows a **⇅ SFTP** badge (with the `host:/path` in its tooltip; click it to manage connections), so it's
  clear at a glance that you're editing over SFTP. (The tab already showed a cloud icon + path tooltip.)

- **Macro recording indicator.** While recording a keyboard macro (`F3`), the status bar now shows a red
  **● REC** badge so it's obvious you're recording; click it to stop (or `F4`).

- **Log viewer: jump to next/previous error.** Two palette commands — **Log: Next Error** / **Log: Previous
  Error** — move the caret to the next/previous line at WARNING level or higher in a `.log` file (wrapping
  around), so you can hop between problems without scrolling.

- **AI connection status (green/red).** The Settings → AI Actions page now shows a live health-check
  indicator under the enable checkbox: green **Connected** when the configured provider/endpoint/key
  (and model) actually accept a request, red **Not working: …** with the error otherwise (a wrong
  endpoint path, a refused connection, a bad key, an unknown model). It checks when the page opens and
  re-checks — debounced — after you edit the provider, endpoint, key, or model, so a misconfigured
  local server (LM Studio/Ollama) or a typo'd URL is obvious immediately instead of failing silently at
  first use. Also a palette command **AI: Test Connection** that reports to the status bar. The probe is
  a tiny one-token request and never disturbs a real generation.

- **View: Open as Text** — a palette command that opens the current file as plain text, bypassing the
  image/hex viewer. The counterpart to *Open as Hex*: use it when a text file is mis-detected as binary, or
  to read a binary's raw text.

- **Local LLMs for the AI features (LM Studio, Ollama, vLLM).** The AI actions and inline completion
  can now talk to a **local OpenAI-compatible server** instead of the Anthropic API — pick
  *Local (OpenAI-compatible)* under Settings → AI Actions → **Provider**. The default endpoint is LM
  Studio's local server (`http://127.0.0.1:1234/v1/chat/completions`) and **no API key is required**;
  the endpoint is configurable for Ollama/vLLM/llama.cpp. Leave the model fields blank to use whatever
  model the server has loaded, or name one explicitly. The OpenAI chat-completions streaming dialect
  (SSE `data:` chunks → `[DONE]`, `choices[].delta.content`, `finish_reason`) is handled alongside the
  Anthropic Messages format; commit-message generation, explain/rewrite selection, and ghost-text
  completion all work against either. Palette: *AI: Set Provider* / *AI: Set Endpoint*. Schema 56→57,
  additive.

- **AI inline completion (ghost text).** After a short typing pause with the caret at the end of a
  line, a muted single-line AI suggestion appears at the caret — **Tab accepts it**, Esc or typing
  dismisses it (the same ghost-text presentation prose autocomplete already uses). Requests are
  strictly bounded: one per ~600 ms idle pause, a windowed prefix/suffix prompt, a one-line stop
  sequence, and a small output cap — and each keystroke supersedes the in-flight request, so stale
  suggestions never appear. Runs on its own fast model (default `claude-haiku-4-5`, configurable)
  independent of the AI-actions model, and never competes with the local snippet/dictionary popup.
  Off by default (Settings → AI Actions → "Enable AI inline completion"; palette "Toggle AI Inline
  Completion"); needs the AI Actions feature + an API key; inert in large files and read-only buffers.

- **AI actions — one-shot Anthropic-API features, no agent required.** Three streamed, palette-driven
  actions that call the Anthropic Messages API directly (a hand-rolled `java.net.http` client — no SDK,
  no new dependency): **AI: Generate Commit Message** turns the staged diff into a commit message and
  drops it into the Commit window for review; **AI: Explain Selection** streams an explanation of the
  selected code into a new Markdown buffer; **AI: Rewrite Selection…** rewrites the selection per your
  instruction as a **single undoable edit** (aborted safely if the buffer changed while generating), and
  **AI: Cancel** stops an in-flight generation. Off by default (Settings → AI Actions, Beta) with a
  configurable model (default `claude-opus-4-8`) and an API key from `ANTHROPIC_API_KEY` or a Settings
  override (stored in plain text — the environment variable is preferred). Separate from (and
  complementary to) the AI Agent chat window.

- **Auto Close Tags.** Typing the `>` that completes an HTML/XML open tag now inserts the matching
  closing tag after the caret (`<body` + `>` → `<body>|</body>`), with the caret left between the
  tags. Closers, self-closing `/>`, doctypes/comments, HTML void elements (`<br>`, `<img>`, …) and a
  `>` typed inside an attribute string are all left alone. Works in macro replay too. On by default
  for `.html`/`.xml`-family files (Settings → Editor, palette "Toggle Auto Close Tags").

- **Embedded AI agent (ACP) — chat with Claude Code inside Editora.** A new **AI Agent** tool window
  drives any [Agent Client Protocol](https://agentclientprotocol.com) agent over stdio — the default
  command is `claude-code-acp` (Claude Code's ACP adapter; `npm i -g @zed-industries/claude-code-acp`),
  and Gemini CLI or any other ACP agent works by changing the command. The transcript streams the
  agent's replies and tool activity; Enter sends, Shift+Enter inserts a newline; Stop cancels a turn and
  New Session starts fresh. The integration is editor-aware: the agent's **file reads serve open
  buffers' unsaved text**, and its **edits to open files apply as undoable buffer edits** (one `C-z`
  reverts; you review and save) — writes to files you don't have open go to disk and refresh the
  Project tree. Agent permission requests pop a dialog with the agent's own options. Off by default
  (Settings → AI Agent, Beta) with a configurable command; the agent process is tracked like the LSP
  servers (killed on window close / app exit, reaped after a crash). Commands: **AI Agent** window
  toggle, *Agent: New Session*, *Agent: Stop*, *Toggle AI Agent*, *Agent: Set Command*.

- **MCP server: six new tools — an AI agent can now edit, save, and navigate, not just read.** The
  embedded Model Context Protocol server grows from six to twelve tools: `edit_buffer` applies an
  **undoable** text edit to an open buffer (exact-match str-replace — the old text must occur exactly
  once unless `replace_all` — or a whole-buffer rewrite; one `C-z` reverts it), `save_buffer` writes an
  open buffer to disk (untitled buffers are refused rather than popping a Save-As dialog), `open_file`
  opens a file and optionally jumps to a line/column, `get_selection` reports the active buffer's caret
  and selection, `document_symbols` returns a file's LSP symbol outline, and `git_status` reports the
  branch, ahead/behind counts, and changed files. The security-notice dialog now discloses that a
  connected agent can edit and save files, not just read them. Everything stays loopback-only +
  bearer-token-gated and off by default (Settings → MCP Server).

- **Auto Rename Tag** (the VS Code behavior). Editing an HTML/XML tag name now renames the paired
  open/close tag live as you type — nested and same-name sibling tags pair correctly, real-world HTML's
  unclosed optional-close tags (`<li>`, `<p>`, …) don't break the pairing, comments, CDATA,
  doctypes, quoted attribute values, self-closing tags, HTML void elements (`<br>`, `<img>`, …) and
  `<script>`/`<style>` content are all skipped, and an old-name guard makes sure typing a brand-new tag
  never renames an unrelated one. On by default for `.html`/`.xml`-family files (Settings → Editor,
  palette "Toggle Auto Rename Tag"); inert in read-only and very large files.

- **String manipulation commands** (the JetBrains *String Manipulation* plugin family). Case-style
  conversions on the selection — or the identifier at the caret — to **camelCase / PascalCase / snake_case /
  SCREAMING_SNAKE_CASE / kebab-case / dot.case**, plus **Cycle Case Style** (repeated invocations step the
  same token through the styles) and **Swap Case**; and whole-line transforms on the selected lines (or the
  whole file): **sort ascending/descending** (numeric-aware — `file2` before `file10` — and
  case-insensitive), **sort by length**, **reverse**, **shuffle**, **remove duplicate lines**, **remove
  empty lines**, and **trim trailing whitespace**. Every operation is its own palette command and
  key-bindable; **Edit: String Manipulation…** (`C-c x`) opens one filterable picker over all of them.
  Each transform applies as a single undoable edit and re-selects its result.

- **Hex viewer for binary files.** Opening a binary file (an executable, archive, `.class`, `.pdf`, …) now
  shows a read-only `offset | hex | ASCII` dump instead of dumping its bytes as garbage text — completing the
  image-viewer story. Binaries are detected by content (a NUL byte / high control-character density, with a
  BOM guard so UTF-16 text isn't misrouted), large files show their first slice with a truncation note, and
  the **View: Open as Hex** command force-opens any file's bytes as hex. Hex tabs restore across sessions.

- **More default TODO keywords + structured comments (Comment Manager).** The built-in TODO
  highlight keywords now include **HACK, NOTE, XXX** and **DONE** alongside TODO/FIXME (existing custom
  patterns are kept; the defaults grow on upgrade). Editora now recognizes the IntelliJ-style
  `KEYWORD [tag] (priority) description` comment format — e.g. `// TODO [auth] (high) fix token refresh` —
  and **colors each part** in the editor: the keyword in its pattern color, the `[tag]` in blue (underlined),
  and the `(priority)` by level (critical→red, high→orange, medium→amber, low→cyan). The **TODO tool window**
  now shows each match with those parts colored and a **"Group by"** selector — **File, Priority, Tag, or
  Keyword** — so you can, for example, see every critical item across the project together. **Right-click a
  match** to edit it directly in your source: **Mark Done** (rewrites the keyword to `DONE`) / **Reopen**,
  **set the priority**, or **edit the description** — each applied as one undoable edit to the file.

- **Image viewer.** Opening a raster image (`.png`, `.jpg`/`.jpeg`, `.gif`, `.bmp`) now renders the picture in
  a read-only tab instead of dumping its binary bytes into a text buffer. A small top bar zooms out / in /
  fit-to-window / actual size (and **Ctrl**+mouse-wheel zooms); large images scroll, small ones center. SVG is
  deliberately left as editable text. Image tabs are restored across sessions like any other tab.

- **Filter/search box focused on open.** Opening the **Project**, **Structure**, **Bookmarks**, or **Personal
  Notes** tool window now puts keyboard focus on its filter/search box so you can type to filter immediately;
  **Down** moves into the results and **Enter** opens the selected (or first) one.

- **Project filter skips .gitignore.** The Project tool window's filter now skips `.gitignore`d files and
  folders (`target/`, `node_modules/`, `*.log`, …) by default — governed by the same "respect .gitignore"
  search setting. The full project tree still shows every file.

- **Active file first in Problems & TODO.** In the Problems and TODO tool windows, the file you're currently
  editing now sorts to the top of the list, so its diagnostics / TODOs are the first thing you see (matching
  the IDE convention). Re-orders automatically as you switch tabs.

- **macOS "Open With" support.** The packaged `Editora.app` now registers as a handler for common text and
  source files, so Editora appears in Finder's right-click **Open With** menu (and the "Always Open With"
  picker). Opening a file that way launches or focuses Editora and opens the file — even though macOS delivers
  it as an Apple Event rather than a command-line argument. It's offered as an *alternate* handler, so it
  doesn't take over your existing default editor. (Move `Editora.app` to `/Applications` so macOS registers it.)

- **More plain-text developer formats.** Syntax highlighting for **unified diffs** (`.diff`/`.patch` —
  added/removed lines tint green/red, hunk headers stand out), **Makefiles** (`Makefile`/`GNUmakefile`/`.mk`),
  **justfiles**, **Protocol Buffers** (`.proto`), **GraphQL** (`.graphql`/`.gql`), and **`.gitattributes`**
  (including a repo's `.git/info/attributes`). Java **`.properties`** files get a dedicated grammar
  (`:` and `=` separators, Unicode escapes, backslash line-continuations) instead of borrowing the INI one.
  Proto and GraphQL also fold on braces and auto-indent like C-family code, and comment toggling
  (`M-;`) works in all of them.

- **Compare / Annotate in the Project tree.** The Project tool window's file **Git** submenu now also offers
  **Compare with Branch…** (diff the file against its version on any local or remote branch), **Compare with
  Revision…** (diff against a commit from the file's history), and **Annotate** (open the file and show inline
  Git blame) — alongside the existing Compare with HEAD.

- **Git actions in the Project tree.** Right-clicking a file in the Project tool window now offers a **Git**
  submenu with **Stage**, **Unstage**, **Revert…** (discard changes — untracked files are removed, tracked
  files reset to HEAD, with a confirmation), and **Add to .gitignore**, alongside Compare with HEAD and Show
  File History. Folders offer **Stage** and **Revert…** of the whole subtree. Actions enable or disable based
  on the file's real Git status, and the whole submenu is hidden when the folder isn't a Git repository.

- **Git status colors in the Project tree.** Files in the Project tool window are now colored by their Git
  status — added (green), modified (blue), deleted (gray), renamed (violet), and untracked (olive) — and a
  folder that contains changes is tinted, just like IntelliJ. Updates as you edit, stage, commit, or switch
  branches; rides the Git integration (nothing to turn on).

- **References tool window.** Find References now shows its results in a dedicated, browsable **References**
  tool window — grouped by file, with a line + preview per reference — instead of a one-shot picker. Enter or
  double-click jumps to a reference; if there's only a single reference, it jumps straight there.

- **Go to Symbol in Workspace.** A new command opens a live search box that finds any symbol (class,
  function, method, …) across the whole project by name and jumps to it — the LSP `workspace/symbol` feature,
  like VS Code's `Ctrl-T` / IntelliJ's `Ctrl-N`. Available from the command palette in a code file with a
  running language server.

- **Ctrl/Cmd-click to go to definition.** In a code file with a running language server, holding
  Ctrl (or ⌘ on macOS) and clicking a symbol now jumps to its definition — the same gesture as VS Code /
  IntelliJ. It reuses the existing Go to Definition, so it also works across files and records a jump you can
  step back from (Back / `nav.back`).

- **Markdown table: export to CSV / Excel / ODF file.** The editor's right-click **Table** submenu (and the
  command palette) now export the Markdown table under the cursor to a **`.csv`**, **Excel `.xlsx`**, or
  **OpenDocument `.ods`** file — alongside the existing "Copy as CSV" (clipboard).

- **CSV grid: columns fit their content.** The CSV Grid now sizes each column to the width of its widest
  cell (or header), so you rarely need to drag a column edge to read the data. Very long values are capped so
  one giant field can't push the rest of the table off-screen, and you can still resize any column by hand.

- **CSV: align / shrink columns.** Two new commands reformat a `.csv`/`.tsv` file so it's easy to read as a
  table: **CSV: Align Columns** pads each field with spaces so the delimiters line up in the editor, and
  **CSV: Shrink Columns** removes that padding again (fully reversible). Both preserve quoted fields, blank
  lines, and the trailing newline, and are undoable. (Files with a quoted field that spans multiple lines
  are left alone, since aligning them would split a record across lines.)

- **Rainbow CSV columns.** `.csv`/`.tsv` files are now colored **per column** in the editor — each column
  gets a distinct color that cycles every eight, so field boundaries are easy to follow (like VS Code's
  Rainbow CSV). On by default; toggle in Settings → Editor → CSV or the **Toggle Rainbow CSV Columns**
  command.

- **CSV grid: sort, filter, and inconsistent-row highlighting.** The CSV Grid now has a filter box that
  live-filters rows as you type, a right-click **Sort Ascending / Descending / Clear Sort** on any column
  (numbers sort numerically), and rows whose field count doesn't match the header are tinted red with an
  "N inconsistent" count in the summary line — so ragged CSVs are easy to spot. Sorting and filtering only
  change the view; editing a cell and jumping to the editor still land on the right line.

- **CSV grid: export menu.** Right-click the CSV Grid to **Export to PDF**, **Print**, **Export to Excel
  (.xlsx)**, or **Export to an ODF spreadsheet (.ods)** (also available as palette commands). Numbers are
  written as real spreadsheet numbers where safe — leading-zero codes (like ZIP codes) and
  thousands-separated values stay text so they aren't corrupted — and the header row is bold.

- **CSV grid: editable headers + spreadsheet polish.** Column headers in the CSV Grid are now editable —
  double-click a header, type a new name, and it's written back to the file's first row. The grid also got a
  more spreadsheet-like look: a "#" row-number gutter, zebra striping, bold headers, and right-aligned
  numeric columns.

- **CSV / TSV syntax highlighting.** `.csv` and `.tsv` files now open as a proper "csv" language instead of
  plain text: quoted fields are colored as strings (with the `""` escape), bare numeric fields as numbers,
  and the field separators (comma / semicolon / tab / pipe) get a muted tint so column boundaries stand
  out. First step of broader CSV support (a column readout, a table/grid preview, and grid editing are
  planned next).
- **CSV / TSV column readout.** For a `.csv`/`.tsv` file the status bar shows a "Field N of M" segment —
  which column the caret is in and how many the row has (RFC-4180 quote-aware, delimiter auto-detected).
  Clicking the segment, or the new **CSV: Copy as Markdown Table** command, copies the whole file to the
  clipboard as a GitHub-flavored Markdown table.
- **CSV / TSV grid preview.** A new **CSV Grid** tool window shows the active `.csv`/`.tsv` file as a
  read-only spreadsheet: a first-row-as-header toggle, a summary line (row/column counts + how many
  columns are numeric vs. text), and double-click / Enter on a cell jumps the editor caret to that field.
  Refreshes as you edit; on by default for CSV/TSV files (Settings → Editor → CSV, or the
  **Toggle CSV Grid Preview** command).
- **Editable CSV grid.** The CSV Grid is now editable: double-click a cell, type a new value, and it writes
  back to the file (undoable, with proper RFC-4180 quoting). Editing is available when the file is writable
  and has no quoted multi-line fields; otherwise the grid stays read-only. Right-click **Reveal in editor**
  jumps to a cell's location either way.

### Changed

- **Local File History is now entirely in its tool window — no more separate window.** Opening a
  revision's diff used to spawn a standalone "Local History" window alongside the File History tool
  window. The tool window is now a split: the revision list on the left, and the live side-by-side diff of
  the selected revision vs the current file on the right — with the Ignore-whitespace / Highlight-words
  toggles, the "N differences" count, the per-hunk apply chevrons (selective restore), and the whole-file
  Revert, all in one place. Selecting a revision shows its diff inline; nothing pops out into a second
  window.

### Fixed

- **Blank editor after switching tabs.** Clicking a tab could occasionally show a blank editor until you
  clicked it a second time to give it focus. The editor area now repaints on the layout pulse right after the
  tab is shown, so its content appears on the first click (without moving the scroll position or stealing
  focus from a Find-in-Files preview).

- **Markdown print preview: tables no longer get pushed to their own page.** When printing (or print-
  previewing) a Markdown file, a table could be bumped onto the next page even when it easily fit under the
  preceding heading, leaving the first page mostly blank. The page-layout measurement was collapsing the
  table's columns and over-estimating its height; it now measures at the real page width, so the printed
  output matches the on-screen preview.

### Added

- **Find in Files popup.** A keyboard-first overlay for multi-file content search (command
  **"Find in Files (Popup)"**, palette-discoverable) — type a query, see results grouped by file, and
  press Enter to jump straight to a match (Enter on a file header opens that file); Esc closes. It has
  case / regex / whole-word toggles (no replace) and an editable **search-folder** field that defaults to
  the project root in a project window, or the current file's folder otherwise, and can be pointed at any
  folder. Reuses the same fast (ripgrep-backed) search engine as the Find in Files tool window and shares
  its search history, and shows a **ripgrep** badge when that backend is active.

- **Bundled "Shell Script" and "Zsh Script" file templates.** New File From Template now includes shell
  starters: a **Bash** one (`#!/usr/bin/env bash`, `set -euo pipefail`, a `main`/`main "$@"` scaffold →
  `${baseName:script}.sh`) and a **Zsh** one (`#!/usr/bin/env zsh`, `emulate -L zsh` +
  `setopt errexit nounset pipefail`, the same `main` scaffold → `${baseName:script}.zsh`); both prompt for
  the file name. Available from the template picker, the toolbar, and the project-tree "New From Template…"
  menu.

- **Doc comments in the Structure tool window.** Each item in the Structure outline now shows its leading
  documentation comment as a hover tooltip when one is present — Javadoc/JSDoc/PHPDoc and plain block
  comments (`/** … */`, `/* … */`), and runs of `//`, `#`, or `--` line comments above the declaration.
  Annotation/decorator lines (`@Override`, `#[derive(...)]`, …) between the comment and the declaration are
  skipped, and long comments are trimmed. Works for both LSP-served and heuristic outlines.

### Fixed

- **New File From Template no longer doubles the extension.** When a template's file-name pattern ends in
  an extension (e.g. `${baseName}.md`) and you typed a name that already included it (`notes.md`), the new
  file came out as `notes.md.md`. The engine now collapses a doubled trailing extension — but only when the
  two extensions are identical, so `types.d.ts`, `foo.tar.gz`, and `app.min.js` are left alone.

- **Release builds package from a clean tree, fixing a rare "dead keyboard" in the installer.** The
  native-installer build fed jlink from an incrementally-compiled `target/`, where a synthetic
  `$SwitchMap` class (emitted for a `switch` over an enum, e.g. the key dispatcher's) could go missing if
  a background compiler or an interrupted build left `target/classes` inconsistent — javac won't
  regenerate it while the source looks up-to-date. The packaged app then threw a `ClassNotFoundException`
  on the first keypress. The release workflow now runs `clean` before `-Pdist`, guaranteeing every class
  is regenerated. (Only the native installers could be affected, and only when built from a dirty tree;
  `mvn javafx:run` and the fat jar were never affected.)

- **Markdown "Insert Table" size picker no longer fills the editor.** The grid picker was stretched to
  cover the whole code area; it now hugs the grid (with padding) and is centered.

- **Structure tool window: methods no longer show a doubled `()`.** When a language server (e.g. jdtls)
  already includes the parameter list in a method's name (`setX(Foo)`), the outline no longer appended a
  redundant empty `()` — so it reads `setX(Foo)` instead of `setX(Foo)()`.

### Added

- **User-defined themes from the config folder.** Drop a theme CSS in your config directory and it shows up in the theme pickers, named from the file (`my-cool-theme.css` → "My Cool Theme"). Two folders: **`<configDir>/themes/*.css`** for a full AtlantaFX theme (a self-contained stylesheet defining `-color-*`) — it becomes both the app theme and its editor colors via the adaptive syntax palette (base colors parsed from the CSS); and **`<configDir>/editor-themes/*.css`** for a hand-authored editor override (`.editor-area` + `.text.<class>` rules, like the bundled ones) that appears in the Editor Theme picker only. Themes load at startup; the palette command **"Theme: Reload User Themes"** re-scans without a restart.

- **Per-hunk selective restore in the Local History window.** The window's diff is now editable on the current-file side — the IntelliJ-style **apply chevrons** are back, so you can copy an individual fragment from a past revision into the file (single chevron per changed row, double at each hunk start), routed through the undoable buffer with Undo/Save. The diff re-computes after each apply so the remaining chevrons update; the whole-file **Revert** button still does a full restore.

- **IntelliJ-style Local History window.** Opening a file's local history (double-click a revision in the File History tool window, or "Compare with Current") now opens a dedicated window with the revision list on the left and a live side-by-side diff of the selected revision vs. the current file on the right — selecting a revision re-diffs instantly. A toolbar shows the revision timestamp, an "N differences" count, **Ignore whitespace** and **Highlight words** toggles (both re-diff), and a **Revert** button (undoable whole-file restore); a **Search by content** box filters the revision list. The diff pane keeps its side-by-side/unified toggle and prev/next-change navigation. Backed by a new `DiffEngine.DiffOptions` (ignore-whitespace normalizes lines one-to-one so the original text still renders; word-level toggles the intra-line emphasis).

- **19 new color themes.** The theme picker gains the MIT-licensed AtlantaFX community themes from the
  DLSC collection — **Blue**, **Navy**, **Army**, and the four **seasonal** sets (**Spring**, **Summer**,
  **Fall**, **Winter**) in light/dark pairs, plus **Autumn**, **Browny**, **News** (dark) and **Yacht**
  (light) — bringing the total to **26 app themes**. Each ships a matching syntax-highlighting theme: a
  single **adaptive** editor palette draws every token color from the active theme's own AtlantaFX
  `-color-*` variables, so the editor surface and syntax colors always coordinate with the chrome. Pick
  one from Settings → Appearance or the `appearance.setTheme` / `appearance.setEditorTheme` palette
  commands. (The GitHub colorblind/tritanopia variants are intentionally excluded — an adaptive
  red/green token palette would defeat their accessibility purpose, and GitHub's default duplicates the
  existing Primer Light.)

### Performance

- **Dirty-state tracking no longer allocates the whole document on every keystroke.** The editor's modified-flag listener was bound to RichTextFX's `textProperty()`, which forces the full document `String` to be materialized on each change — an O(n) allocation per character on a very large single buffer (e.g. minified JS/CSS or a one-line JSON/log that slips past the line-count heavy-file tier). It now runs off `plainTextChanges` with a cheap `getLength()` gate, so the full text is only built in the rare near-clean state, never while typing. Behavior is unchanged (reverting an edit still clears the marker).

### Internal

- **Broadened the headless-FX harness over the Run + External-Tool console panels.** New `RunPanelFxTest` and `ExternalToolPanelFxTest` exercise the console lifecycle (started → appendOutput stdout/stderr → finished/failed, Stop-button enablement) and the one-shot tool console (command header + stdout + stderr + status line, clear).
- **Broadened the headless-FX harness over the Notes + TODO panels.** New `NotesPanelFxTest` and `TodoPanelFxTest` feed an in-memory note bucket / a `TodoService.Outcome` into the panels and assert the rendered file→note / file→match grouping, empty-input handling, the note-body filter, and the TODO summary label.
- **Broadened the headless-FX harness over the Structure + Bookmarks panels.** New `StructurePanelFxTest` and `BookmarksPanelFxTest` feed an LSP `SymbolNode` tree / an in-memory bookmark bucket into the panels and assert the rendered hierarchy — Structure's class→methods outline (and that symbols for a non-attached buffer are ignored); Bookmarks' file→bookmark grouping, empty-file skipping, and the name filter.
- **Broadened the headless-FX harness over the Git + Problems tool-window panels.** New `GitPanelFxTest` and `ProblemsPanelFxTest` feed pure-record data (`GitStatus` / `LspDiagnostic` maps) into the panels and assert the rendered grouping — Git's Staged/Changes/Untracked tree, branch label, and commit-button enablement (incl. clean / not-a-repo states); Problems' language→file→diagnostic tree plus empty-input handling — using no-op `Actions` stubs (no live git / LSP).
- **Broadened the headless-FX harness over `MainController` command dispatch.** A new `MainControllerCommandsFxTest` drives service-free commands through the real `CommandRegistry` against a fully-wired window (text zoom, the read-only toggle, line-numbers/minimap/whitespace view toggles, select-all), covering the `ui` command-execution surface that the palette + keymap share.
- **Broadened the headless-FX harness over `EditorBuffer`.** A new `EditorBufferFxTest` exercises Markdown view-mode switching (incl. the minimap-hidden-in-preview rule), read-only / view mode, programmatic typing through the macro-replay path (with auto-close), per-language run-glyph detection (compact Java / Python / gated shell), and display-name/dirty/EOL bookkeeping — raising `editor`-package coverage with no new production code beyond two helper-visibility tweaks.
- **Test coverage: added unit tests + ratcheted the JaCoCo floors.** New tests for `config` models/stores (`Breakpoint`, `BreakpointStore`, `RecentFiles`, plus `FileIdentity` null/empty branches) raised `config` line coverage to ~88%, and new `InstallService` tests cover `findBinary`/`makeExecutable`/`Result`. The per-package `jacoco-check` floors were ratcheted to sit a few points below the measured coverage (migration·diff 0.85→0.90, config split out at 0.86, template·editorconfig 0.80→0.82, completion·http·pdf 0.68→0.70).

### Changed
- **Indent detection now defaults to spaces when a file has no indentation**, matching VSCode/IntelliJ ("spaces unless the file is detected to use tabs"). A file whose first indented line uses a tab still gets tabs, and spaces still get spaces; only the no-evidence case (empty or flat file) flipped from tab to spaces. This affects the default `detect` indent style; an explicit `space`/`tab` setting or `.editorconfig` still wins.
- **Settings → Snippets: the language picker is now a plain dropdown** (no longer an editable/typing combo box) — it lists the curated languages plus any that already have a user snippet file, and you select from it rather than typing.
- **The Structure and Bookmarks tool-window filter fields now have a clear (✕) button**, matching the Personal Notes and Project panels. It appears only while the filter has text and clears it on click.
- **Settings → Keymaps → Customize shortcuts now reveals the Record/Reset buttons only for the selected row.** Every command row used to show both buttons, cluttering the long list; a row's actions now appear only when you click to select it (the selected row is highlighted), so the list reads as a clean command + chord table.
- **Save As from the command palette (or a keybinding) now prompts for the path in-scene** instead of opening the native file chooser, keeping the keyboard flow mouse-free. The field is pre-filled with the current file's path (or the project folder + suggested name for an untitled buffer); a bare name resolves against that folder and `~` expands to home. The **toolbar Save-As button still opens the native file chooser** (it's a mouse action), and overwriting a different existing file asks for confirmation.

### Fixed
- **File templates now prompt for a variable used in the file-name pattern.** A single-file template whose file name was e.g. `${baseName:Main}.java` never asked for `baseName` at creation time, so it silently used the default and always produced `Main.java` — even though the same variable worked in the body. The file-identity built-ins (`baseName`/`fileName`/`extension`) can't be derived for a brand-new file, so when they appear in the *file-name pattern* they're now prompted in the wizard (the answer flows into both the file name and the body); used only in the body, they're still auto-derived as before.
- **Markdown-preview code blocks stay readable when the preview theme is toggled independently.** Code-block syntax colors followed the *editor* theme, so forcing the preview to the opposite brightness (the sun/moon toggle) left dark tokens on a light code background (or vice versa). They now follow the preview's own light/dark — a fixed GitHub Primer palette matching the preview's prose — while still tracking the editor theme when the preview follows the app.
- **The 80-column ruler no longer jumps to the left edge when word wrap is on.** The ruler derived its per-column width from the caret x at the start vs. the end of the longest visible line; with wrap on, that line spans multiple visual rows, so the two points sat on different rows and the width collapsed — planting the ruler near column 13 instead of 80. It now measures the advance on a single visual row (falling back to an adjacent-column advance when the line wraps), so the ruler stays at the correct column in both modes.
- **Settings labels no longer truncate.** A long settings-row label (e.g. "Max size / project (MB)" under Workspace → Local History) was clipped to "…"; the label column now keeps its alignment floor but grows to fit longer labels (and translations). The Settings window also has a minimum size now, so wide rows (e.g. the large-file threshold row + its unit) can't be clipped by shrinking it.
- **Flaky FX test fixed.** The headless-FX harness could intermittently error with `NoSuchFileException: settings.toml.tmp` — it now flushes pending async config writes before deleting the temp config dir (`FxWindowFixture`).
- **Clearer Mermaid render error when headless Chrome is missing.** A failed `mmdc` render whose error is the Puppeteer "Could not find Chrome" stack now leads with a one-line fix (the `npx puppeteer browsers install chrome-headless-shell` command).
- **Multi-line `$$…$$` block math now exports as an image** to PDF/DOCX/ODT. A display-math block with the `$$` delimiters on their own lines was exported as raw text (the export's `soleDisplayMath` bailed on the soft line breaks); single-line `$$…$$` and inline `$…$` were unaffected.
- **Markdown preview: multi-line `$$…$$` block math now renders.** A display-math block with the `$$` delimiters on their own lines was shown as raw text (the paragraph scanner bailed on the soft line breaks); single-line `$$…$$` and inline `$…$` were unaffected.
- **PDF export now works in packaged builds.** PDFBox + pdfbox-io + fontbox all log via commons-logging, which jlink was silently pruning (jdeps dropped the `requires` under `--ignore-missing-deps`), so every packaged PDF export failed with `NoClassDefFoundError: org/apache/commons/logging/LogFactory`. Fixed with hand-written module descriptors; also made the PDF/print/office export services catch `Throwable` so a failure reports instead of hanging the status.
- **Minimap now shows in Markdown SPLIT view.** It had been hidden whenever the preview was on (gated to `EDITOR` mode only); it now hides only in full `PREVIEW` (no editor surface), and stays visible in SPLIT where the editor is on screen.
- **Markdown → PDF export no longer fails on SVG images.** A badge/image served as SVG (e.g. shields.io) made the whole export throw (`IllegalArgumentException: Image type UNKNOWN`); SVG images are now rasterized to PNG (via the same JSVG path the on-screen preview uses) and any undecodable image degrades to alt text instead of aborting.
- **Tool-window consoles really match the editor font now.** The `.run-output`/`.debug-console` CSS carried a `-fx-font-size` that overrode the programmatic font on every CSS pass, so the External Tools / Run / Debug consoles kept rendering at 12px regardless. Removed the font from those CSS rules so the editor-matched `setFont` sticks (guarded by a new `ConsoleFontFxTest`).
- **Tool-window consoles now actually pick up the editor font.** The previous attempt set `-fx-font-size` via `setStyle`, which a JavaFX `TextArea` ignores; the consoles (External Tools / Run / Debug) now set the control's `font` property directly so they match the editor's family + size.
- **Command palette: clicking a result now runs it.** A card-wide `MOUSE_CLICKED` consume filter was swallowing the result cells' click-to-run handler (only Enter worked).
- **Command palette: removed the stray separator line above the command description.**

- **Project / Current-Folder filter now finds top-level files in a large root.** The filter-box search walked the tree depth-first, so under a big root (e.g. a home directory with huge `.cache`/`.m2`/`node_modules` subtrees) it exhausted its visit budget deep inside one subtree before ever reaching a shallow file like `.profile` — which then never appeared in the results. The search is now breadth-first, visiting every shallower entry before descending, so top-level matches are never starved by a deep sibling subtree.

- **Hardened several spots against NullPointerExceptions from corrupted config or non-conforming servers** (found via a full-codebase null-flow audit). `Macro` now defaults a missing `name` to `""`, so a hand-edited/corrupted `macros.json` entry without a name can't NPE macro lookups (find/save/delete). `SnippetManager` skips a snippet file whose content is the literal `null` instead of throwing. `LspManager` go-to-definition / find-references guard a `Location`/`LocationLink` range that a non-conforming language server leaves null. `DapClient.evaluate` tolerates a null adapter response like `evaluateFull` already did. (The wider audit found the `ui`/`editor`/`MainController` packages already guard nullable returns consistently.)

- **Hardened the Bookmarks drag-reorder against a theoretical NPE.** `handleDrop` dereferenced a dragged mark row's `getParent()` directly; it's always non-null today (a mark is always under a file group), but a defensive guard now returns early if a detached row is ever dropped, so a future tree-shape change can't turn it into a crash.

- **Markdown preview no longer renders HTML comments.** `<!-- … -->` blocks (and inline comments) were shown as visible text/code in the preview (and PDF/print export); they're now hidden, matching GitHub and every other Markdown renderer.
- **Markdown preview hides the editor minimap.** In Split/Preview mode the minimap was wedged between the editor and the preview; it's now hidden while the preview is shown (the preview is the overview) and restored when you return to the plain editor view.

- **The "install language support?" banner no longer shows when the language server is actually installed and running.** It was reading the server-availability flag before startup detection finished (and never re-checking), so it could falsely claim e.g. Java support was missing while jdtls was serving the file. The banner now only appears once a server is *confirmed* absent (not merely "not probed yet"), never when a live session is already serving the file, and re-evaluates as soon as detection settles. Also fixed the banner text dropping its apostrophe ("isnt") — the message runs through `MessageFormat`, where `'` must be doubled.

### Added
- **Drag a raw image out of a web browser into a Markdown file.** Alongside dropping image files and pasting a clipboard image, you can now drag an image straight from a web page onto a Markdown editor — Editora downloads it (off the UI thread) into the sibling `assets/` folder and inserts `![](assets/…)`. It resolves the source from the drag's URL, an `<img src>` in the dragged HTML, or a `data:` URI; a raw dropped image is encoded to PNG; if the download fails it falls back to inserting the remote URL. (Requires the Markdown file to be saved, so `assets/` has a home.)
- **Syntax highlighting in Markdown-preview code blocks.** A fenced code block with a language (`` ```java ``, `` ```python ``, `` ```js ``, …) is now tokenized with the same TextMate engine + theme the editor uses, so the preview shows colored code instead of flat monospace. Unknown/absent languages and indented blocks stay plain; very large blocks skip highlighting.
- **Save as Administrator (Linux & macOS).** Editing a file you lack write permission for (e.g. one under `/etc`) now offers to save it via the OS's own authentication prompt — Editora never sees the password. On Linux `pkexec`/polkit handles it; on macOS AppleScript's `do shell script … with administrator privileges` shows the native Touch ID / password prompt. The View-mode banner on a read-only-on-disk file shows **Edit as Administrator**, and `Ctrl-S` (or the `File: Save as Administrator` palette command) then writes through the elevated helper, which rewrites the file in place so its owner and permissions are preserved. Off by default (Settings → Editor → Saving; palette `Toggle Save as Administrator`); Windows isn't supported; each elevated save prompts for authentication.
- **Shebang language detection.** A file with no telling extension (e.g. `shellscript`, `myscript`) whose first line is an interpreter shebang now gets the right language — syntax highlighting, folding, run gutter, and LSP all light up. Recognizes direct paths (`#!/bin/bash`), `env` lookups (`#!/usr/bin/env python3`), and `env -S` with args, mapping the interpreter (version-stripped: `python3.12`→python) to shell (bash/zsh/sh/dash/ksh/…), python, javascript (node), typescript (deno/bun/ts-node/tsx), ruby, php, lua, groovy, or powershell. A `#!/usr/bin/env -S java --source NN` line is treated as a **Java compact source file** (JEP 512) — highlighting plus a Run ▶ that launches `java --source NN <file>`. Only applies when the extension didn't already decide the language (never overrides a real type), and the status-bar language picker still overrides manually.
- **Back / forward navigation (jump list).** A browser-style history of caret jumps — going to a definition, a search hit, a file-history location, a diagnostic, or any go-to-line records where you were, and `Go: Back` / `Go: Forward` (palette commands, user-bindable) walk the trail. A new jump after going back forks the history (drops the forward stack), consecutive same-file+line jumps are deduped, and the trail is capped.
- **Word wrap.** Toggle soft wrapping of long lines (`View: Toggle Word Wrap`, Settings → Editor → "Word wrap"; default off, per buffer/window). The 80-column ruler stays visible when wrap is on.
- **Plugin API Javadoc build.** `./mvnw -Papidocs javadoc:javadoc` generates the `com.editora.plugin` API docs and doclint-validates their references/HTML (a broken `@link` in the public API fails the build). Also added the missing class header on `App` (100% type-level Javadoc).
- **Inline math (`$…$`) exports as an image to PDF, DOCX and ODT.** Previously only block `$$…$$` math embedded; inline math now renders to a PNG inline in all three exporters (falls back to literal text when math support is off).
- **Markdown table of contents.** `markdown.toc` inserts a TOC (nested heading links, GitHub-style anchors) wrapped in `<!-- toc -->` markers, or regenerates it in place on re-run — palette command + the right-click Markdown menu. (Moves the former `markdown-toc` plugin into the editor core.)
- **Markdown table ↔ CSV.** Convert selected CSV (or the clipboard) into a GFM table (`markdown.tableFromCsv`; delimiter auto-detected, RFC-4180 quoting) and copy the table at the caret to the clipboard as CSV (`markdown.tableToCsv`) — palette commands + the right-click Table menu.
- **Smart Backspace clears an empty Markdown list/quote marker.** Pressing Backspace at the end of an empty `- `/`1. `/`> `/`- [ ] ` item now deletes the whole marker (→ a blank line), instead of removing one space at a time.
- **Export the Markdown preview to MS Word (`.docx`) and OpenDocument Text (`.odt`).** From the preview right-click menu or the `preview.exportDocx` / `preview.exportOdt` commands — headings, text styling, lists, quotes, code, GFM tables, and local images. `.docx` uses Apache POI; `.odt` is generated natively. Mermaid diagrams, `$$…$$` math, and images (incl. SVG badges) embed as pictures; the packaged installer build is supported (device-tested).
- **Markdown table tools.** Insert a table from a Typora/Word-style grid size-picker (format-bar button + `markdown.insertTable`), add/delete the caret's row or column, and set a column's alignment (left/center/right) — via the editor right-click **Table** submenu and palette commands. Tab-to-next-cell and add-row-on-Enter already worked; tables stay auto-aligned after every edit. The command-palette Insert-Table is a keyboard-only `RxC` size prompt (e.g. `4x4`), while the format-bar button and right-click menu use the mouse grid picker.
- **Markdown format bar now uses icons** (the same glyphs as the editor right-click menu) instead of letter labels, and gained a **checkbox / task-list button** that toggles a GFM `- [ ] ` item (command `markdown.taskList`, `C-c C-s k`).
- **Generic `rc` config files now highlight as INI.** An unrecognized runtime-config file whose name ends in `rc` (extension-less or a single-dot dotfile — e.g. `.vimrc`, `.inputrc`, `.screenrc`, `.curlrc`, `/etc/inputrc`) is parsed as INI. Specific rc formats still win (`.bashrc`=shell, `.npmrc`=ini, `.babelrc`=json, `.condarc`=yaml).
- **Jump to next/previous TODO** — `todo.next` / `todo.previous` (`M-g ]` / `M-g [`) move the caret between TODO/FIXME (highlight-pattern) matches in the active file, wrapping around.
- **Clickable inline note marker** — click a personal note's amber start triangle to edit it (restores the click-to-edit the old gutter marker had).
- **`notes.toggleResolved`** — resolve/reopen the personal note at the caret from the keyboard (was panel-only).
- **Keybindings for `notes.editNote` (`C-c e`), `notes.search` (`M-g s`), and `undoHistory.jump` (`M-g v`)** — previously palette-only.

- **Undo History popup.** Besides the Undo History tool window, the new `undoHistory.jump` palette command opens the active buffer's edit checkpoints as a filterable popup (caret-line preview + capture time); pick one to jump back to that state (undoable). Same checkpoints, a faster keyboard-driven path.

- **Clear (✕) button on the Project / Current-Folder filter.** A small clear button appears at the right of the filter box once you've typed something; clicking it empties the filter (restoring the full tree) and returns focus to the field. It's hidden whenever the filter is empty.

- **"Beta" pills in the Settings sidebar.** Still-beta features (Language Servers, Debugger, Web — HTTP client + HTML preview, Templates, Git, Remote, MCP) now show a small *Beta* pill beside their name in the Settings category list, so their maturity is clear at a glance.
- **One-click install completed for the binary-only language servers** — C/C++ (`clangd`), XML (`lemminx`), Kotlin (`kotlin-language-server`), Terraform (`terraform-ls`), and Lua (`lua-language-server`). Editora downloads the right per-OS/architecture release archive (from GitHub releases, or releases.hashicorp.com for terraform-ls), extracts it into `~/.editora/plugins/lsp/<server>/`, and points that server's command at the extracted binary — so every supported language server now has an in-app installer (Install… button / banner / **Install: Language Server…** picker). With this, all 21 language servers across the npm, toolchain, and binary tiers are covered.

- **One-click install extended to toolchain-based language servers:** Go (`gopls`), SQL (`sqls`), Ruby (`ruby-lsp`), C# (`csharp-ls`), Rust (`rust-analyzer`), and PHP (`phpactor`) join the Install… buttons / banner / **Install: Language Server…** picker. Each installs via that language's own package manager — `go install`, `gem install`, `dotnet tool install`, `rustup component add`, `composer global require` — so they only run when the relevant toolchain (Go / Ruby / .NET / rustup / Composer) is already on your PATH; otherwise Editora tells you which toolchain to install first.

- **One-click install extended to more language servers (npm batch):** JSON, HTML, CSS (one package, `vscode-langservers-extracted`), Bash/Shell, YAML, Dockerfile, and TOML now have **Install…** buttons on Settings → Language Servers, an in-editor install banner (when LSP is on but the server is missing), and a new **Install: Language Server…** palette command that lists every installable server. All install globally via `npm` (needs Node).

- **In-editor "install language support?" banner.** When you open a Java/Python/JavaScript file (with the Language Servers feature on) whose language server isn't installed — or a Mermaid file with the Mermaid feature on but no `mmdc` — a slim banner appears above the editor offering **Install** (one click, with a progress spinner) or **Not now** (dismissed for that file this session). It only appears when the relevant feature is enabled, and you can turn the nudge off globally via the new **Offer to install missing language support** checkbox (Settings → Language Servers) or the `view.toggleInstallPrompts` palette command.
- **Install buttons on the Settings → Language Servers and Mermaid pages.** Each installable language server row (Java/Python/JavaScript) and the Mermaid page now has an **Install…** button that runs the same one-click install as the palette commands (downloading that language's server + debugger, or the Mermaid CLI). The button shows **Installed** and disables once the tool is detected, updating live right after an install finishes.
- **One-click install of language support (LSP + debugger) for Java, Python, and JavaScript/Node, plus the Mermaid diagram CLI** — cross-platform (macOS/Linux/Windows). New palette commands **Install: Java / Python / JavaScript-Node Language Support** and **Install: Mermaid Diagram Tools** download and set up each ecosystem's language server and debug adapter for you, instead of hand-running scripts. Node-based tools (Pyright, typescript-language-server, mermaid-cli) install globally via `npm`; debugpy installs via `pip` into the config folder; java-debug downloads from Open VSX; the Java language server (Eclipse JDT-LS) and the Node debugger (js-debug) download into `~/.editora/plugins/`. After installing, the tools are auto-detected and activate without a restart. Editora never installs the underlying runtimes — if Node/Python/`tar` is missing it tells you which one to install first. (An in-editor "install support?" banner follows.)

- **"Compare with Commit…" in the editor tab's right-click menu.** Sits next to "Compare with HEAD" and opens the same commit picker as the `diff.vsCommit` command (diffs the tab's file against a commit you choose). Like the other Git items in the menu, it's shown only when the file is inside a repo with Git enabled.

- **`--single-window[=project]` command-line flag.** Opens exactly one window at startup instead of restoring every window that was open at last quit: bare `--single-window` opens the no-project window; `--single-window=MyProject` opens that project's window (falling back to the no-project window if no project matches that name). It's **session-only** — it does not change your saved multi-window layout, so a normal launch afterwards restores all your windows as before (and quitting a one-window run won't shrink the saved set). CLI file targets / `--zen` / `--new-file` / `--simple` apply to the single window.
- **Syntax highlighting for many extension-less dotfiles / named config files** (matched by name via `ConfigFileType`, so they also get the matching language server when enabled):
  - **Shell** — `.bashrc`, `.bash_profile`, `.bash_aliases`, `.zshrc`, `.zshenv`, `.zprofile`, `.zsh_aliases`, `.profile`, `.aliases`, `.kshrc`, the `.<rc>.local` overrides, and X-session scripts (`.xprofile`/`.xinitrc`/`.xsession`). (`.bash_aliases` is standard — Debian/Ubuntu sources it; `.zsh_aliases` isn't auto-sourced by zsh but is a common user-chosen name.)
  - **Ruby** — `Gemfile`, `Rakefile`, `Guardfile`, `Capfile`, `Thorfile`, `Berksfile`, `Brewfile`, `Podfile`, `Vagrantfile`, `Fastfile`, `Appfile`, `Dangerfile` (lockfiles like `Gemfile.lock` are intentionally left alone).
  - **Groovy** — `Jenkinsfile` (and `Jenkinsfile.<env>`).
  - **INI** — `.npmrc`, `pylintrc`/`.pylintrc`, `.flake8`, `.coveragerc`, `.pep8`, `.gitlint`, `.hgrc`; **git-config** — `.gitmodules`.
  - **YAML** — `.clang-format`, `.clang-tidy`, `.condarc`, `.gemrc`, `.yamllint`.
  - **JSON** — `.babelrc`, `.eslintrc`, `.prettierrc`, `.stylelintrc`, `.swcrc`, `.bowerrc` (the bare forms; the explicit `.json`/`.yaml`/`.js` variants were already handled by extension).
- **The window title bar now shows the active file's name + path.** Format: `<file path> — <project> — Editora` (e.g. `~/src/app/Main.java — MyProject — Editora`), with a `•` prefix for unsaved changes; an untitled buffer shows its name, and with no file open it's just the project/app name. The path is home-collapsed (`~`) and updates on tab switch, save, Save-As, and rename.
- **Linux `.deb`: an `editora` command on `PATH`, plus a proper menu entry + app icon.** The Debian package now (1) creates a `/usr/bin/editora` symlink to the installed launcher (jpackage otherwise installs it only at `/opt/editora/bin/Editora`, off `PATH`), so you can start Editora from a terminal with arguments — e.g. `editora some/file.java:42`; and (2) registers the bundled `.desktop` into `/usr/share/applications` (injecting `StartupWMClass` so the running window matches it), so the application menu and dock show the Editora icon instead of the generic Java icon. Both are removed on uninstall. Implemented via custom DEB maintainer scripts (`packaging/linux/postinst`/`postrm`) passed through `jpackage --resource-dir` in the installer-wrap step — these *replace* jpackage's generated scripts, so they reproduce jpackage's menu/icon registration in addition to the new symlink. The build (`scripts/aot_build.java`) also overwrites the app image's `lib/Editora.png` with our branding PNG before wrapping — jpackage's app-image phase left its default Java icon there and `jpackage --app-image` ignores `--icon` at wrap time, so the menu/dock were showing the generic icon. `.rpm` is unaffected (run `/opt/editora/bin/Editora`).

### Fixed

- **A "Compare with HEAD" diff no longer blanks its HEAD pane on refresh.** In a window with no project open, opening a file's vs-HEAD diff and then triggering a refresh (clicking an "apply change" chevron, or regaining window focus) could empty the left (HEAD) pane and render the whole working side as all-added. The diff's HEAD side was re-fetched against the *live* repo root, which the Git status machine drops to "none" when the active tab is a diff tab with no backing buffer (a No-Project window has no project root to fall back to) — so the re-fetch ran against a null root and returned nothing. The diff now captures the repo root when it's opened and reuses it on every refresh. Affected all Git-backed diffs (vs HEAD, vs commit, Git-panel staged/unstaged rows, a commit's file, and the Local File History revision diff). Covered by a new FX regression test.

### Changed
- **Perf:** the Markdown minimap now coalesces scroll-driven repaints to one per pulse (was a synchronous repaint per scroll event); the completion popup's prefix check reads a bounded window before the caret instead of materializing the whole document on every caret move.
- **Installing Mermaid support now also installs the headless Chrome it needs.** `install.mermaidSupport` (Settings → Mermaid → Install…) runs `npx puppeteer browsers install chrome-headless-shell` from mmdc's own dir after `npm i -g @mermaid-js/mermaid-cli`, so diagrams render on a fresh Linux box without the *"Could not find Chrome"* Puppeteer error.
- **Snippet choice dropdown (`${1|a,b|}`) now uses the same keys as autocomplete**: `C-n`/`C-p` to move, `Tab`/`Enter` to accept, `C-g` to cancel (Down/Up/Enter/Esc still work).
- **Command palette ranks results by relevance.** Matches are ordered exact > whole-word > word-start > substring > scattered-subsequence (tie-broken by shorter title), so e.g. typing `undo` now lists `Edit: Undo` first instead of a scattered match like `Unsplit Editor`.
- **Clearer unsaved marker in the window title.** A modified file now shows a prominent leading `●` in the title bar (was a small `•`), so the modified state is visible in Zen mode where the tab strip is hidden. (Native title bars are plain text, so the tab's colour/italics can't be reproduced there.)
- **The tool-window consoles (External Tools, Run, Debug) now use the editor's code-area font** (family + effective size, tracking the zoom) instead of a fixed JetBrains Mono 12.
- **The Personal Notes inline indicator is 25% larger** (the amber corner triangle at a note's start), making it easier to spot; its click target grew to match.
- **Personal Notes tool window: bold file headers + a clear-filter button.** File-name rows are now bold (matching Find-in-Files), and the filter has a trailing `✕` button shown only when there's text (like the Project panel).
- **Personal Note editor: Markdown hint + delete confirmation.** The note edit popup now shows a hint that the body supports Markdown formatting, and its **Delete** button asks for confirmation first (the body already rendered as Markdown in the hover tooltip).
- **The External Tools tool-window stripe is off by default.** It's registered default-hidden (like Undo History and Remote Sites); reach it via the `tool.externalTools` command, the `externalTool.run` picker, or Settings → Tool Windows. The feature is unaffected.
- **Refactor: pure text-editing operation cores moved to `com.editora.editops`.** The 10 toolkit-free editing/indent/navigation cores (`Indenter`, `AutoClose`, `BraceMatcher`, `Commenter`, `Transposer`, `LineOps`, `EmacsEdits`, `SexpNav`, `Filler`, `LineIndent`) and their tests moved out of `com.editora.editor`. Internal-only — no behavior, dependency, or `module-info` change.
- **Refactor: pure Markdown logic moved to a `com.editora.markdown` package.** The 11 toolkit-free Markdown cores (editing, lint, outline, image-paste) and their tests moved out of `com.editora.editor` into their own package; the FX renderer and editor overlays stay. Internal-only — no behavior, dependency, or `module-info` change.
- **The "New File From Template" picker is bigger.** Widened (and given a taller minimum) so the longer template descriptions are no longer clipped (the default width showed a horizontal scrollbar).
- **Personal Notes show an inline marker at the note's start instead of a gutter icon.** A small amber corner-triangle now marks where a note begins (the selected character/word/range), reinforcing that notes are anchored to the selection — not the line. The gutter note slot is gone; edit the note at the caret with the new `notes.editNote` command (the inline highlight + hover tooltip + Notes tool window are unchanged).
- **The Undo History tool-window stripe button is now off by default.** With the new Undo History popup (`undoHistory.jump`) as the primary entry point, the side-stripe icon is hidden until you enable it (Settings → Tool Windows, or the `tool.undoHistory` command). Existing sessions keep whatever visibility you had.

- **Internal: moved the last Git blame UI bits into `GitCoordinator`.** The blame annotation engine already lived in `GitCoordinator`; the three remaining UI methods — the `git.toggleBlame` toggle, the caret-line `blameShowCommit`, and the gutter-annotation click (`onGutterBlameClick`) — moved there too, so the whole inline-blame feature now resides with the engine. The two surfaces those touch outside the coordinator route through new `WindowOps` callbacks: `syncBlameCheck()` (re-sync the Settings checkbox) and `openCommitFileDiff()` (open the line's commit diff — routed through the window to reach `DiffCoordinator` without a circular dependency). `MainController` shed ~34 lines (8,716 → ~8,682); its blame command + gutter-click wiring delegate to `git::toggleBlame`/`git::blameShowCommit`/`git::onGutterBlameClick`. No behavior change — part of the ongoing `MainController` decomposition.
- **Internal: moved the Git clone flow into `GitCoordinator`.** The `git.clone` UI — the URL + destination `OverlayInput` form (with the `<home>/<repo>` auto-fill and the Browse picker), the `git clone` call, and opening a file from the fresh clone (`openClonedEntry`) — moved into `GitCoordinator` alongside the engine it already used (`GitService.clone`/`gitError`), as `cloneRepo()` (renamed off `clone` to avoid clashing with `Object.clone()`). The pure/unit-tested `repoNameFromUrl` helper moved with it (`GitCoordinator.repoNameFromUrl`). `MainController`'s three clone callbacks — the `git.clone` command, the empty-`GitPanel` button, and the `BranchPopup` No-VCS action — now delegate to `git::cloneRepo`. `MainController` shed ~131 lines (8,847 → ~8,716). Verified by `GitStateFxTest` (a new case asserts the clone form renders as an in-scene overlay through the coordinator wiring) + the moved unit tests. No behavior change — part of the ongoing `MainController` decomposition (the remaining Git-Log / blame / branch-popup wiring is a follow-up).
- **Internal: extracted `RemoteCoordinator` from `MainController`.** The Remote file access (SFTP) feature — the SSH connect form, the saved-connection picker, mounting a remote folder as the Project tree root, single-file open from an `sftp://` URI, and disconnect — moved into its own `CoordinatorHost`-style coordinator. It owns the `RemoteFileSystems` engine (the SSH client + `Vfs` resolver), the `RemoteConnectionsPanel`, and the mount state; `MainController` keeps the `remote` `ToolWindow` (built with the coordinator's panel), queries the coordinator for the current-folder view, and wires the `remote.*`/`tool.remote` commands + the Welcome-page quick-connect callback to it. `MainController` shed ~172 lines (9,019 → ~8,847). Verified by a new FX test that drives the connect flow on a real window and asserts the SFTP form renders as an in-scene overlay (no network). No behavior change — part of the ongoing `MainController` decomposition.
- **Internal: extracted `PluginCoordinator` from `MainController`.** The Plugin support feature — discovery/apply/install/uninstall plus the per-window `PluginContext` adapter that lets a plugin contribute commands, keybindings, tool windows, status-bar segments, and editor menu items — moved into its own `CoordinatorHost`-style coordinator. It owns the per-window `PluginRegistry`/`PluginInstaller`, the started-plugin instances, the plugin-contributed tool windows + editor menu items, the "Browse Plugins" picker, and the two inner adapters (`PluginContextImpl`/`ActiveEditorImpl`). The shared `PluginManager` (owned by `WindowManager`) is passed in; `MainController` keeps the `pluginManager` field + thin `disposePlugins()`/`pluginsEnabled()` delegates and wires `applyPlugins` / the tool-window gating / the editor-menu contributor / the `plugins.*`+`view.togglePlugins` commands to the coordinator. `MainController` shed ~574 lines (9,578 → ~9,004). Device-tested live (MCP bridge): the example plugin's Java-registered command, its tool-window toggle, and its declarative external command all loaded + ran through the moved `PluginContextImpl.start` path. No behavior change — part of the ongoing `MainController` decomposition (the largest single extraction in the campaign).
- **Internal: extracted `HistoryCoordinator` from `MainController`.** The Local File History feature (per-file save/label/delete snapshots stored outside the file) moved into its own `CoordinatorHost`-style coordinator: it owns the `HistoryService` (off-thread snapshot/blob engine) + the `FileHistoryPanel`, and the whole flow — record (save / autosave / external / pre-delete), the per-file + folder-history views, label / recent-changes pickers, and revision diff/restore (reusing the shared `DiffCoordinator`). `MainController` keeps the File History `ToolWindow` (built with the coordinator's panel) and wires the record hooks + the `history.*`/`tool.fileHistory` commands + the project-tree "Show Local History" item to it. `MainController` shed ~300 lines (9,881 → ~9,578). Device-tested live (MCP bridge): saving a file recorded a revision into `history/index.json`, and the history commands ran clean. No behavior change — part of the ongoing `MainController` decomposition.
- **Internal: moved the Git operations into `GitCoordinator`.** The user-facing Git mutation operations that were still in `MainController` — `gitOp`/`gitCommit`/`discardChanges`, branch checkout/create, fetch/pull/push (`gitSync`), the stash commands, the active-file stage/unstage/discard, the commit-focus action, and the `gitError` dialog — moved into `GitCoordinator` (which already owned the `GitService` + repo state), so the whole Git engine + its mutations now live in one cohesive coordinator. `MainController` keeps the panels/tool-windows/branch-popup + command registrations (delegating to `git.*`). `MainController` shed ~294 lines (10,175 → ~9,881). No behavior change. (The commit/branch/log/blame/clone UI wiring is a follow-up.)
- **Internal: extracted `DiffCoordinator` from `MainController`.** The diff + merge-conflict viewer (the `DiffService`, the diff-tab open/refresh machinery, the "apply change" hunk flow through an undoable buffer with Undo/Save, the compare entry points — vs HEAD / vs commit / compare-with-file / Git-panel rows / a commit's file — patch export, and merge-conflict resolution) moved into its own `CoordinatorHost`-style coordinator. Git-backed diffs reach the repo via the shared `GitCoordinator`; `openDiff` + the `DiffSide` interface stay package-visible so the Local File History flow reuses them. `MainController` shed ~317 lines (10,492 → ~10,175). No behavior change — continuing the `MainController` decomposition.
- **Internal: extracted `DebugCoordinator` from `MainController`.** The whole DAP debugging integration (the `DebugPanel`, breakpoint persistence + gutter gating, the DAP event sink + panel actions, the inline-values / hover / execution-line editor surfaces, the start/attach/step/run-to-cursor/jump flows, and the `debug.toggleAdapter`/`debug.setAdapterPath` pickers) moved into its own `CoordinatorHost`-style coordinator. The `DapManager` stays a `MainController` field (it's built on the LSP `lspManager`, and `SettingsWindow` + window-dispose reach it) and is passed to the coordinator, mirroring the LSP split; Java debugging layers on the jdtls session, so the coordinator also takes the `lspManager` + `LspCoordinator`. `MainController` shed ~565 lines (11,057 → ~10,492). This completes the `MainController` decomposition campaign (13,017 → ~10,492). No behavior change.
- **Internal: extracted `LspCoordinator` from `MainController`.** The whole LSP integration moved into a new `CoordinatorHost`-style coordinator across three slices: the navigation/format flows (go-to-definition, find-references, hover, document formatting + the hover popup); the diagnostics routing (the per-open-file `problems` map, the `ProblemsPanel`, the `publishDiagnostics` callback); and the configure/detect/gating + per-buffer lifecycle (`applySupport`/server detection/the per-server enable+command switches/`syncBuffer` open-activate-close/the status-bar `LSP:` segment/the Structure outline/semantic tokens/the per-buffer hook wiring/the `lsp.toggleServer`+`lsp.setServerCommand` pickers). The `LspManager` stays owned by `MainController` (Debug layers on the jdtls session, the MCP bridge reads its diagnostics) and is passed to the coordinator; its callbacks route through thin method-ref delegates. `MainController` shed ~685 lines (11,742 → ~11,057). No behavior change — part of the ongoing decomposition.
- **Internal: extracted `BookmarkCoordinator` from `MainController`.** The Bookmarks feature (the panel + jump picker, per-buffer persist/restore, the gutter-click flow, and the `bookmarks.*` commands) moved into its own `CoordinatorHost`-style coordinator. No behavior change — part of the ongoing `MainController` decomposition.
- **Internal: extracted `NotesCoordinator` from `MainController`.** The Personal Notes feature (the panel + cross-file pickers, per-buffer persist/restore with anchor re-keying, the note editor dialog, and the `notes.*` flow) moved into its own `CoordinatorHost`-style coordinator. No behavior change — part of the ongoing `MainController` decomposition.
- **Internal: documented soft code-size limits in `CLAUDE.md`.** Added review heuristics for class/method size (target ≤1000 lines/class, ≤50/method; refactor-smell beyond ~2000/~100) and codified the `CoordinatorHost` decomposition as the way to keep `MainController` (and future features) in check. Docs only — no tooling/behavior change.
- **Internal: extracted `RunCoordinator` from `MainController`.** The run-a-file feature (the run service + console panel, the run/rerun/stop flow, and the launcher-command building) moved into its own `CoordinatorHost`-style coordinator. No behavior change — part of the ongoing `MainController` decomposition.
- **Internal: extracted `SearchCoordinator` from `MainController`.** The Find-in-Files feature (the multi-file search service + results panel, scope tracking, replace-in-files, and the ripgrep/walker backend selection) moved into its own `CoordinatorHost`-style coordinator. No behavior change — part of the ongoing `MainController` decomposition.
- **Internal: extracted `TodoCoordinator` from `MainController`.** The TODO / highlight-pattern feature (service + tool-window panel + the in-editor highlight matcher + the project/open-files scan + `todo.*` commands) moved into its own `CoordinatorHost`-style coordinator. No behavior change — part of the ongoing `MainController` decomposition.
- **Internal: extracted `MacroCoordinator` from `MainController`.** The keyboard-macro feature (per-window service + record/replay capture hooks + `macro.*` commands) moved into its own `CoordinatorHost`-style coordinator. No behavior change — part of the ongoing `MainController` decomposition.
- **Internal: extracted `ExternalToolCoordinator` from `MainController`.** The External Tools feature (service + console panel + run/output logic + `externalTool.*` commands) moved into its own `CoordinatorHost`-style coordinator, trimming the `MainController` god-class. No behavior change — first step of an ongoing `MainController` decomposition.
- **The Remote Sites tool window is off by default and opens on the right.** Its stripe button is now hidden until you enable it (Settings → Tool Windows, or the `tool.remote` command / `M-g r`), and when shown it docks on the right. The remote (SFTP) functionality is unchanged — only the tool window's default visibility and side moved. Existing layouts (if you'd already shown or moved it) are preserved.

### Added

- **Open the session log from Settings → Advanced.** Added a "Session log" link next to the settings-file path that opens `editora-session.log` (the captured logging + uncaught exceptions for the current session) in the editor — handy for grabbing diagnostics for a bug report.

- **Open the dictionary files from Settings → Spell Check.** Two links (grouped near the top of the page): **"Open the technical.txt dictionary"** opens the built-in technical dictionary read-only in a new tab so you can browse what it covers, and **"Open dictionary.txt…"** opens your personal dictionary file in the editor (created empty if you haven't added a word yet).

- **Large source files stay responsive (intermediate large-file tier).** A very long single file (e.g. a 13k-line source) below the 5 MB hard cutoff used to get the full treatment — syntax highlighting *plus* the minimap (which redraws every line) *plus* a language server churning over a huge AST — which made it slow to load and scroll. There's now an intermediate tier: above a configurable **line count** (Settings → Editor → Performance → "Disable minimap & LSP above", default **10000 lines**, `0` = never), the **minimap and language server auto-disable** while syntax highlighting and full editing stay on. A status note reports it on open, and you can flip it per file from the palette (**Toggle Large-File Mode**). Palette `editor.setLargeFileThreshold` changes the threshold; `view.toggleLargeFileMode` overrides per buffer.

- **The TODO tool window shows the folder it's scanning.** Like Find in Files, the TODO panel now has a "Scanning in:" line at the top showing the scope — the **project root** when a project is open, otherwise **open files only** (full path on hover).
- **The TODO scan skips `.gitignore`d files and folders.** Scanning a project for TODO/FIXME markers now excludes the paths your `.gitignore` excludes (`target/`, `node_modules/`, build output, `*.log`, …), so the TODO panel stops filling with generated-code noise. Governed by the same **Settings → Search → "Exclude .gitignore files and folders from search"** toggle as Find in Files.
- **Find in Files shows the folder it's searching.** The Find-in-Files panel now has a "Searching in:" line at the top showing the exact scope — the **project root** when a project is open, otherwise the **current file's folder** (with the full path on hover). Non-project searches now actually scan that folder on disk (previously they only matched open buffers), and the scope updates as you switch tabs.
- **Find in Files skips `.gitignore`d files and folders.** Searching a project now excludes the paths your `.gitignore` excludes — `target/`, `node_modules/`, `build/`, `dist/`, `*.log`, etc. — so build output and dependencies stop cluttering results. New setting **Settings → Search → "Exclude .gitignore files and folders from search"** (default **on**; palette `view.toggleSearchGitignore`). The ripgrep backend already honored `.gitignore`; the built-in walker now does too (via a new pragmatic `.gitignore` parser), and turning the setting off searches everything (rg `--no-ignore`).

- **Mexican Spanish spell-check dictionary (`es_MX`).** Added a Spanish (Mexico) dictionary alongside the existing Spanish (Spain) one — both from the RLA / LibreOffice project, redistributed under MPL-1.1. Choose it per file via *Spell Check: Set Language…* or as the default in Settings → Spell Check; the existing `es` entry is now labelled "Spanish (Spain)".

- **Technical-terms dictionary for spell check.** A bundled list of common programming / web / data / devops words that standard prose dictionaries flag as misspellings (`config`, `async`, `boolean`, `middleware`, `kubernetes`, `stdin`, `json`, …) is now accepted by the spell checker, so code-adjacent prose stops getting peppered with red squiggles. Toggle it in **Settings → Spell Check → "Enable technical dictionary"** (default **on**) or via the palette command `Toggle Technical Dictionary` (`view.toggleTechnicalDictionary`). It layers on top of the language dictionary and your personal dictionary, each independently switchable.

- **Command-palette parity for actions that were UI-only.** Every remaining click-only action now also has a registered, keybindable palette command:
  - **Git Log** commit operations (Checkout / New Branch / Revert / Cherry-Pick / Reset… / Copy Hash) → `git.log.*`, acting on the selected commit.
  - **Debugger** data inspection → `debug.evaluate` (open console + focus the eval field) / `debug.addWatch` / `debug.setValue`.
  - **Diff viewer** toolbar → `diff.toggleView` (unified ↔ side-by-side) / `diff.applyAll` / `diff.nextChange` / `diff.previousChange`, acting on the active diff tab.
  - **Find/Replace bar** → `find.next` / `find.previous` / `find.replaceCurrent` / `find.replaceAll` (open the bar if it's closed).
  - **Git** working-file ops → `git.unstageFile` / `git.discardFile` (active file; discard confirms first), mirroring the existing `git.stageFile`.
  - **Console Clear** → `run.clear` / `externalTool.clearOutput`.
- **Macros management in Settings.** A new **Settings → Macros** page lists your saved keyboard macros and lets you rename them, edit/reorder/remove their steps (add command or text steps), delete them, and assign a keybinding inline — all in one place.
- **Snippet & template body editors got syntax highlighting + Emacs keys.** The Snippets and File Templates pages in Settings now edit the body in a real code editor (RichTextFX `CodeArea`) with live syntax highlighting for the chosen language and basic Emacs caret movement (`C-a`/`C-e`/`C-f`/`C-b`/`C-n`/`C-p`/`M-f`/`M-b`/`C-d`/`C-k`), instead of a plain text box. Also fixed the form-field labels that could collapse to “…” on the Snippets/Templates/External-Tools pages.
- **Reorganized the Settings window.** The flat 22-item sidebar is now grouped into five sections (General · Editor · Languages & Tools · Version Control · System) with non-selectable headers and indented items. The overloaded *Editor* page was split (Code Completion and TODO are their own pages), the *Application* grab-bag became **Interface** (window chrome + Simple/Zen modes) and **Workspace** (projects, notes, local history), HTTP Client + HTML Preview merged into **Web**, and the author field moved to Templates. Search now also hides empty group headers. Every setting is preserved — only the organization and presentation changed.

- **Saved remote sites UI.** Your saved SFTP connections (in `connections.json`) now have three visible surfaces beyond the palette picker: a **Remote Sites** tool window (`M-g r`) listing every saved site with New / Connect / Remove, a **Settings → Remote** page to add/edit/remove them (label, host, port, user, auth, key path), and a **Remote Sites** quick-connect list on the Welcome page. Picking a site opens the connection form pre-filled (your password/passphrase is still prompted and is never stored). Commands `Remote Sites` (`tool.remote`) and `Manage Remote Sites…` (`remote.settings`).

- **Personal dictionary editor.** Settings → Spell Check now has a *Personal Dictionary* section listing your accepted spell-check words (from `dictionary.txt`) with add/remove and an **Enable personal dictionary** checkbox (on by default; off re-flags words you've added) — so you can prune, add, or temporarily ignore words without editing the file by hand. Opens via the palette `Spell: Manage Personal Dictionary…` (`spell.manageDictionary`) and the toggle `view.togglePersonalDictionary`; changes apply globally across all languages.

- **File template management GUI.** A new Settings → Templates page lists your file templates — the **bundled (shipped) ones too**, tagged read-only — alongside your own. Editing a bundled template writes a user override to `<configDir>/templates/<id>.json` (the shipped one is never modified); add/remove your own with an id / name / description / language / file-name / body form. Opens via the palette `Templates: Manage File Templates…` (`template.manage`); multi-file templates are shown read-only (edit via `Edit User Templates`). The raw-JSON command still works. Both the Snippets and Templates pages also got a taller list and an explicit **Save** button (edits still auto-save on Enter/focus-loss).

- **Undo History tool window** (`M-g u`) — an in-session timeline of document checkpoints captured as you edit (one per typing burst); double-click or Enter to jump back to any recent state (a single undoable restore). Finer-grained than save-based Local History, session-only, and disabled for very large files.

- **Word/line-level undo.** Undo no longer collapses an entire typing burst into one step — one `C-z` now undoes a word or line. The undo manager starts a new group at word/whitespace/newline boundaries and after a short typing pause (idle break), matching VS Code/IntelliJ. (First of a planned undo arc; an undo tree and a history panel are next.)

- **Snippet management GUI.** A new Settings → Snippets page lists the snippets for a language — the **bundled (shipped) ones too**, tagged read-only — alongside your own. Editing a bundled snippet writes a user override to `<configDir>/snippets/<lang>.json` (the shipped one is never modified); add/remove your own snippets with a name / trigger / description / body form, applied live. Opens via the palette `Snippets: Manage Snippets…` (`snippets.manage`); the raw-JSON `Edit User Snippets` command still works.

- **Sample corpus for manual feature testing.** A new top-level `samples/` folder holds small, curated
  text samples organized by feature (syntax per language, folding, indent, markdown, mermaid, todo, spell,
  search, editorconfig, http, log, diff, encodings) — open the relevant file to smoke-test a feature.
  `samples/README.md` is the manifest; `SamplesCorpusTest` keeps it in sync with the files on disk. Large
  perf inputs are generated on demand by `java scripts/GenSamples.java` (git-ignored, never committed), and a
  scoped `.gitattributes` preserves the encoding/EOL samples verbatim.

- **Syntax highlighting for common project config files.** `.editorconfig` (INI), `.gitignore` (and other
  `.*ignore` files, via a new `ignore` grammar), `mvnw`/`gradlew` (shell), and Eclipse `.classpath`/`.project`
  (XML) are now recognized by name and highlighted — wired through the shared `ConfigFileType` resolver so the
  grammar and language registries stay in sync.

- **Local History, closer to IntelliJ.** Three additions to the File History tool window:
  **selective restore** — the snapshot-vs-current diff is now editable on the current side, so the
  apply-chevrons copy individual fragments from a revision back into the file (undoable) instead of only a
  whole-file revert; **named snapshots** — a "Put Label" command (`history.putLabel`) records a labeled
  point in time (shown bold in the list, even when the content is unchanged); and **search + recent
  changes** — a filter field over the revision list (by label/reason/time) plus a `history.recentChanges`
  picker of the most recent revisions across the whole project. (`history/index.json` schema 1→2, additive.)
- **Local History: folder view + recover a deleted file.** Right-click a folder in the Project tool window →
  **Show Local History** lists every file under it that has recorded revisions, with **deleted** files badged;
  a revision's "Restore This Version" writes it back to disk (recreating the file). Deleting a file from the
  project tree now snapshots it into history first, so an accidental delete is recoverable. (Recovery covers
  files deleted in Editora or that already had edit history — not files deleted by an external tool that were
  never opened.)

### Fixed

- **Tool-window sizes are now remembered when you quit with them open.** Resizing a tool window (e.g. the Project panel) and quitting with it still open used to reset its divider to the default on next launch — the position was only captured when a tool window was *hidden*, never on quit. The session now captures the live divider positions on quit, so the layout comes back as you left it. (Window size/position already persisted.)
- **Minimap no longer shows a second, stale viewport box on large files.** The minimap's content render calls `canvas.snapshot()`, which forces a synchronous layout pass that could settle the editor's viewport and re-enter the minimap's redraw mid-render — painting a duplicate viewport highlight that lingered. The minimap now guards against re-entrant paints, so exactly one viewport box is drawn.
- **Tidier, better-fitting Settings → Templates page.** It showed "Templates" three times (page title + two section headers) and its body editor was tall enough to overflow the window. The section headers are now **"Author"** and **"Manage templates"**, and the template/snippet body editors use a more modest height (they still grow to fill a taller window).
- **Settings page titles no longer shave the bold heading.** The big bold heading on each Settings page (e.g. "Markdown") looked like it was missing slivers of its letters — a JavaFX `Label` clips its text to the glyphs' logical (advance-based) bounds, which cuts the outer strokes of bold faces (pipeline- and font-independent). The heading is now a `Text` node with `TextBoundsType.VISUAL`, which renders the full glyph ink without clipping.
- **EditorConfig BOM charset no longer truncates files on open.** When a project's `.editorconfig` declared `charset = utf-8-bom` (or `utf-16le`/`utf-16be`) but a file on disk had no actual byte-order mark, the first 2–3 content bytes were silently dropped on open — and saving re-added a BOM, corrupting the file. `EditorConfigCharset.decode` now strips the BOM only when the bytes really start with it.
- **Typing a closing bracket over a selection no longer silently deletes it.** With text selected, typing `)`/`]`/`}` where the next character was that same bracket "typed over" it and discarded the selection without inserting anything. It now replaces the selection like normal typing. (`AutoClose.decide` gates skip-over on having no selection.)
- **Regex "Replace All" now respects the whole-word toggle.** With both *Regex* and *Whole word* enabled, Replace All replaced every unbounded occurrence instead of the `\b`-bounded matches it had highlighted and counted. It now uses the same `SearchMatcher.compileRegex` as the search/highlight path.
- **A hung `javac` can no longer freeze debugging.** The loose-Java compile-and-debug fallback now runs the compile through `ProcessRunner` with a 60-second timeout (and concurrent pipe draining), so a stuck compiler can't pin the single-threaded debug-adapter executor and block every later debug start.

### Changed

- **Headless FX tests now use JavaFX 26's built-in headless platform.** The `@Tag("fx")` harness boots over
  the Headless Glass platform that ships inside `javafx.graphics` (`-Dglass.platform=Headless`) instead of the
  self-built Monocle backend. The vendored `openjfx-monocle` jar (and the rebuild-on-every-JavaFX-bump ritual it
  required) are gone — nothing extra to download or maintain, and the backend can no longer go stale on a JavaFX
  bump. Test-scope only; no runtime, packaging, or `module-info` impact. The harness only uses TestFX to boot the
  toolkit (never the robot), so the prototype platform's input/rendering limits don't apply.

- **Command docs now open the version-matched page.** The command palette's "open docs" (C-h) now links to
  `…/docs/v-<appVersion>/commands/<command-id>`, matching the website's new versioned docs layout, so each
  build opens the docs for the version that's actually running.

- **De-duplicated the plugin examples.** The full plugin catalog lives canonically in the
  [adriandeleon/editora-plugins](https://github.com/adriandeleon/editora-plugins) repo (source +
  signed registry index), so the editor repo no longer keeps copies under `examples/` — those had
  already drifted from the registry. Only `examples/example-plugin/` remains as the in-tree SDK
  reference; `docs/plugins.md`, the README, and `CLAUDE.md` now point the catalog at the registry repo.

### Added

- **Plugin API: `needsBuffer` opt-out for tool windows.** Plugin tool windows are gated on an open editor
  buffer by default (they act on the active editor), but a new `registerToolWindow(…, icon, needsBuffer)`
  overload lets a self-contained tool window (scratchpad, calculator, color picker, …) pass
  `needsBuffer = false` so its stripe button stays available on every tab, including Welcome. The existing
  overloads are unchanged (they default to `needsBuffer = true`).

- **Markdown lint, substantially expanded.** The Markdown linter now covers more of the markdownlint rule
  set — MD001 (heading-level increment), MD010 (hard tabs), MD022 (headings surrounded by blank lines),
  MD023 (heading indent), MD026 (heading trailing punctuation), MD031 (fenced code surrounded by blank
  lines), MD034 (bare URLs), and MD041 (first line should be a top-level heading) — joining the existing
  MD009/012/018/019/025/040/047/052. New capabilities:
  - **Per-rule configuration.** Turn individual rules off in *Settings → Markdown* (a per-rule
    checklist) or via the `markdownLint.toggleRule` palette picker; persisted in `settings.toml`
    (`markdownLintDisabledRules`, schema 39→40).
  - **Inline disable comments.** `<!-- markdownlint-disable [MDxxx] -->` / `enable` / `disable-line` /
    `disable-next-line` are honored, matching markdownlint.
  - **`.markdownlint.json` discovery.** The nearest config file (walking up from the file) is merged into
    the disabled-rule set (`{"MD013": false}` / `{"default": false}` forms).
  - **Auto-fix.** `markdownLint.fix` (palette) corrects the safely-mechanical issues in the active file
    (trailing whitespace, tabs→spaces, blank-line runs, heading-marker spacing, heading indent, heading
    trailing punctuation, final newline) as one undoable edit.
  - **Overview stripe + minimap ticks.** Lint warnings now appear as an IntelliJ-style stripe over the
    scrollbar (click to jump, hover for the rule + message) and as ticks on the minimap, beside the TODO
    and LSP-diagnostic stripes.
  - **Dedicated Settings page.** The Markdown options (format bar, math preview, lint enable + per-rule
    checklist) moved out of *Editor* into their own *Settings → Markdown* page.

- **Export to HTML opens the result in a tab.** After *Preview: Export to HTML* writes the file, the
  generated `.html` now opens in a new editor tab (re-selecting it if already open).

- **Project tool window can show hidden files.** A new *Settings → Tool Windows → "Show hidden files in the
  project tree"* checkbox (and the `view.toggleProjectHidden` palette command) reveals dot-files/folders in
  the project tree and its filter search. Off by default. Persisted in `settings.toml` (schema 38→39).

### Fixed

- **"Add to Dictionary" now persists to `dictionary.txt`.** The added word was applied for the current
  session but never written to disk, so it reappeared as misspelled after a restart. The buffer added the
  word to the shared dictionary set before calling the persist callback, and `addUserWord` only writes the
  file when the word is newly added to that (same) set — so the write was always skipped. The word is now
  persisted on the first add.
- **Structure tool window no longer jumps the editor back while navigating.** A language server that
  re-announces readiness repeatedly (jdtls re-sends `language/status` "ServiceReady" on workspace events)
  re-pushed the same document symbols, rebuilding the outline tree, resetting the selection to the first
  row, and snapping the editor back to the top of the file as you clicked around. Re-pushing an unchanged
  outline is now a no-op, and a genuine rebuild preserves the symbol you had selected.
- **Java LSP (jdtls) now initializes reliably; completion works.** jdtls was launched without a `-data`
  workspace, so every project shared one default Eclipse workspace and deadlocked on its `.lock` (contending
  with a leaked previous run or another editor) — the server never finished `initialize`, leaving the
  status-bar loading bar spinning forever and autocomplete dead. Each project root now gets its own jdtls
  workspace under `<config>/jdtls-workspaces/<hash>` (its index persists across sessions, so later opens are
  faster). A user-supplied `-data` in the configured command is respected. A language server's **stderr** is
  also now captured into the Debug Log (it was discarded), so when a server fails to start the reason —
  missing JDK, a locked workspace, a bad command — is finally visible.

- **Opening a large source file no longer freezes the UI.** Computing fold regions on open recreated a gutter
  graphic for *every* fold header in the file (thousands, for a big file like a 12k-line Java source) on the
  JavaFX thread, blocking it for several seconds. The fold recompute now only refreshes the chevrons of
  currently-visible rows — offscreen rows get the correct fold state when the gutter factory builds them on
  scroll — cutting the per-open cost from seconds to a few milliseconds.

- **Breadcrumb dropdown uses the same file icons as the Project tool window.** The folder dropdown on a
  breadcrumb crumb now shows per-type file glyphs (and the boxed folder icon) via `FileIcons`, instead of a
  single generic file icon — matching the Project tree.

- **The Git Log tool window refreshes when opened.** Opening it — via the stripe icon (which toggles the
  window directly) or a command — now reloads the commit history, so it no longer shows stale commits from a
  previous open. (Opening via the stripe button previously bypassed the load entirely.)

- **Tool windows that act on the active file now hide on non-buffer tabs.** The **Git Commit** and **Git Log**
  stripe buttons — and **plugin** tool windows (e.g. GnuPG, the Regex tester) — only appear when there's an
  open editor buffer to act on; on the Welcome tab (or any non-buffer tab) they're hidden, matching how the
  Structure/File-Info/TODO windows already behave. Commit/Log additionally stay gated on being inside a repo.

- **Find/Replace bar buttons disable when there's no search term.** Prev, Next, Replace, Replace-All, and
  the case/regex/whole-word toggles are now disabled while the Find field is empty — nothing they do is
  meaningful without a query (an empty *replacement* still works as "delete matches").

- **Buffer-only tool windows are hidden when there's no buffer to act on.** The Structure, File Information,
  TODO, Markdown Lint, and External Tools stripe buttons no longer show on the Welcome page (or other
  non-buffer tabs) — they now gate their availability on an actionable active editor buffer (Markdown Lint
  additionally needs a Markdown buffer; External Tools needs a buffer and non-Simple mode), mirroring how
  Run / Debug / HTTP / Commit already gate their buttons.

- **Settings → Editor → "Enable log viewer" checkbox now reflects the actual setting.** It was omitted from
  the Settings window's `load()` (the value was only synced in `syncViewChecks()`, which `load()` doesn't
  call), so the box always rendered unchecked even though the log viewer was on. The feature itself was
  unaffected — only the checkbox display.

### Changed

- **More features on out of the box (new installs only).** The default `settings.toml` now enables:
  **Breadcrumbs**, **Personal Notes**, **Git integration** (self-gating — inert until `git` is on PATH, so
  effectively "on when Git is installed"), **HTML Live Preview**, the **HTTP Client**, **Render LaTeX math**
  in Markdown, and **Mermaid diagrams** (self-gating — inert until the `mmdc` CLI is found). The server-log
  viewer was already on. The built-in **TODO/FIXME highlight patterns are now case-sensitive** (the
  uppercase/lowercase check is on), so only `TODO`/`FIXME` match, not `todo`/`fixme`. These are *defaults* for
  a fresh config — existing `settings.toml` files keep their saved values; flip any of these in *Settings* or
  via the palette toggles.

### Added

- **HTTP tool window auto-shows for `.http` files.** Opening (or switching to) a `.http` buffer now
  automatically opens the HTTP Client tool window, which **defaults to the right** side. Switching to
  another tab auto-hides it (restoring any right-side window it displaced) and returning re-opens it; a
  **manual close is remembered per file** — once you close it for a `.http` buffer it stays closed until
  you reopen it (running a request or toggling it back on re-enables auto-show).

- **Find in Files improvements.** The Find-in-Files tool window (`C-S-f`, `M-6`) gains: **include/exclude
  file globs** (comma-separated, `.gitignore`-style — e.g. include `*.java, src/**`, exclude `**/test/**`),
  applied by ripgrep (`-g`/`-g !`) and by the Java-walker fallback + open-buffer overlay alike; **query
  history** (the query field is now an editable dropdown of recent searches, persisted in
  `search-history.json`); **regex capture groups in replace** (`$1`/`${name}` in regex mode via
  `Matcher.replaceAll`, with a bad-reference guard; literal mode still inserts `$` verbatim); a **ripgrep
  badge** shown in the panel when that backend is the active one; **bold file-name** rows in the results
  tree; **single-click / keyboard selection opens the match** (a focus-keeping preview, like the Structure
  tool window — double-click / Enter still dives into the editor); and the window now **defaults to the
  right** side (existing sessions keep their saved position).

### Changed

- **Refactor: extract the `git push` argv decision into a pure, tested `GitService.pushArgs` (developer-facing).**
  The first-push-of-an-upstream-less-branch logic (`--set-upstream origin <branch>` so the first push "just
  works", else a plain `push`) — including the guard that a blank/detached branch never emits
  `--set-upstream origin <empty>` — moved out of `MainController.gitPush` into a pure static helper with a
  focused `GitPushArgsTest` (6 cases). No behavior change; the decision is now unit-covered instead of buried
  in the UI layer.

- **Refactor: centralize the "no repo" guard on the `GitCoordinator` (developer-facing).** Every repo-only
  Git operation opened with the same four-line guard — `if (repoRoot == null) { echo "not a repo" / "git not
  installed"; return; }` — duplicated 11×. It's now one engine method, `git.reportIfNoRepo()`, and each call
  site is a one-liner (`if (git.reportIfNoRepo()) return;`). The status string lives in exactly one place.
  Pure consolidation, no behavior change; full suite green.

- **Refactor: move inline Git blame into the `GitCoordinator` (developer-facing).** The blame annotation
  engine — eligibility (`isBlameEnabled`), the off-thread per-file fetch (`applyBlame`/`refreshBlame`), and
  the blame→`BlameInfo` mapping (author/date label, full-commit tooltip, age-heatmap tint) — moves out of
  `MainController` into `GitCoordinator`, where `applyState` now drives it directly. That **removes the
  `WindowOps.refreshBlame` Host hop** added when the engine was extracted: the coordinator owns blame end to
  end and needs nothing new from the window (only the shared `CoordinatorHost`). The click→commit-diff
  actions (`blameShowCommit`/`onGutterBlameClick`) stay in `MainController` since they open a diff tab. No
  behavior change; full suite green.

- **Refactor: extract the stateful Git core into a `GitCoordinator` (developer-facing).** Under the net added
  in the previous step, the engine of the Git integration moves out of `MainController` into
  `ui/GitCoordinator`: the `GitService` CLI facade, the repo state (root / branch / upstream), and the
  state machine that keeps the status bar, the Commit / Log tool windows, and the active buffer's gutter
  change bars in sync (`isEnabled`/`isAvailable`/`ifEnabled`/`applySupport`/`refresh`/`applyState`/
  `afterMutation`/`contextPath`). It reaches the window through the shared `CoordinatorHost` plus a small
  git-specific `WindowOps` (status-bar/tool-window/blame/diff surfaces). The user-facing Git **operations**
  (commit, branch, log, blame, stash, clone, diff) stay in `MainController` and reach the engine via
  `git.service()` / `git.repoRoot()`. No behavior change; `GitStateFxTest` re-pointed at the new home with
  the same assertions, full suite green.

- **Refactor: a characterization-test net + pure helpers for the Git integration (developer-facing).** First
  step of the Git extraction (the largest, least-tested subsystem): before moving any stateful code, this
  adds a safety net — `GitStateFxTest` drives the `applyGitState`/`applyGitSupport` core directly with a
  synthetic `RepoState` and pins the repo-root/branch/upstream state machine, and a new pure, unit-tested
  `git/GitChangeBars` (the diff→CSS-class mapping + the background-buffer re-diff predicate) dedups the gutter
  change-bar logic at its two call sites. No behavior change; sets up the subsequent `GitCoordinator`
  extraction under the net.

- **Refactor: extract the HTTP Client into an `HttpClientCoordinator` (developer-facing).** The fourth (and
  most window-entangled) feature coordinator peeled off `MainController`: the request runner, the response
  tool-window panel, response-tab opening, clipboard curl import/export, and `.env` scanning move to
  `ui/HttpClientCoordinator`. It takes the shared `CoordinatorHost` **plus** a small `WindowOps` extension
  (open a tab / open-toggle the tool window / re-gate the run affordance / persist the selected environment);
  `MainController` keeps only the `ToolWindow` registration + availability plumbing. Thanks to the shared
  host, this dropped ~210 lines from `MainController` (vs ~60 for the earlier ones). Behavior is identical;
  covered by a new `HttpClientCoordinatorFxTest` against fake host + ops.

- **Refactor: a shared `CoordinatorHost` for the feature coordinators (developer-facing).** The three feature
  coordinators (log viewer / HTML preview / Mermaid) had a bespoke ~60-line anonymous `Host` adapter each in
  `MainController`. They now share one `ui/CoordinatorHost` interface and a single `MainController.Services`
  adapter, dropping the per-feature boilerplate — and future coordinators reuse it for free (zero adapter
  code). Test fakes extend a no-op `CoordinatorHostStub` and override only what they exercise. No behavior
  change; full suite green.

- **Refactor: extract Mermaid into a `MermaidCoordinator` (developer-facing).** The third feature coordinator
  peeled off `MainController`: the `mmdc`/`maid` service, the detected-tool availability, and the
  apply/gating/export logic now live in `ui/MermaidCoordinator`, behind a narrow `Host` interface. The few
  mermaid reads other features need are exposed as coordinator methods (`mmdcCommandOrNull()` for PDF/print,
  `effectiveAutocomplete()` for the completion config, `exportDiagram()` for PDF export). Behavior is
  identical; covered by a new `MermaidCoordinatorFxTest` against a fake `Host`. Continues de-godding
  `MainController` one feature at a time.

- **Refactor: extract HTML Live Preview into an `HtmlPreviewCoordinator` (developer-facing).** The second
  feature coordinator peeled off `MainController` (after `LogViewerCoordinator`): the loopback HTTP server,
  the detected-browser list, and the browser picker now live in `ui/HtmlPreviewCoordinator`, reaching the
  window only through a narrow `Host` interface (an anonymous adapter in `MainController` + one-line
  delegations at the call sites). Behavior is identical; covered by a new `HtmlPreviewCoordinatorFxTest`
  against a fake `Host`. Continues de-godding `MainController` one feature at a time.

- **Refactor: extract the log viewer into a `LogViewerCoordinator` (developer-facing).** The first
  *feature coordinator* split out of the ~13k-line `MainController`: the server-log-viewer state (tail
  service + per-buffer follow/offset/control maps) and all its logic now live in `ui/LogViewerCoordinator`,
  which reaches back into the window only through a narrow `Host` interface. `MainController` supplies an
  anonymous `Host` adapter and keeps one-line delegations at the call sites (open/save-as/rename, tab close,
  settings apply, huge-file load, command registration), dropping ~240 lines. Behavior is identical; the
  payoff is a smaller controller and a unit-testable seam — the new `LogViewerCoordinatorFxTest` exercises
  the coordinator against a hand-written fake `Host`. This establishes the template for de-godding
  `MainController` one feature at a time.

- **Upgraded to JavaFX 26 + enabled the macOS Metal rendering pipeline.** On Apple silicon (e.g. M-series), the
  deprecated es2/OpenGL backend JavaFX 25 used could render parts of the UI as blank/garbled rectangles in the
  packaged app (the file breadcrumb and tool-window stripe icons). JavaFX 26 adds the Metal pipeline, now
  selected first on macOS (`-Dprism.order=mtl,es2,sw`; es2/sw fallback). Windows keeps Direct3D, Linux keeps
  es2 — the pipeline order is set per-OS. The JDK stays 25 (JavaFX 26 runs on JDK 24+).

- **Performance: config writes happen off the JavaFX thread (developer-facing).** The frequent, coalesced
  in-session save (`requestSave` — fired by settings toggles, view changes, tab/fold changes) now serializes
  a consistent snapshot on the FX thread and writes it on a dedicated `config-writer` daemon thread, instead
  of blocking the UI on `settings.toml` + `workspace-state.json` disk I/O. A new `config/ConfigWriter`
  coalesces writes per file (latest wins) and keeps them ordered through one thread, with an atomic temp-file
  + move so a crash never leaves a half-written config. Durable saves (quit, one-off actions, export) and a
  JVM-shutdown flush block until written, and all settings/session writes funnel through the one writer
  queue, so an async write can never land after and clobber a durable one. No behavior change; covered by a
  new unit test (`ConfigWriterTest`).
- **Performance: lazy-attach feature overlays (developer-facing).** Each editor buffer no longer eagerly
  builds the five overlays that are inert for most files — find-match highlight, log-level, Mermaid-lint,
  LSP-diagnostic, and AceJump. They're constructed (Canvas + scroll/edit subscriptions) only on first
  activation and inserted in their original z-order, so a buffer with LSP off, no diagram, not a log, and
  never searched/ace-jumped allocates none of them. Cuts per-buffer memory and per-pulse listener fan-out
  when many tabs are open; no behavior change. Covered by a new headless-FX test (`LazyOverlayFxTest`).

### Added

- **Server log viewer.** Opening a `.log` file now gets log-aware treatment: **level highlighting** (ERROR/
  WARN/INFO/DEBUG/TRACE coloured inline and as a left-edge bar that works even on huge files where syntax
  highlighting is off), **Follow tail** (`tail -f` — a floating toggle streams new lines as the file grows on
  disk and auto-scrolls to the bottom), **open-the-tail** for very large logs (a multi-GB log opens read-only
  at its *end* instead of its start), and **live level + regex filtering** (a floating control narrows the
  view to e.g. `WARN+` or lines matching a pattern as you type — the query is a case-insensitive regex that
  falls back to a literal substring when it isn't valid one; a stack trace inherits its record's level so it
  stays visible).
  Detection covers Logback/Log4j, `java.util.logging`, syslog, nginx, structured `level=…`/JSON logs, zerolog,
  and Apache/Nginx access logs (5xx→error, 4xx→warn). Log files **open in View mode** (read-only with an
  Enable-Editing banner) by default, since they're for reading — follow still streams while read-only.
  On by default for `.log` files (**Settings → Editor →
  Logs**, or `View: Toggle Log Viewer`); the rest is per-buffer via the floating control or the palette
  (`Log: Toggle Follow`, `Log: Filter by Level`, `Log: Filter by Pattern`, `Log: Clear Filter`,
  `Log: View as Log`). No new dependency.

- **Ripgrep backend for Find in Files.** When [ripgrep](https://github.com/BurntSushi/ripgrep) (`rg`) is
  installed, project-wide **Find in Files** uses it for a much faster, `.gitignore`-aware search; otherwise the
  built-in walker is used unchanged. It's automatic (on when `rg` is detected) and falls back silently when rg
  is absent, the regex is one rg can't compile, or the project is remote (SFTP). Unsaved edits in open buffers
  still win (their in-memory text is searched). Configurable in **Settings → Search** (toggle + command path +
  a found/not-found status) and via the palette (`Toggle Ripgrep Search`, `Set Ripgrep Command…`). No new
  dependency — `rg` is an external tool resolved on PATH.

- **Headless-JavaFX test harness (developer-facing).** `ui/` controller behavior is now covered by real,
  headless FX tests — TestFX over a self-built JavaFX 25 Monocle backend (vendored in `m2-repo`, test scope
  only), so they run on CI with no display/xvfb. Tests lock down Zen mode hiding/restoring chrome,
  Simple-mode toolbar stripping, chrome-visibility prefs, the tab/buffer lifecycle, the floating
  toolbar-restore/Zen-exit buttons, tab pinning + Close-Others/Close-All, and command-palette feature gating.
  FX tests are tagged `fx` (run the pure suite alone with `-DexcludedGroups=fx`). No change to the app, the
  `dist`/`fatjar` builds, or `module-info` — Monocle is never shipped.
- **Window/tool-window decision logic extracted + unit-tested (developer-facing).** The multi-window
  restore-set / primary-window / orphan-session-GC logic (where a quit-vs-close drain bug once lived) moved
  into the pure `ui/WindowKeys`, and the tool-window stripe/button visibility rules into `ui/ToolWindowVisibility`;
  `WindowManager`/`ToolWindowManager` now delegate to them. No behavior change.

- **External Tools (IntelliJ-style).** Define your own CLI commands in **Settings → External Tools** and run
  them on the current file/buffer. Command and arguments support `$Name$` macros (`$FilePath$`, `$FileDir$`,
  `$FileName$`, `$FileNameWithoutExtension$`, `$SelectedText$`, `$LineNumber$`, `$ColumnNumber$`,
  `$ProjectFileDir$`), and a tool can pipe the **selection** or **whole buffer** to the command's stdin. Each
  tool chooses what happens with its output: show it in a read-only **console** tool window, **replace the
  selection**, **replace the whole buffer** (undoable), or **insert at the caret** — so the same feature
  covers both "run a command and see the output" and text transforms (formatters/filters like `jq`, `sort`,
  `sed`). Every defined tool becomes its own palette command (and is bindable to a key via Settings →
  Keymaps); there's also **External Tools: Run…** (a picker) and **Rerun Last**. Tools run off the UI thread
  with a 2-minute timeout. Available by default (the tool list is empty until you add one) and disabled in
  Simple UI mode.

- **Markdown support improvements.** A broad set of upgrades to the Markdown experience:
  - **More CommonMark extensions** in the preview — **YAML front matter** (rendered as a metadata block),
    **footnotes**, **heading anchors**, and **`++inserted++`** text, on top of the existing GFM tables /
    task lists / strikethrough / autolinks.
  - **Heading outline** — the **Structure** tool window now shows a Markdown document's `#`…`######` heading
    tree (ATX + Setext), so long documents are navigable; click a heading to jump.
  - **Markdown linting** — high-confidence rules (trailing whitespace, multiple blank lines, ATX hash
    spacing, multiple top-level H1, fenced block without a language, missing final newline, broken
    reference links) shown as inline squiggles with hover messages and listed in a **Markdown Lint** tool
    window. On by default; toggle with **View: Toggle Markdown Lint**.
  - **Image paste & drag-drop** — paste an image from the clipboard, or drop image files onto a saved
    Markdown buffer, and the image is written into a sibling `assets/` folder with an `![](…)` link inserted.
  - **Smart link paste** — paste a URL over a selection to wrap it as `[selection](url)`.
  - **Table editing** — **Tab** / **Shift-Tab** move between pipe-table cells and **Enter** on the last row
    adds a row, reflowing the table as you go.
  - **LaTeX math** — render inline `$…$` and display `$$…$$` math in the preview (and block formulas in PDF
    export) via JLaTeXMath, with GitHub-style delimiter rules so prose dollar amounts are left alone.
    **Off by default** (Settings → Editor → *Render LaTeX math*, or **View: Toggle Math Rendering**).
  - **Export to HTML** — export the rendered preview to a standalone, self-contained `.html` file (embedded
    stylesheet, heading anchors, math as images) via **Preview: Export to HTML**.

- **More Emacs editing & movement commands.** Filled the remaining gaps versus a standard Emacs
  `global-map`: **backward-kill-word** (`M-DEL`); case commands **upcase/downcase/capitalize-word**
  (`M-u`/`M-l`/`M-c`) and **upcase/downcase-region** (`C-x C-u`/`C-x C-l`); whitespace and line surgery —
  **join-line / delete-indentation** (`M-^`), **delete-horizontal-space** (`M-\`), **just-one-space**
  (`M-SPC`), **delete-blank-lines** (`C-x C-o`), **open-line** (`C-o`), **kill-whole-line** (`C-S-DEL`),
  and **zap-to-char** (`M-z`); structural navigation — **forward/backward-sexp** (`C-M-f`/`C-M-b`),
  **mark-sexp** (`C-M-SPC`), **kill-sexp** (`C-M-k`), **beginning/end-of-defun** (`C-M-a`/`C-M-e`, heuristic
  in a language-agnostic editor), and **mark-paragraph** (`M-h`); plus **mark-whole-buffer** (`C-x h`) and
  **move-to-window-line-top-bottom** (`M-r`). All are palette-discoverable and rebindable like any command.
  *Kill-ring behavior (yank-pop, consecutive-kill accumulation) remains deferred — these "kill" commands
  delete, and cut/copy/paste still use the system clipboard.*

- **Keyboard macros (Emacs-style).** Record a sequence of editor actions and replay it. Recording captures
  the faithful interleaved stream of **invoked commands and literally typed text**, and replay reproduces the
  exact sequence — replayed typing runs through the same auto-close / auto-indent assists as live typing.
  Start with **F3**, stop with **F4**, replay the last macro with **C-x e**; the palette adds **Replay Last N
  Times**, **Name and Save Last** (saved macros persist across sessions in `macros.json`), **Run Saved**, and
  **Delete Saved**. A saved macro becomes its own palette command, so it can be **bound to a keyboard
  shortcut** via Settings → Keymaps like any other command. The recording hooks are inert (a single boolean
  check) when not recording, so there's no idle cost. *Known limitation:* a recorded command that opens a
  modal picker/prompt pauses replay for input (it isn't scripted).

- **New Window command.** A palette command **Window: New Window** (`C-x 5 2`) opens an additional editor
  window without having to load a project — previously a second window only appeared when a project was
  opened. Each new window has its **own session** (independent open files, layout, and bounds — windows no
  longer share or clobber one state file) and is **restored on the next launch** like a project window.
  Works whether or not the Projects feature is enabled.

- **TODO / highlight patterns (IntelliJ-style).** Configurable regex patterns (TODO and FIXME by default,
  each with its own color) are highlighted everywhere they match in the editor, and listed in a new **TODO**
  tool window (`M-g o`) grouped by file — scanning the open project's tree when a project is open, else the
  open files; double-click a result to jump to it. Matches also show as **overview stripes** over the
  scrollbar and on the minimap edge (each in its pattern's color; click to jump, hover for the line). On by
  default. Add/edit/remove patterns (name, regex, a **color picker**, case-sensitivity, enabled) in
  **Settings → Editor → TODO Highlighting**; commands `tool.todo`,
  `todo.refresh`, `todo.addPattern`, and `view.toggleTodoHighlight`. Highlighting runs off the UI thread and
  is debounced; the project scan is lazy (only when the tool window is open or refreshed).

- **LSP-powered Structure tool window.** For files served by a language server, the Structure outline is now
  built from the server's `textDocument/documentSymbol` response — a precise hierarchy of classes, methods,
  fields, enums, etc. with proper kinds and per-kind icons, and methods showing their signature (parameters
  and return type, in a muted colour) — instead of the fold/TextMate heuristic (which
  remains the fallback for non-LSP languages, when LSP is off, or for remote files). The outline refreshes
  live as you type and once the server finishes starting, and clicking an entry still navigates to it. New
  toolbar controls: a **sort** selector (Position / Name / Kind) and a **kind filter** to hide categories.

- **Project tool window auto-refreshes on filesystem changes.** A watcher on the project tree (the root +
  currently-expanded folders) refreshes the view when files are created, deleted, renamed, or modified on
  disk — by another app, a build, git, or Editora itself — preserving expanded folders and the selection.
  Local projects only; on macOS (where the JDK uses a polling watcher) external changes may take a few
  seconds to appear. Deleting a file from the tree's right-click menu now also removes its row immediately.

- **EditorConfig status-bar indicator.** When an `.editorconfig` governs the current file, the status bar
  shows an **EditorConfig** segment; clicking it (or the **EditorConfig: Open Active File** palette command)
  opens the nearest governing `.editorconfig`. Hidden when no config applies and in Simple UI mode.

- **Global indent-style preference.** Settings → Editor → "Indent style" (and the **Editor: Set Indent
  Style…** palette command) lets you force **Spaces** or **Tabs** for Tab/Enter indentation, or keep
  **Detect from file** (the default — per-file auto-detection from the file's existing indentation). A
  file's `.editorconfig` `indent_style` still wins over this when EditorConfig is enabled; the global
  preference is the fallback, and per-file detection is the fallback below that. Applies live to every
  open buffer.

- **EditorConfig (`.editorconfig`) support.** Opening a file now resolves the nearest `.editorconfig`
  chain (walking up to `root = true`, nearest-directory-wins) and applies its style to the buffer:
  - `indent_style` / `indent_size` / `tab_width` drive Tab/Enter indentation (tabs vs. N spaces) and the
    minimap/indent math, overriding Editora's auto-detection.
  - `end_of_line` (lf/crlf/cr) sets the line ending used on save; a manual EOL change still wins for the
    session.
  - `charset` (utf-8, utf-8-bom, latin1, utf-16le, utf-16be) is honored on **read and save** — files are
    decoded with the right charset (BOM-detected first, else the `.editorconfig` value) and written back
    in the same charset, BOM preserved. The status bar shows the active charset.
  - `max_line_length` sets the column-ruler position (`off` hides it).
  - `trim_trailing_whitespace` and `insert_final_newline` are applied **on save** (disk bytes only — the
    on-screen document, caret, and undo history are untouched).
  - Glob sections support `*` `**` `?` `[seq]` `[!seq]` `{a,b}` `{n1..n2}`; unknown keys/sections are ignored.
  - **On by default**; toggle via Settings → Editor → "Enable EditorConfig" or the `view.toggleEditorConfig`
    palette command. Local files only.

- **Tab re-indents the current line to the language server's convention.** In an LSP language whose server
  supports range formatting (e.g. Java via jdtls), pressing Tab snaps the current line's indentation to
  what the formatter would produce — only the leading whitespace changes, the rest of the line is left
  alone. It still accepts an open completion / advances a snippet tab-stop first, indents a multi-line
  selection, and on a blank line or when the server can't format it falls back to the normal smart-Tab
  indent. Uses `textDocument/rangeFormatting` (resolved off-thread).

- **IntelliJ-style code completion popup.** The autocomplete list now looks and behaves much closer to
  IntelliJ IDEA:
  - **Per-kind icons** — each row shows a glyph for its kind (method, field, variable, class, interface,
    enum, keyword, module, snippet, …), mapped from the language server's `CompletionItemKind`.
  - **Matched-character bolding** — the characters you've typed are bolded within each candidate
    (contiguous prefix/substring, or camelCase/subsequence).
  - **Relevance ordering** — LSP candidates are ordered by the server's relevance (preselect, then
    `sortText`), and the server's preselected item starts highlighted, instead of raw server order.
  - **Documentation side-popup** — a panel beside the list shows the selected symbol's documentation
    (and signature), resolved lazily from the server and rendered as Markdown. It auto-shows (toggle the
    default in Settings → Editor) and **Ctrl+Q** shows/hides it for the current item. It positions to the
    right of the list and flips left at the screen edge.
  - **Deprecated APIs** are struck through; **PageUp/PageDown** page the list.
  - The type/detail column is enriched from the server's `labelDetails`.
  Negligible cost on the typing hot path — the icon/label/ordering work is cheap and per-render, and the
  documentation is resolved off the FX thread and debounced per selection.

### Performance

- **Smoother scrolling in files with many lines.** The line-number gutter set each row's number via a
  per-row reactive binding that subscribed/unsubscribed as cells recycled during a scroll — measurable churn
  on the hot path. The number is now set directly from the live line count, and re-pads only when the count
  crosses a power of 10 (rare). A layout-forced scroll sweep over a ~375-line Markdown file was ~12–21%
  faster in the gutter (measured A/B in one process). Line numbers, folding, and all gutter markers are
  unchanged.

- **Cheaper spell-check overlay redraw while scrolling prose.** The red-squiggle overlay re-runs each scroll
  pulse over the visible words. It now checks the (cached) spelling verdict *before* the per-word syntax-style
  lookup, so that lookup runs only for the few misspelled words instead of every visible word, and it hoists
  the per-word offset query out of the loop. Redrawing a ~375-line Markdown file was ~16–22% faster (measured
  A/B in one process); squiggle placement is unchanged.

### Changed

- **Git blame is now an IntelliJ-style "Annotate" gutter column instead of a single inline annotation.**
  Turning on blame (`M-g a`, or Settings → Git) now shows a per-line column on the left of the editor —
  author and commit date on **every** line, with an age-heatmap background (recent commits tinted warmer,
  older ones faded), a hover tooltip with the full commit (author / date / relative time / message / hash),
  and click to open that line's commit. This replaces the previous GitLens-style annotation that only
  appeared after the current caret line. Same toggle and setting; only the active file is annotated, and the
  blame still runs once per file off the UI thread (no per-keystroke cost).

- **Tool windows and status segments now follow the active file's capabilities.** The **Problems** stripe
  button shows only when the active file is served by a language server; the **Debug** stripe button only for
  a debuggable file (Java/Python/JS) or while a debug session is live; and the **Git Log** stripe button plus
  the status-bar **branch** segment only when the active file is inside a git repo. Each is hidden (not greyed)
  when it doesn't apply — so a Welcome/Markdown/plain-text tab no longer shows Problems, Debug, the branch
  segment, or a git context inherited from the working directory. The `LSP:` status segment already cleared
  on non-LSP files.

- **Find and Find in Files pre-fill from the selected text.** Select a word or phrase on a single line, then
  open the in-editor Find/Replace bar (the toolbar icon, `C-s`/`C-r`, or Replace) or Find in Files (its
  toolbar icon or `C-S-f`) and the selection becomes the search term — the field is also selected so you can
  type over it. A multi-line selection is left alone (it isn't a sensible find term) and whatever query was
  already there is kept. Matches the VS Code / browser convention.

- **Markdown split view now keeps the editor and preview scrolled together.** In SPLIT mode (editor +
  rendered preview side by side), scrolling one pane scrolls the other by the same fraction of its content,
  so the two stay roughly aligned even though their heights differ. The pane the mouse is over drives the
  other, which keeps the sync one-directional at any moment so it can't oscillate; entering SPLIT aligns the
  preview to the editor's current position. Negligible cost — it rides existing scroll-position listeners with
  no new per-keystroke or per-pulse work.

- **The Project tool window shows the current file's folder when no project is open.** In the global
  ("No Project") window it no longer just shows an empty "No project open" placeholder — it roots the tree at
  the active file's parent directory and titles the tool window **"Current Folder"**, so it doubles as a file
  explorer that follows you as you switch tabs. With a project open it's unchanged (the tree stays the project
  root, titled "Project"); a mounted remote folder is also left as-is. An unsaved/Welcome tab (no folder)
  falls back to the placeholder.

- **File-type icons now line up in the Project tree (and other file lists).** Each glyph's node bounds equal
  its ink, which varies per file type, so the icon column had a ragged width and file names jumped left/right
  (and the taller document glyphs sat high). Every file/folder glyph is now centered in a fixed-size box, so
  names start at the same x and icons are vertically centered. Applies wherever file icons appear — the tree,
  tabs, the Open-Files / Recent pickers, and the Switcher.

- **Distinct icons for the tab right-click close menu.** The six close commands (Close, Close Other Tabs,
  Close All Tabs, Close Unmodified Tabs, Close Tabs to the Left/Right) all shared the plain ✕. They now each
  carry a variant of the ✕ so they're distinguishable at a glance: one small square (other), three squares
  (all), a check (unmodified/saved), and a left/right triangle (direction). "Close" keeps the plain ✕.

- **The Find in Files toolbar icon now toggles the panel** — one click opens it (focusing the query field),
  another click closes it (matching `C-s`'s in-file find and the `M-6` tool-window toggle). `C-S-f` toggles
  too, since it shares the command.

### Fixed

- **Zen mode is now per-window and restores chrome reliably.** Zen mode used to hide the toolbar, status
  bar, tab bar, breadcrumb, and editor view options by flipping the *shared, app-wide* view preferences and
  snapshotting/restoring them — so entering Zen in one window changed every window (and the saved settings),
  and the restore could desync. Zen is now a **per-window effective overlay** (like Simple UI mode): it lives
  in the window's own session state and is layered on top of the saved prefs without mutating them, so one
  window can be in Zen while another isn't, leaving Zen always restores exactly what was showing, and your
  saved view preferences are never touched.

- **Git menu items are hidden/disabled when there's no VCS.** The tab right-click menu no longer shows
  **Compare with HEAD** and **Git: Show File History** when the file isn't under Git (feature off, or not
  inside a repo) — only "Compare With…" (which isn't a Git action) remains. The Project tool window's **Git**
  submenu is greyed out in the same case. Both now key off "Git available" (enabled *and* inside a repo)
  rather than just the feature toggle.

- **HTTP Client: you can now right-click the response body to copy it.** The response body is a read-only
  RichTextFX editor, which (unlike the headers' text area) has no built-in context menu, so right-clicking the
  JSON/XML/etc. output did nothing. It now has a **Copy** / **Select All** menu — Copy puts the selection, or
  the whole body when nothing is selected, on the clipboard.

- **Loading a large file (5–50 MB) with a language server enabled no longer freezes the editor.** Large-file
  mode already turns off syntax highlighting and the minimap, but LSP stayed on — so opening, say, a big
  `.json`/`.xml`/`.log` with its server enabled sent the whole document to the server and then mapped and
  rendered the flood of diagnostics it returned (the Problems tree, minimap, and scrollbar stripes) on the UI
  thread, locking up the editor. LSP is now disabled in large-file mode too (like highlighting, the minimap,
  Git change bars, and inline blame), so the file just opens as fast, plain text. Autocomplete is likewise
  disabled in large-file mode — it allocated the whole document on every keystroke to find the word prefix
  (the same per-cursor-move cost brace matching already avoids), which made typing in a large file stutter.

- **Diff viewer: next/previous-change no longer makes the scroll jump erratically.** The two side-by-side
  panes were kept in sync by copying the absolute pixel scroll position from one to the other, in both
  directions. That value is a RichTextFX *estimate* refined as lines are measured, so each pane settled to a
  slightly different value and pushed the other back — a feedback loop, worst when navigation jumped into an
  unmeasured region. Now only the *focused* (actively scrolled) pane drives the other, so interactive sync is
  strictly one-directional and cannot oscillate. Next/previous-change navigation bypasses the sync entirely:
  it pins *both* panes to the same top row explicitly (the rows are 1:1 aligned, so this can't desync) and
  puts the selection caret at the block's top so caret-follow agrees with the scroll.

- **Spell check no longer flags commands, URLs, paths, and identifiers.** Tokens like `./mvnw`,
  `javafx:run`, `https://github.com/…`, `path/to/file.txt`, `snake_case`, and `a@b.com` were getting red
  squiggles — in Markdown prose, in fenced ` ``` ` code blocks, and in comments/strings of Java/Python/Shell
  and other code files. The checker now recognizes a flagged word that's part of a URL, path, e-mail, or
  dotted/underscored/colon-joined token and leaves it alone (ordinary words, including hyphenated ones like
  `well-known`, are still checked). In Markdown it additionally skips whole fenced code blocks and link
  targets. The check runs only for the few already-flagged words, so it doesn't affect scroll performance.

- **Language servers and debug adapters no longer pile up as orphaned processes.** Previously the external
  servers Editora spawns (e.g. `jdtls`) were only killed when a window was closed through the app — so
  force-quitting, a crash, an OS quit, or a `kill` left them running, where they accumulate and hold their
  workspace locks. Now a central `ProcessRegistry` reaps them three ways: a **JVM shutdown hook** force-kills
  every spawned server tree on exit (covers normal quit and SIGTERM/`kill`); the per-server **teardown
  escalates** (SIGTERM the whole tree, then force-kill anything that ignores it after a grace period); and a
  small on-disk **ledger** lets a fresh launch **reap any server leaked by a previous run that died hard**
  (SIGKILL / power loss), matched by PID + start time + executable so it can never kill an unrelated process.

- **Silenced a spurious LSP "Internal error" log.** Language servers that support pull diagnostics
  (the HTML/CSS/JSON servers) send a `workspace/diagnostic/refresh` request; the client didn't implement it,
  so lsp4j logged a SEVERE `UnsupportedOperationException` each time (visible in the debug log / dev console).
  The client now acknowledges the request. Diagnostics were never actually affected — they already refresh on
  each edit, on save, and when a server reports ready.

- **Find/Replace highlights now stay in sync when you edit the buffer.** With the find bar open, editing the
  text left the match highlights painted at their old offsets (no longer aligned with the searched text).
  The bar now re-runs the search on each (debounced) edit and re-highlights at the new positions — without
  moving the caret or selection, so it doesn't interfere with typing.

- **Right-click Cut/Copy/Paste now act on a column (box) selection.** The editor context menu's
  Cut/Copy/Paste called the native single-caret operations, so on an Alt+drag column/box selection they only
  touched the primary row — even though the `edit.cut`/`copy`/`paste` *commands* already worked (they route
  through the multi-caret-aware path). The menu items now use that same path (falling back to the native op
  when there's a single caret), Cut/Copy are enabled on a box selection even when the primary selection is
  empty, and the mutating Cut/Paste are gated to editable buffers.

- **Every window that was open at quit now reopens.** With multiple project windows open, a Cmd-Q / OS quit
  fired each window's close handler in turn, and the eager "mark closed" drained the saved open-window set
  down to just the last window — so only one reopened on the next launch. Closing a window is now reconciled
  on a short debounce that can't elapse during a quit's close-handler burst: a genuine single close persists
  the reduced set (that window is forgotten), but a quit exits the JVM before the timer fires, leaving the
  full pre-quit set on disk so every window reopens. (`app.quit` already worked — it fires no close handlers.)

### Added

- **Open a command's online docs from the palette (C-h).** In the command palette, press **C-h** to open the
  highlighted command's documentation page (`https://editora-project.dev/docs/commands/<id>`) in your system
  default browser. The palette footer now lists `C-h docs` alongside the other shortcuts.

- **Format Document command (LSP).** A new command-palette command **LSP: Format Document** reformats the
  whole active file via its language server (`textDocument/formatting`) — available when a server is running
  for the file's language and advertises formatting. Edits apply through the undoable buffer (so a single
  Undo reverts), and it reports when no formatter is available or the file is already formatted. Also
  available as **Format Document** in the editor right-click menu, shown only when the server advertises
  formatting.

- **Right-click a tool-window icon → Hide.** Right-clicking a button in the tool-window stripe now offers
  **Hide**, which removes that icon from the stripe (persisted). Bring it back from Settings → Tool Windows.

- **Reveal in File Manager / Open Terminal Here from the breadcrumb.** Clicking a folder or file crumb in the
  navigation bar now lists **Reveal in File Manager** and **Open Terminal Here** for that crumb (above the
  folder-contents dropdown) — Reveal selects the file or opens the folder; Open Terminal opens at the folder
  (the file's parent for a file crumb). Shown for local files only. This also **fixes the crumb dropdown not
  opening at all**: the AtlantaFX breadcrumb skin owns each crumb button's action, so the click is now caught
  via the control's crumb-action event instead of an `onAction` the skin overrode.

- **"Comment / Uncomment" in the editor right-click menu.** For files whose language has comment syntax, the
  code-area context menu now offers Comment / Uncomment (the same action as the `M-;` keybinding and the
  command palette) — it line-comments the caret's line or block/line-comments the selection, and toggles back
  off when already commented. Hidden for plaintext (no comment syntax) and while read-only.

- **MCP server (Model Context Protocol).** Editora can run a small **Model Context Protocol** server so an
  LLM agent (Claude Code, etc.) can observe live editor state and drive the command registry. It's a
  **loopback-only** HTTP/JSON-RPC server with **bearer-token auth** that writes its endpoint to
  `<configDir>/mcp-endpoint.json` for discovery, and exposes six tools: `list_open_files`, `read_buffer`,
  `get_diagnostics`, `find_in_files`, `list_commands`, and `execute_command`. A status-bar **MCP** indicator
  shows when it's running (click to copy the connection command) and is hidden in Simple UI mode. Off by
  default and gated by a security-notice dialog — enable it under **Settings → MCP Server** (or the
  **Toggle MCP Server** command, `view.toggleMcp`; `mcp.copyEndpoint` copies the endpoint). No new
  dependency (the JDK's built-in `HttpServer` + Jackson).

- **Reveal in File Manager / Open Terminal Here (built-in).** Revealing a file in Finder/Explorer/your file
  manager and opening a terminal at a folder are now core features (previously an example plugin), reachable
  from the **command palette** (`file.revealInFileManager` / `file.openTerminal`), the **tab right-click
  menu**, and the **Project tool window** menu (both files and folders). Local saved files only; per-OS argv
  (Finder/Explorer select, `xdg-open` on Linux) launched off the UI thread. (The breadcrumb crumb menu — see
  below — surfaces the same two actions.)

- **Show Local History + a Git submenu in the Project tree menu.** The Project tool window's per-file
  right-click menu gained **Show Local History** and a **Git** submenu (Show File History, Compare with HEAD,
  Stage File), mirroring the editor tab menu but acting on the clicked tree path. The Git items grey out when
  Git is off, and Local History when it's off.

- **HTTP Client — request chaining, multipart, dynamic vars, and a richer viewer.** The `.http`/`.rest`
  client moved much closer to IntelliJ's: reference an earlier named request's response in a later one
  (`{{name.response.body.$.path}}` / `.headers.H` / `.status`, threaded across a run-all), **multipart/
  form-data** bodies (inline + `< ./file` parts), **external request bodies** (`< ./file` raw, `<@ ./file`
  substituted), **response-to-file** redirects (`>> file` / `>>! file`), per-request directives
  (`# @no-redirect` / `@no-cookie-jar` / `@no-log` / `@timeout N`), a per-run cookie jar, and the full
  **dynamic-variable** family (`{{$random.*}}`, `{{$datetime "fmt"}}`, `{{$processEnv.X}}`, `{{$dotenv.X}}`).
  The response viewer is now a content-type-highlighted read-only editor with split status/headers/body, an
  in-session history picker, **Copy as cURL**, **Import cURL**, and **Open in editor tab**. No new dependency.

- **HTTP Client — Digest auth, URL auto-encoding, date math, and `$shared` env.** A second IntelliJ-parity
  pass: the `Authorization: Digest user pass` shorthand now performs the 401-challenge round-trip
  (RFC 2617); request URLs are **percent-encoded automatically** after substitution (disable with
  `# @no-auto-encoding`); `{{$datetime}}` / `{{$localDatetime}}` / `{{$timestamp}}` take signed **offsets**
  (`y/M/w/d/h/m/s/ms`); and a `$shared` section in `http-client.env.json` merges its variables under every
  environment (and is hidden from the picker). New directive `# @connection-timeout N`.

- **Per-file-type icons.** File icons now reflect the file's type instead of a single generic document glyph.
  Editing `Main.java` shows the Java logo, `app.py` the Python logo, `style.css` the CSS logo, an image its
  picture glyph, a `.zip` an archive glyph, and so on — covering every language/format Editora supports
  (Java, Python, JS/TS, Go, PHP, Ruby, C/C++, C#, Kotlin, HTML, CSS, Markdown, YAML, JSON, XML, shell,
  Docker, Terraform, Mermaid, SQL, INI/TOML, images, PDFs, archives, CSV, …), with the generic document glyph
  as a fallback for unknown types. The icons appear everywhere a file is listed — **editor tabs**, the
  **Project tool window** tree, the **Jump to Open File** and **Jump to Recent File** pickers, the
  **Switcher** (Ctrl-Tab), and the **file/folder finders**. They are monochrome single-path glyphs in the
  existing toolbar-icon style, so they track the light/dark theme automatically (no per-theme overrides).
  Brand logos are from Simple Icons (CC0); category glyphs from Material Design Icons (Apache-2.0).

- **Local file history.** Editora now silently snapshots local files over time — on save, auto-save, and
  before an external-change reload — so you can recover earlier versions independently of any VCS. Browse them
  in a new **File History** tool window (`M-g l` or the palette **File History: Show**): each revision shows
  its date/time, reason, and size, with the latest tagged **Current**. Double-click a revision for a read-only
  diff against the current file, or restore one (an undoable whole-file replace). Snapshots are deduped by
  content, stored gzip-compressed and content-addressed under `<configDir>/history/`, and pruned by
  configurable limits (max revisions/file, max age, max size per project — Settings → Application → Local
  History). On by default; local-only (no SFTP) and off in Simple UI mode.
- **Emacs fill commands.** Re-wrap paragraphs to a fill column: **Fill Paragraph** (`M-q`) reflows the
  paragraph at the caret, **Fill Region** reflows every paragraph in the selection, and **Set Fill Column**
  (`C-x f`) sets the target width (default 70, also in Settings → Editor). Filling preserves the paragraph's
  indentation and an adaptive fill prefix — line comments (`//`, `#`, `;`, …), Markdown blockquotes (`>`),
  and Javadoc (`*`) — so code comments and quoted text wrap correctly; a word longer than the column is
  never broken. (Auto Fill mode and justification are deferred.)

### Fixed

- **LSP completion now triggers on each server's own trigger characters** (e.g. `<` for HTML, `:` / space
  for CSS), not just on `.` or a 2-character word prefix. This makes HTML/CSS/JSON/YAML autocomplete behave
  like VS Code — typing `<` in an HTML file now pops up tag completions. The trigger set is read from the
  server's advertised capabilities, so every language server gets its correct triggers.
- **LSP pull diagnostics** (`textDocument/diagnostic`, LSP 3.17) are now supported. Servers that deliver
  diagnostics on request rather than by pushing `publishDiagnostics` — vscode-html/css/json and others — now
  surface their warnings/errors as squiggles and in the Problems panel. Push-only servers (jdtls, Pyright,
  tsserver) are unaffected.

### Added

- **HTML Live Preview.** A floating browser-globe button now appears at the top-right of any `.html`/`.htm`/
  `.xhtml` editor (mirroring the Markdown preview toggle). Click it to open the current file in a **detected
  browser** — Safari, Chrome, Firefox, Edge, or the system default. The file is served over a tiny embedded
  web server bound to **loopback only**, so its sibling CSS, JS, and images load, and a small injected script
  **reloads the page live as you type** (serving the buffer's in-memory text, so unsaved edits show
  instantly). Off by default — enable it under **Settings → HTML Preview** (or the **Toggle HTML Live
  Preview** command). Palette commands: **Open in Browser** / **Open in Browser…** (pick a browser). No new
  dependency (uses the JDK's built-in `HttpServer`).

- **Command descriptions in the palette.** Every command now has a one-line description, shown as a detail
  line between the results list and the navigation hint in the command palette (`M-x`) — it updates live as
  you arrow through the list, so you can tell what an unfamiliar command does before running it. All ~231
  descriptions are fully localized in every bundled language (en/it/es/fr/pt/de); a build test guarantees
  every command has one. The website's Commands page reads the same descriptions, so the app and the site
  never drift.

- **Configurable shortcuts (keybinding editor).** Settings → Keymaps now lists every command with its
  current shortcut and a filter box: **Record** a new chord (multi-key sequences like `C-x C-s` are
  captured live), **Reset** one command to the keymap default, or **Reset all shortcuts**. Rebinding
  replaces the keymap's default (with a conflict warning before stealing another command's chord) and
  applies **live, no restart**, across all windows; overrides persist in `settings.toml` and layer on top
  of whichever keymap theme is active.
- **Select All, Duplicate Line, Move Line Up/Down** commands (`edit.selectAll` / `edit.duplicateLine` /
  `edit.moveLineUp` / `edit.moveLineDown`), bound in the CUA/Sublime/VSCode/IntelliJ keymaps
  (Ctrl/Cmd-A, Alt/Option-Up/Down, etc.) and available in the command palette + the new keybinding editor.

- **Keybinding themes (CUA, Sublime Text, VSCode, IntelliJ IDEA).** Beyond the default **Emacs** keymap,
  Editora now bundles four familiar non-modal keymaps. Pick one in **Settings → Keymaps** (or the
  **Keymap: Select…** command) — switching is **live, no restart**, across all open windows. Each keymap
  ships a Ctrl-based (Windows/Linux) and a Cmd-based (macOS) variant, auto-selected per platform. User and
  plugin keybinding overrides are re-applied on top of whichever keymap is active. (Modal **Vim** is
  deferred — it needs a mode state machine the current resolver doesn't model.)

- **Signed plugin registry (authenticity).** The registry `index.json` is verified against a **bundled
  Ed25519 public key** via a detached signature (`index.json.sig`) before Editora trusts it, and **"Require
  signed plugins"** (Settings → Plugins, default on) blocks installs from a registry that doesn't verify —
  closing the registry-tampering / silent-malicious-update gap that SHA-256 alone can't. A non-default
  registry must be signed with its own key or have the setting turned off. JDK-only (no dependency); sign
  with `scripts/PluginSigningTool.java`. Signing proves *who* published — not a sandbox.
- **Plugin security hardening (consent + integrity).** Enabling a plugin now shows a **capability
  disclosure** first — whether it runs executable code, the exact external commands it declares, and any
  keybindings it remaps — at every arming point (Browse install, install-from-file, and the per-plugin
  enable checkbox). The registry index and plugin downloads are read with a **hard size cap** (a hostile
  registry can't OOM the app), registry-entry fields are length-capped, and a **non-default registry URL**
  is flagged with its host. (Consent/integrity, not a sandbox — plugins remain full-trust.)
- **Plugin support** — extend Editora without forking it. A plugin is a folder under
  `<configDir>/plugins/<id>/` with a `plugin.json` manifest plus, optionally, a Java jar and asset dirs.
  Two flavors, usable together: a **Java SPI** (implement `com.editora.plugin.Plugin`; a `PluginContext`
  lets it register palette commands, keybindings, dockable tool windows, editor right-click items, and
  status-bar segments, and edit the active buffer) and a **declarative manifest** (keymap bindings, palette
  commands that run an external program, and `snippets/`/`templates/` dirs merged into the registries).
  Plugins are loaded once at startup by a child `URLClassLoader` (parent = the app loader), so the same jar
  works in dev **and** inside the packaged jlink installers — no module path, `ServiceLoader`, or
  `ModuleLayer` needed. **Off by default and full-trust** (no sandbox): a master *Enable plugins* gate plus
  a per-plugin enable list live in **Settings → Plugins** (with a security note, the plugins-folder path, a
  Reload button, and load-error reporting); enabling/disabling takes effect on the next launch. Simple UI
  mode disables plugins along with the other local-process features. Palette: `view.togglePlugins`. See
  `docs/plugins.md` and `examples/example-plugin/` (a complete example with a build script).
- **Plugin registry & install** — install plugins without hand-copying folders. *Settings → Plugins →
  Browse plugins…* (palette `plugins.browse`) fetches a curated GitHub-hosted **`index.json`** over HTTPS
  and lists installable plugins; picking one downloads its `.zip`, **verifies the SHA-256**, and unpacks it
  into the plugins dir (with a zip-slip guard + size caps). *Install from file…* (`plugins.installFromDisk`)
  installs a local `.zip`, and each Settings row has a **Remove** button. The registry URL is configurable
  (a baked-in default you can override); entries show *Installed / Update available / Install / Requires
  Editora ≥ x*. The distribution format is a `.zip` whose top level is the plugin folder contents;
  `examples/example-plugin/build.sh` now emits one (+ its SHA-256) and `examples/editora-plugins-registry/`
  is a ready-to-host sample index. All network/zip/disk work is off the FX thread; no new dependency
  (`java.net.http`). See `docs/plugins.md` → *Publishing & installing*.
- **More example plugins + `ActiveEditor.setText`** — the plugin `ActiveEditor` facade gained `setText`
  (whole-buffer, undoable replace) for whole-file transforms. New real example plugins under `examples/`:
  **lorem-ipsum** (text generator), **text-tools** (case convert / sort / unique / reverse / trim, on the
  selection or whole document), **insert-tools** (UUID / timestamp inserters), **scratchpad** (a persistent
  tool window backed by the plugin's data dir), and **format-runner** (run an external formatter —
  prettier/black/gofmt/rustfmt/clang-format — over stdin→stdout). Plus **encode-tools** (Base64/URL/HTML/
  ROT13/hex), **hash-tools** (MD5/SHA-1/SHA-256), **markdown-toc** (insert a TOC from headings), and
  **regex-tester** (a live regex tester tool window); Text Tools also gained *Squeeze Blank Lines* (v1.1.0).
  All are published in the `adriandeleon/editora-plugins` registry, which carries each plugin's source.
- **Git history, blame & stash** (IntelliJ/VSCode parity) — a **Git Log** tool window (`M-g h`, or *Show
  File History* on a tab) lists commits; select one to see its changed files, double-click a file for a
  read-only diff against its parent, and right-click a commit to Copy Hash / Checkout / Reset
  (soft·mixed·hard) / Revert / Cherry-Pick / New Branch. **Inline blame** (`M-g a`, GitLens-style) shows a
  faint "author, time ago • summary" after the current line, following the caret; toggle it from *Settings
  → Git* or the palette. **Stash** push (with an optional message) / pop / apply / drop via the palette or
  the status-bar branch dropdown. All require Git enabled (and are off in Simple UI mode); blame is off by
  default.
- **Simple UI mode** — a one-toggle minimal layout that strips the editor to the essentials: it hides the
  extra toolbar groups (new-from-template, recent, find-in-files, split, project selector), the
  tool-window stripe, the file breadcrumb, the **entire gutter** (line numbers, fold chevrons, and
  bookmark/note/run/breakpoint/git markers — any collapsed regions are unfolded first so nothing is
  stranded), the minimap, and most status-bar segments (git, the LSP server indicator, language, tab size,
  line endings, encoding), while keeping
  the tabs, core toolbar icons (including **Open**), and the echo / read-only / zoom / Ln-Col / file-size status. It also **disables the
  heavier features** — language servers (LSP), debugging, the HTTP client, Git, and multiple cursors /
  column selection — for a quiet, plain
  editor (the saved enable settings are untouched and restored on exit). Toggle it from the toolbar
  icon, the command palette (**View: Toggle Simple UI Mode**),
  or *Settings → Application*; it persists. The **`--simple`** command-line flag starts a session in Simple
  mode without changing the saved preference. Your own line-number/minimap preferences are preserved and
  return when you toggle it off.
- **Remote file access (SFTP)** — connect to a server over SSH/SFTP and edit its files as if they were
  local. **Remote: Connect to SFTP…** opens a connection form (host/port/user, and auth via your default
  `~/.ssh` keys, a chosen key file, or a password); on connect the remote folder is **mounted in the
  Project tool window** so you can browse it, and opening a file edits + saves it directly over SFTP. Saved
  connections are remembered (metadata only — never a password) and reachable via **Remote: Saved
  Connections…**; **Remote: Open File…** opens a single `sftp://` URI and **Remote: Disconnect** returns to
  your local project. Features that need a local process (language servers, debugging, Git, Run, the HTTP
  client) are automatically disabled for remote files. Built on Apache MINA SSHD; no external tools needed.
- **HTTP Client (`.http`/`.rest` files)** — open a `.http` file with syntax highlighting and a green
  ▶ on every request; click it to run that request and see the response (status, headers, pretty-printed
  JSON body, timing/size) in an "HTTP Client" tool window (`M-0`). Uses Editora's **built-in HTTP
  client** — no external tools needed (off by default; enable in **Settings → HTTP Client**). Supports
  in-file `@variable`/`{{var}}` substitution (incl. `{{$uuid}}`/`{{$timestamp}}`), **environment files**
  (`http-client.env.json` + `.private.env.json`, with an environment picker), running the whole file,
  and **saving the response**. Commands: `HTTP: Run Request at Caret`, `HTTP: Run All Requests in File`,
  `HTTP: Select Environment`.

- **File templates** — "New File From Template" creates a file (or set of files) from a reusable
  template with `${variable}` substitution, a `${cursor}` placement, and a variable-entry wizard:
  - A template picker (palette `Template: New File From Template…`, **C-c C-n**, and a toolbar button)
    lists bundled + user templates; a wizard prompts for any variables the template references (e.g.
    `${className:Main}`), pre-filled with defaults. Built-ins (`${author}`, `${projectName}`,
    `${date}`, `${baseName}`, …) are filled automatically.
  - **Single-file** templates open as an untitled buffer with the caret at `${cursor}`; **multi-file**
    templates (a `files[]` array) and the project tree's folder right-click **New From Template…**
    write the files to disk and open the primary one. A traversal guard rejects paths that escape the
    target folder; existing files are never overwritten.
  - User templates live in `<configDir>/templates/*.json` (`Template: Edit User Templates…`,
    `Template: Reload Templates`); a new **Author name** setting (Settings → Application → Templates,
    defaulting to the OS user) feeds `${author}`. Always-on, like snippets.
- **Diff viewer** — compare files visually in a dedicated tab:
  - **Side-by-side** (default) with synchronized scrolling, original line numbers, syntax highlighting,
    per-line added/removed/changed backgrounds, and **intra-line word emphasis** on changed lines; a
    toolbar toggle switches to a **unified** (+/−) view.
  - **Prev/next change** navigation and **Export patch…** (writes a `.patch`).
  - Entry points: **Compare with HEAD** for the current file (`C-x v =`, also the tab and Git-panel
    menus), **Compare With…** any other file, **Compare with Commit…** (pick from the file's history),
    and **Show Diff** on a Git-panel row (staged → index↔HEAD, unstaged → working↔index).
  - **Merge-conflict resolution** — open a file with Git conflict markers in a merge view and accept
    **Ours / Theirs / Both** per conflict, then save the resolved file (`Merge: Resolve Conflicts`).
  - Commands `diff.vsHead`, `diff.compareWith`, `diff.vsCommit`, `merge.resolve` (palette-discoverable).

- **Better Compact Source File (JDK 25) support** — running and debugging single-file programs is now
  much more complete:
  - **Console input** — the Run window has an input field: type a line and press Enter to send it to
    the program's stdin (so `IO.readln(...)`-style interactive programs work instead of hanging).
  - **Program arguments** — *Run File with Arguments…* prompts for arguments (quote-aware), remembers
    them per file, and both **Run and Debug** pass them to the program. *Rerun Last Run* repeats the
    previous run.
  - **Clickable stack traces** — double-click a Java/Python/Node stack-trace line in the Run or Debug
    console to jump straight to that file and line.
  - **JDK version check** — running a `.java` file with an older JDK on PATH now says "Compact source
    files need JDK 25 or newer — found Java N" instead of a cryptic launcher error.
  - **Quieter diagnostics** — implicit-class complaints from a Java language server that doesn't know
    JDK 25 yet are filtered out for compact files (real errors still show).
  - **Snippets** — `cmain` (compact `void main()` skeleton), `ioprintln`, and `ioreadln`.

- **IntelliJ-style debugger UI** — the Debug tool window and the editor now match IntelliJ IDEA's
  debugger as closely as practical:
  - **New look** — an icon-only grouped toolbar (resume / pause / stop / restart | step over / into /
    out / run to cursor, with full titles in tooltips), a **thread selector** dropdown over the call
    stack, rich frame cells (`method:line, File`), and **type-colored variable values** (strings green,
    numbers blue, booleans amber, null muted) with a muted type suffix.
  - **Pause** (`C-c C-d p`) suspends a running program; **Run to Cursor** (`C-c C-d u`) resumes and
    stops at the caret line via a temporary breakpoint that is cleaned up automatically.
  - **Watches** — a "Watches" node at the top of the variables tree with a "+ Add watch…" row;
    add/edit/remove via the context menu or double-click. Watches re-evaluate on every stop and frame
    selection, expand when the result is structured, and persist across restarts (per project).
  - **Set Value** — change a variable's value mid-session from the context menu, F2, or double-click;
    the tree updates in place and watches re-evaluate.
  - **Jump to Line** (`C-c C-d j`) — move the execution pointer to the caret line *without executing*
    the code in between (like the IntelliJ "Jump to Line" plugin). Works with Python (debugpy); the
    Java and JS adapters don't support it yet and say so.
  - **Inline values** — while suspended, grey italic `name: value` annotations appear at the end of
    each visible line that mentions a variable of the current frame, and vanish on resume.
  - **Hover values** — hovering a variable in the editor while suspended shows its value in a tooltip.

- **Multiple cursors & column/box selection** — VS Code–style multi-caret editing, powered by a personal
  RichTextFX fork. **Alt+drag** makes a vertical column/box selection; **Cmd/Ctrl+click** adds a caret at
  another spot; **Cmd/Ctrl+D** selects the next occurrence of the current selection; **Esc** collapses back
  to a single caret. While multiple carets exist, typing, Enter/Tab, Backspace/Delete, paste (one line per
  caret), and arrow/Home/End movement apply to every caret as a single undoable step. On by default; toggle
  under **Settings → Editor → "Enable multiple cursors & column selection"** (or the `view.toggleMultiCaret`
  command). The palette also lists *Add Caret at Next Occurrence / Above / Below* and *Collapse to Single
  Caret* (unbound by default — bind them in your keymap if you like). With a single caret everything behaves
  exactly as before. *(Note: Emacs movement chords like `C-f`/`C-n` act on the primary caret only — use the
  arrow keys to move all carets together.)*

- **Java debugging (DAP)** — a full debugger for Java, complementing the existing Java LSP support, off by
  default (Settings → **Debugging** → "Enable Java debugging"). It layers on the running **jdtls** language
  server plus the Microsoft **java-debug** plugin jar (not bundled — auto-detected from a VS Code Java
  extension / nvim mason install, or set its path in Settings) and speaks the Debug Adapter Protocol over a
  socket. Features:
  - **Breakpoints** in a clickable leftmost gutter strip (red dot), persisted per project in
    `breakpoints.json` and re-anchored to their line's content across edits; **conditional breakpoints** and
    **logpoints** via *Edit Breakpoint* (`C-c C-d e`).
  - **Launch** a standalone Java file (incl. JEP 512 compact source) or a project main class (a picker
    appears when several are found), or **attach** to a running JVM (`host:port`).
  - **Stepping** (continue / over / into / out), a **current-execution-line** highlight that follows
    execution, and a **Debug tool window** (`M-g d`) with the call stack, a lazily-expanding variables tree,
    an output **console** with an evaluate REPL, and **exception breakpoints**.
  - Commands `debug.start` (`C-c C-d d`), `debug.continue`/`stepOver`/`stepInto`/`stepOut`
    (`C-c C-d c`/`n`/`i`/`o`), `debug.stop` (`C-c C-d k`), `debug.attach` (`C-c C-d a`),
    `debug.toggleBreakpoint` (`C-c C-b`), `debug.restart`, `debug.toggleExceptionBreakpoints`, and a
    status-bar "Debug" segment. Requires the Java LSP server enabled + jdtls and the plugin detected;
    everything no-ops cleanly otherwise.

- **Python & JavaScript debugging (DAP)** — the debugger now also debugs **Python** (via **debugpy** over
  stdio) and **JavaScript / Node** (via **vscode-js-debug** over a socket), reusing the same breakpoints,
  Debug tool window, stepping, variables, console, and execution-line highlight. Each is enabled
  per-language under Settings → **Debugging** (default on, only effective when the master debugging toggle is
  on). Like the Java adapter, the runtimes are **not bundled** — install them with the new
  `scripts/install-debugpy.sh` and `scripts/install-js-debug.sh` (auto-detected from
  `~/.editora/plugins/dap/{python,javascript}`), or point Settings at an existing install. The breakpoint
  gutter now appears only for debuggable files (Java / Python / JavaScript). JavaScript is Node-only for now
  (no browser); TypeScript and remote attach for Python/JS are not yet supported.

- **Debug Log viewer** — a new in-app window that shows the application log (java.util.logging output +
  uncaught exceptions), so you can see errors/warnings in a packaged build (DMG/MSI/DEB) where stderr
  isn't visible. Open it via the **Debug Log** command in the palette or **Settings → Advanced → Show
  Debug Log…**; it has Refresh / Copy / Clear / Export. The same log is also mirrored to
  `editora-session.log` in your config folder, so it survives a crash and can be attached to a bug report.

- **Icons on every right-click menu item** — all context menus (editor surface + Markdown preview, tab
  strip, Project / Git / Bookmarks / Notes / Message-log panels) now show a leading Material-style glyph
  next to each item, matching the toolbar and tool-window icons. Editor-package menus use a new
  `MenuIcons` glyph factory (so the `editor` package stays independent of `ui`); colors track the theme
  via the existing `.toolbar-icon` style, so there's no new CSS.

- **Markdown preview right-click menu** — the preview pane now has a context menu with **Select All**,
  **Copy** (both copy the preview's rendered plain text — markup stripped, via commonmark
  `TextContentRenderer`), **Export to PDF**, and **Print**. Copy puts the visible text on the clipboard.

- **Markdown selection format bar** — selecting text in a Markdown buffer shows a floating, IntelliJ-style
  bar above the selection with **Bold / Italic / Strikethrough / Inline code / Link / Bulleted list**
  buttons and a **Normal / H1–H6** paragraph-style dropdown. Each action toggles the wrapping (a second
  press removes it). It never steals typing focus, repositions on scroll, and hides on Escape / empty
  selection. Toggle it in Settings → Editor → Markdown ("Show format bar on text selection", default on)
  or the `markdown.toggleFormatBar` command.
- **Markdown smart editing** — the same actions are available as commands, `C-c`-prefixed keybindings
  (`C-c C-s b/i/s/c` bold/italic/strike/code, `C-c C-a l` link, `C-c C-h p`/`C-c C-h d` heading
  promote/demote, `C-c C-o` open link, `C-c C-s t` reformat table), and a right-click **Format** group.
  Plus: **list/blockquote continuation** on Enter (`- `, `* `, `1. ` (auto-incrementing), `- [ ] `,
  `> ` — Enter on an empty item ends the list), **link helpers** (wrap a selection as a link using a
  clipboard URL when present; Ctrl/Cmd-click or `markdown.openLink` opens the link under the caret),
  and **GFM table reflow** (`markdown.reflowTable` pads/aligns a table's pipes honoring `:--`/`:-:`/`--:`).

### Changed

- **Keyboard popups are now in-scene overlays** — the command palette, the "Jump to…" pickers (recent
  files, structure, open files, tool windows, bookmarks, notes, snippets, projects, LSP references,
  spell/app/editor-theme), the file/folder finder, the IntelliJ-style Switcher, the Git **branch
  dropdown**, and the status **message-log** popup no longer use separate `Popup` windows. They render as
  a card inside the main window over a dim, click-to-dismiss backdrop (a shared `OverlayHost`). This
  fixes a Windows bug where a `Popup` didn't reliably take OS keyboard focus — opening the palette (or a
  picker) could leave the keyboard dead until restart. Anchored popups (branch dropdown, message log)
  still "drop" from their status-bar segment; clicking the segment again closes them cleanly.
- **Input dialogs are now in-scene too** — the text-input dialogs (Personal Note editor, file/bookmark
  **rename**, bookmark **note**, Git **clone**, **new branch**, **go to line**) are no longer separate
  native windows; they show as a form card in the main window over the same backdrop (a new shared
  `OverlayInput` helper). `Esc`/`C-g` cancel; `Enter` accepts (`Ctrl/Cmd+Enter` for the multi-line note
  editor). This continues the Windows keyboard-focus fix to every text prompt. (Simple yes/no
  confirmations and choice pickers — overwrite, delete, set language/tab-size/line-endings — stay native.)
- **Status bar polish** — the Git segment now **toggles** its branch dropdown (click to open, click
  again to close). The status **message-log popup** is 20% wider and gains a **Copy** button (copies the
  selected messages, or all of them when none are selected); its per-row right-click menu was removed in
  favor of the more discoverable button (⌘/Ctrl+C still works too).
- **Command palette polish** — removed the separator line above the footer legend, and the legend now
  lists **`C-g`** alongside **esc** as a way to close the palette.

- **`C-a` (beginning of line) is now "smart home"** — the first press moves to the beginning of the
  line's *text* (the first non-whitespace character); a second press toggles to the true line start
  (column 0). Shift-extends selection as before. Applies in the editor for every file type.

### Fixed

- **LSP (and DAP) failed to initialize in the packaged native build** — in the modular `jlink` runtime,
  `lsp4j` is a real module that didn't open its packages to `gson`, so its JSON-RPC `initialize` threw
  `InaccessibleObjectException` the moment a language server (or debugger) started. The `lsp4j` modules
  are now built as open modules. Only affected the installers / app image (not `mvn javafx:run`).

### Added

- **LSP support for C/C++, C#, HTML, CSS, Kotlin, Lua, Dockerfile, SQL, Terraform, and TOML** — ten more
  language servers (twenty-one total): **clangd** (one server for both C and C++), **csharp-ls** (C#),
  **vscode-html-language-server**, **vscode-css-language-server**, **kotlin-language-server**,
  **lua-language-server**, **docker-langserver**, **sqls** (SQL), **terraform-ls**, and **taplo**
  (TOML). Each has its own enable toggle + command field in Settings → LSP (auto-detected on PATH, never
  bundled, off by default with the rest of LSP). Lua, Dockerfile, Terraform, and TOML had no syntax
  support, so each gained a bundled TextMate grammar (from the shiki tm-grammars collection) — they now
  highlight, fold, and comment-toggle. `Dockerfile` (and `Containerfile`) is recognized by filename, not
  just extension. TOML moved off the borrowed INI grammar onto its own. Settings schema 11→12→13
  (additive-identity migrations).
- **Lua auto-indent** — Lua now has a dedicated indent style (`do`/`then`/`function(…)`/`repeat`/`{`
  open a block; `end`/`else`/`elseif`/`until` de-indent), so pressing Enter inside Lua blocks indents
  like the other keyword-block languages instead of just inheriting the previous line.

- **LSP support for YAML, Go, Rust, PHP, and Ruby** — five more language servers (eleven total):
  **YAML** via `yaml-language-server`, **Go** via `gopls`, **Rust** via `rust-analyzer`, **PHP** via
  [phpactor](https://github.com/phpactor/phpactor), and **Ruby** via
  [ruby-lsp](https://github.com/Shopify/ruby-lsp). Each has its own enable toggle + command field in
  Settings → LSP (auto-detected on PATH, never bundled, off by default with the rest of LSP). YAML/Go/
  Rust/Ruby already had syntax highlighting + folding; **PHP** gained a bundled TextMate grammar
  (`php.tmLanguage.json`, from VS Code, MIT) so `.php` files now get highlighting + folding +
  comment/indent support alongside the LSP. Settings schema 10→11 (additive-identity migration).

- **LSP support for XML, JSON, and Bash/Shell** — three more language servers alongside Java/TypeScript/
  Python: **XML** via [lemminx](https://github.com/eclipse/lemminx) (`lemminx`; schema-aware completion +
  validation for `.xml`/`.xsd`/`.xsl`/`.svg`/`.fxml`/…), **JSON** via `vscode-json-language-server`
  (schema completion + validation), and **Bash/Shell** via
  [bash-language-server](https://github.com/bash-lsp/bash-language-server) (`bash-language-server start`;
  diagnostics come from `shellcheck` when it's on your PATH). Each has its own enable toggle + command
  field in Settings → LSP (auto-detected on PATH; never bundled). Off by default with the rest of LSP.
  These ride the existing server-centric foundation — XML/JSON/shell already had syntax highlighting +
  folding, so no new grammars. The Settings → LSP page is now data-driven over a server-descriptor list.
- **Gutter Run ▶ for shell scripts** — a green play glyph in the gutter of `.sh`/`.bash`/`.zsh` files
  runs the script with `bash` and streams output into the Run tool window (reusing the Java/Python Run
  plumbing). Shown only when the **Bash LSP server is enabled** (Settings → LSP), gating it separately
  from the Java/Python Run glyphs.
- **LSP support for Python** (Pyright) — diagnostics, completion, go-to-definition/references, and hover
  for `.py`/`.pyw`/`.pyi`. Configure the server command in Settings → LSP with its own enable toggle
  (auto-detected on PATH; `npm i -g pyright`). Off by default with the rest of LSP. (Python already had
  syntax highlighting, so this rides the existing server-centric LSP foundation.)
- **Gutter Run ▶ for Python scripts** — a green play glyph in the gutter (on the `if __name__ ==
  "__main__":` line, else the first line) runs the file with `python3` and streams output into the Run
  tool window, reusing the Java compact-source Run plumbing.
- **Python completion fixes** — the popup now appears for fuzzy-matching servers (Pyright returns
  subsequence matches; the client falls back to those when no literal-prefix match exists). Also wired up
  auto-import support (declare `completionItem.resolveSupport`/`workspace.configuration`, push
  `python.analysis.autoImportCompletions=true`) so accepting a cross-module symbol inserts its `import`
  — auto-import is wired but still being verified.

- **LSP support for TypeScript & JavaScript** (`typescript-language-server`) alongside Java — diagnostics,
  go-to-definition/references, hover, and completion for `.ts`/`.tsx`/`.js`/`.jsx`/`.mjs`/`.cjs`/`.mts`/
  `.cts`. One TypeScript server serves all of them; the LSP registry is now server-centric (one session
  per server + project root). Also bundles TypeScript/TSX TextMate **syntax highlighting** (so JS/TS are
  no longer plain text) and supports **auto-imports** — accepting a completion that needs an import
  inserts the `import` line via `completionItem/resolve`. Configure the server command in Settings → LSP
  (auto-detected on PATH; `npm i -g typescript-language-server typescript`). Off by default with the rest
  of LSP.

### Fixed

- **Settings → Mermaid:** the `mmdc`/`maid` detection status is now colored green (found) / red
  (missing), matching the LSP and Git status labels, instead of plain text.

- **Windows: keyboard froze and the command palette wouldn't open.** Two distinct bugs (found via
  on-device key-event logging):
  - **Keyboard freeze on Alt.** A bare `Alt` or an *unbound* `Alt+<key>` was interpreted by Windows as
    menu/mnemonic activation, putting the window into "menu mode" — which froze all keyboard input
    app-wide (mouse still worked, restart required) and broke the many `M-` chords. Editora now consumes
    the bare Alt and any unbound plain-Alt key on Windows/Linux so menu mode never engages. Bound `M-`
    chords work as before; **AltGr** (Ctrl+Alt) is left alone so international layouts keep composing
    characters; macOS is unchanged.
  - **Command palette (`M-x`) never appeared.** It was a `javafx.stage.Popup`, which on Windows doesn't
    reliably take OS keyboard focus — focus got orphaned and the keyboard went dead. It's now an in-scene
    overlay (centered card + dim backdrop) that takes focus correctly on every platform.

  (Note: if a *specific* chord like `Alt+X` still does nothing on Windows, check for a global hotkey
  manager — e.g. XKeymaps — grabbing it before Editora; that's external to the editor.)

- **Diagnostic hover tooltip flickered while the mouse moved over a mark** — the tooltip was re-shown
  (and re-positioned to the cursor) on every mouse-move event, so hovering a squiggle or a scrollbar
  diagnostic mark looked janky. It's now shown once and left in place until you move to a different
  mark/message. Applies to the editor squiggle hover, the Mermaid-lint hover, and the scrollbar
  diagnostic stripe.

- **LSP error squiggles / Problems never appeared when the file path involved a symlink** — a language
  server reports diagnostics under the file's *real* (canonical) path (e.g. `/private/tmp/…` for a
  `/tmp/…` symlink on macOS, or any symlinked project directory), but the open tab was matched by a
  non-symlink-resolved path, so **every diagnostic was silently dropped**. The match now uses the
  canonical (symlink-resolved) path on both sides.
- **jdtls (and other wrapper-script servers) leaked orphaned processes and could hang on restart** — the
  Homebrew `jdtls` launcher is a wrapper (`jdtls` → python → java); Editora only killed the wrapper on
  shutdown, orphaning the real server JVM, which kept running and held its Eclipse workspace lock so the
  next session for that folder couldn't start (stuck on "Starting…", no completion/diagnostics). Editora
  now tears down the **whole process tree**. *(Orphans left by an earlier version may need a one-time
  manual kill, e.g. `pkill -f org.eclipse.jdt.ls`.)*
- **A chatty language server could deadlock during startup** — its stderr stream was never drained, so
  once the ~64 KB OS pipe buffer filled the server blocked. The server's stderr is now discarded.

- **Language servers installed via a Node version manager (nvm/fnm/asdf) showed "not found" in the
  packaged app** — a Finder-launched `.app` inherits a stripped `PATH` that omits the version manager's
  bin dir (e.g. nvm's `~/.nvm/versions/node/<ver>/bin`), so npm-global servers like
  `typescript-language-server`, `pyright-langserver`, `vscode-json-language-server`,
  `bash-language-server`, and `yaml-language-server` couldn't be located even though they were installed.
  `ProcessRunner` now resolves the user's **login-shell `PATH`** once (running `$SHELL -l -i -c` with a
  timeout) and folds it into the PATH it gives every subprocess, recovering all version-manager and
  profile-set dirs at a stroke. This also helps Git (a Homebrew `git`) and Mermaid (`mmdc`/`maid`).
- **LSP loading bar in the status bar never stopped spinning** — it was only cleared by incoming
  diagnostics or jdtls's vendor-specific status message, so a file with no diagnostics (or any non-jdtls
  server) left it running indefinitely. It now clears as soon as the universal LSP `initialize`
  handshake completes.

- **Intermittent black screen / render glitches in the packaged macOS app after opening many files** —
  JavaFX's GPU texture pool defaults to a 512 MB ceiling; with enough open files (each holding editor +
  overlay + minimap textures, plus cached preview/diagram images) it filled up and the render thread
  threw `NullPointerException`s (null `RTTexture` / mask texture), showing as a black window. Reduced
  retained GPU memory so it no longer scales with the number of open files — background tabs now drop
  their minimap snapshot (regenerated when shown), and the Markdown image + Mermaid diagram caches are
  LRU-bounded — and raised the Prism texture caps (`prism.maxvram`, `prism.maxTextureSize`) in the
  packaged app and dev run as a safety net. (Only reproduced in the jlink/jpackage build, not under
  `mvn javafx:run`.)
- **Editor froze (render thread crashed) when opening a Markdown file straight into Preview/Split
  mode** — the editor's `Canvas` overlays (minimap, whitespace, spell-check, note-highlight, Mermaid
  lint) were sized directly from the editor pane, which is momentarily zero-width while a Markdown
  buffer opens into Preview/Split. A buffered draw on a zero/NaN-sized canvas made JavaFX allocate a
  null GPU texture and the render thread NPE'd in `NGCanvas` — stalling the whole pipeline so the UI
  looked hung. Canvas dimensions are now clamped to a finite, in-range size (new unit-tested
  `CanvasGuards`) and overlays skip painting into a collapsed surface.
- **External tools not found in the installed app** — a GUI-launched app (a macOS `.app` from Finder,
  a Linux `.desktop`) inherits a stripped `PATH` that omits Homebrew / npm / Node locations, so `mmdc`,
  `npx`/`maid` (and a Homebrew-installed `git`) showed up as "not found" even when installed. Editora now
  augments the subprocess `PATH` with the usual install dirs (e.g. `/opt/homebrew/bin`, `/usr/local/bin`)
  and resolves a bare command name to its absolute path before launching. Setting an absolute path in
  Settings still works as an override.
- **App freeze with several Markdown previews open (macOS)** — rendering SVG badges in the Markdown
  preview pulled in AWT/Java2D, whose native macOS pipeline contended with JavaFX for the AppKit run
  loop, an intermittent deadlock that grew more likely the more previews were open. AWT/Java2D now runs
  headless (software rasterization only), eliminating the conflict; SVG badges still render.
- The **Markdown preview table** columns no longer collapse to one character wide (short headers like
  "Phase"/"Depends on" stacking letter-by-letter); columns now size proportionally to their content.
- **Thread leak on tab close** — each open file's two background worker threads (Markdown preview +
  syntax highlighter) are now shut down when its tab closes, instead of lingering for the rest of the
  session.
- The **message log** opened from the status bar now toggles: clicking the status message again closes
  the popup.
- The **Welcome page** now shows a scrollbar (vertical or horizontal) when its content doesn't fit the
  window, and its tab can be dragged to reorder like any other tab.

### Added

- **LSP support (Phase 1: Java)** — language intelligence via the Language Server Protocol, backed by
  **Eclipse JDT LS**. Live **diagnostics** (severity-colored squiggles + hover tooltips + a **Problems**
  tool window, `M-8`), **go-to-definition** (`M-.`), **find references** (`M-?`, results in a picker),
  **hover documentation** (`C-c h`, rendered Markdown), and **LSP-backed completion** merged into the
  existing completion popup. The server is **auto-detected** (`jdtls` on your PATH; a Settings command
  override is available) and **never bundled** — install it (e.g. `brew install jdtls`). Off by default:
  enable it in **Settings → LSP** (or the `view.toggleLsp` command). The workspace root is the active
  Editora project, else the nearest build file (`pom.xml`/`build.gradle`/…), else the file's folder.
  All server I/O runs off the UI thread; document sync is debounced. (Built on LSP4J; more languages,
  formatting, and refactoring are planned.)

- **Toolbar refinements** — the top icon bar's edit buttons are now **state-aware**: Save is disabled
  unless the file has unsaved changes; Undo/Redo only when there's something to undo/redo; Cut/Copy only
  with a selection (Cut also needs an editable buffer); Paste only when the buffer is editable and the
  clipboard holds text (all disabled on the Welcome tab). A new **Find in Files** icon sits beside the
  in-file Find icon. The toolbar can be **hidden** (Settings → Application "Show toolbar", or the
  `view.toggleToolbar` palette command); when hidden, a small floating **Tool** button (top-right, like
  the Zen "Z") brings it back. The decorative Switcher keybinding hint was removed from the bar.

- **Search** — a big upgrade across three areas. **In the editor**, the find bar (`C-s`) now searches
  **incrementally** as you type, **highlights every match** (the current one accented), shows a
  "{n} of {total}" count, and adds a **whole-word** toggle (regex + case already existed). **Find in
  Files** (`C-S-f`, or the Search Results tool window on `M-6`) searches the project and open files
  off-thread and lists matches grouped by file — Enter/double-click jumps to one — with **replace
  across files** (open buffers edited undoably, closed files rewritten on disk, after a confirmation).
  **AceJump** (`M-g j`, avy-style): type a character, then a 1–2 char label drawn over any on-screen
  occurrence, to jump the caret there.

- **Printing** — two new commands show a **print preview** first, then send to the printer.
  **`File: Print…`** (`editor.print`) previews/prints the active buffer's source code — syntax-highlighted
  with an optional line-number gutter, paginated by whole lines, in a clean light theme. **`File: Print
  Preview…`** (`preview.print`) handles the rendered **Markdown** preview (block-aware pagination — a
  paragraph, table, or image is never split across a page boundary) or a standalone **Mermaid** `.mmd`
  diagram scaled to the page. A modal preview window lets you page through the exact output (scaled to
  fit, with page navigation); **Print…** then opens the native dialog (printer, copies, paper) to send,
  **Close** cancels. Reuses the *Include line numbers* / *Syntax highlighting* settings (the Settings →
  Editor section is now **Export & Print**); preparation runs off the UI thread.

- **Export to PDF** — two new commands. **`File: Export to PDF`** (`editor.exportPdf`) writes the active
  buffer's source as a real, **searchable** PDF: embedded JetBrains Mono, syntax highlighting, and a
  right-aligned line-number gutter (both toggleable), in a clean light theme regardless of the app/editor
  theme. **`File: Export Preview to PDF`** (`preview.exportPdf`) exports a previewable buffer: a Markdown
  document becomes **native vector text** (headings, lists, tables, block quotes, code blocks, links and
  images — including embedded Mermaid diagrams and SVG badges as images), and a standalone Mermaid `.mmd`
  file exports via mmdc's native vector PDF. New **Settings → Editor → PDF Export**: *Include line
  numbers*, *Syntax highlighting*, and a *Page size* selector (Letter / A4). PDF generation runs entirely
  off the UI thread. Powered by Apache PDFBox.

- **Mermaid diagram support** (Settings → Mermaid, off by default) — renders Mermaid diagrams in the
  preview: standalone `.mmd` files (syntax-highlighted, with the Editor/Split/Preview toggle) and
  ` ```mermaid ` fenced blocks inside Markdown. Uses the external **mmdc** (mermaid-cli) to render and
  to **export** a diagram to SVG/PNG/PDF (`Mermaid: Export Diagram` command), and **maid**
  (probelabs/maid) to validate — a failed diagram shows the error with its line/column in the preview.
  Configure the `mmdc`/`maid` commands in Settings (blank = `mmdc` on `PATH` and `npx -y @probelabs/maid`).
  Rendered diagrams are cached by content so editing stays responsive. `.mmd` files also get **live maid
  linting** (red squiggly underlines + hover tooltip, debounced while typing) and **autocomplete** of
  Mermaid keywords + diagram snippets — both with their own toggles, automatically disabled when Mermaid
  is off or the tools aren't detected.

- **Export configuration** — a new "Export Configuration…" button (Settings → Advanced) and the
  `Configuration: Export to Zip` command zip your active config folder (`~/.editora`, `~/.editora-dev`,
  or a `--config-dir` override) into a timestamped `.zip` in your home directory, for quick backups.

- **Show/hide the tool stripe** — a new "Show tool stripe" setting (Settings → Tool Windows) and the
  `View: Toggle Tool Stripe` command (command palette) hide the side icon bars. This is UI only — tool
  windows still open via their keyboard shortcuts (e.g. `M-1`) and the command palette. Hiding the
  stripe takes precedence over each tool window's individual visibility toggle.

- **Build commit in dev mode** — when running with `--dev`, the About dialog and the Welcome page now
  show the short git commit the app is running from, so it's easy to tell which build you're testing.
  Not shown in normal (non-dev) runs.

- **Independent Markdown preview theme** — the rendered preview can now be light or dark independently
  of the app/editor theme, so you can read docs in light while coding in a dark theme (or vice versa).
  Toggle it with the floating sun/moon button next to the preview's −/+ zoom controls, or the
  **Markdown: Toggle Preview Light/Dark** command. The choice is remembered; until you first toggle it,
  the preview follows the app theme as before.

### Changed

- **Unsaved-file marker everywhere** — the dot + amber-italic style the tabs use for a file with
  unsaved changes now also appears in the **Switcher**, the **Open Files** picker, and the **Project**
  tool window tree, so every list of open files reads consistently. The Project tool window also
  refreshes when files/folders change outside Editora (on window focus), keeping its expanded folders
  and selection.

- **Markdown preview readability** — the rendered preview now lays its content out as a centered,
  width-capped column (GitHub-style) with more generous margins and vertical spacing (line height,
  space between blocks and list items, heading separation), instead of stretching edge-to-edge across
  a wide window.

### Added

- **SVG images in the Markdown preview** — the preview now renders SVG images, which JavaFX can't
  decode on its own. This makes **shields.io / GitHub status badges** (served as SVG) show up in the
  preview as they do on GitHub. Images load off the UI thread and are cached. (Uses the lightweight
  JSVG library.)

- **Keyboard scrolling in the Markdown preview** — Space / PageDown (page down), Backspace / PageUp
  (page up), and the Emacs `C-v` / `M-v` now scroll the rendered preview.

- **Status-bar message log** — click the status message on the left of the status bar to see every
  message from the current session in a scrollable popup, newest first, each with an `HH:mm:ss` time
  indicator. Messages are selectable (multi-select) and can be copied to the clipboard (Cmd/Ctrl+C or a
  right-click **Copy**). The log is in-memory only (not persisted), capped at the most recent 200
  messages, with a **Clear** action. Also available as the **Message Log** command (`view.messageLog`).

- **Welcome page** — instead of opening an empty "Untitled" buffer, Editora now opens a VSCode-style
  Welcome page in its own **tab** at startup when there's no session to restore. It offers **Start**
  actions — New File, Open File, then Open Folder as a Project and Clone Git Repository (each shown only
  when that feature is enabled), and the Command Palette last — each with its configured keybinding shown
  alongside, plus a **Recent** files list and a footer with the app version, a link to the project home
  page, and the license. Being a real tab, it activates, switches, and closes like any other tab; the
  **Welcome Page** command (`view.welcome`) opens it (or re-selects it if already open) on demand even
  with files open. The **About** dialog now also shows the
  home-page link, copyright, and license. New CLI flag
  **`--new-file[=name]`** bypasses it by opening a fresh buffer instead: `--new-file=notes.md` opens an
  unsaved buffer titled `notes.md` (highlighted by its extension; first save prompts for a location), and
  bare `--new-file` opens a blank untitled buffer.

- **Personal Notes** — private annotations attached to a file *without modifying the file*, for
  read-only / generated / shared code where you want knowledge stored separately. Three **scopes**
  (word / line / range), an optional **body**, **tags**, and a **status** (active / resolved /
  orphaned). Notes follow their content as you edit (live offset tracking) and re-anchor on reopen by
  the captured selection + surrounding context; a note whose anchor can no longer be found is marked
  **orphaned** (kept and recoverable, never silently lost). File identity is **content-hash + path**, so
  a note re-attaches even when the file is renamed/moved outside the app. Indicators: a gutter glyph, a
  soft in-editor highlight behind the anchored span, and a hover tooltip (the body is **rendered as
  Markdown** in the editor's font) — the gutter/highlight is toggleable via *Settings → Editor → Show
  note indicators*. A **Personal Notes** tool window
  (`M-5`) groups notes per file with a filter, edit/resolve/delete, and "delete all in file"; commands
  cover add (`C-c n`), next/previous, cross-file **Jump to Note** (`M-g n`), **Search Notes** (a picker
  that matches the full note body, tags, and file path),
  **delete** (the note on the caret line — also available from the panel and as a Delete button in the
  note editor), and **export** to JSON (resolve/reopen lives in the tool window's context menu). Note bodies are edited in a **multi-line** text box
  (Enter inserts a newline; Ctrl/Cmd+Enter saves). Stored per project in a single versioned `notes.json` (reuses the bookmark
  per-project bucket model + the config migration framework). Bookmarks are unaffected (separate gutter
  slot, no layout shift). The whole feature is **off by default** — enable it via *Settings → Application
  → Enable Personal Notes* (when off, the tool window, commands, and editor menu items are hidden).

- **Config schema versioning + migrations** — every structured config file (`settings.toml`,
  `workspace-state.json`, `projects/<id>.json`, `projects.json`, `bookmarks.json`, `recent-files.json`)
  now carries a per-file integer `schemaVersion` (baseline 1), and reads go through a small migration
  framework (`config/migration/`) so future releases can evolve a file's format with one registered
  `v→v+1` step. Existing unversioned files load unchanged and get stamped on the next save (no data
  loss); `recent-files.json` (previously a bare array) is migrated to a versioned object. If a file is
  **newer** than the running build (e.g. after a downgrade), it's backed up to `<name>.v<n>.bak` and
  defaults are loaded rather than overwriting newer data.

- **Autocomplete** — appears as you type (debounced) and on demand (`C-M-i` / `M-/`, command
  "Edit: Trigger Autocomplete"). **Code** files show a caret-anchored popup of **snippet** completions
  (accepting one expands the snippet with its tab stops); **Enter/Tab** accept, ↑/↓ navigate, Esc
  dismisses. **Prose** files (plain text / Markdown) instead show **inline "ghost text"** — a single
  greyed continuation drawn after the caret, completed from the bundled spell dictionary plus your
  personal dictionary; press **Tab** to accept, Esc (or just keep typing / move the caret) to dismiss.
  In the code popup, ↑/↓ and **C-n/C-p** move the selection. Settings → Editor has a master "Enable
  autocomplete" toggle plus per-source checkboxes — **Words (prose)** and **Snippets (code)** — all on
  by default (more sources can be added later), and there are command-palette toggles for each
  ("View: Toggle Autocomplete", "… Words (Prose)", "… Snippets"). The dictionary word list is parsed
  off-thread and cached; prefix lookup is a binary-search range, so typing/scrolling stay unaffected.

- **Multi-language interface (i18n)** — Editora's UI can now run in **English, Italian, Spanish,
  French, Portuguese, or German**. Command-palette titles, toolbar tooltips, tool-window titles, and
  the full Settings window are translated; pick a language under **Settings → Appearance → Language**
  (default *Automatic* follows the system language, falling back to English). A language change
  applies on the next restart. Strings live in a `messages[_<lang>].properties` catalog loaded by the
  new `com.editora.i18n.Messages` helper; a key-parity unit test keeps every translation complete.
  **Essentially the entire interface is translated** — command palette, toolbar tooltips, tool-window
  titles and panel contents (Project, Structure, Bookmarks, File Information, Commit/Git), the full
  Settings window, status-bar segments, echo-area status messages, every dialog (titles, bodies and
  buttons), context menus, and popups (branch dropdown, command palette, file finder, switcher). The
  chosen language also drives `Locale.setDefault`, so JavaFX's own OK/Cancel buttons match.

### Changed

- **Settings window redesigned** — a scalable left **category sidebar** (Appearance, Editor, Tool
  Windows, Spell Check, Application, Advanced, plus "coming soon" placeholders for Keymaps/Plugins/Git/AI)
  replaces the single stacked list, with a **search** box that filters settings + jumps to matches, a
  **live preview** on Appearance (sample code that recolors/re-fonts as you change theme/font), and a
  **Reset to Defaults** on the Advanced page. Changes still apply live. Tool-window placement moved to
  its own page; the new **Tab size** control lives on the Editor page. **About** moved out of Settings
  (it's on the toolbar + the "About Editora" command); the settings-file link now lives on the Advanced
  page.

- **Markdown preview now renders closer to GitHub** — task lists (`- [x]`/`- [ ]`) show real checkboxes
  instead of bullets, inline `code` gets a rounded gray pill (instead of blue text), `#`/`##` headings
  get an underline rule, and links are no longer permanently underlined. Standalone images now render as
  block images (with the alt text / title as a tooltip), in addition to inline images; relative image
  paths resolve against the file's folder, and `http(s)`/`file`/`data:` URLs are supported.

### Fixed

- **In-app file rename now carries bookmarks and personal notes to the new path** — renaming an open
  file from the tab/right-click menu already moved its folds and recent-files entry; it now also re-keys
  that file's bookmarks and notes, so they no longer get stranded under the old path.

### Added

- **Emacs transpose commands** — transpose characters (`C-t`), words (`M-t`), and lines (`C-x C-t`),
  also in the command palette ("Edit: Transpose …"). At end of line, `C-t` swaps the two preceding
  characters (typo fix); `C-x C-t` swaps the current line with the one above.

- **"Enable Git" setting (Settings → Git), off by default** — Git integration is now opt-in. When off,
  the status-bar VCS segment is disabled, the Commit tool window is hidden, gutter change markers are
  cleared, and all Git commands/keybindings are inactive (and hidden from the palette). Turn it on in
  Settings → Git to use branch/status, change bars, commit, and sync. The Git page detects the `git`
  command: it shows the installed version when found, or "git command not found" and disables the
  checkbox when git isn't on `PATH`.

- **Save / Save As… in the tab right-click menu** — save the right-clicked tab directly (Save is greyed
  out for an unchanged, already-saved file).

- **Git support (native CLI)** — Editora now talks to your installed `git`. The status bar shows the
  current branch with ahead/behind counts (`⎇ main ↑2 ↓1`, click for the branch dropdown — an
  IntelliJ-style searchable list of actions + Local/Remote branches, each local branch showing its
  upstream and incoming/outgoing (`↓`/`↑`) commit counts); the editor gutter
  draws change bars relative to `HEAD` (green added / blue modified / red deleted); and a **Commit** tool
  window (`M-4`) groups Staged / Changes / Untracked files with stage, unstage, discard, **Stage All**,
  a **Push** button, and a commit message box (Ctrl/Cmd+Enter to commit). **Clone** a remote repo with "Git: Clone
  Repository…" (or the button in the empty Commit tool window) — one dialog asks for the repo URL and
  the destination directory (with a Browse button; the directory auto-fills to `<home>/<repo>` from the
  URL), clones, and opens a file from it (its README, if any) so Git lights up; cloning is independent
  of projects (you don't need project support to clone or use Git). Palette commands: "Git: Clone Repository…", "Git: Commit…" (`C-x g`),
  "Git: Stage Current File", "Git: Switch Branch…", "Git: New Branch…", "Git: Fetch", "Git: Pull",
  "Git: Push", and "Git: Refresh Status". Pushing a brand-new branch automatically sets its upstream
  (`--set-upstream origin <branch>`), and Git command failures are shown in a readable dialog rather
  than the one-line status bar. The **Commit** tool window is shown only when the active file is under
  Git; otherwise it's hidden. The status-bar VCS segment is always present: outside a repo it reads
  **No VCS**, and clicking it offers **Clone Git repository…**. All Git calls run off the UI thread;
  everything degrades silently when a file isn't in a repository or `git` isn't installed. (No new dependencies — Editora
  shells out to your real git, so credential helpers, SSH, and signing all work.)

- **Theme commands in the palette** — "Theme: Set App Theme…" and "Theme: Set Editor Theme…" open a
  fuzzy picker. The app-theme picker switches the chrome theme *and* the editor theme to match; the
  editor-theme picker changes only the editor colors (and pins it so it won't follow the app theme).

- **Spell checking** — misspelled words get a red wavy underline; right-click for suggestions (click one
  to replace), **Add to Dictionary**, or **Ignore**. In source files only comments and string literals
  are checked (identifiers aren't flagged); plaintext and Markdown are checked in full. Toggle it with
  "View: Toggle Spell Check" (or the **Settings window**, where you can also choose the default
  dictionary language); pick the dictionary per file with "Spell Check: Set Language…" (ships
  **English (en_US, en_GB)**, **Spanish (es)**, and **French (fr)**; your added words live in
  `dictionary.txt`). Powered by Apache Lucene's pure-Java Hunspell engine.
- **Detect external file changes** — when the file in the active tab is modified by another program,
  Editora notices (on window focus or when you switch to that tab) and offers to reload it, or keep your
  version (also when you have unsaved edits). The prompt only appears for the tab you're looking at.
  Deleted files are left untouched. Editora's own saves never trigger the prompt.
- **Smart backspace** — pressing Backspace while the caret is in a line's leading indentation deletes
  the whole indent in one press instead of one space at a time. On a blank, auto-indented line a single
  Backspace jumps back to the end of the previous line (undoing the Enter); on an indented line that has
  content, it clears the indent. Outside leading whitespace, Backspace behaves normally.

### Changed

- **Bookmarks now live in their own `bookmarks.json`** (in the config directory) instead of inside each
  session's `workspace-state.json`, so they're all in one easy-to-find file. They remain **scoped per
  project** — switching projects shows only that project's bookmarks (the global session has its own),
  and deleting a project deletes its bookmarks. Existing bookmarks are migrated automatically on first
  run (moved into the right per-project bucket; the old session files are cleaned up). This also fixes
  the cross-file "Jump to Bookmark" picker (`M-g b`) showing an empty list when a project was active.
- **Reorder bookmarks in the Bookmarks tool window** — move a bookmark within its file, or a whole file
  group, with **Alt+Up/Down**, the right-click **Move Up/Down** menu, or **drag-and-drop** (with the
  same drag visuals as the editor tab strip: a translucent ghost, the dragged row dimmed, and an accent
  insertion line on the drop target). The order you set is exactly the order the `M-g b` jump picker
  uses, and it persists (and survives edits to the file).

### Fixed

- The About dialog now shows the **live** config/settings path, so `--dev` (`~/.editora-dev/`) and
  `--config-dir` are reflected (it previously always showed the default `~/.editora/settings.toml`).

### Added

- **Zen-mode exit button** — a small floating "Z" button appears at the top-right of the window while
  in Zen mode (same look as the Markdown preview controls); click it (tooltip: "Exit Zen mode") to leave
  Zen. It's hidden whenever Zen is off, and on Markdown files it sits just below the preview controls so
  the two don't overlap.
- **Navigation key hints in the pickers** — the Command Palette, the "Jump to…" pickers (recent files,
  bookmarks, structure, tool windows, snippets), and the file finder now show a footer legend of their
  relevant keys (move / select / cancel, plus Tab-complete in the file finder), matching the Switcher.
- **Focused tool window is highlighted** — the panel that currently holds keyboard focus (Project,
  Structure, Bookmarks, File Information) gets an accent-tinted header, so it's clear where you are when
  moving between panels and the editor.
- **`--dev` command-line flag** — runs Editora against a separate `~/.editora-dev/` config directory so
  a development instance can run alongside your everyday editor without sharing settings or session
  state. Config-dir precedence is now `--config-dir` > `EDITORA_CONFIG_DIR` > `--dev` > `~/.editora/`.
  A light-red "dev mode" badge appears in the toolbar (just left of the About icon) so the dev instance
  is visually distinct.
- **Comment / uncomment** (`M-;` or the palette: "Edit: Toggle Comment"). A single line toggles a line
  comment; a multi-line selection toggles a block/region comment — using whichever the language has
  (e.g. `//` and `/* */` for Java/C-likes, `#` for Python/shell/YAML, `<!-- -->` for XML/HTML/Markdown,
  `/* */` for CSS, `--` for SQL). Falls back gracefully (block-only languages always wrap; line-only
  languages comment each line), preserves indentation, and is a no-op for languages without comments.

- **Auto-close brackets and quotes** — typing `(`, `[`, `{`, `"`, `'`, or `` ` `` inserts the matching
  closer and keeps the caret between; typing the closer (or a quote) when it's already next to the
  caret types over it; typing an opener/quote with a selection wraps the selection; and Backspace
  inside an empty pair deletes both halves. Quotes are not auto-paired next to a word character (so
  apostrophes in `don't` are left alone).
- **Highlight matching brackets** — when the caret is next to a `()`, `[]`, or `{}`, both it and its
  match are highlighted.

- **Auto / smart indentation** for all 21 languages. Enter keeps the current line's indentation; a
  block-opener adds a level (braces `{ ( [`; `:` in Python/YAML; `do`/`then`/`in` in shell;
  `def`/`class`/`do`/… in Ruby; an open tag in XML/HTML); pressing Enter between a matching pair
  (`{}`/`()`/`[]` or `<a>|</a>`) opens an indented stanza with the closer dropped below; and typing a
  closer (a `)]}` bracket alone on the line, or a keyword like `end`/`fi`/`done`) re-aligns the line to
  its opener. The indent unit (tab vs spaces) is inferred from the file.

- **Ctrl + mouse wheel zooms the Markdown preview** while in Preview mode (scroll up/down to zoom
  in/out, driving the preview's `−`/`+`); the editor text zoom is left untouched there. In Editor and
  Split modes Ctrl+wheel still zooms the editor text.

- **Snippets** (VS Code / TextMate-style) — expand templates with interactive tab stops. Type a
  prefix and press **Tab** to expand (Tab still indents when nothing matches), or pick from the
  **"Snippet: Insert…"** fuzzy list (`C-c i`). After expanding, **Tab / Shift-Tab** cycle the fields,
  placeholders are pre-selected to overtype, mirrored fields update live, and `$0` is the final caret.
  Bodies use the standard syntax — `$1`, `${1:default}`, mirrors, `${1|a,b|}` choices (a dropdown
  appears on the field), variables (`$TM_FILENAME`, `$TM_DIRECTORY`, `$CLIPBOARD`, `$CURRENT_YEAR`,
  the selection, …) and `\$` escapes. Snippets ship for **all 21 highlighted languages** — most from
  the MIT-licensed [friendly-snippets](https://github.com/rafamadriz/friendly-snippets) collection
  (attributed in `NOTICE`), the rest written for Editora. Add your own in
  `~/.editora/snippets/<language>.json` (or `global.json`) — "Snippet: Edit User Snippets…" opens the
  file and "Snippet: Reload Snippets" picks up changes. User snippets override bundled ones.

- **Read-only / View mode** — toggle a buffer read-only with `C-x C-q` (or the palette: "View: Toggle
  Read-Only"), so it can't be edited by accident. Typing and the editor's own edit commands are blocked
  (with a status-bar hint), while highlighting, minimap, folding, scrolling, and copy keep working. A
  file opens read-only automatically when it isn't writable on disk, a status-bar toggle flips it
  either way ("Read-Only" ⇄ "Editable") and the tab title is muted, and the per-file state is
  remembered across restarts. A Word-style "View Mode" banner docks above the editor with the
  navigation hint and an **Enable Editing** button (shown only when the file is writable). While
  read-only, **Space pages down and Backspace pages up** (pager-style, like `less`/man).
- **More command-line options** — `--version`/`-V` and `--help`/`-h` (print and exit, no window);
  positional `FILE`, `FILE:LINE`, and `FILE:LINE:COLUMN` to open a file (and jump); `--project[=]<dir>`
  to open a folder as a project (only when Projects are enabled, else ignored); and `--zen` to start in
  Zen mode. File/project args are additive on top of the restored session. Cross-platform.
- **Custom config folder** — point Editora's config directory somewhere other than the default
  `~/.editora/` with the `--config-dir <path>` command-line argument (or `--config-dir=<path>`) or the
  `EDITORA_CONFIG_DIR` environment variable. Precedence: `--config-dir` > `EDITORA_CONFIG_DIR` >
  `~/.editora/`. Works on macOS, Linux, and Windows.
- **Text zoom** — quickly scale the editor text up/down independent of the configured font size (the
  set size is 100%). Use the status-bar `− 100% +` control, `C-=` / `C--` (and `Ctrl`+Plus) / `C-0` to
  reset, **Ctrl + mouse-wheel**, or the palette ("View: Zoom In / Out / Reset Text"). The zoom level
  persists across restarts and is separate from the font-size setting (it's not shown in Settings).
- **Markdown preview** — an IntelliJ-style 3-mode view for Markdown files via a small floating control
  at the top-right of the editor: **Editor**, **Editor + Preview** (side-by-side), and **Preview**. The
  preview is rendered natively (no WebView) from CommonMark + GitHub-flavored Markdown (tables, task
  lists, strikethrough, autolinks), updates live as you type (debounced, off-thread), follows the
  active theme, and remembers its mode per file. The preview has −/+ buttons (top-left) to zoom the
  text in/out. Also available as palette commands ("Markdown: Editor / Editor and Preview / Preview",
  and "Markdown: Zoom In / Out / Reset Preview").
- **Bookmarks** — mark lines and jump back to them, across files. Toggle a bookmark on the caret line
  with `C-c m`, or click the gutter to add one (clicking an existing bookmark asks before removing it);
  a light-orange bookmark glyph appears in the line-number gutter. Each bookmark
  can carry an optional note ("Bookmarks: Edit Note…"). The **Bookmarks** tool window (`M-2`) lists every bookmark across all files (open
  or closed), grouped by file, with a filter box; Enter opens the file and jumps to the line, and
  right-click edits the note or deletes. `C-c ]` / `C-c [` cycle through the current file's bookmarks,
  and `M-g b` ("Bookmarks: Jump…") is a fuzzy cross-file picker. Bookmarks persist with the session
  (per project) and track their line as you edit above them.
- The Project tool window's file tree now colors its icons to match the active theme: folders use the
  theme accent and files a muted tone. The colors are driven by two looked-up CSS variables
  (`-project-folder-color`/`-project-file-color`) that default to the GUI theme (AtlantaFX) and are
  overridden per editor theme, so the tree tracks the chosen code theme. Files open with unsaved
  changes are marked in the tree like dirty tabs — a "• " prefix and amber italic — updating live as
  you edit and save.
- Reorder the tool-window stripe icons: drag an icon up/down within its side, or use the new ▲/▼
  buttons next to each row in Settings → "Tool window placement". The order persists in the session
  state (`workspace-state.json`).

- Projects (VSCode single-folder-workspace style), **off by default** — enable via Settings →
  "Enable projects" (when off, the Project tool window, the toolbar open-folder icon + project
  switcher, and the project commands are all hidden). Each project is a root folder plus its own
  saved session (open files with carets/pins, active file, folds, tool-window layout). Open one via
  the toolbar's open-folder icon (native dialog) or "Project: Open Folder…" (`C-x C-p`, a keyboard
  folder picker like `C-x C-f`); "Project: Switch…" (`C-x p`) saves the current session and restores
  another's; "Project: Close" returns to the global session (with confirmation); "Project: Delete…"
  (also a trash button in the Project tool window) removes a project from your list — its folder and
  files on disk are kept, only the project entry and its saved session are removed. The active project
  is shown in the window title and a project switcher combobox in both the toolbar and the Project
  tool window (each with a "No Project" entry that returns to the global session without closing any
  project). The Project tool window shows the project's files as a lazy tree with Emacs-style
  keyboard navigation (C-n/C-p, C-f/C-b, Enter to open), a filter box that runs a bounded
  project-wide filename search (like the Structure filter), and a right-click menu to rename or
  delete files (syncing open tabs). Opening a recent file that belongs to another project switches to
  that project first (restoring its session/tree) before opening the file. Settings stay global.
- Keyboard file finder (Emacs `find-file` style) on `C-x C-f`: a path popup with a live directory
  listing that prefix-autocompletes as you type. `Tab` completes the common prefix, Enter descends
  into a folder or opens a file (a non-existent path opens a new buffer, written on save),
  `C-n`/`C-p` move, `Esc` cancels — no mouse needed. Also the `file.find` palette command. The
  toolbar Open icon (and `file.open`) still uses the native OS dialog.
- Fold/unfold the single region at the caret: `view.fold` (`C-c C-f`) collapses the innermost
  region around the caret, `view.unfold` (`C-c C-u`) expands it, and `view.toggleFold` (`C-c C-t`)
  toggles it — complementing the existing Fold All (`C-c f`) / Unfold All (`C-c u`).
- Keyboard "Jump to…" pickers — command-palette-style fuzzy popups: recent files (`C-x C-r`),
  the active file's structure/symbols (`M-g i`), open files/tabs (`C-x b`), and tool windows
  (`M-g t`). Type to filter, `C-n`/`C-p` or ↑/↓ to navigate, Enter to act (open the file / jump to
  the symbol / switch tabs / open the tool window), `Esc` to cancel. All are also in the command
  palette ("Recent Files: Jump", "Structure: Jump", "Open Files: Jump", "Tool Windows: Jump").
- More Emacs movement keys: `M-m` (back to indentation), `C-l` (recenter the caret line in the
  viewport), `M-{` / `M-}` (backward/forward paragraph), and `M-a` / `M-e` (backward/forward
  sentence) — all registered commands, so they also appear in the palette. Note: on macOS the
  Option dead keys (`Option`+`e`/`i`/`u`/`n`/`` ` ``) are intercepted by the OS for accent
  composition and never reach the app, so `M-e` (forward sentence) can't be triggered by keyboard
  there — use the palette command "Go: Forward Sentence" instead.
- Auto save (VS Code-style), off by default. Choose a mode in Settings: "After delay" saves a file
  once it's been idle for a configurable number of seconds (default 1), or "On focus change" saves when
  you switch editor tabs or the window loses focus. Only dirty, file-backed, writable buffers are
  saved (untitled/read-only ones are skipped); writes happen off the UI thread. Cycle the mode with
  the `file.toggleAutoSave` palette command or `C-c a`.
- File breadcrumb bar (IntelliJ-style) along the bottom, above the status bar: shows the active
  file's path as clickable segments. Click a segment to drop down that folder's contents
  (sub-folders first, then files); pick a folder to drill in (it becomes the trailing crumb and the
  dropdown reopens) or a file to open it. Long paths scroll, anchored to the file. Off by default;
  toggle via the Settings checkbox, the `view.toggleBreadcrumb` palette command, or `C-c p`.
- Runnable fat jar: `mvn -Pfatjar package` builds `target/Editora-<version>.jar`, launchable
  with `java -jar` (bundles JavaFX classes + natives for the build host's platform via a
  non-`Application` `Launcher` main class). The release pipeline builds one per platform and
  attaches them to the GitHub release alongside the native installers.
- Zen mode (distraction-free): one toggle hides the editor view options (80-column ruler,
  current-line highlight, line numbers, minimap, hidden characters), the toolbar, status bar,
  and tab bar, and all open tool windows — leaving just the editor. While in Zen you can still
  switch individual items back on (e.g. line numbers or the status bar); toggling Zen off
  restores your previous configuration exactly. The mode persists across restarts. Via the
  Settings checkbox, the `view.toggleZen` palette command, or `C-c z`.
- Toggle the toolbar, the status bar, and the tab bar — via Settings checkboxes
  ("Show toolbar", "Show status bar", "Show tab bar"), the `view.toggleToolbar`
  (`C-c t`) / `view.toggleStatusBar` (`C-c s`) / `view.toggleTabBar` (`C-c b`)
  palette commands, and persisted in settings.
- Application icon: the Editora logo now appears as the window/dock/taskbar icon, in
  the About dialog, and as the native installer icon (macOS `.icns`, Windows `.ico`,
  Linux `.png`), all generated from `branding/editora-icon.svg`.
- Emacs-style text selection: `C-SPC` sets the mark, then any caret-movement chord
  (`C-f`/`C-b`/`C-n`/`C-p`/`M-f`/`M-b`/`C-a`/`C-e`/`M-<`/`M->`/`C-v`/`M-v`) extends
  the selection from it. `C-x C-x` exchanges point and mark; `C-g` (and a mouse
  click, cut/copy/paste) clears the mark. The region works with `C-w`/`M-w`/`C-y`.
- Bundled monospace fonts (no install needed): JetBrains Mono (the new default),
  Cascadia Code, Fira Code, IBM Plex Mono, and Source Code Pro. They're loaded at
  startup and listed first in the Settings font picker. (Existing configs keep their
  saved font; the new default applies to fresh installs.)
- Editor color themes (syntax tokens + editor surface) chosen in Settings under
  "Editor theme": Primer Light/Dark, Nord Light/Dark, Cupertino Light/Dark, Dracula
  (these match the AtlantaFX themes), plus JetBrains-style Islands Light and Islands
  Dark. Selecting an AtlantaFX app theme switches to the matching editor theme
  automatically, until you pick an editor theme yourself.
- Editor tabs can be reordered by dragging them with the mouse. Pinned tabs stay
  grouped at the front (a drag is clamped to the dragged tab's group).
- Very large files (50 MB or larger) now open read-only with a capped load — at most
  the first 50 MB is read (so a multi-GB log can't exhaust memory) and editing/undo are
  disabled; the status bar notes the truncation.
- Undo history is now bounded (300 entries per view) instead of unlimited, and is
  disabled entirely in large/huge-file mode, capping undo memory.
- Large-file mode: opening a file 5 MB or larger skips syntax highlighting and the
  minimap (regardless of view settings) to stay responsive, and the status bar
  announces this when it happens.
- The main window now remembers its size, position, and maximized state across
  launches (stored in `workspace-state.json`). A saved position that no longer
  lands on a connected screen falls back to the default centered window.
- Release pipeline: pushing a `vX.Y.Z` tag builds native installers for Linux
  (x64/arm64), macOS (x64/arm64), and Windows (x64) on per-platform GitHub runners
  and publishes a GitHub Release via JReleaser (`jreleaser.yml` + `release.yml`).
  Installers are currently unsigned.
- Session restore: on launch, reopens the files that were open at last exit (in tab
  order), reselects the active tab, and restores each file's caret position (scrolled
  into view). Missing files are skipped; an empty session opens a fresh buffer.
- Editor split view: toolbar icons (and `view.splitVertical` / `view.splitHorizontal`
  / `view.unsplit` commands, `C-x 3` / `C-x 2` / `C-x 1`) toggle a second synced view
  of the current file — side by side or stacked. Edits/highlighting stay in sync;
  scroll, caret, and minimap are per-pane. The icons reflect the active split state.
- "Show hidden characters" view option: renders markers for spaces (·), tabs (→),
  and line ends (¶) on a transparent overlay without altering the document.
  Toggle via `view.toggleWhitespace` (`C-c w`) or Settings; off by default.
- The About dialog now shows the build date/time (baked in at build time).
- Editor tab right-click context menu: Close, Close Other Tabs, Close All Tabs,
  Close Unmodified Tabs, Close Tabs to the Left/Right, Copy Path, Pin/Unpin Tab,
  and Rename File…. Pinned tabs are marked with a pin icon, kept grouped at the front, and
  skipped by the bulk-close actions. Each action is also a palette command
  (`buffer.closeOthers`, `buffer.closeAll`, `buffer.copyPath`, `buffer.togglePin`,
  `buffer.rename`, …). Tabs now show a full-path tooltip on hover.
- Status bar segments: live cursor position/selection, language, indentation
  (tab size), line endings, file size, and encoding, shown right of the message area.
  Segments are clickable and dispatch commands — `nav.goToLine` (`M-g g`),
  `buffer.setLanguage` (override the syntax grammar), `buffer.setTabSize`, and
  `buffer.convertLineEndings` (LF/CRLF). Tab size now affects the minimap.
- Syntax highlighting via bundled TextMate grammars (using tm4e) for 21
  languages: Java, XML, shell, PowerShell, DOS batch, Python, Groovy, Kotlin,
  Ruby, C, C++, Rust, Go, C#, Markdown, JSON, CSS, HTML, YAML, INI, and SQL.
  Stateful tokenization carries grammar state across lines so multi-line
  constructs (block comments, heredocs, fenced code) highlight correctly.
- Code folding now covers more languages (C, C++, Rust, Go, Kotlin, Groovy, C#,
  CSS) in addition to Java, JSON, XML/HTML, and Markdown.
- `NOTICE` file attributing the bundled TextMate grammars and third-party
  libraries.
- Structure tool window: a collapsible tree of the active file's foldable
  regions with a search filter and Emacs-style keyboard navigation. Selecting
  an entry navigates the editor and anchors the target line at the top of the
  viewport. Bound to `M-7`.
- `C-x o` (Window: Other) to cycle keyboard focus between the editor and open
  tool windows.
- Code/text folding with `view.foldAll` (`C-c f`) and `view.unfoldAll`
  (`C-c u`); fold state persists across sessions.
- Editor view options for line numbers and a minimap.
- Theme switching and a recent-files list.
- IntelliJ-style left/right/bottom tool windows, a buffer Switcher, and a File
  Information panel.
- Toolbar icons, a settings window, and an expanded Emacs keymap.
- Command palette (`M-x`) with fuzzy matching, driven by the command registry.
- GitHub Actions CI workflow with a status badge, plus a Maven wrapper.
- README, MIT license, and `CLAUDE.md` project guide.

### Changed

- Opening a tool window now moves keyboard focus into it and selects its first item (the first
  folder/file in Project, the first bookmark, the first symbol in Structure), so you can navigate it
  with the keyboard immediately. Restoring tool windows on startup/session-switch does not steal focus.
  While a tool window is focused, global commands still work — `M-x`, the tool-window toggles
  (`M-1`/`M-2`/…), the `M-g …` jumps, and `C-x …` prefixes; the panel only intercepts the editor
  navigation chords it reuses (`C-n`/`C-p`, `C-f`/`C-b`, …).
- The **Switcher** (`C-x C-b`) now lists only the open files — the Tool Windows column was removed. It's
  a single column in **tab order**, with a **bold header** and a **fixed width** (sized to the longest
  file path on open) so it no longer resizes while you navigate. Tool windows are still reachable via
  their stripe icons and the "Tool Windows: Jump" picker (`M-g t`).
- Silenced the benign tm4e/Oniguruma grammar warnings that flooded the console
  (`'…]' without escape`, `No grammar source for scope …`) by raising the `org.eclipse.tm4e`
  log level to `SEVERE` at startup. They were harmless bundled-grammar quirks; real errors still
  surface.
- The Switcher (`C-x C-b`) now lists all open files (labeled "Open Files", most-recently-used
  first) instead of only previously-activated ones. It is also larger with bigger column
  headers; the selection bar now tracks the active column (vivid in the focused column, dimmed
  in the other), and the highlighted file's full path is shown at the bottom of the popup, above
  a legend of the navigation keys. A "Switcher: C-x C-b" hint on the toolbar makes the keybinding
  discoverable.
- Syntax highlighting is now incremental: an edit re-tokenizes only from the changed
  line to the end of the document (reusing stored per-line grammar states for the
  unchanged prefix) instead of the whole file, lowering highlight latency on larger
  files. Still runs off the UI thread with stale-result discarding.
- App chrome (toolbar/tool-window icons, status bar, tool windows, command palette,
  switcher, find bar, File Information) now uses AtlantaFX theme variables instead of
  hardcoded light colors, so it adapts to dark themes (icons and text stay legible).
- Clearing the recent-files list now asks for confirmation; each entry in the
  recent-files menu has an inline ✕ icon to remove just that file (no confirmation).
- Go to Line (`M-g g`) now accepts an optional column as `line:column` (e.g.
  `342:35`); a bare number still goes to the line's start. The dialog notes the
  column is optional and the column is clamped to the target line's length. If the
  target line is inside a collapsed fold, the region is unfolded so the line is shown.
- File Information tool window: values are now selectable/copyable (read-only text
  fields), with tighter padding and without the boxed section borders. Long values
  (e.g. the full path) no longer overflow the panel, which previously truncated the
  key labels to "..." and showed scrollbars.
- The Settings font-family picker now lists only monospaced fonts, with a note
  explaining the filter (a previously saved non-monospaced font stays selectable).
- Quitting now asks for confirmation first (the Quit button, `C-x C-c`, and the
  window close button), in addition to the existing per-buffer unsaved-changes prompts.
- User preferences are now stored as TOML in `~/.editora-v2/settings.toml` (was
  `settings.json`). Session state (fold regions, tool-window layout) moved to a
  separate `workspace-state.json`; recent files stay in `recent-files.json`. No
  migration — a pre-existing `settings.json` is ignored.
- Closing a pinned tab now asks for confirmation first (the X, the Close menu
  item, and the close command); bulk-close actions still skip pinned tabs.
- The Structure tool window now labels entries with the symbol name and kind
  (functions, types/classes, namespaces, Markdown headings, XML tags) derived
  from the TextMate grammar, and hides trivial blocks (if/for/while/…), instead
  of showing the raw brace line. Works across all bundled languages; clicking a
  node jumps to the symbol's line.
- Folded regions now shade their header line (in addition to the gutter chevron)
  so collapsed code is easier to spot.
- Opening a file that is already open now switches to its existing tab instead
  of opening a duplicate.
- The recent-files picker is now a toolbar menu button (with a per-entry remove
  action) instead of a combo box.
- The key dispatcher now yields only single-key chords to windows that own
  their keys (e.g. the Structure tool window); multi-key Emacs chords such as
  `C-x o` stay global so window switching and `C-x`/`C-c` commands work from
  anywhere.

### Fixed

- **Bookmarks no longer drift to the wrong line when a file is edited outside the editor.** Bookmarks
  followed their content only for edits made inside Editora, so changing a file in another program
  left its markers on stale lines. Each bookmark now re-anchors to its saved line text when the file
  is opened (re-found at the nearest matching line), and the corrected position is saved back so the
  session self-heals.
- **Adding a bookmark no longer shifts that line's text indentation.** The gutter now reserves a
  fixed-width bookmark column on every line, so the marker glyph no longer widens only the bookmarked
  row (which had pushed its text rightward).
- **A bookmark's gutter marker now follows its line when an edit moves it.** Inserting or deleting
  lines above a bookmark already moved the bookmark (and its Bookmarks-panel entry), but the gutter
  glyph wasn't repainted onto the new line; it is now.
- Switching projects (or "No Project"), or closing/deleting a project, while in Zen mode no longer
  leaves the UI permanently mangled. Zen stores its "everything off" view state in the global
  settings while keeping the restore snapshot in the per-session state; swapping sessions orphaned
  that snapshot. The editor now exits Zen (restoring the real view settings) before a session switch
  and lands the incoming session in normal view.
- The `M-<` / `M->` (document start/end) and `M-%` (find & replace) keybindings now work. They were
  stored in Emacs notation (`M-<`) but the dispatcher emits shifted symbols as `M-S-,` etc., so the
  entries never matched; the keymap now uses the dispatcher's token form.
- No more light-gray flash on startup with a dark theme: the scene is pre-filled with
  the theme's background color, so the first frame paints in the theme color instead of
  JavaFX's default background before the CSS is applied.
- Much faster startup when restoring a session: all tab headers are created first
  (so they appear at once), then each file's content and folds are loaded one per
  pulse — the active file first — keeping the UI responsive. Previously a session
  with a heavily-folded file could freeze the window for several seconds on launch.
- Folding no longer smears the hidden lines' numbers onto the fold-header row in
  the gutter: a collapsed paragraph's gutter cell is laid out at zero height, but
  its line-number label did not clip to it; the gutter is now clipped to its bounds.
- "Show hidden characters" no longer piles stray space/tab/EOL markers onto the
  fold-header row when a region is collapsed: folded (hidden) paragraphs are now
  skipped by the whitespace overlay (and the 80-column ruler's measurement).
- Running a command via a chord that opens a dialog no longer types a stray
  character afterwards (e.g. `M-g g` then OK inserted a `g`): the dispatcher now
  also swallows the character event paired with a key press it handled, which a
  modal dialog (`showAndWait`) would otherwise deliver to the editor after closing.
- `M-` (Meta) chords no longer insert a stray character on macOS. Option is the
  Meta key, and `Option+<key>` also emits a special character (e.g. `M-f` → "ƒ",
  `M-x` → "≈") whose KEY_TYPED was inserted into the editor (or palette) after the
  command ran. Characters typed with Alt held are now swallowed on macOS — globally
  by the key dispatcher and in the command palette popup. (Scoped to macOS so
  AltGr-composed characters keep working on other platforms.)
- Command palette entries can now be run with the mouse — clicking a command
  runs it (previously only Enter on the keyboard-selected row worked).
- Next/previous line (`C-n` / `C-p`) now move the caret like Emacs: they were
  bound to RichTextFX's scroll-based `nextLine`/`prevLine` (which move relative to
  the viewport and no-op when the caret is off-screen). They now move by paragraph
  and preserve a goal column, so passing through short lines keeps the original
  column.
- Syntax highlighting no longer intermittently fails to load when multiple files
  of the same language are open. They share one TextMate grammar instance, and
  tm4e's tokenizer is not thread-safe, so concurrent highlighting on each buffer's
  background thread could throw and silently drop a file's highlighting;
  tokenization is now serialized per grammar.
- The minimap no longer stretches short files across its full height (scattering
  sparse blocks): line height is capped, so short documents fill from the top and
  long documents still compress to fit.
- Pinned tabs are now remembered across sessions: a tab's pinned state is saved
  on exit and restored on the next launch (alongside the open files and carets).
- The 80-column ruler now lands exactly on column 80. It was positioned from a
  font-probe character width and ignored the line-number gutter, so it drifted off
  the text grid; the column is now derived from the editor's live layout (caret
  positions, glyph-independent). It is hidden when column 80 is outside the visible
  text width (window too narrow, or scrolled past it).
- The 80-column ruler now tracks horizontal scroll instead of staying pinned to
  a fixed x-offset when a horizontal scrollbar is present.
- The Structure tool window no longer shows "No structure" for documents whose
  regions were computed before the panel attached.
- The editor context menu now dismisses on left-click.

[Unreleased]: https://github.com/adriandeleon/Editora/commits/master
