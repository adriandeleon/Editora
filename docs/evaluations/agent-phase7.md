# Phase 7: execution intelligence and real-task reliability

Phase 7 adds bounded execution guidance, explicit search intent, completion-loop controls and
reproducible trajectory measurements. Deterministic runtime and IDE integration checks pass.
**The live results do not establish reliable autonomous task completion.** Correct code, current
acceptance evidence and a completed user task remain distinct outcomes.

The [architecture guide](../subsystems/agent-execution.md), [all-trial measurements](agent-phase7-summary.md)
and [round trajectories](agent-phase7-trajectories.md) provide implementation and raw-report links.

## Trajectory analysis

The [historical analysis](agent-phase7-historical-trajectories.md) covers 42 preserved Phase 3–6
trials: 135 repeated argument sets, 21 pattern-like literal queries and 11 validation-before-observed-save
attempts. These are diagnostic signals, not proof of redundancy: old reports cannot always align
calls to rounds or establish revision/range freshness. Unknown alignment remains unknown.

Recurring failures were repeated discovery without inspecting implementation, rereading unchanged
ranges, validating unsaved edits, and evidence lookups after useful work was already complete. The
Phase 6 save investigation made 20 pattern-like literal queries and never read implementation;
one Phase 4 diff attempt spent 29 rounds without a completed task. Plans and candidate final prose
could not resolve these failures. The classifier now uses actual tool activity plus native progress
state, preserving mixed rounds and distinguishing candidate completion from accepted completion.

## Execution-phase architecture

`AgentExecution` observes the session beside `AgentRuntime`; it is not a second acceptance engine.
It derives orientation, investigation, implementation, validation, acceptance or completion from
bounded read ranges/revisions, discovered files, tool observations and direct native acceptance
facts. Missing deliverables keep guidance in implementation after another file has changed.
Two inspected files can suggest implementing a recognized change; this heuristic never forces an
edit or claims that investigation is complete.

The model receives current known files, changed/saved files, pending requirements, validation facts,
evidence debt, failed tool families and the next useful action. This state survives ordinary history
compaction independently of source blobs. Restored session hints are explicitly historical and
carry no revision or evidence authority. Opaque MCP/plugin result novelty permits useful work
without granting native acceptance evidence. Bounded caches evict old hints instead of permanently
stopping discovery when capacity is reached.

Two idle rounds produce a warning, four suggest another tool family and six require a replan or
an incomplete final response. `execution_control` requires a reason and an advertised next tool for
replanning/reopening, grants a brief grace window, and permits at most two unsuccessful replans.
Nine idle rounds without active grace return resumable `NEEDS_INPUT`. Plan wording, completed
checkboxes and unchanged acceptance queries are not progress. A completed plan with pending
requirements produces a warning. Useful observations reset the soft guard; hard limits remain.

`COMPLETION_READY` requires satisfied recognized requirements, no evidence debt and successful
native verification where changes exist. Final completion still rechecks state. Continued discovery
or reads after readiness require an explanation after two rounds; completion claims remain available.
This signal does not certify all natural-language intent or automatically finish a task.

A configurable whole-turn deadline caps individual model, tool and approval waits and propagates
cancellation to active children. Budget hints appear only in the last 90 seconds/four iterations.
Existing desktop defaults retain their configured operation/iteration limits; the evaluation sets
the aggregate deadline.

## Context and tool ergonomics

Execution state and acceptance reminders are budgeted transient observations at the request tail,
outside saved history. The pre-existing changing compaction notice also moves out of the system
prompt. The system prefix remains stable, and memory/notice framing plus a prospective compaction
notice are included in output sizing. Tests cover exact protocol pairing and context limits.
Server cache-hit data is unavailable: cache reuse is a plausible benefit, not a measured causal claim.

`search_text` explicitly accepts `LITERAL` or `REGEX`, checks Java regex syntax before IO and reuses
Editora's bounded matcher. Regex budget abortion reports truncation rather than false absence.
Only literal results can establish the existing literal no-reference acceptance predicate.
`read_file` offers up to 50 surrounding lines on either side within the existing 200-line/6,000-character
limit and identifies actual/requested line positions. LSP positions remain explicitly zero-based.

