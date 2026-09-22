# Agent trajectory measurements

Labels use tool activity and native progress events. COMPLETE includes candidate finals, not necessarily accepted completion. Historical reports lack per-round revision/range freshness; REDUNDANT is only assigned where native progress telemetry exists. UNKNOWN/None indicate missing round alignment in early reports. Validation-before-save is an observed ordering signal, not proof that the workspace was dirty. Different budgets, profiles and task mixes are not a controlled model ranking.

| Trial | Task result | Rounds before first edit / after last edit | Literal pattern queries | Validation before observed save | Trajectory |
|---|---|---:|---:|---:|---|
| phase7-qwen-ledger-tests-43b07504-1790028872934.json | FAIL | — / — (no edit observed) | 0 | 0 | INSPECT → DISCOVER → RECOVER |
| phase7-qwen-ledger-tests-43b07504-1790029238051.json | FAIL | — / — (no edit observed) | 0 | 0 | INSPECT → INSPECT → RECOVER |
| phase7-final-qwen-ledger-tests-43b07504-1790029962689.json | FAIL | — / — (no edit observed) | 0 | 0 | DISCOVER → INSPECT → INSPECT → INSPECT → RECOVER |
| phase7-final-qwen-ledger-tests-43b07504-1790030327457.json | FAIL | 4 / 2 | 0 | 0 | DISCOVER → INSPECT → INSPECT → INSPECT → EDIT → SAVE → RECOVER |
| phase7-stable-qwen-editora-diff-documentation-43b07504-1790033642418.json | FAIL | 6 / 7 | 0 | 0 | RECOVER → INSPECT → INSPECT → INSPECT → REDUNDANT → REDUNDANT → EDIT → SAVE → VALIDATE → INSPECT → EDIT → SAVE → VALIDATE → COMPLETE → REDUNDANT → REDUNDANT → REDUNDANT → RECOVER |
| phase7-stable-qwen-editora-diff-newline-43b07504-1790032772983.json | FAIL | 2 / 11 | 0 | 2 | DISCOVER → INSPECT → EDIT → INSPECT → RECOVER → REDUNDANT → INSPECT → INSPECT → REDUNDANT → REDUNDANT → REDUNDANT → REDUNDANT → RECOVER → RECOVER |
| phase7-stable-qwen-editora-save-cancellation-43b07504-1790033277953.json | FAIL | — / — (no edit observed) | 0 | 0 | DISCOVER → INSPECT → INSPECT → INSPECT → REDUNDANT → REDUNDANT → INSPECT → INSPECT → INSPECT → INSPECT → REDUNDANT → REDUNDANT → REDUNDANT → REDUNDANT → REDUNDANT → RECOVER → RECOVER → RECOVER |
| phase7-stable-qwen-editora-save-understanding-43b07504-1790031685063.json | FAIL | — / — (no edit observed) | 0 | 0 | DISCOVER → INSPECT |
| phase7-stable-qwen-ledger-bug-43b07504-1790032049687.json | FAIL | 8 / 9 | 1 | 0 | DISCOVER → INSPECT → INSPECT → DISCOVER → REDUNDANT → INSPECT → DISCOVER → REDUNDANT → EDIT → REFRESH_EVIDENCE → REDUNDANT → RECOVER → REDUNDANT → RECOVER → RECOVER → RECOVER → RECOVER → RECOVER |
| phase7-stable-qwen-ledger-refactor-43b07504-1790032408728.json | FAIL | — / — (no edit observed) | 0 | 0 | DISCOVER → INSPECT → DISCOVER → INSPECT → DISCOVER → INSPECT → REDUNDANT → REDUNDANT → REDUNDANT → REDUNDANT → REDUNDANT → RECOVER → RECOVER → REDUNDANT → RECOVER → REDUNDANT |
| phase7-stable-qwen-ledger-tests-43b07504-1790031264652.json | FAIL | 4 / 3 | 1 | 0 | DISCOVER → INSPECT → DISCOVER → INSPECT → EDIT → SAVE → VALIDATE → RECOVER |
| phase7-stable-qwen-ledger-tests-43b07504-1790031630787.json | FAIL | — / — (no edit observed) | 1 | 0 | DISCOVER → INSPECT → DISCOVER → INSPECT → INSPECT → RECOVER |
| phase7-hardening-gemma-ledger-bug-3e51de5f-1790034838317.json | FAIL | 6 / 1 | 0 | 1 | INSPECT → DISCOVER → INSPECT → DISCOVER → INSPECT → DISCOVER → EDIT → SAVE → DISCOVER → INSPECT → EDIT → SAVE → RECOVER → RECOVER → RECOVER → REDUNDANT → REDUNDANT → EDIT → EDIT → EDIT → INSPECT → EDIT → EDIT → RECOVER → RECOVER → EDIT → EDIT → EDIT → RECOVER |
| phase7-hardening-gemma-ledger-tests-3e51de5f-1790034938936.json | PASS | 14 / 3 | 0 | 0 | DISCOVER → DISCOVER → INSPECT → DISCOVER → INSPECT → INSPECT → RECOVER → REDUNDANT → ORIENT → INSPECT → REDUNDANT → REDUNDANT → REDUNDANT → REDUNDANT → EDIT → SAVE → VALIDATE → COMPLETE |
| phase7-hardening-qwen-ledger-bug-43b07504-1790034217941.json | PASS | 4 / 4 | 0 | 0 | DISCOVER → INSPECT → DISCOVER → INSPECT → EDIT → SAVE → VALIDATE → INSPECT → COMPLETE |
| phase7-hardening-qwen-ledger-tests-43b07504-1790034469900.json | FAIL | 2 / 11 | 0 | 0 | DISCOVER → INSPECT → EDIT → SAVE → VALIDATE → COMPLETE → REDUNDANT → REDUNDANT → REDUNDANT → REDUNDANT → REDUNDANT → REDUNDANT → REDUNDANT → REDUNDANT |
| phase7-noop-gemma-ledger-bug-3e51de5f-1790035345043.json | PASS | 8 / 3 | 0 | 0 | INSPECT → DISCOVER → INSPECT → DISCOVER → INSPECT → DISCOVER → INSPECT → RECOVER → EDIT → SAVE → VALIDATE → COMPLETE |

## Distributions

Range and median include failures. Historical absence of progress events is not zero no-progress.

- google/gemma-4-12b-qat, phase7-hardening-gemma: 2 trials, 1 full-task passes; rounds min/median/max 18/23.5/29; seconds min/median/max 99.0/230.0/361.0.
- google/gemma-4-12b-qat, phase7-noop-gemma: 1 trials, 1 full-task passes; rounds min/median/max 12/12/12; seconds min/median/max 57.3/57.3/57.3.
- qwen/qwen3-coder-next, phase7-final-qwen: 2 trials, 0 full-task passes; rounds min/median/max 5/6.0/7; seconds min/median/max 362.7/362.8/362.9.
- qwen/qwen3-coder-next, phase7-hardening-qwen: 2 trials, 1 full-task passes; rounds min/median/max 9/11.5/14; seconds min/median/max 169.8/210.1/250.4.
- qwen/qwen3-coder-next, phase7-qwen: 2 trials, 0 full-task passes; rounds min/median/max 3/3.0/3; seconds min/median/max 362.9/363.0/363.2.
- qwen/qwen3-coder-next, phase7-stable-qwen: 8 trials, 0 full-task passes; rounds min/median/max 2/15.0/18; seconds min/median/max 45.1/362.5/402.6.
