# Autonomous coding evaluation

Phase 3 evaluates the [native agent](agent-platform.md) using real HTTP models, production editor
documents, an actual JavaFX window and optionally an installed JDT LS. It is separate from the
deterministic runtime, protocol, document and LSP tests. Ordinary `mvn verify` never enables live inference.

## Running the benchmark

Use the project's normal JDK 25 and Maven environment. Start a configured tool-capable model server,
then run from the repository root:

```sh
AGENT_EVAL_MODEL=qwen/qwen3-coder-next scripts/probes/agent-evaluation.sh \
  -Dagent.eval.tasks=editora-endpoint-bug,ledger-refactor \
  -Dagent.eval.jdtls=/absolute/path/to/jdtls \
  -Dagent.eval.label=local-baseline
```

The script calls `mvn test -Dtest=AgentCodingEvaluationTest -Dagent.eval=true`. Without that explicit
flag the JUnit test skips before reading credentials, opening a window or contacting a model.
Reports go to `target/agent-evaluations/` with unique run names. Scratch projects and final answers are
retained in the temporary directory printed per scenario, for independent review. Remove those
directories when review is complete. Do not run evaluation concurrently with another Maven build in
the same checkout. Real inference may incur costs for a configured remote provider.

| Property | Default / meaning |
| --- | --- |
| `agent.eval.model` | Required explicit model id. |
| `agent.eval.provider` | `lmstudio`; uses the production `AiProvider` and `HttpAgentModel` adapters. |
| `agent.eval.endpoint` | Normal provider default; optional explicit endpoint. |
| `agent.eval.keyEnv` | Environment variable name, default `ANTHROPIC_API_KEY` or `OPENAI_API_KEY`. Never put a key in Maven arguments. |
| `agent.eval.context` | 65536, runtime's conservative context budget; no model tokenizer is assumed. Output allowance is 4096, matching the current native adapter profile. |
| `agent.eval.serverContext` | Optional separately observed loaded server context limit, 0 means unknown. |
| `agent.eval.iterations` | 32; tool-call limit is four times this value. |
| `agent.eval.minutes` | 12 per task; individual model requests have a three-minute deadline. |
| `agent.eval.jdtls` | Optional installed executable. Cold startup and recovery are included. |
| `agent.eval.trust` | `WORKSPACE`; `ASK` and `AGENT` exercise the same production policy. Read-only tasks always use Ask. |
| `agent.eval.label` | Run label; use distinct labels for baseline and refinements. |

## Scenarios and independent checks

`AgentEvaluationCases` is the benchmark catalog. Every task declares its goal, initial editor file,
allowed/required changed paths, semantic expectation and an advisory tool-call budget. Expected
outcomes concern observable behavior, never exact assistant text.

| Task | Repository | Independent outcome |
| --- | --- | --- |
| `editora-save-understanding` | Full current Editora source/docs snapshot | No edits; reviewer checks callers, queued versus running writes and atomic commit explanation. |
| `editora-endpoint-bug` | Actual Editora endpoint code and tests in a small Maven project | Seeded trailing-slash regression fixed; existing tests and a separate endpoint oracle pass. |
| `ledger-bug` | Controlled Java invoice project | Quantity multiplication, multiple lines and empty totals correct. |
| `ledger-refactor` | Controlled Java invoice project | New API exists, old API absent, all callers/tests migrated, totals unchanged. |
| `ledger-feature` | Controlled Java invoice project | Both layers expose item count, multiple quantities and empty input work, pricing unchanged. |
| `ledger-tests` | Controlled Java invoice project | Tests pass, production untouched, and tests kill an independently injected quantity mutant. |
| `ledger-docs` | Controlled Java invoice project | Only README changes, tests pass; reviewer checks units and quantity semantics. |

The full snapshot includes current uncommitted source, not the user's Git data, editor settings or
credentials. Controlled projects make expected behavior known; they do not represent the complexity
of a large repository. The endpoint scenario uses real code but is explicitly reported as a reduced
component, not a whole-Editora bug fix. Additional concurrency, UI/settings and multi-layer tasks still
need independently reviewed specifications.

Preparation and oracles write only disposable fixtures. All **model-directed** reads, edits, saves,
semantic queries and validation go through production tools and document/version APIs. The oracle
runs outside the model workspace after execution and independently runs Maven and Java checks.
`COMPLETED` alone is insufficient: missing required edits, unrelated edits or failed oracles fail the
task. Explanations and documentation remain `REVIEW_REQUIRED`, even when mechanical checks pass.

The harness supplies bounded automatic consent for document edits and specific Maven test argv in
the fixture root; shell commands and arbitrary options are denied. This is labeled harness consent,
not a study of human approval behavior. Commands and JDT LS are external programs, without OS
sandboxing. Only evaluate trusted repository snapshots on the host. Known newly generated JDT LS
metadata is recorded separately; edits to pre-existing metadata remain unexpected changes. Attribution
of new metadata is a harness convention, not proof that every such file was written by JDT LS.

