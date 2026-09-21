# Adaptive model profiles and structured validation

Phase 4 extends the existing single-writer runtime; it does not replace document transactions, policy,
context pairing, LSP ownership, MCP lifecycle or ACP. See [platform contracts](agent-platform.md),
[IDE integration](agent-intelligence.md) and [evaluation methodology](agent-evaluation.md).

## Model, provider and server are different evidence

`AgentModelProfile` records the provider, exact configured model identifier, server metadata format,
model maximum context, loaded-instance context, effective context, preferred/maximum output, tool
support and feature evidence. Every claim carries `DISCOVERED`, `CONFIGURED`, `KNOWN_PROFILE`,
`HEURISTIC` or `UNKNOWN` provenance. There is no model-name substring capability table. Missing
parallel calls, reasoning, structured output, tokenizer and caching information stays unknown;
transport support does not prove that a model can use a feature. Observed usage/parallel-call support
continues to strengthen the existing adapter capability map separately.

`AgentModelDiscovery` performs only same-origin `GET /api/v1/models` for an LM Studio provider or a
literal-loopback OpenAI-compatible endpoint. It never loads, downloads or unloads a model, follows a
redirect, reads local credentials, or transfers a key to a different origin. Remote cleartext
credential requests are refused. Discovery has bounded time, a 256 KiB response limit and parent
cancellation. Unsupported, malformed and unavailable metadata fall back honestly. It probes before
inference and once after the first response, when JIT loading may reveal a smaller loaded context.
A repeated preparation before the first response does not consume that refresh.

