# Phase 3 live coding evaluation — 2026-09-20

This report records exploratory runs on the development host, not a statistically controlled product
comparison. The [21 original metric reports with separate review annotations](agent-phase3-data.json)
preserve unsuccessful runs and earlier scoring mistakes. The [methodology and commands](../subsystems/agent-evaluation.md) describe the checked-in
opt-in harness. Deterministic tests and real-model outcomes are deliberately separate.

## Environment and baseline

The available LM Studio server supplied Qwen3 Coder Next (Q4_K_M, 80B) and Gemma 4 12B QAT (Q4_0).
Tests use the real LM Studio and OpenAI-compatible provider paths against that local server. This is
not a comparison with OpenAI-hosted inference. Anthropic/cloud credentials were unavailable; their
wire adapters remain covered by deterministic tests, not by a live comparison here. Credentials and
user configuration were not changed. Local server model loading is part of cold-run cost.

The agent context budget was 65,536 conservative byte-based token estimates, with 4,096 output tokens;
the observed loaded server contexts were 16,384 actual tokens for Qwen and 154,368 for Gemma. These
are different units and the adapter does not negotiate a tokenizer or discover the loaded server's context automatically. Actual
token usage was not returned. JDK 25, real JavaFX document ownership and the installed JDT LS were used.
Initial tasks had a 24-iteration/six-minute limit. Per-task disposable workspaces isolate edits from
the developer checkout, but are not OS security sandboxes. Server sampling defaults were retained;
these are single stochastic trials across incremental working-tree revisions, not paired seeded runs.
Prompt/catalog fingerprints appear in later reports; the labels and narrative identify earlier variants.

| Trial | Outcome | Iterations / executed tools | Elapsed including oracle | Finding |
| --- | --- | --- | --- | --- |
| Qwen baseline, Editora endpoint bug | LIMIT; oracle failed | 24 / 12 | 132 s | Could not discover tests efficiently; 12 denied commands. |
| Qwen instrumented baseline, endpoint | LIMIT; oracle failed | 24 / 10 | 109 s | 15 denials; repeated command attempts consumed the turn. |
| Qwen baseline, API refactor | Correct source and passing oracle | 20 / 21 | 139 s | Six plan updates, separate edits, attempted tests before save. Initial scorer misattributed JDT LS metadata and reported FAIL. |
| Qwen first refinement, endpoint | NEEDS_INPUT; oracle passed | 16 / 13 | 298 s | Filename discovery found tests and edits fixed behavior; harness consent rejected validation, so runtime correctly refused completion. |
| Qwen first refinement, API refactor | PASS | 20 / 18 | 151 s | Used live references and one three-file edit batch; semantic previews failed, then text fallback and Maven validation succeeded. |

Do not interpret the first refinement as a speedup. Qwen response latency varied sharply, and both
product and harness changed: active-file selection was repaired, JDT LS-generated metadata was
separated from source edits, rejected-call measurement was added, and consent was later corrected to
accept an absolute cwd identifying the fixture root. Those fixes are disclosed rather than silently
rewriting the raw baseline. The original endpoint trials and the first refinement also used stricter
Maven argument consent than later runs. No harness denial is claimed as an actual human rejection.

A preliminary Gemma understanding run made 17 reads of the same first page before requesting a larger
window and completing. Its report failed to serialize the newly added catalog fingerprint because
Jackson had no Duration module. That harness error was fixed by hashing the stable descriptor text;
the trial is excluded from completed outcome counts. Its tool observations motivated explicit read
paging and a bounded unchanged-read recovery guard.

## Expanded corpus and measured refinements

The seven-scenario Gemma run used the OpenAI-compatible path, 28 iterations and the same 4,096-token
output allowance. Times below exclude fixture preparation and the independent oracle.

| Scenario | Outcome before the last fixes | Agent seconds | Iterations / requested calls |
| --- | --- | --- | --- |
| Full Editora save explanation | REVIEW_REQUIRED; source review found important omissions | 30.7 | 3 / 2 |
| Real Editora endpoint regression | FAILED at streaming envelope/content bound | 92.1 | 4 / 3 |
| Invoice quantity bug | NEEDS_INPUT at output-token limit | 83.3 | 10 / 9 |
| API refactoring | PASS, independent API and behavior oracle | 47.0 | 20 / 18 |
| Multi-layer item-count feature | NEEDS_INPUT despite passing independent oracle | 85.6 | 16 / 14 |
| Regression tests | PASS; new tests also killed the quantity mutant | 33.8 | 13 / 11 |
| Documentation consistency | REVIEW_REQUIRED; source review confirmed cents/quantity correction | 49.6 | 16 / 13 |

