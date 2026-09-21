# Acceptance recipes and evidence refresh

Phase 6 turns the native acceptance gate into a guide for the current agent turn. The task contract
still states **what** the user asked for. `AgentAcceptanceRecipes` describes **how** current native
facts could establish a guard. A recipe cannot mark a requirement satisfied, run a command, approve
a tool or create a `USER_EXPLICIT` requirement. The existing tool policy controls every suggested
action.

## Progress and recovery

After a meaningful milestone (read, search, edit, save, semantic query, validation or diff review),
the runtime reconciles the contract once and sends changed evidence debt to the model. Each debt
entry has a requirement id, `TASK_INCOMPLETE` or `EVIDENCE_INCOMPLETE` (or user action), the missing
fact, the reason prior evidence became stale, a scope path, recipe provenance and candidate tools.
The next model request also carries a compact reminder outside transcript eviction. The completion
gate uses the same debt after a rejected final attempt; the user sees separate wording for missing
work and missing proof. This is guidance, not an automatic tool executor.

Built-in recipes suggest source edits for missing deliverables, saving a changed test followed by
targeted validation, fresh semantic references for observed callers, an untruncated native search
for a named removed API, and a broad verify only when explicitly requested. A project can add a
preferred validation operation per path prefix in `.editora/acceptance.json`:

```json
{
  "schemaVersion": 1,
  "rules": [
    {"pathPrefix": "src/main/java/com/editora/lsp/", "preferredValidation": "CHECK", "note": "LSP changes"}
  ]
}
```

This bounded JSON accepts only `pathPrefix`, `preferredValidation` (`TEST` or `CHECK`) and an optional short `note`.
It contains no command string, permission field or requirement authority. Invalid or oversized
files are ignored. `CHECK` remains a normal permission-controlled `run_validation` operation.
The selected recipe and its `WORKSPACE_CONFIGURATION` provenance appear in the contract view.
The project rule is advice; it cannot weaken an explicit broad-validation requirement.

## Freshness dependencies

The evidence ledger records why a fact was invalidated. A document edit stales prior build and
test execution, diagnostics, workspace-wide search absence and semantic reference queries. It
stales reads and symbols for the edited path while retaining independent reads and symbols in
other documents. External commands retain the more conservative execution invalidation because
their effects are not bounded to one document. Saving refreshes the tracked saved-state fact at the
same revision. Reconciliation checks leased document revisions and diagnostic server generations.
These rules remain conservative where dependencies are unknown: a new caller could appear in an
otherwise unobserved file, so a prior reference query needs refresh after any edit.

## Test identity and explicit acceptance

Fresh Surefire/Failsafe JUnit XML cases retain the report path, class, invocation name and a
normalized Java method when the name has a conventional `method`, `method(Type)` or
`method(Type)[index]` form. Source correlation requires the matching class path and current
parse-only declaration. Arbitrary display names are recorded as execution but cannot claim
new or changed source coverage. A requested `FooTest`/`FooTest#method` targeted run must have a
matching current report case and a matching `TARGETED_TEST` validation scope.

Actual user follow-ups can add, correct or remove a requirement by id through the Acceptance
panel's controls. They enter as normal user messages; model tools cannot perform these operations
on user requirements. Common negations such as “don't update docs” become current-diff
constraints. Scope words such as “except”, “instead” and “unless” raise a visible interpretation
warning when automatic interpretation is uncertain. The interpreter remains a bounded English
heuristic; review the displayed contract for consequential work.

“Don't run the full suite; just run FooTest” creates a targeted execution guard. The native
validation handler rejects broad `TEST`/`CHECK`/`PACKAGE` requests before execution, and the
generic process handler rejects recognized validation commands for that turn. A later actual-user
request to run the full suite lifts the restriction. Arbitrary external programs retain their
normal approval policy; the restriction is not a substitute for a process sandbox.

An explicit “no references to OldApi remain” guard can be supported by a fresh, untruncated,
whole-workspace native literal search with zero matches. This proves absence only in the bounded
accessible text search, not semantic or external dependency completeness. It is invalidated by
later edits.

For “trace” or “explain how” investigations, a bounded trace recipe requires current reads of at
least two distinct files plus a semantic reference observation or a native search hit in one of
those inspected files. A simple “inspect this file” request still needs only one read. This checks
that the agent followed a path; it does not verify the causal correctness of its prose.

## Limits

Recipes do not yet infer the ideal test selector from arbitrary frameworks, run validations
automatically, or prove the quality of a passing regression test. Test case correlation remains
conservative for unusual JUnit display names, nested layouts and non-Java frameworks. The search
and LSP boundaries are explicitly scoped. The post-mutation tail still depends on a model following
the guidance, so live evaluation measures actual recovery rounds separately from deterministic
correctness.
