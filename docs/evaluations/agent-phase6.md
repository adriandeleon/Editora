# Phase 6: acceptance recipes, evidence refresh and completion recovery

This evaluation uses the Phase 5 native agent and adds active acceptance guidance. The
[architecture](../subsystems/agent-acceptance-recipes.md) defines the recipe and freshness model.
Deterministic checks and live model trials are reported separately. A green build, a behavioral
oracle and accepted task completion are different outcomes. The
[trial measurement table](agent-phase6-summary.md) links the preserved raw reports.

## Acceptance recipe architecture

Requirements remain user-goal guards. Built-in recipes give candidate tools for each pending guard,
and a bounded `.editora/acceptance.json` can suggest a validation operation by path prefix. Recipe
provenance appears in the contract view. Neither a recipe nor repository text can grant permission,
mint `USER_EXPLICIT` requirements or declare success. The runtime reconciles once after a batch of
meaningful tools and sends a compact changed debt list to the model. Final rejection returns the
same structured information, including invalidation reason and candidate tools.

## Evidence dependency and freshness model

Document edits invalidate validation, diagnostics, search absence and reference observations. Reads
and symbols from independent documents survive. Native revision and dirty-state checks refresh
saved-state facts at the same document revision. External execution retains global invalidation.
This preserves independent observations without trusting stale workspace-wide relationships.

## Test identity model

Fresh JUnit XML cases retain class, invocation and report path. Conventional parameterized names
normalize to a source method; arbitrary display names do not become source coverage proof. Matching
requires a current Java declaration in the corresponding class path and a fresh non-skipped report.
Explicit targeted acceptance also requires the requested test selector and matching executed case.
The model remains Java/JUnit-focused; framework adapters are a future extension.

## User-reviewable contract UX

The collapsible Acceptance section shows requirements, current status and missing evidence in a
tooltip. Add, Correct and Remove controls prepare actual user follow-ups; model tools cannot edit
user requirements. The interpreter handles several common corrections and negations. Scope words
that may be ambiguous produce a visible interpretation warning. It remains a bounded heuristic.

## Automatic evidence refresh and completion recovery

Current document state, changed files, saved state, validation counts and report identities are
reconciled from native adapters without an extra model call. A changed test source produces a
targeted validation suggestion. A missing test source is classified `TASK_INCOMPLETE`; a changed
test lacking fresh execution is `EVIDENCE_INCOMPLETE`. The agent sees this before candidate final
completion and receives the same debt after rejection. No expensive test/build action starts merely
to satisfy the gate; ordinary permission policy still applies. Interleaved identical reads now
receive a bounded progress warning.

## Deterministic validation

Focused tests cover a forgotten test, one-step recovery, unrelated file evidence, stale references,
fresh no-reference search, user correction, targeted scope matching, parameterized JUnit names,
malicious workspace recipe fields and task versus evidence state. Final
`mvn -q spotless:apply clean verify` passed: 5,059 tests in 770 suites, zero failures/errors and
34 skipped opt-in/environment-dependent tests. Separately enabled production-editor validation
(1 test), independent behavioral oracles (4 tests) and visible GTK desktop review (1 test) passed.
The [Acceptance panel capture](phase6/acceptance-desktop.png) was inspected: requirement progress,
Correction and Remove controls, tool rows and validation summaries render in the real GTK fixture.
Real JDT LS passed 12 cold/warm/unsaved/restart interoperability operations in 11.920 seconds.

## Live-model evaluation

The [early corrected-scenario summary](phase6/early-trials.json) records two five-minute failures
against an early Phase 6 build. The diff task ran eight model rounds and seven tool calls, repeated
source/test reads and did not edit. The migration ran five rounds and thirteen tool calls, made one
production edit and ended with test, validation, caller and inspection evidence pending. Both were
cancelled by the trial cap, failed independent oracles and had zero completion checks. Their
reconciliation work totaled 8.343 ms and 5.778 ms respectively. The original aggregate JSON files
were mistakenly removed by the next clean build after their fields were inspected; the linked
summary preserves the measured failure facts and explicitly records that artifact loss. These
trials do not show whether the model would have followed post-edit debt with a larger budget.