The feature exposed a production defect: background Ledger edits did not acquire deferred LSP
ownership, so Invoice diagnostics still said the new method was absent after Maven passed. After
attaching edited/created buffers before LSP synchronization, the same task completed, passed the
oracle and all three tests in 71.5 seconds (16 iterations / 14 requested calls). The verifier was not
weakened to disregard errors. The harness also correctly permits a new focused `InvoiceTest.java`
instead of demanding an unnecessary edit to `LedgerTest.java`; that scope correction alone would
not have changed the earlier NEEDS_INPUT outcome.

Separating SSE envelope overhead from retained content changed the Gemma endpoint failure into an
accurately reported output-token limit (86.6 seconds); it **did not make that model complete the task**.
The fixed 4,096-token production allowance and lack of reasoning/profile negotiation remain problems.
Qwen's final endpoint rerun completed and passed the independent oracle in 14 iterations / 13 requested
calls (197.7 agent seconds), versus two 24-iteration unsuccessful baselines. The successful run still
contained one denied command and one premature validation attempt; no zero-friction claim is made.

Paging removed the long same-page loop in the understanding task, but source review matters more than
speed. The first two-read explanation incorrectly suggested `begin` blocks on the write lock and did
not trace actual callers. Stronger general instructions to trace callers produced an eight-read
explanation mentioning `FileWorkflowCoordinator`, but it still omitted the final commit predicate and
atomic writer. Neither explanation is counted as a correct autonomous solution. These were agent
source reviews, not a human usability study.

The final Qwen refactor isolated two independent problems: guessed UTF-16 positions and a legitimate
JDT LS `WorkspaceEdit` with an empty unused form. Tool guidance now recommends obtaining selection
ranges from document symbols. The normalizer accepts an empty unused form, while still rejecting two
populated forms, unseen targets, stale versions, resource operations and annotations requiring consent.
The affected rerun passed through `semantic_prepare` and `semantic_apply`, saved all three files, ran
Maven and passed the independent API/behavior oracle: 15 rounds / 18 calls, 159.8 agent seconds, one
command approval and no denials. The immediately preceding run used 21 rounds / 24 calls and text
fallback (189.5 seconds). The corrected run first requested a rename at an unhelpful position, received
no changes, then obtained symbols and recovered; guidance improves discovery without guaranteeing
perfect first-call selection. These single runs establish the repaired path, not statistical speedups.

## Trust-mode trials

All approval decisions below are bounded harness consent, not human interruptions measured in a
usability study. The same production permission policy runs in all modes.

| Gemma task / trust mode | Outcome | Approval requests / denied | Agent seconds |
| --- | --- | --- | --- |
| Feature / Workspace, after LSP fix | PASS | 2 / 1 | 71.5 |
| Feature / Ask | PASS | 6 / 1 | 71.1 |
| Regression tests / Workspace | PASS, quantity mutant caught | 2 / 0 | 33.8 |
| Regression tests / Ask | PASS, quantity mutant caught | 4 / 1 | 30.9 |
| Regression tests / Agent | PASS, quantity mutant caught | 1 / 0 | 43.3 |

The Ask feature run approved two edits, one creation, one save and a valid Maven command, while
rejecting a different executable. Ask testing added edit/save approvals. Agent mode retains external
execution approval because it has no OS sandbox; its workspace-write policy currently matches
Workspace. It recovered from an attempted `create_file` on an existing test by rereading and editing.
Different model decisions and warm-up costs prevent attributing elapsed-time differences to trust mode.
The new test ranked third after the Ask feature run (RECENTLY_EDITED, score 35), above a merely
inspected source file (score 25). This verifies candidate metadata, not a causal quality improvement.

## Context ranking and model differences

Metadata snapshots showed that the active file ranked first and the refactor's three changed source/
test files occupied the first three positions after discovery. However, a newly created regression
test had score zero; creation now records the existing RECENTLY_EDITED signal. Broad search also
returned 15 KB of JSON, which the runtime cut mid-object: native search now bounds complete records.
The controlled projects lack a gitignore, so generated test-report/class paths appear below source
in some rankings. This is a corpus limitation, not evidence for inventing new ranking weights.

