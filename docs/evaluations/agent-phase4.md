# Phase 4: adaptive models and production reliability

This change extends the existing single-writer native agent. It preserves document revision leases,
centralized approvals, complete tool/result pairing, semantic proposals and the completion verifier.
The implementation and its limits are described in [the reliability guide](../subsystems/agent-reliability.md).
The [trial dashboard](agent-phase4-summary.md) includes every archived live trial, including failures.

## Architecture and model intelligence

`AgentModelProfile` separates provider transport, model claims, loaded server configuration and user
overrides. Capability values carry explicit provenance. Discovery is a bounded, cancellable,
same-origin, read-only LM Studio API request. No capability guesses from model names, model loading, server-setting changes or credential
discovery is involved. Unsupported metadata remains unknown. The settings
schema and all six message catalogs support per-provider/model overrides and optional sampling.

The runtime refreshes discovery after initial inference to detect JIT-loaded context, reserves input
headroom, and chooses bounded output allowances. Complete protocol responses marked `length` or
`max_tokens` can retry twice; every call in a truncated batch is discarded. EOF without a complete
provider envelope remains an error. Generic tool/schema/semantic/save guidance was improved without
family-specific prompt patches or weakening policy.

The first Qwen migration trial read seven callers in one response and then exhausted the estimated
16K context before another model request. The first diff trial alternated between the same two files
for seventeen calls without edits. Its retained request size repeatedly fell as history was compacted.
This motivated an additional input-pressure adjustment: before discarding recent code, an automatic
non-reasoning output budget can shrink within 2,048–4,096 tokens. Explicit output preferences are not reduced by this pressure rule. Automatic recovery allocations
also preserve enough room for the mandatory catalog, user goals and newest complete exchange. A deterministic regression uses the production tool
catalog and the seven-caller read batch. The compaction note now distinguishes usable retained
results from historical summaries, instead of unconditionally requesting another reread. This is not an exact tokenizer, and observed rereading is not attributed solely to context budgeting.

## Structured validation and isolation

`validation_profiles` discovers Maven/Gradle descriptors and explicit operations. `run_validation`
builds fixed argv for compile, test, targeted test, check or package, always with external-execution
approval. It rejects unsaved state, checks saved revisions before/after execution, extracts fresh
JUnit test failures, and rejects exit-zero test commands without fresh non-skipped test cases.
Successful evidence is `AGENT_VALIDATED`; hidden evaluation checks are graded separately.

On this Linux host, bubblewrap validation ran real offline Maven tests. Probes checked a private home,
inaccessible host SSH configuration and denied access to a host loopback listener. The production
editor integration test exercised edit → dirty rejection → save → isolated Maven → fresh test and
revision evidence. Process timeout/output/cancellation reuse `ProcessRunner`. Unsupported platforms
require an explicit `HOST_REDUCED` choice; there is no silent fallback. This is reduced exposure,
not hostile-code containment: the workspace remains writable, approved host commands/LSP/MCP are
not isolated, tool installations are mounted read-only, and resource quotas are not implemented.
Gradle templates have not been exercised against a real Gradle installation.

## JDT LS interoperability and recovery

The real installed JDT LS fixture exercises twelve operations: cold initialization/agent-only
buffers, symbols, references, implementations, definition, code actions, three-file rename proposal
and application, unsaved `didChange`, formatting proposal, diagnostics, repair while diagnostics are
pending, and restart preserving unsaved buffers. The recorded trials are in
[lsp-trials.json](phase4/lsp-trials.json).

Two early failures exposed useful distinctions. Initialization can precede dynamic semantic
capability registration; an empty catalog is no longer advertised as available. Immediately after
`didChange`, a symbol response can still reflect the server's older analysis. The test waits for
observed semantic convergence, and results now label client revision stability separately from the
unversioned server response. No malformed edit is repaired by guessing. Subsequent complete runs
passed, including a final actual-worktree run in 11.286 seconds.

