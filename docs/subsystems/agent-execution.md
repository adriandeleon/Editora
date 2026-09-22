# Agent execution strategy

`AgentExecution` is a bounded, session-thread observer beside `AgentRuntime`. It does not replace
task acceptance, tool policy, editor revisions or final verification. It uses tool arguments/results
and direct native acceptance facts to produce an execution phase and a compact working-state JSON
object. The object is rebuilt outside transcript compaction, so known files and pending work do not
depend on retaining every raw tool result. Current execution state and acceptance reminders are
budgeted transient observations at the request tail. They are not saved in raw history or appended
to the system prompt, preserving its stable request prefix even when compaction runs. Compaction
notices and historical-memory framing are explicitly included in context accounting. Repository paths and external results remain untrusted
data; neither a tool result nor a model strategy declaration can mint acceptance evidence.

## Progress and phases

The observer distinguishes orientation, investigation, implementation, validation, acceptance and
completion. These are guidance, not a mandatory order. New read ranges/revisions, discovered files,
scoped semantic items, edits, saves, validation attempts and changed requirement states can indicate
progress. Native edits compare before/after text; unchanged replacements report `changed=false`
and cannot reset progress or mint change evidence. A no-op still passes normal revision validation;
unchanged revisions preserve current native validation. Successful opaque plugin/MCP results receive bounded digest-based novelty tracking,
without creating native files or evidence. Repeated acceptance queries, plan wording and identical validation requests at the same
mutation generation do not. A first validation attempt is a useful action, not proof that tests
passed: only native validation evidence establishes that.

Known-file state retains at most 64 paths and 32 merged read ranges per file. File hints and
observation digests evict old entries, so cache capacity cannot stop new useful discovery. The model sees a
smaller list with inspected files first, recent tool families, changed files, fresh validation facts,
pending requirements and suggested tools. Source bodies, environment variables, command output and
model reasoning are not stored in this summary. It retains no current authority after restart:
restored read hints are explicitly historical and must be reacquired.

After two rounds without new observed state, guidance reports `NO_PROGRESS`. At four it recommends
another tool family. At six, further tool execution requires `execution_control(action=replan)` or
a final response. A replan needs a reason and an advertised next tool; it grants a short grace
window, not progress or permission. Two unsuccessful replans are allowed. Nine idle rounds without
an active grace window return `NEEDS_INPUT`, preserving the session and paired tool protocol.
Useful new observations reset the idle counter; normal hard iteration/call limits still apply.

Plan completion has no authority over requirements. Marking every plan step complete while native
requirements remain pending produces a plan warning. Edits or new evidence can move execution
back to investigation. Discovery saturation only suggests implementation after multiple inspected
files and a recognized mutation request; it never forces a write.

## Completion and budgets

`COMPLETION_READY` requires satisfied recognized requirements, empty evidence debt and successful
native verification when changes exist. It includes current changed-file and validation facts.
The final response still runs normal verification and acceptance. Two subsequent discovery, inspection or evidence
lookup rounds require completion or `execution_control(action=reopen)` with an explanation. A
reopen does not erase evidence, lower requirements or grant permissions. Completion claims remain
available, and new mutations invalidate readiness.

`AgentRuntime.Limits` optionally carries a whole-turn deadline. Each model/tool/approval operation
uses the smaller of its own timeout and the remaining turn time. Expiry cancels the active child
operation and returns a resumable `LIMIT`, never completion. Budget state appears only in the last
four iterations or last 90 seconds. Existing interactive configurations retain their iteration and
operation limits; the evaluation harness supplies a turn deadline.

## Tool affordances

`search_text` now accepts `mode: LITERAL | REGEX`, defaulting to literal for compatible callers.
Regex syntax is checked before workspace reads. Matching reuses Editora's search engine and its
interruptible regex budget; a budget abort is reported as truncated, not as absence. Regex results
can guide discovery but cannot satisfy the literal no-reference acceptance predicate.