Further opt-in trials use disposable workspaces with local Qwen, requested temperature zero/seed 41,
real JDT LS where applicable and independent behavioral oracles. The
[full-repository save investigation](phase6/live-save-understanding.json) timed out after nine
rounds/25 calls. The model saw evidence debt on every round but repeatedly sent literal search
queries containing regex syntax and never read the implementation; `INSPECTED` stayed pending.
Its `oraclePassed=true` field is not a behavioral success: this investigation requires human review,
and `taskSuccess=FAIL`. Native reconciliation took 8.333 ms. The
[ledger refactor](phase6/live-ledger-refactor.json) reached an unsaved edit after six pre-mutation
rounds, then timed out after eight rounds/12 calls. The disk-change set was empty, the independent
oracle failed, and validation/fresh caller evidence remained pending. Reconciliation took 4.277 ms;
Java declaration analysis took 360.492 ms. Neither trial reached a completion check. These reports
were copied out of Maven `target` before later clean builds. The
[test-only regression task](phase6/live-ledger-tests.json) passed its independent behavioral oracle:
the changed test was saved and executed, and `TEST_ADDED_EXECUTED` reached `SATISFIED`. The agent
still timed out after nine rounds and nine calls without a completion check. It spent five rounds
after its last edit, including acceptance evidence lookups after all recognized requirements were
satisfied. Native reconciliation took 8.529 ms. This specific failure prompted an explicit
completion-ready observation, added after that batch was compiled. The production-plus-documentation
run, a substantial [Editora diff task](phase6/live-diff-documentation.json), also timed out after
ten rounds/eleven calls without editing. It repeatedly listed and read files despite visible debt;
the behavioral oracle failed and no completion check occurred. Reconciliation took 5.897 ms.
The search guidance, bounded repeated-discovery warning, trace requirement and completion-ready
signal were added after this four-task batch was compiled. A focused rerun on the updated code is
reported separately below.

The [focused test-only rerun](phase6/live-ledger-tests-completion-signal.json) used the final
completion signal with the same local model, requested temperature zero and seed 41. It made the
same test edit in round four, saved in round five and validated in round six. In round seven it
queried completion claims, then issued a final response in round eight. `taskSuccess=PASS`, the
independent behavioral oracle passed, acceptance had one satisfied explicit test requirement,
and two completion checks were performed. Four submitted structured claim references across the
checks were unsupported and labeled unverified; they did not become evidence. This single trial
shows recovery in the previously failing test-only scenario, not a general completion rate for
larger Editora tasks. The run took 309.848 seconds, with 17.083 ms of reconciliation and 336.594 ms
of Java declaration analysis.

## Performance and limits

The acceptance work itself is bounded; model latency and exploration dominate wall time in prior
trials. New evaluation metadata separates rounds before first mutation, rounds after last mutation,
rounds with acceptance debt and validation calls after the last mutation. A workspace search is
bounded to accessible text and cannot prove semantic or external dependency completeness. The
contract interpreter cannot certify arbitrary intent; a passing test does not prove test quality.

## Answers

The runtime now deterministically identifies a refresh path for recognized missing evidence and
places it in the next model request. The focused test-only rerun obtained the evidence and finished
in round eight, two rounds after validation, while the larger four-task batch had three unsolved
timeouts and
one behaviorally correct but unfinished test-only run. Thus the answer is **yes for the observed
test-only recovery, not yet reliably for broader coding tasks**. Model exploration still dominates
the time budget.

The runtime now labels a missing deliverable `TASK_INCOMPLETE` and missing fresh proof
`EVIDENCE_INCOMPLETE`. Focused tests verify both branches, while heuristic intent gaps remain.

Phase 7 should focus on calibrated real-task evaluation, broader test-runner adapters and
user studies of contract correction. It is not implemented here.