## Evaluation scope and interpretation

The catalog grew from seven to thirteen scenarios: real diff/Git components, a seeded full-repository
save-ordering bug, LSP/UI and settings/persistence investigations, and an eight-file controlled billing
migration. Catalog entries are not counted as completed evaluations. Full-repository save/UI/settings
model tasks and credentialed hosted models were not exercised in this pass.

Live runs use anonymous localhost LM Studio, requested temperature 0 and seed 41, fresh task
workspaces, bounded harness consent and independent post-turn oracles. Discovery observed Gemma's
loaded context as 125,696 tokens and Qwen's as 16,384; both advertised architectural maxima of 262,144.
The session cap was 65,536. The loaded limits, not those maxima, constrain the runtime. These are
small diagnostic samples with different task mixes and runtime revisions, not a model ranking or a
statistical improvement claim. Sampling requests do not guarantee deterministic results.

Profiles, sampling, server origin, prompt/catalog/task hashes, approval counts, outcomes and timings
are archived in each trial. Qwen reports also include compiled implementation fingerprints, dirty
repository state and unsaved-file metadata. Earlier Gemma reports predate those metadata additions;
absence is not filled with invented values. Implementation changes made while a model batch was
running were not recompiled into that batch. Initial/JIT profiles and run labels remain separate in
the dashboard. Token usage was unavailable from these streams; byte estimates are not actual tokens.

Independent probes reject the original diff and stash bugs, the old eight-file API, and an older
save ticket invalidating a newer one; repaired fixtures pass. Missing fixture classes also fail:
oracles cannot fall back to Editora's host classes. Full product snapshots exclude grading sources and
the integration fixtures importing them; the remaining source/test tree is compilation-checked. All six live fixtures with passing behavioral
oracles were rechecked after the batches using only their own compiled classes; all six passed. Local scratch directories retain final answers, error observations
and unsaved fixture buffers for diagnosis; aggregate data contains no source bodies or arguments.

## Live trial findings

The preserved baseline produced two passing endpoint fixes with Gemma, two incomplete Gemma stash
fixes, two Qwen diff timeouts, and two Qwen migration context failures. Gemma's endpoint trials needed
one and two output recoveries; its stash trials ended in `NEEDS_INPUT` and `LIMIT`. These failures
remain in the aggregate. The first Qwen batch's parent command returned 143 after all four raw reports
and its zero-error JUnit result were written; the outer termination cause was not established, so the
batch is not described as a clean harness process exit.

With the retention changes, Qwen's first eight-file migration completed in 383.706 seconds and passed
the independent API/caller/overflow oracle. The first revised diff task reached `COMPLETED` and passed
the behavior probe in 649.229 seconds, but failed its required regression-test change. The second
migration passed in 215.531 seconds; the second diff trial added a regression test,
recovered from an unsaved-buffer rejection, and passed in 289.461 seconds. The retention batch
therefore completed all four turns, passed all four behavior oracles and met the full task checks in
three of four trials (median 13 model rounds, 13.5 tool calls, 336.584 seconds). These timings are
agent execution time; the reports record oracle time separately. Across all twelve
archived live trials, five met the full task checks, six passed behavior oracles, and six reached
`COMPLETED`. All seven task failures remain visible. Gemma and retention batches exited zero. The
per-profile measurements and individual reports are in the linked dashboard. A changed runtime,
warmed server, different task mix and small samples prevent attributing these results to one factor.

The new local error record identified invalid `TEST` + test-selector combinations, a descriptor file
supplied where a module directory was required, and an exact-text mismatch in a multi-file edit.
The final code provides explicit selector/directory guidance and names the mismatched edit target
without echoing source text. Those final error-message refinements and hidden-grader exclusions were
made after the recorded retention batch was compiled; no extra live-model improvement is claimed for
them.

## Deterministic and integration verification