`read_file` accepts `context_before` and `context_after` up to 50 lines each, within the existing
200-line/6,000-character total bound. Results report the actual start/end lines and the originally
requested line. LSP positions remain zero-based; read/search lines remain one-based.

Tool names and availability remain stable. The full catalog is retained: these trials do not yet
establish that hiding editing, validation or MCP tools improves behavior, and doing so could obstruct
legitimate workflows. Phase suggestions identify useful families without withholding capabilities.
A semantic trace helper is deferred until round-level evidence justifies a new composite tool.

## Evaluation and limitations

`AgentExecutionCorpus` freezes seven task definitions and independent fixture/oracle source hashes.
The harness verifies the manifest before a versioned trial. Changes require a new corpus version.
`agent-trajectory-report.py` classifies rounds from actual tool activity, preserving mixed activities
and unknown historical alignment. Native progress events distinguish redundant operations from new
ranges or revisions. A candidate final is not counted as successful task completion.

The observer cannot prove relevance from a newly discovered file or semantic item. New unrelated
files can still look like progress, and cycles beyond the bounded cache window may evade the soft
guard. The hard iteration/call limits remain authoritative. It cannot prove explanation
quality or the quality of a regression test. Guidance effectiveness must therefore be measured
with independent oracles and human review, separately from deterministic runtime correctness.

See [the Phase 7 report](../evaluations/agent-phase7.md) for observed results and remaining work.

## Catalog review and deferred options

| Tools | Selection contract and Phase 7 decision |
|---|---|
| `editor_context`, `open_editors` | Metadata/ranked candidates; inspect source with bounded reads. Repeated identical metadata does not reset the guard. |
| `list_files`, `find_files` | Directory paging versus literal filename fragments. Keep separate; filename search avoids repeated directory walking. |
| `read_file` | Live revision, 1-based paging, bounded surrounding context; LSP positions are explicitly 0-based. |
| `search_text` | Explicit literal/regex mode, preflight syntax errors, truncation on regex timeout. Regex cannot certify literal absence. |
| `semantic_capabilities`, `semantic_query` | Discover supported operations, obtain exact symbol positions, then query. Keep fallback text tools available when LSP is unavailable. |
| `semantic_prepare`, `semantic_apply` | Preview/token/application separation; affected files must be inspected and revisions current. |
| `apply_edits`, `create_file`, `save_files` | Undoable document mutations followed by explicit persistence; stale edits and newer user changes remain rejected. |
| `validation_profiles`, `run_validation`, `run_command` | Discover fixed build operations; prefer targeted validation after saving. Commands retain argv, timeout and centralized approval. |
| `diagnostics`, `review_changes` | Current diagnostics/diffs are observations, not automatic test or completion evidence. |
| `update_plan`, `get_plan` | Session plan, with pending-requirement warning; wording/status changes alone do not count as progress. |
| `task_contract`, `task_evidence`, `completion_claims` | Requirements/debt, paged evidence identities, then optional grounded claims. A final response need not page every evidence item. |
| `execution_control` | Inspect, replan or reopen with reason and advertised next tool; never changes trust or acceptance authority. |
| `project_instructions`, `list_skills`, `read_skill`, `agent_model` | Provenance-aware guidance/capability discovery; useful on demand, not mandatory every round. |
| Audited MCP `document_symbols`, `git_status`; configured external MCP | Existing discovery/policy remains. Opaque result novelty is guidance only; external annotations cannot lower permissions. |

No catalog names were migrated and no tools hidden. A progressive-catalog A/B trial remains unmeasured;
these runs cannot establish a benefit for it. A composed semantic trace tool is also deferred. The
current state shows inspected ranges but cannot establish that arbitrary important callers or
persistence boundaries have all been understood. Investigation quality therefore remains an oracle/
human-review concern rather than a fabricated completeness score.
