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
