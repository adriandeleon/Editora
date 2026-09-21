# Task contracts, evidence and completion

Phase 5 adds an acceptance boundary to the existing native runtime. Document leases, approvals,
validation, cancellation, LSP ownership and model transport keep their existing responsibilities.
See [platform](agent-platform.md), [structured validation](agent-reliability.md) and
[evaluation](agent-evaluation.md). Phase 6's active recovery model is described in
[acceptance recipes](agent-acceptance-recipes.md).

## Authority and interpretation

`AgentRuntime.submit(userMessage, retrievedContext)` separates actual user input from repository,
skill and editor context. The coordinator previously concatenated those into a single user message;
that is unsuitable for requirement provenance. Retrieved content now enters as an untrusted
observation. Only the actual user-message ingress calls `AgentTaskContract.user`.

The contract retains the original messages verbatim, outside transcript eviction. Requirements have
stable ids, types, provenance, assessment state, evidence ids and an explanation. User-derived
guards refer to their original message. Model tools may add and supersede `AGENT_DERIVED` hypotheses;
they cannot create `USER_EXPLICIT` provenance, remove user requirements, declare satisfaction or
inject evidence. Contract operations do not alter policy or approve tools.

The automatic interpreter is deliberately a **bounded English heuristic**, not a complete natural
language parser. It recognizes common change, test, documentation, investigation, validation and
constraint requests. The full original request remains authoritative. Interpretation can miss
obligations, overconstrain an ambiguous request or fail to understand another language. An accepted
set of guards is not a theorem that every intent in arbitrary prose was captured. This limitation is
visible in the contract metadata and completion summary; it must not be hidden behind a green status.

User corrections such as “don't modify tests” supersede earlier test obligations, retain their
history and introduce a current-diff constraint. “Also update documentation” adds an obligation;
“only change the Java implementation” adds a file-type constraint and supersedes earlier
documentation obligations. A later documentation request can expand that scope again. Arbitrary conflicting corrections
are not resolved by guessing. Model hypotheses can be revised independently of those user guards.

The contract is bounded to 64 user messages, 150,000 user characters and 64 requirements. Limits fail
explicitly. A compact id/check/state reminder accompanies model requests, so compaction, recovery,
replanning and resume do not depend on a generated summary to preserve obligations. Original user
turns also retain the existing context protection.

## Observations, not assertions

`AgentEvidence` is an immutable metadata record: identity, kind, subject, revision/generation,
freshness, strength, source and bounded facts. The native adapters mint evidence directly from their
document operations, structured validation reports, diff review and semantic responses. Parsing a
model or MCP response that says “tests passed” never creates validation evidence.

The ledger holds at most 768 records. It distinguishes current, stale and historical observations.
Mutation and later execution invalidate dependent build/test/semantic evidence. Final acceptance
rereads observed revisions and brackets the scan with open-document state checks. User edits,
missing files and concurrent buffer changes reject freshness. These are observations at a checked
point in time, not a promise that a developer cannot edit immediately afterward. Unobserved external
dependencies and hostile build-report forgery remain outside the proof boundary.

File-change facts come from the native transaction's before/after content, not tool names or model
claims. Reverting to the original text removes the change. Created files, saved/dirty state, native
diff review, current reads and the known validation scope remain distinct facts. A selected
validation scope still must cover changed files under the existing verifier.

Structured validation supplies aggregate counts and up to 512 bounded JUnit identities internally;
the evidence adapter retains at most 128 identities per run, prioritizing changed tests. Aggregate
counts come from the complete bounded report scan, never extrapolation from that identity sample.
Case identities beyond the limit are unverified, not invented. An ordinary `run_command` remains
available under its existing policy but does not mint the stronger structured acceptance evidence.

## Test evidence levels

Java test methods are identified through the JDK's parse-only compiler API. This adds
`java.compiler`/`jdk.compiler` to the module graph and bundled runtime. It does not analyze, compile,
load project classes, run annotation processors or invoke project build scripts. Comments and
string literals cannot supply declarations. Ambiguous overload names and invalid syntax fail closed.
Parsing runs on the agent worker only for changed Java test files, not editor keystrokes.

