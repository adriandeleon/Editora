# Phase 5: goal, evidence and completion intelligence

The native agent now has an acceptance boundary after the existing mutation verifier. A successful
build no longer suffices for recognized requests to add/update tests, update documentation or inspect
observed callers. This is a bounded foundation: automatic English interpretation is heuristic and
cannot certify that every obligation in arbitrary prose was captured.

See [architecture and contracts](../subsystems/agent-acceptance.md), the
[complete trial dashboard](agent-phase5-summary.md) and [verification measurements](phase5/verification.json).

## Task contract and evidence architecture

Actual user input now has a separate runtime ingress from retrieved repository/editor/skill content.
Only that input can create `USER_EXPLICIT` requirements. Stable requirement ids retain original user
provenance, type, state and evidence links. Model-derived hypotheses may be revised without removing
user requirements. Corrections preserve history. Compact reminders live outside ordinary transcript
eviction; plans may reference requirements but cannot mark them satisfied.

A bounded native ledger records document changes/creation, current reads, test identities/results,
validation counts, diagnostics, scoped semantic observations and diff review. Revisions, execution
generations, source, freshness and strength remain explicit. Mutations and later execution invalidate
dependent authority. Resume makes persisted evidence historical; matching change receipts require
fresh reads and fresh validation. Repository/MCP assertions never mint native evidence or permissions.

The existing save/diagnostic/build verifier runs first. Acceptance then checks current observations,
returns missing obligations to the agent loop, or ends `NEEDS_INPUT` after three rejected completion
attempts. Cancellation and resource limits retain their existing behavior.

## Test quality and grounded completion

Parse-only JDK ASTs identify new or structurally changed Java methods; fresh JUnit identities establish
execution and passing results. Comments, strings, skipped tests and unrelated report identities cannot
substitute for requested coverage. The module graph adds `java.compiler` and `jdk.compiler`; parsing
runs on the worker and never invokes annotation processing or executes project source.

Added, executed, passed and proven-to-detect-the-old-failure are separate facts. An evaluation-only
probe copies an owned fixture, restores its seeded old implementation there, and checks whether the
specified new test fails. A vacuous test is rejected by that probe. The production workspace is never
mutated to manufacture proof, and production completion does not claim that the probe ran.

Concrete final statements are rendered from an internal requirements/changes/validation/remaining/
claims object. Optional structured claims must match current evidence identities and authoritative
counts. Nonexistent files/tests, wrong counts, unavailable diagnostics and unobserved validation cannot
become verified statements. General “all callers everywhere” claims remain unverified. Investigation
prose stays a separately marked interpretation; subjective or arbitrary prose is not universally
fact-checked. Code-work drafts are buffered while tool activity remains visible.

## UX and integration

A collapsible checklist shows localized acceptance progress separately from plan checkboxes. It is
hidden for lightweight single-guard tasks and scroll-bounded for longer contracts. Plan rows may show
requirement ids. Tool summaries present assessment and validation results without raw protocol objects.
The [desktop capture](phase5/acceptance-desktop.png) was reviewed for layout; this was an automated GTK
fixture, not an independent human usability study.

## Deterministic validation

Focused tests exercise green-build omissions, fabricated identities/counts, wrong validation scope,
comment-only/skipped coverage, actual-user corrections, compaction, stale diagnostics, concurrent
edits, bounded evidence, historical restore, missed observed callers and continued work after an
omitted test. Independent oracles reject broken implementations and vacuous coverage. A real editor
fixture edits existing buffers, rejects dirty validation, saves, runs isolated Maven and grounds the
newly executed test. A native checkpoint test prevents plan completion or restored hashes from
substituting for current evidence. Real JDT LS exercises twelve semantic/lifecycle operations.

`mvn -q spotless:apply clean verify` passed: 5,045 tests, zero failures/errors, 34 skipped
opt-in/environment-dependent tests across 770 suites. Separately enabled editor, oracle, desktop and JDT fixtures passed;
the dashboard has three passing Python tests. Command outcomes are recorded in
[verification.json](phase5/verification.json). Early failures were retained in that record rather than silently treating retries as initial success.

## Live-model evaluation

Results, budgets and implementation fingerprints are separate for each run label. Trials use
anonymous localhost LM Studio, requested temperature 0/seed 41, fresh fixtures and independent
post-turn behavioral oracles. Sampling requests do not guarantee repeatability. No credentialed
hosted provider or full-repository developer task is claimed here.

The first Qwen batch predates a defect found in final diff review: successful ordinary `apply_edits`
was not feeding the new acceptance adapter, although creation and partial-failure edits did. Its diff
trial timed out with an unsaved production edit and no regression change. Its migration trial timed
out with only three of eight files changed and failing compilation. Both failed their independent
oracles. Neither reached acceptance completion, so these trials do not measure claim rejection.