The [LM Studio model-list API](https://lmstudio.ai/docs/developer/rest/list) distinguishes the model's
maximum context from `loaded_instances[].config.context_length` and exposes tool-training/reasoning
claims. Multiple matching model entries are ambiguous; multiple loaded limits use the smallest
known limit. A missing loaded limit is **not** interpreted as the architectural maximum. Available
model picking remains in the existing provider model selector. General Ollama/proxy discovery and
exact tokenizer negotiation remain future work.

Settings → AI Agent → model profile stores overrides for the selected provider/model: context,
preferred/maximum output, tool support, discovery, optional temperature and optional seed. Zero token
limits mean automatic; blank sampling fields preserve server defaults. Settings schema 107 adds an
empty profile list through an identity migration, preserving existing selections. Changes apply to
new sessions. Seed is sent only through OpenAI-compatible transport; the Anthropic control is disabled.
Server acceptance and reproducibility are not guaranteed. The [OpenAI Chat Completions reference](https://developers.openai.com/api/reference/python/resources/chat/subresources/completions/methods/create)
deprecates `max_tokens` in favor of `max_completion_tokens` and describes seed as best-effort. The
official OpenAI origin uses the newer field; other compatible origins retain `max_tokens`. Sampling
is omitted unless explicitly requested, because support differs across models.

Profile JSON is constructed explicitly rather than opening the runtime JPMS package to Jackson.
The `agent_model` tool exposes the profile and token-estimate provenance. Provider feature support
and model evidence remain separate; an unknown feature is unconfirmed, not an invented guarantee.

## Output recovery without replaying incomplete actions

The adaptive HTTP adapter uses an explicit UTF-8-byte/3 token heuristic, **not a model tokenizer**.
The runtime caps context by both session policy and effective profile context, then reserves the
output allowance and at least 1,024 tokens or 10% headroom. This is an estimate, not proof that a
server will accept every request. Existing adapters can retain conservative counting and fixed budgets.

The default preferred output is 4,096, or 8,192 when server metadata exposes reasoning; the default
ceiling is 16,384 and never more than half the effective context. Without discovered reasoning support, automatic tool-turn output trades headroom for retained
input before compaction, within a 2,048–4,096 allowance (or a lower configured ceiling). Explicit
output preferences are not reduced by this input-pressure rule. Automatic recovery budgets also
leave room for the tool catalog, user goals and newest complete exchange; they cannot starve those
mandatory inputs merely to increase output. An output exhaustion increases the allowance, bounded by the profile ceiling. User
limits take precedence. This deliberately does not maximize every response or guess task complexity
from goal keywords.

A protocol `length`/`max_tokens` response discards **all** calls in that response, including an
apparently complete first call in a truncated multi-call batch. The incomplete text/call ids never
enter durable model history. A runtime-owned observation explains that no action ran and asks for a
complete smaller step. At most two recoveries occur per submitted turn; repeated exhaustion stops
with `NEEDS_INPUT`, not verified completion. An unmarked EOF/malformed stream remains a transport
failure, not permission to infer a missing stop. The [Anthropic stop-reason guidance](https://platform.claude.com/docs/en/build-with-claude/handling-stop-reasons)
likewise treats a token-limited tool response as incomplete. Editora retries a whole action step;
it does not splice arbitrary partial JSON across requests.

`AgentToolFeedback` supplies bounded structured recovery hints for schema, semantic-position,
stale-document, unsaved-buffer and validation errors. Existing literal-search and exact UTF-16
symbol-range guidance remains generic. No model-family-specific prompt hacks were added.

## Project validation is an explicit operation

`validation_profiles` discovers Maven/Gradle descriptors at a requested module and immediate child
modules (bounded to 200 children). It does not execute build files or infer capabilities from arbitrary
repository instructions. Ambiguous descriptors require choosing a discovered system. A project
wrapper is preferred when present; platform wrapper names are respected.

`run_validation` takes an operation, optional module/test selector and explicit isolation choice:

| Operation | Maven | Gradle | Evidence requirement |
| --- | --- | --- | --- |
| COMPILE | compile | classes | Exit zero and current saved revisions; no test-success claim. |
| TEST | test | test | Exit zero plus fresh, non-skipped test cases and no reported failures. |
| TARGETED_TEST | test -Dtest=Class#method | test --tests Class.method | Same, with an application-validated selector. |
| CHECK | verify | check | Same fresh-test requirement; check-only projects can fail closed. |
| PACKAGE | package | assemble | Exit zero; reports are inspected but tests are not assumed. |

Arguments are application-owned lists, without implicit shells or arbitrary extra model options.
Offline flags are always supplied. Maven test-skip properties are explicitly false. Arbitrary
`run_command` remains available behind its existing external-execution permission and heuristic
validation recognition; it does not acquire the stronger structured-test semantics.

Every build operation requires approval in every trust mode: repository build scripts execute code.
Dirty buffers prevent execution. A selected module cannot validate changed files outside that module.
Before and after execution the existing document/save boundary checks open-buffer revisions, dirty
state and exact saved bytes. A concurrent user edit invalidates evidence. A later command or edit
invalidates previous command evidence. Successful structured validation participates in the existing
completion verifier and the final-answer recheck; the model cannot grant itself verification.

`AgentValidationReports` extracts fresh Surefire/Failsafe/JUnit XML cases and compact failure
class/test/type/message fields before bounded console output. Unchanged reports do not count.
DTD/entity/external-resource resolution is disabled. Bounds are 20 path levels, 100,000 entries,
1,000 report files, 1 MiB/file and 25 MiB total input; a truncated/unreadable scan fails closed.
Only four failure summaries are returned. The full revision map stays in the verifier; the tool
returns a bounded display list, count and fingerprint of the saved revisions. Test reports are build
artifacts, not a hostile-project proof. Compiler locations and richer non-JUnit parsers remain open work.

`AGENT_VALIDATED` names the evidence origin, with a separate `passed` boolean. Evaluation probes run
later, outside the model workspace, and report `EVALUATION_ORACLE_PASSED` independently. An agent
cannot see or edit the hidden oracle before its turn finishes.

## Linux isolation and honest fallback

`AgentValidationSandbox` supplies an execution-policy boundary behind the validation plan. On Linux,
`ISOLATED` uses bubblewrap user/mount/PID/network namespaces, a fresh home and controlled environment.
Only the workspace is writable on the host; `/usr`, required runtime/tool distributions and an
existing Maven artifact repository are mounted read-only. Those installed tools and cached artifacts
remain trusted inputs and may contain custom configuration; isolation does not scrub their contents. The user’s ~/.m2/settings.xml, SSH home, credentials,
agent sockets and environment secrets are not inherited. `/tmp`, `/proc` and `/dev` are private mounts.
The network namespace denies access to the host network, including its loopback services. Maven's
cache is read-only; missing artifacts cannot be fetched and local-repository writes may fail.

There is no silent downgrade. Missing bubblewrap, unsupported namespaces or unavailable tools return
an error. `HOST_REDUCED` is a separate explicitly approved choice with the existing reduced process
environment; offline flags there **do not enforce network denial**. macOS/Windows currently use that
lower-isolation path. Gradle command templates exist but Gradle cache/distribution isolation has not
been validated; a wrapper needing downloads fails offline. The discovery boolean means the isolation
executable is present, not that the current kernel permits its use.

The existing `ProcessRunner` enforces a 120-second validation deadline, bounded output and termination
of descendants on cancellation/timeout. There are no cgroup memory/CPU quotas or syscall filters.
A writable workspace still lets a malicious build destroy workspace data; source content and any
secrets deliberately placed there remain accessible. LSP, MCP, ACP and arbitrary commands are not
sandboxed by this feature. This is reduced build/test exposure, not comprehensive hostile-code containment.

## Readiness, approvals and desktop presentation

Real JDT LS testing demonstrated delayed dynamic capability registration and transient old symbol
results immediately after `didChange`. `WindowAgentSemantics` no longer describes an empty semantic
catalog as available. Consumers discover the required operation rather than guessing a delay. A
response's freshness label explicitly means the **client revision** remained current; an unversioned
server response does not prove the server analyzed that revision. Existing leases and strict
WorkspaceEdit normalization still reject newer client edits and invalid server targets. No guessed
server edits or semantic-operation replay were introduced.

Session-local approval telemetry contains category/count, allow/deny/interruption, total wait and
repeated-equivalent count. A bounded in-memory digest set detects identical serialized requests (not every semantically equivalent argument spelling); arguments/digests are
not exported or logged. FINE logs contain category/outcome/latency. Existing multi-file edit batches
already group one bounded operation into one decision. The dialog now lists its reviewed paths and
keeps exact arguments expandable; this creates no future/session permission. Validation rows show
case/failure/skip totals and actionable details rather than raw JSON in normal expanded content.

The opt-in GTK fixture exercises visible production panels, dialogs and diff UI with isolated
configuration. Scene snapshots avoid capturing unrelated desktop content. Automated button events
and an agent's visual inspection are not measurements of human permission fatigue or a manual-user
session; those studies remain necessary.
