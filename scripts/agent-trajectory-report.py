#!/usr/bin/env python3
"""Classify observed tool rounds without interpreting model prose as progress or success."""
import argparse
import collections
import json
import statistics
from pathlib import Path


def activity(call):
    if call.get("error"):
        return "RECOVER"
    if call.get("changed"):
        return "EDIT"
    tool = call.get("tool", "")
    if tool == "read_file":
        return "INSPECT"
    if tool in {"find_files", "list_files", "search_text", "semantic_capabilities"}:
        return "DISCOVER"
    if tool == "semantic_query":
        return "DISCOVER" if call.get("operation") == "workspace_symbols" else "INSPECT"
    if tool in {"update_plan", "execution_control"}:
        return "PLAN"
    if tool == "save_files":
        return "SAVE"
    if tool == "run_validation" or tool == "run_command" and call.get("purpose") == "validate":
        return "VALIDATE"
    if tool in {"task_contract", "task_evidence", "review_changes", "diagnostics"}:
        return "REFRESH_EVIDENCE"
    if tool == "completion_claims":
        return "COMPLETE"
    return "ORIENT"


def trajectory(report):
    calls = report.get("toolCalls", [])
    runtime = {s["iteration"]: s for s in report.get("executionTrajectory", [])}
    classified = []
    # Mixed rounds keep every activity; the primary label only makes transitions readable.
    priority = ["EDIT", "RECOVER", "VALIDATE", "SAVE", "INSPECT", "DISCOVER", "PLAN", "REFRESH_EVIDENCE", "COMPLETE", "ORIENT", "UNKNOWN"]
    for round_ in report.get("modelRounds", []):
        n = round_["iteration"]
        selected = [c for c in calls if c.get("iteration") == n]
        kinds = {activity(c) for c in selected}
        state = runtime.get(n)
        native_activities = state.get("round_activity", []) if state else []
        kinds.update(k for k in native_activities if k in priority)
        if not kinds:
            kinds = {"RECOVER" if round_.get("failure") else "COMPLETE" if round_.get("stop") in {"stop", "end_turn"} else "UNKNOWN"}
        primary = next(k for k in priority if k in kinds)
        if state and (selected or native_activities) and not state.get("new_state_observed") and state.get("no_progress_rounds", 0) >= 2 and primary != "RECOVER":
            primary = "REDUNDANT"
        classified.append({"round": n, "activity": primary, "activities": sorted(kinds),
                           "calls": len(selected), "elapsedMs": round_.get("elapsedMs"),
                           "progressObserved": None if state is None else state.get("new_state_observed"),
                           "completionReady": None if state is None else state.get("completion_ready")})
    mutation = [c["iteration"] for c in calls if c.get("changed") and "iteration" in c]
    unknown_mutation = any(c.get("changed") and "iteration" not in c for c in calls)
    def validation(c):
        return c.get("tool") == "run_validation" or c.get("tool") == "run_command" and c.get("purpose") == "validate"
    validations = [c for c in calls if validation(c)]
    first = min(mutation) if mutation else None
    last = max(mutation) if mutation else None
    pre_save = 0
    dirty = False
    for c in calls:
        dirty |= bool(c.get("changed"))
        if c.get("tool") == "save_files" and not c.get("error"):
            dirty = False
        if dirty and validation(c):
            pre_save += 1
    return {"rounds": classified, "transitions": dict(collections.Counter(
                f'{a["activity"]} → {b["activity"]}' for a, b in zip(classified, classified[1:]))),
            "roundsBeforeFirstMutation": None if unknown_mutation else len(classified) if first is None else first - 1,
            "roundsAfterLastMutation": None if unknown_mutation else 0 if last is None else len(classified) - last,
            "validationBeforeObservedSave": pre_save,
            "validationAttempts": len(validations),
            "literalPatternQueries": sum(c.get("tool") == "search_text" and c.get("containsPatternSyntax", False)
                                         and c.get("searchMode", "LITERAL") == "LITERAL" for c in calls),
            "runtimeProgressAvailable": bool(runtime)}


def load(paths):
    for path in paths:
        data = json.loads(path.read_text())
        if data.get("kind") == "AUTONOMOUS_CODING_EVALUATION":
            yield str(path), data
        for item in data.get("reports", []):
            if isinstance(item, dict) and "metrics" in item:
                yield str(path) + "#" + item["id"], item["metrics"]


def report(rows):
    lines = ["# Agent trajectory measurements", "",
             "Labels use tool activity and native progress events. COMPLETE includes candidate finals, not necessarily accepted completion. "
             "Historical reports lack per-round revision/range freshness; REDUNDANT is only assigned where native progress telemetry exists. UNKNOWN/None indicate missing round alignment in early reports. "
             "Validation-before-save is an observed ordering signal, not proof that the workspace was dirty. "
             "Different budgets, profiles and task mixes are not a controlled model ranking.", "",
             "| Trial | Task result | Rounds before first edit / after last edit | Literal pattern queries | Validation before observed save | Trajectory |",
             "|---|---|---:|---:|---:|---|"]
    for name, data in rows:
        t = trajectory(data)
        sequence = " → ".join(r["activity"] for r in t["rounds"])
        calls = data.get("toolCalls", [])
        if any(c.get("changed") for c in calls):
            edit_timing = f'{t["roundsBeforeFirstMutation"]} / {t["roundsAfterLastMutation"]}'
        elif (data.get("executionMetrics", {}).get("firstMutationElapsedMs") == -1
              or calls and all("changed" in c for c in calls)):
            edit_timing = '— / — (no edit observed)'
        else:
            edit_timing = 'unknown / unknown'
        lines.append(f'| {Path(name).name} | {data.get("taskSuccess", "UNKNOWN")} | {edit_timing} | {t["literalPatternQueries"]} | {t["validationBeforeObservedSave"]} | {sequence} |')
    groups = collections.defaultdict(list)
    for name, data in rows:
        groups[(data.get("model"), data.get("label", "historical"))].append(data)
    lines += ["", "## Distributions", "", "Range and median include failures. Historical absence of progress events is not zero no-progress.", ""]
    for (model, label), trials in sorted(groups.items()):
        rounds = [len(t.get("modelRounds", [])) for t in trials]
        seconds = [t["elapsedMs"] / 1000 for t in trials if "elapsedMs" in t]
        lines.append(f'- {model}, {label}: {len(trials)} trials, {sum(t.get("taskSuccess") == "PASS" for t in trials)} full-task passes; '
                     f'rounds min/median/max {min(rounds)}/{statistics.median(rounds)}/{max(rounds)}; '
                     f'seconds min/median/max {min(seconds):.1f}/{statistics.median(seconds):.1f}/{max(seconds):.1f}.')
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="+", type=Path)
    args = parser.parse_args()
    files = sorted({file for path in args.paths for file in (path.glob("*.json") if path.is_dir() else [path])})
    print(report(list(load(files))), end="")