The defect failed closed, but prevented valid edits from receiving credit. Review also removed an
incorrect baseline callback during restore that could erase a rehydrated change receipt. Production
editor/checkpoint tests now cover both paths. The obsolete batch was intentionally stopped during
trial two's diff task; its partial log and interruption record remain in the archive, while the last
unstarted migration is explicitly excluded from trial counts. No oracle outcome is invented for the
interrupted attempt. Completed pre-fix JSON is preserved exactly, including its fixed benchmark
request text; final telemetry omits requirement prose and unsupported claim subjects.

The corrected diff trial passed the independent behavioral oracle and fresh structured validation,
but omitted the requested regression-test change. It timed out at 720.015 seconds after 20 model
rounds/18 recorded tool calls (19 requested; the denied command is a separate observation). Reconciliation marked production change,
validation and inspection satisfied while `TEST_CHANGED_EXECUTED` remained pending. The full task
therefore failed. It never reached a final completion attempt (`checks=0`), so this is live evidence
of omission detection, not a measured final-claim rejection. All reconciliation took 3.897 ms.

The corrected migration changed all eight required files, updated the existing test and passed
its behavioral oracle and structured validation. It still timed out at 720.006 seconds after 17
model rounds/29 tool calls. Current caller-reference evidence was absent and inspection facts were
stale after the edits, leaving two of four derived guards pending. This is a conservative evidence
limit despite correct observed behavior, not evidence that a caller remained broken. Reconciliation
took 1.223 ms; the first Java declaration analysis took 447.682 ms, materially higher than warmed
synthetic tests. Neither corrected trial reached a final completion attempt. Across all four completed
trial reports, two behavioral oracles passed and zero full tasks completed; the interrupted pre-fix
attempt is listed separately.

Corrected-build trial results are reported in the dashboard. That compiled batch predates a final
evidence refresh: saving at the same text revision now supersedes the earlier dirty-state fact;
server-generation changes invalidate diagnostic facts without erasing unchanged file deltas; and
structured validation immediately refreshes requirement status for the next model request. The
migration also exposed an interpretation gap for “updating existing tests”; the final interpreter
recognizes updating/modifying/extending clauses with dedicated provenance tests, and handles narrowing
a previous documentation request to Java-only work. The
existing live-buffer verifier already enforced saves. These follow-ups have deterministic coverage
but were not re-evaluated with a live model in this pass. Small samples, changing runtime
revisions and incomplete attempts do not establish statistical improvement or a model ranking.
Zero structured claim submissions must not be read as proof of zero hallucinations.

## Performance and remaining boundaries

Acceptance uses native observations without an extra inference round to paraphrase successful
verification. Timers separate completion checks, all reconciliation work, and Java declaration parsing;
completion-check time includes its reconciliation, so those counters are not additive. Early live
reports predate the all-reconciliation timer. They cannot report its missing value as zero cost.
The four-round deterministic omission/recovery fixture measured 0.362 ms of acceptance checks and
10.024 ms of Java declaration analysis in the final full-suite run. Retained runs show variation;
these are small synthetic tasks, not editor latency percentiles. Live costs remain separate in the dashboard. No byte estimate is presented as actual
model token usage.

The contract interpreter can miss intent, misunderstand negation, overconstrain a task or fail outside
its supported English patterns. A request with no recognized guard can end `COMPLETED` with an
explicitly unverified response; the state alone does not certify complete intent coverage. A production diff plus successful validation is not a semantic proof
of the requested behavior. Named documentation guards check that files changed, not prose quality.
An observed-caller predicate covers the recorded workspace reference files; unversioned LSP replies
cannot prove whole-program completeness. Investigation reads support what was inspected, not causal
correctness. Old checkpoints without contracts cannot safely promote mixed historical context to user
intent; restating the goal is needed to establish coverage.

JUnit naming/layout conventions and non-Java test identity remain limited. Report scans and ledgers
are bounded; unsupported identities fail closed. Build-report forgery, unobserved external dependencies
and edits made immediately after the final snapshot remain outside the proof boundary. Reconciliation
can conservatively reject otherwise useful work. Free-text investigation interpretations may still be
wrong; only templated concrete statements carry checked evidence authority.

## Answers and Phase 6

**Can Editora distinguish working code from completed requested work?** Yes for recognized acceptance
guards: deterministic and production-editor tests show green builds rejected for missing tests/docs
and missed observed callers, then accepted after current evidence arrives. This does not establish
exhaustive understanding of arbitrary user intent or reliable autonomous success across real tasks.

**Can it prevent unsupported concrete work claims from appearing as verified completion claims?** Yes
within the structured completion vocabulary and native rendering path. Deterministic fake models
cannot promote nonexistent identifiers, wrong counts or unavailable evidence. Raw interpretation is
explicitly unverified; this is not universal natural-language fact checking.

The highest-value Phase 6 work is user-reviewable contract coverage, project-defined acceptance
recipes tied to behavior and scope, stable test-runner identities, and larger real-developer studies
of correction/resume/context pressure. Those extensions were not implemented here.
