# AI integration

`AgentCoordinator` owns the interactive ACP chat; `AiCoordinator` owns explain, rewrite,
commit-message generation and HTTP inline completion. Both require `Settings.aiEnabled` and
their own enable flag, and are suppressed in Simple mode. All palette actions resolve through
`WindowCommandRegistrar`.

AI Actions supports Anthropic Messages, local OpenAI-compatible chat completions, and Codex.
The Codex provider uses `Settings.codexAgentCommand` (default `codex-acp`) and the adapter's
existing login. Install `npm install -g @agentclientprotocol/codex-acp @openai/codex` and run
`codex login`; plain `codex` starts a terminal UI and is not an ACP server. The adapter is
maintained at [agentclientprotocol/codex-acp](https://github.com/agentclientprotocol/codex-acp).

`CodexAiClient` creates an independent ACP process/session for each action in a temporary
working directory. It selects `read-only` before prompting, refuses client filesystem requests
and permission approvals, and forwards only assistant message chunks. The coordinator remains
responsible for applying returned text through the existing undoable edit path. The adapter
continues to own authentication and its own server-side capabilities. Actions do not change
or cancel an interactive chat session. A blank model uses Codex's default; endpoint and API-key
settings are ignored. Inline completion is disabled for Codex to avoid spawning an agent per
keystroke pause; the saved inline preference is preserved for API providers.

Successful explanation buffers end with a Markdown provenance footer naming the provider agent
and model. API streams use the response metadata, so a blank local-server model setting records
the model the server actually selected; Codex uses the ACP session model (or the explicitly selected
model after `session/set_model`).

Generation, inline completion and connectivity checks have separate `AiService` workers.
Codex checks initialize a session and validate the mode/model without generating a reply.
Both automatic and manual checks refresh cached button availability; replies for a changed
provider configuration are discarded. The resulting effective availability is pushed to each
`EditorBuffer`; it gates both the floating selection bar and the editor's AI Actions context
submenu, so neither surface appears while the provider is disconnected. Codex requests poll cancellation while awaiting ACP
responses, enforce a timeout, and dispose their process tree on every terminal path.

`CodexAiClientTest` uses a deterministic child-process peer to cover the ACP handshake,
streaming, refused writes/permissions, cancellation, timeout, startup and authentication errors.
`AiCoordinatorFxTest` covers reconnection and provider gates. To verify a real installed adapter
and login (sends one short prompt), explicitly run:

```sh
mvn test -Dtest=CodexAiProbeTest -Dgroups=probe -Deditora.ai.codex.probe=true
```
