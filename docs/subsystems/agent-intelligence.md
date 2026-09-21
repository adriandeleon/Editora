# IDE-native agent intelligence

Phase 2 builds on the [embedded runtime and security contract](agent-platform.md). It retains the
same model loop, policy, document transactions and completion verifier. Semantic discovery,
instruction provenance, external extensions and durable memory are independent services registered
in `AgentTools`; model adapters contain no editor-specific tool lists.

## Semantic discovery and refactoring

`semantic_capabilities(path)` reports the live server's supported operations. `semantic_query`
supports document/workspace symbols, definition, declaration, implementation, type definition,
references, hover, signature help, highlights, code actions, call hierarchy and type hierarchy.
Unsupported or unavailable operations fail explicitly. All coordinates are **zero-based UTF-16**;
`read_file` uses one-based lines and reports end/total/next-line metadata and explicit truncation.
Most semantic lists contain at most 100 compact entries
(symbol, URI, range, kind, container). Follow returned locations with targeted file reads.

`LspAgentService` uses typed LSP4J futures and live server capabilities, rather than UI callbacks
that turn failures into empty lists. Cancellation reaches the underlying request, including the
second request for hierarchy children. Hierarchies currently expand the first prepared root and
report the number of roots; they do not recursively dump graphs. Workspace symbols without a
resolved range are locations to inspect, not invented ranges. Raw server errors become tool failures.

`WindowAgentSemantics` captures buffer identity/revision on FX, activates deferred LSP ownership
for background files, flushes changes and checks the actual sent text. It waits off FX, then rejects
responses if documents changed, closed/reopened, or the server route changed. Returned freshness is
`CURRENT_AT_RESPONSE`, not a promise about future user edits. Navigation freshness covers the source
document and server identity; it cannot prove the server's entire workspace index is up to date.

Refactoring is a separate preview/commit workflow:

1. Read source and affected callers, then `semantic_prepare` for rename, formatting or a code action.
2. Capture all open workspace preimages **before** sending the request. Refuse unseen targets; read
   those targets and prepare again. This deliberately avoids assigning a fresh lease to an old edit.
3. Validate server document versions, confined URIs, strict UTF-16 ranges, duplicates and overlaps.
4. Show editor diffs and return a session-local proposal token. Preparing never writes document text.
5. `semantic_apply` consumes that token and uses the existing all-target document transaction.
   Newer user edits cause conflicts; applied changes remain undoable and dirty until saved.
6. The normal save, validation, diff and final-answer verification gates apply.

Limits are 16 changed files, 2,000 edits per file, eight pending proposals and 128 preimage documents.
Resource operations (create/rename/delete), snippets, annotated edits, unresolved code actions and
server commands are refused. Code actions must contain an explicit edit with no command; the action
index is re-queried against the same source revision. Formatting currently uses four spaces.
These restrictions preserve the existing safety boundary; file lifecycle transactions and
code-action resolution need their own implementation before those operations are enabled.

## Diagnostic evidence

`LspManager.DiagnosticEvidence` carries generation and protocol version. The window adapter also
compares live text with sent text, covering edits still waiting for debounced `didChange`.

| Freshness | Meaning |
| --- | --- |
| `UNAVAILABLE` | No managed server for the file. |
| `PENDING` | No accepted diagnostic evidence for this server/document lifecycle. |
| `UNKNOWN` | Unversioned push diagnostics; current-revision validity cannot be proven. |
| `CURRENT` | Accepted version matches the synchronized live document. |
| `STALE` | Protocol version or live text differs from the diagnostic source. |

Versioned push and accepted pull results advance evidence. Close/reopen and server restart cannot
reuse the old evidence. A stale result cannot satisfy verification. The completion gate still
requires a recognized successful build/test/check, matching saved revisions/bytes, and no observed
errors. Missing, pending or unknown LSP evidence is labeled as such; a successful build can provide
validation independently of LSP. Neither an empty diagnostic list nor an exit-zero inspection
command is promoted to a clean build.

## Progressive context and guidance

`editor_context` exposes active path, language, whole-document cursor coordinates, bounded selection,
narrowing, dirty state, active diagnostic count/freshness, workspace and up to 128 open-file metadata
entries. No inactive document bodies are added. The optional initial context uses this observation
instead of the old active-buffer prompt dump.