Live failures exposed two concrete affordance defects: successful `save_files` returns plain text,
which the initial observer failed to count as progress; and an empty validation-module path lacked
clear root-directory recovery. The final code counts a successful save once per mutation generation,
and the validation schema/error guidance says to omit `module` or use `.`. Neither change relaxes
saving, stale-revision checks, permissions or validation evidence.

The [catalog review](../subsystems/agent-execution.md#catalog-review-and-deferred-options) covers
native and MCP families. The full catalog remains available. Progressive catalog exposure,
disruptive namespaces, dedicated task-type templates and a composite semantic trace tool remain
unimplemented/unmeasured; these runs do not establish that they improve task selection. No subagents,
DAP, benchmark-specific prompt rules or permission/acceptance weakening were introduced.

## Deterministic and IDE validation

`mvn -q spotless:apply clean verify` passed, reporting **5,080 tests in 772 suites, zero failures/errors,
34 opt-in/environment skips**. Separately enabled JDT LS interoperability, production-editor
validation, GTK desktop review and four independent behavioral-oracle fixtures passed (seven tests).
The final no-op correction also reran the production-editor validation fixture with an assertion
that a no-op preserves fresh validation. JDT covered 12 operations in 11.674 seconds, including unsaved changes, rename, diagnostics repair,
formatting and restart. Nine Python report tests and `git diff --check` passed.

New deterministic cases cover unchanged reads/searches, changing revisions, repeated planning and
acceptance queries, recovery then useful work, no-progress bounds and continuation, completion
readiness/reopening, save progress, cache saturation, deadline cancellation, regex ambiguity and
absence authority, bounded surrounding reads and transient context budgeting. Existing stale-edit,
edit-without-inspection, validation-before-save, provider, permission and cancellation tests remain
part of the full suite. The [desktop capture](phase7/execution-desktop.png) is an inspected GTK
fixture showing the localized phase header alongside the acceptance checklist; it is not a live-task
success screenshot.

## Frozen corpus and trial provenance

[Corpus v1](phase7/corpus-v1.json) freezes seven task classes: localized bug, bug plus regression
test, multi-file refactor, test-only, production plus documentation, investigation, and substantial
Editora save-cancellation work. Task definitions and independent fixture/oracle source hashes stayed
fixed. Every run verifies the manifest and records task snapshot, prompt, profile, corpus and compiled
artifact fingerprints. Artifact hashes include Maven's filtered build timestamp; distinct hashes
alone do not demonstrate runtime source changes. The cross-model batch pins that timestamp and
records source hashes. There is no full seven-task rerun after the final save/cache/error-guidance fixes or the later no-op accounting correction.

Both models used LM Studio discovery and heuristic token estimates. The harness context ceiling
was 65,536 tokens: Qwen's discovered loaded limit reduced its effective budget to 16,384, while
Gemma's discovered 166,144 limit left the harness ceiling in effect. These are materially different
context conditions and are recorded in each profile; the comparison is not a model ranking.

All models requested temperature 0/seed 41, 32 rounds and a six-minute agent deadline; this does not
guarantee deterministic generation. Independent oracle work after the turn can extend reported
elapsed time beyond six minutes. Tool-handler counts exclude calls rejected before invocation and
runtime-owned control calls, so both model-requested and handler counts are reported. Zero reported
usage means unavailable token telemetry, not zero token consumption. The investigation oracle is
structural only; explanation correctness requires human review and is not counted as behavioral success.

Five cohorts are preserved without removing failures:

- **Prefix baseline:** two Qwen test-only trials with changing system-state guidance. Both timed out
  after two completed model rounds/three tool calls without editing; the interrupted third requests
  are also counted. Completed requests took 71–159 seconds.
- **Tail baseline:** two test-only trials moved execution state to the request tail but retained the
  old system compaction notice. Both timed out; one saved a correct test but never reached agent
  validation. The raw `phase7-final-qwen` label is historical and does not denote final Phase 7 code.
- **Stable-system corpus:** eight Qwen trials covering all seven tasks, including two test-only
  repetitions. Runtime source was held fixed across this batch. No full task passed; three of seven
  coding trials passed their independent behavioral oracle. The investigation remains unreviewed.
- **Cross-model hardening:** the same localized-bug and test-only tasks run on Qwen and Gemma after the
  concrete save-progress, cache-capacity and root-module recovery fixes. Qwen completed the localized bug (9 rounds, 169.8 seconds) but stopped on the test-only task
  (14 rounds, 250.4 seconds), despite its oracle passing. Gemma failed the bug (29 rounds, 361.0
  seconds) and completed the test-only task (18 rounds, 99.0 seconds). All four share one artifact
  fingerprint: two full-task passes, three behavioral-oracle passes.
- **No-op correction:** comparison review found native `apply_edits` marked unchanged replacements
  as mutations, resetting progress and distorting the Gemma bug tail metric. The final code compares
  actual before/after text and reports per-file `changed`; unchanged revisions retain current validation.
  Native document revision checks still run. Real-buffer regression tests cover repeated no-ops,
  unchanged dirty state and stale user edits; mixed batches still report real mutations. One bounded Gemma bug follow-up is reported separately on that code.

The [verification record](phase7/verification.json) records the final source fingerprint and checks.
The [all-trial table](agent-phase7-summary.md) reports every result and min/median/max distributions.
These cohorts change runtime layout and include variable model latency; they are not a controlled
model ranking or proof of a causal speedup.

## Search behavior and discovery efficiency

Explicit search modes remove API ambiguity, but mode availability alone did not fix observed model
choices. The stable corpus made three pattern-like literal queries and no regex queries. The five later trials
had zero pattern-like literal queries and zero regex queries, which does not demonstrate correct
regex selection. Its save
investigation did read relevant implementation, improving on the specific Phase 6 failure, but then
stopped after two model requests because the latest whole read batch exceeded the conserved context
budget. That is an infrastructure usability limit, not an accepted explanation. Pattern-like syntax
is a proxy for possible misuse, not an assertion that every such literal query is wrong.

Four of the eight corpus trials made an editor mutation: pre-mutation rounds ranged 2–8 (median 5),
and first-mutation latency ranged 71.6–213.4 seconds (median 162.4). Four made no edit, including the
read-only investigation. Refactor and substantial save-cancellation work repeatedly inspected files
but made no mutation. These outcomes do not establish a general improvement over Phase 6. The
Phase 6 test-only examples edited in round four; the corresponding stable Phase 7 edit was round
five, while its repetition never edited.

## Completion efficiency and no-progress recovery

The four corpus trials with edits had 3–11 rounds after their last edit (median 8). The test-only run
saved and validated, received readiness at round seven and requested no further tools. Its eighth
model response was still streaming when the deadline expired: the request consumed 103.9 seconds,
with first text after 56.1 seconds. This is failure to finish, not continued exploration.

The production-plus-documentation run corrected a genuinely missing README deliverable after its
first validation, saved and revalidated, then reached readiness at round 13. It submitted five
unsupported structured claims (revision strings/wrong evidence kinds) and queried execution state
three times before timing out in round 18. Unsupported IDs never became evidence. It had correct
saved files and satisfied recognized requirements but did not complete the user task.

There were 19 native no-progress/readiness signals in the corpus. Refactoring attempted one
`execution_control` strategy change, then hit a stale semantic revision and stopped; this was not
successful recovery. The substantial save task ignored the replan requirement and ended
`NEEDS_INPUT`. The diff bug run tried validation before saving twice, omitted required regression
coverage, and spent later rounds rereading. The local-bug run saved a correct fix but sent an empty
validation module and repeatedly attempted denied raw commands. The final focused cohort checks
the clarified recovery path; it does not retroactively turn these failures into successes.

In the cross-model cohort, Qwen's successful bug fix made one post-readiness source read and then
finished; its test-only run instead repeated the same two unsupported evidence references nine times
(18 rejected claim instances) and hit the idle bound. Gemma's test-only run spent 14 rounds before
editing, then saved, validated and finished immediately in the next model round after readiness.
It therefore demonstrates completion, but not efficient discovery. Its bug attempt created
`InvoiceTest.java` under production sources, failed compilation, attempted a denied raw command and
reapplied identical edits. Those no-ops were incorrectly recorded as mutations in the original trial;
its one-round post-mutation tail is misleading and remains flagged rather than rewritten in raw data.
No successful live replan recovery is established. Deterministic recovery works, while live signals
frequently limit wasted work without resolving the underlying task.

The post-correction Gemma follow-up passed in 12 rounds/57.3 seconds: it ran a failing baseline
validation, made the production fix in round nine, saved in ten, passed validation in eleven and
finished in twelve with no unsupported claims. It made no no-op edit, so the live pass does not prove
that the no-op fix caused a strategy improvement. The real-buffer deterministic regression proves
the accounting correction; broader final-code reliability remains unmeasured.

## Performance

Across the eight stable-corpus trials, reported elapsed time totaled 2,618.1 seconds; model requests
accounted for 2,512.8 seconds, measured tool handlers for 6.7 seconds and native completion verification
for 13 milliseconds. Acceptance reconciliation totaled 179 milliseconds. Timing buckets do not
cover identical boundaries: fixture work and independent post-turn oracles account for much of the
remainder. Model latency/streaming and unnecessary model turns dominate this sample, rather than
the cost of acceptance checks. The four-trial cross-model batch spent 863.3 seconds in model requests versus 8.6 seconds in
measured tool handlers; the last Gemma follow-up spent 52.8 versus 2.7 seconds. The long local-model
requests are reported rather than hidden by larger deadlines or weaker evidence requirements.

## Remaining limitations and answers

The observer measures novelty, not proven relevance or comprehension. Unrelated new files and
opaque changing payloads can count as progress; cycles beyond bounded caches can evade soft
limits. Failed semantic queries, irrelevant scans and large read batches remain costly. Structured
hints do not guarantee that a model follows saving, validation, replan or finalization instructions.
Investigation completeness, arbitrary intent and regression-test quality still need independent
checks/human review. The acceptance interpreter remains heuristic and Java/JUnit-oriented.

**Is the primary remaining failure infrastructure correctness or model reasoning/tool selection?**
The measured failures are dominated by tool strategy and model/provider response time, while safety
and evidence gates behave correctly in deterministic/integration tests. But this is not a claim that
infrastructure is finished: whole-batch context exhaustion is a real observed limitation, and the
initial save-progress and no-op mutation accounting defects were corrected. The corpus provides no evidence of unsafe
acceptance being necessary for progress. The final small-task comparison had two complete tasks and two failures; it cannot establish broad
production reliability or rank the models.

**Once implementation is correct and evidence is satisfied, does the agent reliably complete?**
**No, not reliably.** Both stable-corpus readiness runs failed to finish. In the fixed four-trial
comparison, three runs reached readiness: Qwen's bug and Gemma's test-only task completed, while
Qwen's test-only run stalled on repeated unsupported claims. Readiness is useful and preserves the
gates, but these observations disprove any claim that a correct/evidenced agent now consistently
finishes. The later Gemma follow-up also finished immediately after readiness, but one additional success
does not erase the observed failures or establish a reliable completion rate.

## Recommended Phase 8 — not implemented

Prioritize context-aware structured tool delivery that preserves useful code within the actual
request budget, plus provider/tokenizer and latency observability. Evaluate an explicit model-requested
native finish action that still runs every final guard and renders grounded evidence, reducing dependence
on a long redundant final stream. It must not auto-finish from coarse requirement satisfaction.
Improve typed recovery and task-grounded tool selection against this same versioned corpus before
adding more tool families. Compare fixed implementations with repeated trials and budget-matched
baselines; do not infer success from a selected best run. Progressive catalog exposure can be tested
as a separate controlled experiment. Subagents and DAP remain deferred.

## Reproducing a versioned trial

Use the repository's JDK 25/Maven environment and a configured tool-capable model. For example:

```sh
AGENT_EVAL_MODEL=qwen/qwen3-coder-next scripts/probes/agent-evaluation.sh \
  -Dagent.eval.corpus=phase7-v1 -Dagent.eval.tasks=ledger-bug,ledger-tests \
  -Dagent.eval.iterations=32 -Dagent.eval.minutes=6 \
  -Dagent.eval.temperature=0 -Dagent.eval.seed=41 -Dagent.eval.label=phase7-reproduction
```

Set `agent.eval.jdtls` to the configured JDT LS launcher for semantic scenarios. Copy all reports out
of `target/agent-evaluations` before `clean`; label changed implementations separately. The corpus
manifest rejects changed task/oracle definitions rather than silently comparing different fixtures.
