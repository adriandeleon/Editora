# Native agent trial measurements

Every supplied completed trial report is included. Interrupted batches are listed separately without invented final outcomes. Task mix differs; these small samples do not establish statistical significance or a model ranking. Sampling controls are requests, not a reproducibility guarantee. Medians use available measurements; task failures include incomplete tasks.

| Model / profile / run label | Trials | Completed | Oracle passed | Task passed | Incomplete | Task failed | Median rounds / calls / seconds | Semantic calls | Output-limit events / recoveries | Context failures | Approvals |
|---|---:|---:|---:|---:|---:|---:|---|---:|---|---:|---:|
| lmstudio: qwen/qwen3-coder-next / `ddf4404072e2` / phase6-completion-signal | 1 | 1 | 1 | 1 | 0 | 0 | 8.0 / 8.0 / 307.0 | 0 | 0 / 0 | 0 | 1 |
| lmstudio: qwen/qwen3-coder-next / `ddf4404072e2` / phase6-final | 4 | 0 | 1 | 0 | 4 | 4 | 9.0 / 11.5 / 360.0 | 3 | 0 / 0 | 0 | 1 |

## Acceptance observations

Guard satisfaction is heuristic coverage, separate from behavioral oracle success. Rejected structured claims and suppressed free-text drafts are different measurements; no structured claims does not mean no hallucinations. Timing is cumulative per task, including repeated checks, without extra inference.

| Trial | Satisfied / active guards | Rejected structured claims | Free-text candidates handled | Checks / reconciliations | Completion check ms / all reconciliation ms | Java declaration ms | Regression quality |
|---|---:|---:|---:|---|---:|---:|---|
| [live-diff-documentation.json](phase6/live-diff-documentation.json) | 0 / 5 | 0 | 0 | 0 / 7 | 0.000 / 5.897 | 0.000 | UNVERIFIED |
| [live-ledger-refactor.json](phase6/live-ledger-refactor.json) | 1 / 3 | 0 | 0 | 0 / 6 | 0.000 / 4.277 | 360.492 | UNVERIFIED |
| [live-ledger-tests.json](phase6/live-ledger-tests.json) | 1 / 1 | 0 | 0 | 0 / 11 | 0.000 / 8.529 | 17.035 | UNVERIFIED |
| [live-save-understanding.json](phase6/live-save-understanding.json) | 1 / 2 | 0 | 0 | 0 / 9 | 0.000 / 8.333 | 0.000 | UNVERIFIED |
| [live-ledger-tests-completion-signal.json](phase6/live-ledger-tests-completion-signal.json) | 1 / 1 | 4 | 1 | 2 / 10 | 4.426 / 17.083 | 336.594 | UNVERIFIED |

## Individual trials

- [live-diff-documentation.json](phase6/live-diff-documentation.json): editora-diff-documentation, trial 1, FAIL / CANCELLED; failures: CANCELLED, INCORRECT_RESULT, REPEATED_DISCOVERY, TIME_BUDGET_EXHAUSTED, MISSING_REGRESSION_TESTS.
- [live-ledger-refactor.json](phase6/live-ledger-refactor.json): ledger-refactor, trial 1, FAIL / CANCELLED; failures: CANCELLED, INCORRECT_RESULT, OTHER_FAILURE, REPEATED_DISCOVERY, TIME_BUDGET_EXHAUSTED.
- [live-ledger-tests.json](phase6/live-ledger-tests.json): ledger-tests, trial 1, FAIL / CANCELLED; failures: CANCELLED, TIME_BUDGET_EXHAUSTED.
- [live-save-understanding.json](phase6/live-save-understanding.json): editora-save-understanding, trial 1, FAIL / CANCELLED; failures: CANCELLED, TIME_BUDGET_EXHAUSTED.
- [live-ledger-tests-completion-signal.json](phase6/live-ledger-tests-completion-signal.json): ledger-tests, trial 1, PASS / COMPLETED; failures: none.