Gemma used mostly single calls and text edits (including semantic exploration but no successful
semantic apply); Qwen issued multiple independent reads and attempted
semantic navigation/refactoring, but also submitted regex-like patterns to literal search and tried
validation before saving. Actual semantic use, model latency and output-limit behavior differed much
more than native read latency. No cross-provider quality claim follows from two local models and one
server. Broader ranking signals and parallel read scheduling were not added without evidence.

## Performance and UX interpretation

File/tool execution was typically milliseconds; model responses were seconds, occasionally tens of
seconds. The baseline refactor's measurable multi-read batch was 24 + 8 ms, giving only 8 ms of ideal
parallel savings. No speculative parallel scheduler was retained. The runtime still accepts multiple
calls per model response, preserves order and serializes edits.

Across the 21 retained reports, handler timings (including failed executions, excluding rejected calls
that never entered a handler) were:

| Tool | Samples | Median ms | Maximum ms |
| --- | --- | --- | --- |
| `read_file` | 80 | 3.5 | 73 |
| `find_files` | 18 | 1 | 4 |
| `search_text` | 18 | 4 | 377 |
| `semantic_query` | 10 | 7 | 55 |
| `semantic_prepare` | 9 | 46 | 226 |
| `run_command` | 18 | 1260 | 1389 |

The largest recorded FX queue delay was 35 ms and transcript control update was 2 ms. These are
headless measurements, not visible rendering latency. The largest later estimate for ideal read
parallelism was 10 ms per entire task; earlier trials lacked that report field. Actual inference token
usage was unavailable, so request bytes and first text-delta timing must not be presented as exact
tokens or universal time to first token.

Native calls previously produced a progress line plus a result pane, with a TextArea allocated even
for collapsed results. They now share one entry and construct details on expansion. Headless tests
check this behavior and transcript reset. Permission dialogs now explain why edits or external
execution need approval, and reviews show relative paths, dirty state and subsequent changes. These
are implementation/FX observations; no claim of a completed human desktop usability study is made.

## Build and regression validation

`mvn clean verify` passed on JDK 25: **4,998 tests, zero failures/errors, 26 skipped**. The live evaluation
test was skipped before credential access or inference. Compilation, modular jar packaging, coverage
thresholds and Spotless checks passed. The live reports were archived before `clean` removed `target`.
Deterministic additions cover read paging/progress recovery, permission-denial continuation, bounded
structured search, created-file ranking, deferred LSP ownership, rename edit normalization, streaming
content/envelope limits, output-limit continuation, lazy transcript rows and evaluation outcome rules.
`git diff --check` and the benchmark launcher's shell syntax check passed. The main checkout stayed
on `master` and clean; implementation remains in the task worktree.

## Remaining barriers to daily use

1. Model profiles and reliable recovery: the fixed output allowance can exhaust a small local model
   even on a localized fix, and byte estimates are not negotiated server context limits. Tool-trained
   local models can also repeat discovery, ignore
   semantic capabilities or claim completion with incomplete exploration. Paging and loop guards
   prevent some waste but do not manufacture correct reasoning.
2. Semantic interoperability and readiness: reference queries worked with real JDT LS, and a live
   rename format incompatibility was fixed. Position selection, cold startup,
   asynchronous diagnostics and broader server/version coverage still need real stress trials.
3. Trust and execution: approved commands are not OS-isolated. Real human approval frequency and
   remote-provider behavior have not been measured. Bounded harness auto-consent is not unattended
   execution policy for arbitrary repositories.
4. Validation depth: independent oracles catch false completion, but most controlled tasks are small.
   Larger concurrency, UI/settings and multi-layer tasks need independently reviewed outcomes and
   repeat runs before claiming broad autonomous reliability.
5. Context and continuity: ranking cannot discover files absent from its candidate pool. Current
   active/inspected/semantic signals need measurements on harder tasks before adding speculative
   weights. Long sessions, realistic intervention and changing user buffers need live stress trials.

Phase 4 should prioritize reproducible model/server profiles, LSP interoperability fixtures, isolated
validation, a richer real-project corpus and human review of task outcomes/permissions. Adding writing
subagents would increase risk before these reliability questions are settled.
