# Native agent trial measurements

Every supplied completed trial report is included. Interrupted batches are listed separately without invented final outcomes. Task mix differs; these small samples do not establish statistical significance or a model ranking. Sampling controls are requests, not a reproducibility guarantee. Medians use available measurements; task failures include incomplete tasks.

| Model / profile / run label | Trials | Completed | Oracle passed | Task passed | Incomplete | Task failed | Median rounds / calls / seconds | Semantic calls | Output-limit events / recoveries | Context failures | Approvals |
|---|---:|---:|---:|---:|---:|---:|---|---:|---|---:|---:|
| lmstudio: qwen/qwen3-coder-next / `12e36fa0cb36` / phase5-contract-qwen | 1 | 0 | 0 | 0 | 1 | 1 | 18.0 / 16.0 / 720.0 | 0 | 0 / 0 | 0 | 5 |
| lmstudio: qwen/qwen3-coder-next / `ddf4404072e2` / phase5-contract-qwen | 1 | 0 | 0 | 0 | 1 | 1 | 18.0 / 24.0 / 720.0 | 0 | 0 / 0 | 0 | 2 |
| lmstudio: qwen/qwen3-coder-next / `ddf4404072e2` / phase5-corrected-qwen | 2 | 0 | 2 | 0 | 2 | 2 | 18.5 / 23.5 / 720.0 | 1 | 0 / 0 | 0 | 8 |

## Acceptance observations

Guard satisfaction is heuristic coverage, separate from behavioral oracle success. Rejected structured claims and suppressed free-text drafts are different measurements; no structured claims does not mean no hallucinations. Timing is cumulative per task, including repeated checks, without extra inference.

| Trial | Satisfied / active guards | Rejected structured claims | Free-text candidates handled | Checks / reconciliations | Completion check ms / all reconciliation ms | Java declaration ms | Regression quality |
|---|---:|---:|---:|---|---:|---:|---|
| [phase5-contract-qwen-billing-contract-migration-43b07504-1790013916039.json](phase5/phase5-contract-qwen-billing-contract-migration-43b07504-1790013916039.json) | 1 / 4 | 0 | 0 | 0 / 1 | 0.000 / N/A | 0.000 | UNVERIFIED |
| [phase5-contract-qwen-editora-diff-newline-43b07504-1790013192615.json](phase5/phase5-contract-qwen-editora-diff-newline-43b07504-1790013192615.json) | 1 / 4 | 0 | 0 | 0 / 1 | 0.000 / N/A | 0.000 | UNVERIFIED |
| [phase5-corrected-qwen-billing-contract-migration-43b07504-1790015751411.json](phase5/phase5-corrected-qwen-billing-contract-migration-43b07504-1790015751411.json) | 2 / 4 | 0 | 0 | 0 / 1 | 0.000 / 1.223 | 447.682 | UNVERIFIED |
| [phase5-corrected-qwen-editora-diff-newline-43b07504-1790015026372.json](phase5/phase5-corrected-qwen-editora-diff-newline-43b07504-1790015026372.json) | 3 / 4 | 0 | 0 | 0 / 2 | 0.000 / 3.897 | 0.000 | UNVERIFIED |

## Individual trials

- [phase5-contract-qwen-billing-contract-migration-43b07504-1790013916039.json](phase5/phase5-contract-qwen-billing-contract-migration-43b07504-1790013916039.json): billing-contract-migration, trial 1, FAIL / CANCELLED; failures: CANCELLED, INCORRECT_RESULT, REPEATED_DISCOVERY, VALIDATION_FAILURE, TIME_BUDGET_EXHAUSTED.
- [phase5-contract-qwen-editora-diff-newline-43b07504-1790013192615.json](phase5/phase5-contract-qwen-editora-diff-newline-43b07504-1790013192615.json): editora-diff-newline, trial 1, FAIL / CANCELLED; failures: CANCELLED, INCORRECT_RESULT, PERMISSION_DENIED, PERMISSION_FRICTION, REPEATED_DISCOVERY, VALIDATION_FAILURE, TIME_BUDGET_EXHAUSTED, MISSING_REGRESSION_TESTS.
- [phase5-corrected-qwen-billing-contract-migration-43b07504-1790015751411.json](phase5/phase5-corrected-qwen-billing-contract-migration-43b07504-1790015751411.json): billing-contract-migration, trial 1, FAIL / CANCELLED; failures: CANCELLED, OTHER_FAILURE, REPEATED_DISCOVERY, STALE_CONTEXT, TIME_BUDGET_EXHAUSTED.
- [phase5-corrected-qwen-editora-diff-newline-43b07504-1790015026372.json](phase5/phase5-corrected-qwen-editora-diff-newline-43b07504-1790015026372.json): editora-diff-newline, trial 1, FAIL / CANCELLED; failures: CANCELLED, PERMISSION_DENIED, PERMISSION_FRICTION, REPEATED_DISCOVERY, VALIDATION_FAILURE, TIME_BUDGET_EXHAUSTED, MISSING_REGRESSION_TESTS.

## Interrupted attempts

- [interrupted-pre-fix-batch.json](phase5/interrupted-pre-fix-batch.json): phase5-contract-qwen, editora-diff-newline, trial 2; no final oracle/task-success measurement. Stopped obsolete compiled runtime after review found normal apply_edits missing acceptance callback. Completed failures and partial metadata log retained. Interrupted attempt has no final oracle or task-success measurement.