New method names must be absent from the original syntax tree and present in the current syntax tree.
Changed existing test bodies must differ structurally; comment-only or formatting-only changes do
not count. Declaration evidence is then joined to a fresh executed JUnit class/method identity for
the matching source path. A skipped test is not executed coverage. An unrelated passing test cannot
satisfy the changed-test requirement. Parameterized/display-name conventions, unusual source layouts,
other languages and ambiguous declarations can remain unsupported rather than guessed.

These facts remain separate:

| Evidence | Establishes | Does not establish |
| --- | --- | --- |
| Test declaration / report identity | A test was observed | That it executed |
| Fresh non-skipped case | The reported test executed | That it passed |
| Fresh passing case | That execution passed | That it detects the old bug |
| New declaration joined to fresh execution | Requested new coverage was added and run | Assertion quality or behavioral completeness |
| Evaluation-only old-source probe | The specified new test fails against the known old implementation | Universal correctness |

No product tool mutates user code to manufacture an old-failure proof. The evaluation harness can
perform that check in a separate disposable fixture after the agent ends. Its result remains
`TEST_PROVEN_TO_DETECT_OLD_FAILURE` evaluation evidence, separate from production acceptance.

## Completion protocol

The existing save/diagnostic/validation verifier runs first for modified work. Acceptance then
evaluates active requirements against current evidence. Structured validation also reconciles the
contract immediately, so the next model request and checklist reflect missing work without waiting
for a final response. Missing work becomes an observation and the
same agent loop continues. Three rejected completion attempts return `NEEDS_INPUT`; ordinary
cancellation and iteration/time limits still apply. A failed guard cannot become satisfied through
a completed plan checkbox or an assistant's statement.

`task_contract` exposes the assessment and permits bounded hypothesis edits. `task_evidence` pages
metadata for current facts. Optional `completion_claims` selects a claim kind, subject and evidence
id, with a count when applicable. Incorrect identities, wrong counts, nonexistent operations and
unavailable diagnostics are rejected. There is no unrestricted claim-text field.

`AgentCompletion` builds a structured requirements/changes/validation/remaining/claims object, then
renders a summary from checked facts. Raw code-work completion prose is not streamed as verified
output before the gate. Tool activity still appears as it occurs. The normal path needs no extra
model call to paraphrase a successful verification result. Requested claims without matching current
evidence are omitted; no fabricated identifier or count is carried into the verified statements.

Investigation answers retain a separately marked model interpretation, while inspected files are
runtime facts. That interpretation is not certified prose and is not a universal factual check.
Requests with no automatic guard are likewise explicitly unverified. The interpreter cannot reliably
distinguish every unfamiliar nontrivial request from chat: such a turn may end `COMPLETED` with an
unverified response. That state alone is not evidence of exhaustive intent coverage. This distinction is
necessary for useful explanations without pretending that source reads prove every causal claim.

Semantic evidence is narrowly scoped. Current client revisions do not make an unversioned LSP
analysis authoritative. Observed reference files can challenge an omitted caller and support a
statement about those observed files after validation. They cannot prove “all callers everywhere.”
That unrestricted completion claim is never upgraded to verified by a reference query alone.

## UI, persistence and diagnostics

The native panel has a collapsible acceptance checklist with localized guard labels. It is hidden
for a single lightweight guard. Plans can reference requirement ids; plan status cannot change an
acceptance status. Tool protocol objects remain internal to assessment and the final summary.

The native session's existing versioned memory envelope stores the contract, bounded historical
evidence and hash/declaration receipts for changes. Old sessions without this field still load, but their mixed legacy user/context turns cannot safely
be promoted to actual-user requirements. Restate the goal to establish guards for those sessions;
a bare “continue” does not reconstruct missing acceptance coverage.
On resume all persisted evidence becomes historical and active requirements await fresh evidence.
Matching content alone does not restore validation authority: a fresh read and fresh validation
are needed. Permissions continue to reset under the existing session policy.

Acceptance counters record checks, reconciliation count, elapsed verification nanoseconds and Java
declaration-analysis time. They distinguish structured rejected claims from handled free-text
candidates; suppressed prose is not automatically counted as a hallucination. Deterministic fake
models exercise omitted work and false claims; real-model trials retain their separate oracle,
acceptance and timing results.

The highest-value next work is reviewed contract coverage and stronger project-specific acceptance
recipes, stable test identity integration and broader real-developer trials. See the
[Phase 5 evaluation](../evaluations/agent-phase5.md).
