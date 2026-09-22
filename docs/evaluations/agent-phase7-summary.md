# Phase 7: all live trials

Raw reports are linked unchanged. A pass requires accepted completion, required edits and independent checks; correct saved code alone does not pass. `Human` means explanation quality has no automated behavioral oracle. All trials requested temperature 0 and seed 41, 32 rounds and a six-minute agent deadline. Elapsed time includes independent oracle/fixture work after the turn, so it can exceed six minutes.

Calls are model requests / measured tool-handler invocations: denials, schema/loop rejection and runtime-owned `execution_control` are not handler-wrapper executions. Rounds include interrupted model requests. First edit is an observed editor mutation, not necessarily a saved or correct change. Before the no-op correction, the changed flag could include unchanged replacements: in particular the cross-model Gemma bug tail undercounts real post-edit work. Raw metadata is preserved, not retroactively corrected. A dash means no edit was observed.

| Cohort / model / task / trial | Full task / state | Oracle | Model rounds | Calls requested / handlers | Before first / after last edit | First edit (s) | No-progress signals | Repeated arguments | Validation handlers | Ready at round | Elapsed (s) |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| [prefix-baseline / Qwen / ledger-tests / 1](phase7/prefix-baseline/phase7-qwen-ledger-tests-43b07504-1790028872934.json) | FAIL / LIMIT | fail | 3 | 3 / 3 | — / — | — | 0 | 0 | 0 | — | 363.2 |
| [prefix-baseline / Qwen / ledger-tests / 2](phase7/prefix-baseline/phase7-qwen-ledger-tests-43b07504-1790029238051.json) | FAIL / LIMIT | fail | 3 | 3 / 3 | — / — | — | 0 | 0 | 0 | — | 362.9 |
| [tail-baseline / Qwen / ledger-tests / 1](phase7/tail-baseline/phase7-final-qwen-ledger-tests-43b07504-1790029962689.json) | FAIL / LIMIT | fail | 5 | 8 / 8 | — / — | — | 0 | 1 | 0 | — | 362.7 |
| [tail-baseline / Qwen / ledger-tests / 2](phase7/tail-baseline/phase7-final-qwen-ledger-tests-43b07504-1790030327457.json) | FAIL / LIMIT | pass | 7 | 10 / 10 | 4 / 2 | 169.0 | 0 | 1 | 0 | — | 362.9 |
| [corpus / Qwen / editora-diff-documentation / 1](phase7/corpus/phase7-stable-qwen-editora-diff-documentation-43b07504-1790033642418.json) | FAIL / LIMIT | pass | 18 | 22 / 19 | 6 / 7 | 146.4 | 3 | 5 | 2 | 13 | 361.5 |
| [corpus / Qwen / editora-diff-newline / 1](phase7/corpus/phase7-stable-qwen-editora-diff-newline-43b07504-1790032772983.json) | FAIL / LIMIT | fail | 14 | 18 / 18 | 2 / 11 | 71.6 | 4 | 10 | 2 | — | 362.4 |
| [corpus / Qwen / editora-save-cancellation / 1](phase7/corpus/phase7-stable-qwen-editora-save-cancellation-43b07504-1790033277953.json) | FAIL / NEEDS_INPUT | fail | 18 | 24 / 21 | — / — | — | 4 | 9 | 0 | — | 402.6 |
| [corpus / Qwen / editora-save-understanding / 1](phase7/corpus/phase7-stable-qwen-editora-save-understanding-43b07504-1790031685063.json) | FAIL / LIMIT | Human | 2 | 7 / 7 | — / — | — | 0 | 0 | 0 | — | 45.1 |
| [corpus / Qwen / ledger-bug / 1](phase7/corpus/phase7-stable-qwen-ledger-bug-43b07504-1790032049687.json) | FAIL / LIMIT | pass | 18 | 19 / 15 | 8 / 9 | 213.4 | 5 | 2 | 0 | — | 362.5 |
| [corpus / Qwen / ledger-refactor / 1](phase7/corpus/phase7-stable-qwen-ledger-refactor-43b07504-1790032408728.json) | FAIL / NEEDS_INPUT | fail | 16 | 26 / 19 | — / — | — | 3 | 5 | 0 | — | 356.3 |
| [corpus / Qwen / ledger-tests / 1](phase7/corpus/phase7-stable-qwen-ledger-tests-43b07504-1790031264652.json) | FAIL / LIMIT | pass | 8 | 12 / 12 | 4 / 3 | 178.4 | 0 | 0 | 1 | 7 | 363.9 |
| [corpus / Qwen / ledger-tests / 2](phase7/corpus/phase7-stable-qwen-ledger-tests-43b07504-1790031630787.json) | FAIL / LIMIT | fail | 6 | 9 / 9 | — / — | — | 0 | 1 | 0 | — | 363.7 |
| [hardening / Gemma / ledger-bug / 1](phase7/hardening/phase7-hardening-gemma-ledger-bug-3e51de5f-1790034838317.json) | FAIL / LIMIT | fail | 29 | 28 / 27 | 6 / 1 | 35.6 | 3 | 10 | 4 | — | 361.0 |
| [hardening / Gemma / ledger-tests / 1](phase7/hardening/phase7-hardening-gemma-ledger-tests-3e51de5f-1790034938936.json) | PASS / COMPLETED | pass | 18 | 17 / 17 | 14 / 3 | 82.0 | 3 | 6 | 1 | 17 | 99.0 |
| [hardening / Qwen / ledger-bug / 1](phase7/hardening/phase7-hardening-qwen-ledger-bug-43b07504-1790034217941.json) | PASS / COMPLETED | pass | 9 | 11 / 11 | 4 / 4 | 106.7 | 1 | 0 | 1 | 7 | 169.8 |
| [hardening / Qwen / ledger-tests / 1](phase7/hardening/phase7-hardening-qwen-ledger-tests-43b07504-1790034469900.json) | FAIL / NEEDS_INPUT | pass | 14 | 16 / 16 | 2 / 11 | 55.4 | 3 | 8 | 1 | 5 | 250.4 |
| [noop-correction / Gemma / ledger-bug / 1](phase7/noop-correction/phase7-noop-gemma-ledger-bug-3e51de5f-1790035345043.json) | PASS / COMPLETED | pass | 12 | 11 / 11 | 8 / 3 | 41.9 | 0 | 1 | 2 | 11 | 57.3 |