`AgentContextRanker` owns explainable weights and stable tie-breaking. A bounded session index merges
semantic locations, text-search matches, inspected files and recent agent edits with fresh active,
selected and unsaved flags. `editor_context` returns the top 12 paths with their signals and scores.
Newly created agent files also receive the recent-edit signal. Phase 3 found that omitting it left a
new regression test below incidental context; the existing weights themselves were retained.
Scores measure relevance, never trust. Earlier discovery signals are memory, not current proof of
a relationship. The index is limited to 256 paths. Current-symbol and Git-change signals are extension
points; automatic imports, test relationships, enclosing-symbol ranking, displayed diffs and run
configuration context are not yet wired. Agents can retrieve symbols, references, Git status and
targeted source through the existing tools.

`project_instructions(path)` walks `AGENTS.md` from workspace root toward the target directory. Every
entry carries source, scope, applicable path, trust and precedence. Closer scopes refine coding
conventions; they cannot override the user goal or tool policy. Discovery is confined to the workspace,
at most 32 scopes and 12,000 text characters total, with at most 4,000 per file. Space is reserved for
every applicable scope so large parent instructions cannot hide the closest guidance. Truncation
is explicit in the text; exact file ranges can be read when necessary.

`list_skills` discovers names and provenance; `read_skill(id)` retrieves up to 8,000 characters:

| Source | Location / ids | Provenance |
| --- | --- | --- |
| Built-in | `builtin:fix_bug`, `feature`, `review`, `tests` | `APPLICATION_GUIDANCE` |
| User | `<config-dir>/agent-skills/<name>/SKILL.md`, `user:<name>` | `USER_CONFIGURATION` |
| Repository | `.editora/skills/<name>/SKILL.md`, `repository:<name>` | `WORKSPACE_CONTENT` |

Names use letters, digits, underscores and hyphens. Discovery limits each file-based source to 64
entries and rejects symlink skill targets. Skills are workflow text, not executable plugins or a
permission mechanism. Repository instructions and tool results remain untrusted data.

## Outbound MCP

Settings → AI Agent → MCP, or palette command `agent.manageMcp`, edits up to eight configured servers
(id, executable/arguments, enabled flag) and displays connection state, exposed tool names and failures.
Configuration is global; connections and discovered catalogs belong to a native session. Start a new
session after changing configuration. Schema 106 adds an empty server list without replacing existing
agent/provider choices. The expanded native catalog requires a minimum context budget of 32,768.

`AgentMcpManager` owns startup, initialize/initialized, discovery, health, reconnect and shutdown.
`StdioMcpConnection` implements bounded UTF-8 newline JSON-RPC with a dedicated writer, virtual readers,
request deadlines and process-tree cleanup. It follows the MCP
[stdio transport](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports) and
[initialization lifecycle](https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle).
It negotiates 2025-06-18 and accepts compatible 2025-03-26/2024-11-05 responses. HTTP transport,
resources/prompts, sampling, elicitation and credential/environment profiles are not implemented.
Only server ping is answered; unsupported server-initiated requests fail explicitly.

Discovery accepts at most 32 tools and 32 catalog pages per server, rejects duplicate names/cursors,
and preserves deterministic catalog order. Tool ids contain server identity, a readable name prefix
and a stable hash. Descriptions, schemas and results retain external origin. JSON schema supports
objects/arrays/scalars, required/additional properties, enum/const, bounds, local references and
anyOf/oneOf/allOf. Unsupported validation keywords fail before permission/execution rather than being
silently ignored; arbitrary remote references and regex constraints are not evaluated. Output schemas
are metadata, not a claim that arbitrary server output has been validated.

Enabling a configured server authorizes launching that executable for discovery. The UI explains
that it is a trusted external program. Every exposed tool remains `EXTERNAL` and requires approval in
every trust mode, regardless of advisory MCP annotations. Like approved validation commands, these
processes are not OS-sandboxed. They use a reduced inherited environment and no implicit shell.

Cancellation or timeout closes the connection and kills its process tree. An interrupted call stops
the agent turn with uncertain effects; it is **never automatically replayed**. A subsequent approved
call can reconnect and rediscover, but executes only if its descriptor still matches the originally
approved tool. Changed catalogs require a new session. Failed setup closes owned resources. Stderr
is drained without retaining potentially sensitive server text; errors disclose protocol stage/state,
not raw payloads. Large catalogs may exceed a configured model context; the runtime reports that
limit instead of silently removing native tools. A future progressive catalog can improve this UX.

## Durable sessions and compaction

`AgentSessionStore` checkpoints completed, failed or cancelled turns to
`<config-dir>/native-agent-sessions/<uuid>.json`. Schema 1 stores goal, workspace identity, provider/model,
complete protocol exchanges, bounded history summaries, plan, inspected/changed file hashes and prior
validation description. Writes use `ConfigWriter.writeAtomic` and owner-only permissions on POSIX.
Checkpoints have size limits; corrupt and future-version files are ignored and preserved.