The normal suite uses fake models and does not require an LLM or API key. Focused checks cover
provider dialects, discovery bounds/cancellation, output truncation (including multi-call batches and
unmarked EOF), profile overrides, fresh XML evidence, denied permissions and stale revisions.
A 240-observation stress test covers compaction, failed verification followed by repair, persistence,
continuation and switching fake providers. Twenty-five MCP disconnect/reconnect cycles preserve one
live connection and do not replay calls. These are lifecycle tests, not evidence that a real model
remains coherent for hundreds of steps.

The final actual-worktree `mvn -q spotless:apply clean verify` exited zero: 5,023 tests reported,
4,990 executed, 33 opt-in skips, zero failures and zero errors across 769 suites. The real-service
probes were run separately. Exact totals and outcomes are recorded in
[verification.json](phase4/verification.json).
The report generator has a deterministic regression proving failed/incomplete trials are retained
and missing timings are not treated as zero. No generated application binaries are included.

## Desktop review and approval human factors

Visible production JavaFX panels, the actual approval dialog, model settings and diff UI were run
with the GTK toolkit on the desktop. Seven owned-scene snapshots were visually inspected: transcript,
expanded tool, expanded validation failure, cancellation, profile settings, validation approval and
diff review. These use synthetic fixture content; the displayed twelve tests are not benchmark
results. Scene snapshots are not compositor captures, manual user interactions or a human study.

The review led to readable collapsed test counts, failure details, scope/isolation summaries and
editable model limit fields. Exact arguments remain initially expanded for arbitrary commands and
MCP. Existing multi-file edit transactions group a bounded reviewed set into one approval; no
approval grants future session authority. Telemetry records category, outcome, wait and repeated
serialized requests without logging arguments or their digests. It cannot yet establish human
permission fatigue. Independent developers still need to evaluate real substantial tasks.

See [transcript](phase4/transcript.png), [validation failure](phase4/validation-failure.png),
[approval](phase4/validation-approval.png), [model settings](phase4/model-profile.png), and
[diff review](phase4/diff-review.png).

## Remaining daily-trust barriers

The first revised diff trial passed the behavioral oracle and reached `COMPLETED`, but still failed
the task because it changed no regression test. Reviewing its retained final answer also found three
claimed test-method names absent from the actual four-test fixture. The structured evidence reported
real counts; it did not justify those invented names. Build evidence, goal fulfillment and truthful
natural-language reporting remain separate concerns.

The three largest remaining barriers are:

1. **Reliable progress under real model/context limits.** The baseline lost both eight-file migrations
   to context pressure and both diff tasks to repeated discovery. Gemma still exhausted its bounded
   output recovery on a stash task. Both revised migrations completed, but changed runtime/server conditions prevent a causal claim,
   and a fake 240-round stress test cannot establish long-model-session coherence. Prioritize exact
   token/wire accounting, selective tool catalogs, retained file/decision state and broader repeated
   model trials before expanding autonomy.
2. **Verifying the whole request and reporting only supported facts.** The revised diff example was
   behaviorally correct but omitted a requested regression test and invented test names in its final
   explanation. Current production verification checks saved state, diagnostics and validation;
   it cannot infer every natural-language acceptance criterion. Add explicit acceptance evidence,
   test-quality/mutation checks where appropriate and final-claim traceability. Passing a build is a
   necessary signal, not a universal correctness or completeness proof.
3. **Evidence across realistic environments and actual developers.** Only four corpus tasks were
   exercised live, with two local models. Full-repository feature/UI/concurrency work, hosted
   providers, macOS/Windows isolation and real Gradle validation remain unmeasured. JDT LS exposed
   real readiness/staleness differences even in a small fixture. Human consent fatigue and daily
   workflow usability have no participant data. Run supervised developer pilots and expand those
   environments while retaining explicit permission boundaries; these results do not justify
   unattended arbitrary execution.
