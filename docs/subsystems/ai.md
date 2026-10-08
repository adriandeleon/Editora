# AI providers and agents

AI actions use `AiCoordinator` → `AiService` → `AiClient`; the agent panel uses
`AgentCoordinator` → `AcpClient` over stdio. Both remain behind the master AI switch,
but their individual enable flags are independent.

AI Actions supports Anthropic Messages, OpenAI-compatible chat completions, the dedicated
LM Studio / Bionic provider, and Codex. All palette actions resolve through
`WindowCommandRegistrar`.

Successful `explanation.md` buffers end with a localized agent/model footer. `AiClient` extracts
model metadata from OpenAI-compatible chunks (including LM Studio) or Anthropic `message_start`;
`AiService` forwards it through its FX-thread generation guard. The explanation captures the provider
and requested model at launch, then prefers the response model. Missing metadata falls back to the
requested model, or explicitly shows Unknown if both are absent. This identifies the direct AI
Actions provider; the independently selected ACP chat agent does not generate these explanations.

## Codex

The Codex provider uses `Settings.codexAgentCommand` (default `codex-acp`) and the adapter's
existing login. Install `npm install -g @agentclientprotocol/codex-acp @openai/codex` and run
`codex login`; plain `codex` starts a terminal UI and is not an ACP server. The adapter is
maintained at [agentclientprotocol/codex-acp](https://github.com/agentclientprotocol/codex-acp).

`CodexAiClient` creates an independent ACP process/session for each action in a temporary
working directory. It selects `read-only` before prompting, refuses client filesystem requests
and permission approvals, and forwards only assistant message chunks. A blank model uses Codex's
default; endpoint and API-key settings are ignored. Inline completion is disabled for Codex.

## LM Studio / Bionic

`AiProvider.LMSTUDIO` uses the same request/SSE dialect as `OPENAI`. It has dedicated
Settings fields for endpoint, model, completion model and optional API token. The
103→104 additive migration preserves existing providers and credentials. The blank
endpoint resolves to `http://127.0.0.1:1234/v1/chat/completions`; `AiEndpoints.resolve`
also accepts a server URL or `/v1` base. A blank inline model uses the local actions
model. Anthropic environment credentials never back either local provider.

The `lmstudio` ACP preset launches a separately installed OpenCode CLI (`opencode acp`).
`LmStudioAgent` builds `OPENCODE_CONFIG_CONTENT` for that child process, using a dedicated
`editora-lmstudio` provider and setting both the main and small model. The preset lists
only that provider as enabled. It does not modify project/global OpenCode files;
OpenCode still merges its other settings, tools and permissions according to its own
configuration rules. Admin-managed OpenCode policy can take precedence over the inline
configuration. The optional token is passed through a separate child environment variable,
never embedded in argv or the generated JSON. Remote cleartext credentials are refused,
matching the direct AI client’s guard.

The model identifier is required for the agent preset because OpenCode needs a model
catalog entry. Configure it under AI Actions with the LM Studio / Bionic provider selected;
AI actions need not be enabled. Changing the model/endpoint/token takes effect on the next
agent session. The preset’s command override is independent of the ordinary OpenCode agent.

Bionic supplies local inference here; OpenCode supplies the coding-agent loop and ACP.
There is no dependency on a Bionic ACP command or its cloud subscription. Reference protocols:
[LM Studio Chat Completions](https://lmstudio.ai/docs/developer/openai-compat/chat-completions),
[Bionic Local Model API](https://lmstudio.ai/blog/muse-glimmer),
[OpenCode LM Studio provider](https://opencode.ai/docs/providers/#lm-studio), and
[OpenCode configuration precedence](https://opencode.ai/docs/config/#precedence-order).

## ACP selectors

Current OpenCode exposes model and mode choices through ACP `configOptions` instead of the
legacy `models`/`modes` objects. `AcpJson` prefers recognized select categories and flattens
option groups. `AcpClient` uses the advertised selector id with `session/set_config_option`,
refreshes the complete state from responses/notifications, and retains legacy set_model/set_mode
requests for older agents. `AgentCoordinator` applies selector updates on the FX thread and
ignores updates for a different session. See the
[ACP selector specification](https://agentclientprotocol.com/protocol/v1/session-config-options).

## Agent and MCP writes

Two channels let a program other than the user change documents: the ACP agent's
`fs/write_text_file` (`AcpClient` → `AgentCoordinator`) and the MCP server's `edit_buffer` /
`save_buffer` (`McpTools` → `WindowMcpBridge`). Neither asks the user before each write, so
each of them keeps to these rules:

- **One path.** `AcpFsGuard` resolves the agent's path inside the session folder and returns
  it; `AcpClient` hands that absolute path to the host, and the host refuses anything that is
  not absolute. MCP rejects an empty or relative `path`. Nothing is ever resolved against the
  editor's own working directory. `AcpFsGuard` also refuses writes into the configuration
  directory and into version-control metadata (`.git/`, `.hg/`, …).
- **MCP stays with the project and the open files.** `open_file` is the one MCP tool that has the
  editor fetch a file by path. `McpAccess` opens it only when it is canonically inside the
  window's project folder (`PathContainment`, so a link out of the project is outside) or
  already open in the window; a window with no project opens nothing new. Everything else MCP
  reads or writes is a buffer the user or that rule put there. `edit_buffer` and `save_buffer`
  refuse a buffer whose file is in the configuration directory or in version-control metadata,
  wherever it is and whoever opened it. `execute_command` is not confined: it runs any
  registered command, as the enable-MCP notice says.
- **A buffer is written through the buffer.** If any window has the file open, the write is an
  undoable whole-document edit of that buffer and nothing reaches the disk until the user
  saves.
- **A file with no buffer is recoverable.** Its previous text goes to Local History first
  (the write is refused if that fails), the replacement keeps the file's charset, byte-order
  mark and line endings (`AgentFileWrites`), and the write is conditional on the bytes that
  were snapshotted.
- **No stale overwrite.** `ServedText` remembers a digest of what each document last looked
  like to the agent (`fs/read_text_file`, `read_buffer`, or its own last write). A
  whole-document write is refused when the text changed since, or when the buffer holds
  unsaved text the agent never read; the error names the read to repeat.
- **Narrow by default.** MCP checks arguments against the schema it publishes: an unknown
  name, a wrong type or an empty `path` is an error, never "the active buffer" or "the whole
  buffer". Replacing a whole buffer takes `replace_whole_buffer: true`. Targeted edits match
  against the whole document, as `read_buffer` returns it, also when the buffer is narrowed.
- **Reports are true.** `save_buffer` waits for the write and answers `saved` only when the
  bytes are on disk. A call that timed out before the FX thread started it is cancelled
  (`FxCall`), so a reported failure is not applied later.
- **The permission dialog cannot be answered by accident.** It opens with the focus on
  "reject once" (else Cancel), has no default button, and ignores keys and button presses for
  a moment after it appears.

## Verification

Use a loopback fake server to exercise streaming, health checks, optional authentication,
and request bodies without contacting a paid service. Pure tests cover local settings
round trips/migration, endpoint normalization, generated OpenCode configuration, and
credential isolation. UI tests cover switching providers without overwriting hidden fields.
Live smoke tests need a running local server and an installed OpenCode CLI; use a scratch
working directory and a harmless prompt rather than sending repository content.

`CodexAiClientTest` covers the ACP handshake, streaming, refused writes and permissions,
cancellation, timeout, startup and authentication errors. To run the opt-in live Codex probe:

```sh
mvn test -Dtest=CodexAiProbeTest -Dgroups=probe -Deditora.ai.codex.probe=true
```