## Distributions

Min / median / max. Discovery and tail distributions include only trials with an observed mutation; no-edit trials are listed separately rather than assigned a fictitious zero latency. No-progress signals are threshold/readiness events, not a count of successful recoveries. Repeated arguments need not be redundant if revisions changed.

| Cohort / model | Trials; full passes; coding-oracle passes | No-edit trials | Rounds | Calls requested | Seconds | Before first edit | After last edit | No-progress signals |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| prefix-baseline / qwen/qwen3-coder-next | 2; 0; 0/2 | 2 | 3 / 3 / 3 | 3 / 3 / 3 | 362.9 / 363.05 / 363.2 | — | — | 0 / 0 / 0 |
| tail-baseline / qwen/qwen3-coder-next | 2; 0; 1/2 | 1 | 5 / 6 / 7 | 8 / 9 / 10 | 362.7 / 362.8 / 362.9 | 4 / 4 / 4 | 2 / 2 / 2 | 0 / 0 / 0 |
| corpus / qwen/qwen3-coder-next | 8; 0; 3/7 | 4 | 2 / 15 / 18 | 7 / 18.5 / 26 | 45.1 / 362.45 / 402.6 | 2 / 5 / 8 | 3 / 8 / 11 | 0 / 3 / 5 |
| hardening / google/gemma-4-12b-qat | 2; 1; 1/2 | 0 | 18 / 23.5 / 29 | 17 / 22.5 / 28 | 99 / 230 / 361 | 6 / 10 / 14 | 1 / 2 / 3 | 3 / 3 / 3 |
| hardening / qwen/qwen3-coder-next | 2; 1; 2/2 | 0 | 9 / 11.5 / 14 | 11 / 13.5 / 16 | 169.8 / 210.1 / 250.4 | 2 / 3 / 4 | 4 / 7.5 / 11 | 1 / 2 / 3 |
| noop-correction / google/gemma-4-12b-qat | 1; 1; 1/1 | 0 | 12 / 12 / 12 | 11 / 11 / 11 | 57.3 / 57.3 / 57.3 | 8 / 8 / 8 | 3 / 3 / 3 | 0 / 0 / 0 |

## Provenance

The corpus fingerprint is shared across all trials. Artifact fingerprints include compiled classes and filtered resources, including Maven build time; distinct artifact hashes need not mean runtime source changed. The cross-model cohort pins the build-time property. `phase7-final-qwen` is an intermediate retained label, not the final implementation.

- prefix-baseline / qwen/qwen3-coder-next: `1662678f783581ab84cd355a408fde5dafcc5401c71f88f8c9512c7cfeda2022`
- tail-baseline / qwen/qwen3-coder-next: `38134d6c6f45909a16af1f3dc7298a32910d9e03dcbfea30def9920066d04452`
- corpus / qwen/qwen3-coder-next: `1b9e352b1edb8a82152737864395c080feb4e7df9bd7d2e94e4eed42bfab3047`, `dd50c6464341002ec5529d37c4118ade370426b0b29010d2c4499ddb9cd6add9`
- hardening / google/gemma-4-12b-qat: `5dd40b3dd642594ea4e84bffad2f245b401277162d18cf2f9c5fe5a2d4f4e9df`
- hardening / qwen/qwen3-coder-next: `5dd40b3dd642594ea4e84bffad2f245b401277162d18cf2f9c5fe5a2d4f4e9df`
- noop-correction / google/gemma-4-12b-qat: `3d6e91df444d0ff82e651eb81a1ac7ae4bc3742cb9a5dcf19beb5a70edf1803c`
