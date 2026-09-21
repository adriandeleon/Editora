# Native agent trial measurements

Every supplied trial is included. Task mix differs; these small samples do not establish statistical significance or a model ranking. Sampling controls are requests, not a reproducibility guarantee. Medians use available measurements; task failures include incomplete tasks.

| Model / profile / run label | Trials | Completed | Oracle passed | Task passed | Incomplete | Task failed | Median rounds / calls / seconds | Semantic calls | Output-limit events / recoveries | Context failures | Approvals |
|---|---:|---:|---:|---:|---:|---:|---|---:|---|---:|---:|
| lmstudio: google/gemma-4-12b-qat / `b721aa3bbcee` / phase4-adaptive-gemma | 1 | 1 | 1 | 1 | 0 | 0 | 13.0 / 9.0 / 652.0 | 0 | 2 / 2 | 0 | 2 |
| lmstudio: google/gemma-4-12b-qat / `dc40151b0395` / phase4-adaptive-gemma | 3 | 1 | 1 | 1 | 2 | 2 | 11.0 / 8.0 / 513.1 | 0 | 5 / 4 | 0 | 6 |
| lmstudio: qwen/qwen3-coder-next / `12e36fa0cb36` / phase4-adaptive-qwen | 1 | 0 | 0 | 0 | 1 | 1 | 18.0 / 17.0 / 720.0 | 0 | 0 / 0 | 0 | 0 |
| lmstudio: qwen/qwen3-coder-next / `ddf4404072e2` / phase4-adaptive-qwen | 3 | 0 | 0 | 0 | 3 | 3 | 2.0 / 9.0 / 69.3 | 0 | 0 / 0 | 2 | 0 |
| lmstudio: qwen/qwen3-coder-next / `ddf4404072e2` / phase4-retention-qwen | 4 | 4 | 4 | 3 | 0 | 1 | 13.0 / 13.5 / 336.6 | 0 | 0 / 0 | 0 | 11 |

## Individual trials

- [phase4-adaptive-gemma-editora-endpoint-bug-3e51de5f-1789964652973.json](phase4/phase4-adaptive-gemma-editora-endpoint-bug-3e51de5f-1789964652973.json): editora-endpoint-bug, trial 1, PASS / COMPLETED; failures: VALIDATION_FAILURE.
- [phase4-adaptive-gemma-editora-endpoint-bug-3e51de5f-1789965734481.json](phase4/phase4-adaptive-gemma-editora-endpoint-bug-3e51de5f-1789965734481.json): editora-endpoint-bug, trial 2, PASS / COMPLETED; failures: VALIDATION_FAILURE.
- [phase4-adaptive-gemma-editora-stash-overflow-3e51de5f-1789965370737.json](phase4/phase4-adaptive-gemma-editora-stash-overflow-3e51de5f-1789965370737.json): editora-stash-overflow, trial 1, FAIL / NEEDS_INPUT; failures: INCORRECT_RESULT, MODEL_OUTPUT_LIMIT, OTHER_FAILURE, OUTPUT_LIMIT.
- [phase4-adaptive-gemma-editora-stash-overflow-3e51de5f-1789966251049.json](phase4/phase4-adaptive-gemma-editora-stash-overflow-3e51de5f-1789966251049.json): editora-stash-overflow, trial 2, FAIL / LIMIT; failures: INCORRECT_RESULT, ITERATION_EXHAUSTED, OTHER_FAILURE, PERMISSION_DENIED, PERMISSION_FRICTION, REPEATED_DISCOVERY, VALIDATION_FAILURE.
- [phase4-adaptive-qwen-billing-contract-migration-43b07504-1789967175807.json](phase4/phase4-adaptive-qwen-billing-contract-migration-43b07504-1789967175807.json): billing-contract-migration, trial 1, FAIL / LIMIT; failures: CONTEXT_EXHAUSTED, INCORRECT_RESULT.
- [phase4-adaptive-qwen-billing-contract-migration-43b07504-1789967972456.json](phase4/phase4-adaptive-qwen-billing-contract-migration-43b07504-1789967972456.json): billing-contract-migration, trial 2, FAIL / LIMIT; failures: CONTEXT_EXHAUSTED, INCORRECT_RESULT.
- [phase4-adaptive-qwen-editora-diff-newline-43b07504-1789967899815.json](phase4/phase4-adaptive-qwen-editora-diff-newline-43b07504-1789967899815.json): editora-diff-newline, trial 2, FAIL / CANCELLED; failures: INCORRECT_RESULT, OTHER_FAILURE, REPEATED_DISCOVERY, MISSING_REGRESSION_TESTS.
- [phase4-adaptive-qwen-editora-diff-newline-43b07504-1789967107541.json](phase4/phase4-adaptive-qwen-editora-diff-newline-43b07504-1789967107541.json): editora-diff-newline, trial 1, FAIL / CANCELLED; failures: INCORRECT_RESULT, OTHER_FAILURE, REPEATED_DISCOVERY, MISSING_REGRESSION_TESTS.
- [phase4-retention-qwen-billing-contract-migration-43b07504-1789969176424.json](phase4/phase4-retention-qwen-billing-contract-migration-43b07504-1789969176424.json): billing-contract-migration, trial 1, PASS / COMPLETED; failures: OTHER_FAILURE, REPEATED_DISCOVERY.
- [phase4-retention-qwen-billing-contract-migration-43b07504-1789969687599.json](phase4/phase4-retention-qwen-billing-contract-migration-43b07504-1789969687599.json): billing-contract-migration, trial 2, PASS / COMPLETED; failures: none.
- [phase4-retention-qwen-editora-diff-newline-43b07504-1789968789612.json](phase4/phase4-retention-qwen-editora-diff-newline-43b07504-1789968789612.json): editora-diff-newline, trial 1, FAIL / COMPLETED; failures: PERMISSION_DENIED, PERMISSION_FRICTION, REPEATED_DISCOVERY, VALIDATION_FAILURE, MISSING_REGRESSION_TESTS.
- [phase4-retention-qwen-editora-diff-newline-43b07504-1789969469022.json](phase4/phase4-retention-qwen-editora-diff-newline-43b07504-1789969469022.json): editora-diff-newline, trial 2, PASS / COMPLETED; failures: PERMISSION_DENIED, PERMISSION_FRICTION, REPEATED_DISCOVERY, VALIDATION_FAILURE.
