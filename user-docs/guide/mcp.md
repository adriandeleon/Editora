---
title: MCP server
description: Let an LLM agent observe editor state and run commands through an embedded Model Context Protocol server.
category: Integrations
order: 2
beta: true
---

Editora can run a small [Model Context Protocol](https://modelcontextprotocol.io)
server inside the editor, so an LLM agent (Claude Code, for example) can see what
you're working on and act through Editora's own commands. It's **off by default**
and guarded by a security-notice dialog; enable it in **Settings → MCP Server**.

## What it exposes

A **loopback-only** HTTP/JSON-RPC server with **bearer-token auth** exposes
eighteen tools, in four groups:

| Group | Tools |
| --- | --- |
| **Reads** | `list_open_files`, `list_tabs`, `read_buffer`, `get_selection`, `get_diagnostics`, `document_symbols`, `git_status`, `todo_scan`, `find_in_files`, `list_commands` |
| **Writes** | `edit_buffer` (undoable str-replace edits), `save_buffer` |
| **Actions** | `open_file`, `execute_command` |
| **Review** | `list_review_comments`, `add_review_comment`, `reply_to_review_comment`, `resolve_review_comment` |

So an agent can observe live state, make **undoable edits**, and drive the editor
through the same command registry the palette uses. It runs on the JDK's built-in
HTTP server, so there's no new dependency.

Each tool says whether it only reads (`readOnlyHint`), and the ones that change
something say whether the change can be lost (`destructiveHint`): `save_buffer`
writes a file and `execute_command` can run anything, while `edit_buffer` is
undoable and stays in the buffer. An agent can use this to decide what to ask
you before calling a tool.

### Resources

Besides tools, the server offers **resources**, which an agent can list, read
and watch:

| Resource | What it reads |
| --- | --- |
| `file:///…` | The live text of each file open in the window, unsaved changes included |
| `editora://diagnostics` | The language-server diagnostics of every open file, by path (JSON) |
| `editora://reviews` | The open [review threads](/docs/code-review) of the window's repository (JSON) |
| `editora://git/status` | The window's Git status: branch, upstream, ahead/behind, changed files (JSON) |

Only open files are resources: a file that isn't open in Editora can't be read
this way. An agent that watches resources is told when you open or close a file
and when a watched file, the diagnostics or the Git status change. Editora looks
once a second, and only while an agent is listening.

### Protocol versions

The server speaks the current MCP revision, **2026-07-28**, and the earlier
ones from **2024-11-05** to **2025-11-25**. An agent on an older revision starts
with `initialize` and gets the revision it asked for; one on the current
revision asks with `server/discover`. Claude Code finds the right one by itself.

### Editing and saving

`edit_buffer` replaces `old_text` with `new_text` in an open buffer, through the
editor's undo history. Nothing is written to disk until `save_buffer`.

- `old_text` must be the exact text to replace and must occur once in the
  buffer, unless `replace_all` is `true`.
- To replace the buffer's entire text, the agent passes
  `replace_whole_buffer: true` and no `old_text`. An edit with a missing or empty
  `old_text` is rejected, never widened to the whole buffer. A whole-buffer
  replacement is also refused when the buffer changed since the agent last read
  it with `read_buffer`; it reads the buffer again and retries.

`save_buffer` waits for the write. Its result is `saved` only when the file is
on disk, and an error when the save was refused, failed, or is still pending.
`execute_command` reports only that a command ran, so an agent that needs to
know a save reached the disk uses `save_buffer` rather than running `file.save`.

### Review comments

An agent can take part in [code review](/docs/code-review) in the window's Git
repository:

- `list_review_comments` lists the threads (of one file, with `path`), each
  with its lines where they are now, its state, and its comments.
- `add_review_comment` starts a thread on lines of a file, with an optional
  `suggestion`, and for a finding a `severity` (`blocker`, `important`,
  `minor`, `nit`) and a `category`. It shows after those lines in the editor,
  like any review comment, marked as the agent's.
- `reply_to_review_comment` answers a thread.
- `resolve_review_comment` resolves an open thread, with a note saying what
  was done; the note is kept, naming the agent.

These tools change no file: they write only the review threads. A comment goes
only on a file the agent may open (see below), and none reaches GitHub.

### Which files an agent can reach

The tools act on the window the MCP server belongs to, whichever window has
the focus: `find_in_files` and `todo_scan` search that window's project,
together with the unsaved text of its open buffers, and `todo_scan` gives each
marker's file, line and column.

`open_file` opens a file only when it is **inside that window's project
folder**, or selects one that is already open there. A link inside the project
that leads out of it counts as outside. In a window with no project, it opens
nothing new. A refused request tells the agent that you have to open the file
yourself.

Files you open yourself, wherever they are, can be read and edited by the
agent like any other open buffer, with one exception: `edit_buffer` and
`save_buffer` refuse a buffer whose file is in Editora's
[config folder](/docs/configuration) or in version-control metadata such as
`.git/`. Settings, keymaps, plugins and Git hooks can make the editor or Git
run commands, so they are not written on an agent's behalf. The
[AI Agent](/docs/ai#ai-agent) has the same limit.

### Plugins and trust

Turning plugins on and installing them are your decisions, never an agent's,
and so is trusting a folder. `execute_command` refuses every command whose id
mentions trust (such as `workspace.trustFolder` and
`lsp.trustProjectSettings`) and leaves them out of `list_commands`.
`execute_command` refuses **View: Toggle Plugins** (`view.togglePlugins`) and
every **Plugins:** command: browsing and installing (`plugins.browse`,
`plugins.installFromDisk`), the signed-registry requirement
(`plugins.toggleRequireSignature`) and the registry address
(`plugins.setRegistryUrl`). They are left out of `list_commands`, and a refused
request tells the agent to ask you to run it. Removing a plugin is done only from
Settings → Plugins, and `edit_buffer` and `save_buffer` refuse files in the
plugins folder and `plugins.json`, which are in the config folder.

An agent can still run the commands an enabled plugin adds.

### Argument checks

Every tool validates its arguments before doing anything:

- An argument the tool doesn't define is rejected, with the accepted names in the
  error, so a misspelled argument can't be silently ignored.
- A `path` must be absolute. An empty or relative path is rejected. Tools that
  take an optional `path` use the active buffer when it is left out.

## Enabling and connecting

1. Turn it on in **Settings → MCP Server** (or run **View: Toggle MCP Server**,
   `view.toggleMcp`), and accept the security notice.
2. Click **Copy Connect Command** on the Settings page, click the status-bar
   **MCP** indicator, or run **MCP: Copy Connect Command** (`mcp.copyEndpoint`).
   It copies a line like this one, with your install's launcher:

   ```bash
   claude mcp add --scope user editora -- editora mcp
   ```

   Paste it in a terminal once. Claude Code then starts
   [`editora mcp`](/docs/cli#connecting-an-agent-over-mcp), which relays to the
   running editor and finds it again after a restart or a new token, so there
   is nothing to set up again. With `--scope user` it works in every folder you
   run Claude Code in.
3. The page's **Server** row shows **Running at** the endpoint URL, or **Off**.
   The endpoint is also written to `mcp-endpoint.json` in your
   [config folder](/docs/configuration) for discovery.

If Claude Code starts before Editora, or the server is off, it still sees the
tools, and each call answers that Editora has to be started with its MCP server
on.

### Connecting over HTTP

For an agent that connects to a URL instead of starting a program, **Copy HTTP
Command** (**MCP: Copy HTTP Command**, `mcp.copyHttpEndpoint`) copies a
`claude mcp add --transport http` command with the endpoint URL and token. That
command is valid only until the server stops or gets a new token: copy it again
after a restart.

![Settings on the MCP Server page: the Enable MCP server switch on, and the Server row reading Running at an http://127.0.0.1 endpoint with Copy Endpoint Command and New Token buttons](/screenshots/docs/settings-mcp.png "Settings → MCP Server while the server runs, with the button that copies the connect command.")

## Security

The server binds to loopback only (never the network), requires the bearer
token, stays off until you enable it, and shows a security notice first.
`open_file` is confined to the window's project and the write tools refuse the
config folder and version-control metadata (see
[Which files an agent can reach](#which-files-an-agent-can-reach)). Even so,
`execute_command` can run almost any command (not the plugin or trust ones;
see [Plugins and trust](#plugins-and-trust)), so only connect agents you trust.
No MCP tool can read the text of an open
[encrypted file](/docs/encrypted-files#what-editora-keeps-out-of-an-encrypted-file). Simple
UI mode turns it off.

`mcp-endpoint.json` holds the endpoint URL **and the bearer token**. Where the
filesystem supports POSIX permissions (so not on Windows), Editora restricts the
file to your user account. It deletes the file when the server stops and leaves
it out of Export Configuration. The token is the only thing that stops another
program on your computer from driving the editor, so any program that can read
that file can connect. The port and token are new each time the server starts.
`editora mcp` reads them from the file for every message, so an agent set up
with the connect command keeps working; one set up with the HTTP command needs
it again. The connect command itself holds no token.

**New Token** on Settings → MCP Server replaces the token while the server keeps
running on the same port, and rewrites `mcp-endpoint.json`. Every agent set up
with the HTTP command is refused from then on; give it the new command. Agents
connected through `editora mcp` pick up the new token by themselves.

`editora mcp` sends the token only to a plain `http://` address on this
computer's loopback interface; it ignores an endpoint file that names any other.

A request sent by a web page (one whose `Origin` is not this computer) is
refused, even with the token, so a site open in your browser can't reach the
editor.

Commands: `view.toggleMcp`, `mcp.copyEndpoint`, `mcp.copyHttpEndpoint`.