## Measurements and interpretation

`AgentEvaluation` wraps the real model, tool registry, permission callback, runtime events and verifier.
It records requested versus executed calls, protocol/permission failures before handler execution,
file paths, semantic operations, command families, response sizes, timings, repeated calls, trust mode,
completion state, test oracle results and changed-file boundaries. Later reports also retain context
rankings, prompt/catalog hashes and an upper bound on time saved by ideal parallel reads.

No prompts, source bodies, argument values, raw tool errors, environment or credentials enter the
aggregate JSON or evaluation log. The final answer stays only in the isolated scratch directory for
review. Paths are workspace-relative. `requestBytes` and `catalogBytes` are UTF-8 size estimates,
**not token usage**; actual usage is separately reported when provided by the server. A zero usage
count with `usageAvailable=false` means unavailable. `firstTextDeltaMs` excludes tool-only deltas and
is not a universal time-to-first-token measurement.

Agent and independent-oracle durations are separate in later reports. `panelUpdateMaxMs` measures
control construction/update on FX, not GPU rendering or layout of a visible transcript;
`fxDispatchMaxMs` measures queue delay. Earlier `panelRenderMaxMs` has the same construction-only
limitation. Headless FX checks do not replace a human desktop UX trial.

Failure categories include permission denial, invalid/missing arguments, unknown tools, stale context,
semantic unavailability, validation failure, incorrect outcome, unrelated edits, context/iteration
exhaustion and provider/timeouts. Repeated calls need interpretation: a reread after editing can be
correct. Semantic capability inspection is counted separately in tool metadata from an actual query;
a successful task that never uses semantic navigation is not evidence of LSP-assisted coding.

## Refinements made from observed behavior

- Added bounded `find_files` using the same workspace/symlink/credential and Gitignore rules as text
  search. It returns compact literal filename matches without opening documents. This avoids directory
  ladders and requests for shell `find` merely to locate a test.
- Search result JSON now respects a character budget before serialization, instead of relying on
  runtime truncation that could cut a large match list midway through JSON. Newly created files
  receive the same recently-edited ranking signal as modified files.
- Clarified literal text versus filename discovery, semantic startup recovery, independent read/edit
  batching, milestone planning and save-before-test ordering. Corrected the system prompt's inaccurate
  suggestion that `save_files` accepts revision arguments; it takes `{}` and uses retained edit leases.
- Three consecutive permission denials now stop with `NEEDS_INPUT`. Unexecuted calls receive paired
  observations, history remains resumable, and no policy or permission is relaxed.
- File reads expose total/end/next lines and truncation. Character limits preserve whole lines where
  possible; an oversized single line is explicitly flagged. Three identical unchanged reads produce
  a recovery observation; six stop with `NEEDS_INPUT`. Changed results reset the guard, and another
  user turn can resume the same session.
- Native progress and result share one collapsed transcript row; the text control is allocated only
  when expanded. ACP shell lines retain their existing behavior.
- Permission dialogs explain the action's risk and why approval is needed, including the actual lack
  of OS sandboxing for external execution, in all six languages. Existing exact argument inspection
  remains available.
- Change reviews identify workspace-relative files, saved/unsaved state and edits since the agent's
  last revision, while reusing Editora's existing diff windows and undo history.
- Agent commits attach deferred background documents to LSP before flushing edits; new documents
  also acquire that ownership. This fixes a live feature task where Maven passed but callers still
  saw the old API on the server. The diagnostic verification gate remains strict.
- JDT LS rename edits may contain an empty unused `WorkspaceEdit` form. Normalization now accepts
  that representation but still rejects two populated forms and preserves target/version/consent checks.
  Semantic guidance recommends symbol selection ranges before position-sensitive queries.
- Streaming envelopes and retained response content have separate bounds (16 million and one
  million characters respectively). Repeated SSE metadata no longer exhausts the content allowance;
  text, ids and tool arguments still share the content bound. Partial tool streams remain unexecutable,
  and output-token exhaustion now explains how to retry.

Read tools remain serial. In the initial refactor, the only nonzero two-read batch took 24 + 8 ms;
perfect parallelism saves at most 8 ms in a roughly 139-second task. Read calls share FX/LSP ownership,
and several `READ` tools also update plans, previews or discovery state. There is no measured reason
to add a scheduler for all of them. Independent model-requested batches already work; reducing model
round trips matters more here.

See the [recorded Phase 3 results](../evaluations/agent-phase3.md) for live observations, comparison
limitations and priorities. This benchmark is a diagnostic instrument, not a product leaderboard.