The existing Resume Session picker now lists native checkpoints when the built-in agent is selected.
Selecting one prepares it for the next user prompt. Resume uses the current provider configuration,
requires the same canonical workspace, detects changed/unavailable file hashes and explicitly labels
historical observations. Permissions reset to Ask. Document identity/revision leases, proposals,
successful-validation authority, endpoints, API keys and MCP connections are never restored from
history. Conversation tool arguments remain historical data and cannot execute without a new call.

An outstanding verification obligation **is** persisted: an interrupted edit cannot evade its gate by
restarting. Previously modified files must be reread before validation. Matching checkpoint contents
receive fresh buffer leases and require a new build/check; differing contents remain pending until
explicitly reconciled through document tools. Reading newer user content never adopts it into
automatic saves. The original pre-edit diff is not reconstructed after restart and is labeled
unavailable; inspect Git/history for that comparison.

This is turn-boundary checkpointing, not crash recovery during a tool operation. Transcript/tool text
can contain source or sensitive user-provided content; checkpoints are local and are not encrypted.
Delete their JSON files to remove saved history. No automatic retention/deletion UI is provided yet.
The picker scans up to 200 files and shows the newest 30 among those. Saved-byte hashing can
conservatively flag non-UTF-8 files because inspected text is hashed as UTF-8.

`AgentContext` evicts whole exchanges, preserves user turns, and retains at most eight bounded memory
entries quoting recorded attempts/results or explicitly unverified assistant statements. It never
invents observations or elevates summaries to system instructions. Fresh rereads win. Plan/file state
also lives outside transcript compaction. All memory participates in budgeting and can itself be
discarded under pressure; enormous retained user goals still produce a clear context limit.

## Provider and token accounting

`AgentModel.Capabilities` distinguishes supported, unsupported and unknown advanced features: parallel
calls, reasoning, structured output, usage, prompt caching and tokenizer availability. HTTP adapters
only promote usage/multiple-call support after observing it; local servers are not assigned imaginary
capabilities. `agent_model` exposes those flags and the configured context/output limits. This phase
does not enable provider-specific reasoning, caching or structured-output request options.

`AgentTokens.Counter` is an adapter extension point carrying `EXACT`, `PROVIDER_REPORTED`,
`TOKENIZER_ESTIMATED` or `HEURISTIC` provenance. Context construction uses that counter, including
schemas, history and summaries, plus framing/output reserves. The supplied fallback is conservative
UTF-8 byte counting and reports `HEURISTIC`; no provider tokenizer is bundled. Reported response usage
does not imply exact token counts for a future request.

## Validation and follow-up

Deterministic tests cover supported/absent LSP capabilities, raw future cancellation, changed servers,
stale semantic responses, diagnostic generations and unsent changes, UTF-16/overlap/version conflicts,
unseen/resource edit refusal, hierarchical precedence/budgets, skill provenance, ranking, saved-session
revalidation, schema subsets, MCP discovery/reconnect/no-replay, malformed frames, and a real local
stdio fixture interrupted after receiving a request. A scripted acceptance test follows semantic
references, previews a multi-file rename, commits/saves, observes a failed check, fixes a test,
revalidates, reviews the diff and completes. No normal test requires a live model or installed MCP server.

The [Phase 3 evaluation](../evaluations/agent-phase3.md) exercised real local models and JDT LS,
including native documents and independent outcome checks. It exposed background-document LSP
ownership and rename-format bugs, now fixed without relaxing stale-edit or diagnostic gates.
A desktop usability trial and broader model/repository coverage remain open.

Remaining priorities:

1. Expand live language-server/model coverage and perform a desktop usability trial; deterministic
   protocol tests alone do not establish autonomous success on real repositories.
2. Add safely prepared resource operations and resolved code actions, then broaden diagnostic evidence
   and live context ranking across imports, tests, Git, run configurations and enclosing symbols.
3. Add progressive MCP catalog admission, HTTP/auth profiles and actionable health diagnostics without
   leaking secrets. Unify ACP/inbound MCP mutations behind the native document boundary.
4. Add session retention controls and stronger structured memory while retaining authority reset;
   implement tested provider tokenizers and negotiated advanced request features.
5. Introduce OS-isolated command execution and remote revision contracts before broader unattended
   trust. Evaluate read-only exploration workers only after useful independent read workloads exist.
   Concurrent writing subagents remain out of scope.
