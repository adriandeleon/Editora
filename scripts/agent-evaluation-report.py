#!/usr/bin/env python3
"""Aggregate every preserved trial by model/profile; retain failure counts and per-run links."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
from statistics import median


def summarize(paths):
    groups = defaultdict(list)
    interrupted = []
    for path in paths:
        report = json.loads(path.read_text())
        if report.get("kind") == "INTERRUPTED_EVALUATION_BATCH":
            interrupted.append((path, report))
        if report.get("kind") != "AUTONOMOUS_CODING_EVALUATION":
            continue
        key = (report["provider"], report["model"], report.get("profileFingerprint", "legacy-unrecorded"), report.get("label", "unlabelled"))
        groups[key].append((path, report))
    lines = ["# Native agent trial measurements", "",
             "Every supplied completed trial report is included. Interrupted batches are listed separately without invented final outcomes. Task mix differs; these small samples do not establish statistical significance or a model ranking. Sampling controls are requests, not a reproducibility guarantee. Medians use available measurements; task failures include incomplete tasks.", "",
             "| Model / profile / run label | Trials | Completed | Oracle passed | Task passed | Incomplete | Task failed | Median rounds / calls / seconds | Semantic calls | Output-limit events / recoveries | Context failures | Approvals |",
             "|---|---:|---:|---:|---:|---:|---:|---|---:|---|---:|---:|"]
    for (provider, model, fingerprint, label), trials in sorted(groups.items()):
        values = [r for _, r in trials]
        count = lambda predicate: sum(bool(predicate(r)) for r in values)
        def med(field, divisor=1):
            measured = [r[field] / divisor for r in values if field in r]
            return f"{median(measured):.1f}" if measured else "N/A"
        output_limits = sum(sum(x.get("stop") in ("length", "max_tokens") or x.get("failure") in ("OUTPUT_LIMIT", "MODEL_OUTPUT_LIMIT")
                                for x in r.get("modelRounds", [])) for r in values)
        lines.append(f"| {provider}: {model} / `{fingerprint[:12]}` / {label} | {len(values)} | "
                     f"{count(lambda r: r['completionState'] == 'COMPLETED')} | "
                     f"{count(lambda r: r.get('oraclePassed') and not r.get('humanReviewRequired'))} | "
                     f"{count(lambda r: r['taskSuccess'] == 'PASS')} | "
                     f"{count(lambda r: r['completionState'] not in ('COMPLETED', 'FAILED'))} | "
                     f"{count(lambda r: r['taskSuccess'] == 'FAIL')} | "
                     f"{med('iterations')} / {med('calls')} / {med('agentElapsedMs', 1000)} | "
                     f"{sum(r.get('semanticQueries', 0) for r in values)} | "
                     f"{output_limits} / {sum(r.get('outputRecoveries', 0) for r in values)} | "
                     f"{count(lambda r: 'CONTEXT_EXHAUSTED' in r.get('failureCategories', []))} | "
                     f"{sum(r.get('approvalsRequested', 0) for r in values)} |")
    measured = [(p, r) for trials in groups.values() for p, r in trials if "acceptance" in r]
    if measured:
        lines += ["", "## Acceptance observations", "",
                  "Guard satisfaction is heuristic coverage, separate from behavioral oracle success. Rejected structured claims and suppressed free-text drafts are different measurements; no structured claims does not mean no hallucinations. Timing is cumulative per task, including repeated checks, without extra inference.", "",
                  "| Trial | Satisfied / active guards | Rejected structured claims | Free-text candidates handled | Checks / reconciliations | Completion check ms / all reconciliation ms | Java declaration ms | Regression quality |",
                  "|---|---:|---:|---:|---|---:|---:|---|"]
        for path, r in measured:
            a = r["acceptance"]
            active = [x for x in a.get("requirements", []) if x.get("state") != "SUPERSEDED"]
            satisfied = sum(x.get("state") == "SATISFIED" for x in active)
            timing = lambda field: f"{a[field] / 1_000_000:.3f}" if field in a else "N/A"
            lines.append(f"| [{path.name}]({path.as_posix()}) | {satisfied} / {len(active)} | "
                         f"{a.get('unsupportedStructuredClaims', 'N/A')} | {a.get('unstructuredCandidatesHandled', 'N/A')} | "
                         f"{a.get('checks', 'N/A')} / {a.get('reconciliations', 'N/A')} | "
                         f"{timing('verificationNanos')} / {timing('reconciliationNanos')} | {timing('javaDeclarationNanos')} | "
                         f"{r.get('regressionQuality', {}).get('state', 'N/A')} |")
    lines += ["", "## Individual trials", ""]
    for trials in groups.values():
        for path, r in trials:
            lines.append(f"- [{path.name}]({path.as_posix()}): {r['scenario']}, trial {r.get('trial', 'unrecorded')}, "
                         f"{r['taskSuccess']} / {r['completionState']}; failures: {', '.join(r.get('failureCategories', [])) or 'none'}.")
    if interrupted:
        lines += ["", "## Interrupted attempts", ""]
        for path, r in interrupted:
            lines.append(f"- [{path.name}]({path.as_posix()}): {r.get('label', 'unlabelled')}, "
                         f"{r.get('interruptedScenario', 'unknown scenario')}, trial {r.get('interruptedTrial', 'unknown')}; "
                         f"no final oracle/task-success measurement. {r.get('reason', '')}")
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directories", nargs="+", type=Path)
    args = parser.parse_args()
    files = sorted({p for directory in args.directories for p in directory.glob("*.json")})
    print(summarize(files), end="")
