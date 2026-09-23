#!/usr/bin/env python3
"""Compare explicit JVM/AOT/native command vectors; no shell, no generated timing claims.

Config format and limitations: docs/native-image-staticfx.md. Python standard library only.
Every run must pass its own protocol; failed runs are retained and excluded from percentiles.
Linux RSS samples cover the direct process, excluding LSP/DAP children; heap is reported separately.
"""
import argparse
import collections
import hashlib
import json
import math
import os
import pathlib
import platform
import queue
import re
import signal
import statistics
import subprocess
import threading
import time


def percentile(values, fraction):
    """Nearest rank: keep sample count alongside tails, especially for short startup studies."""
    values = sorted(values)
    return values[max(0, math.ceil(len(values) * fraction) - 1)]


def summarize(values):
    return {"n": len(values), "median": statistics.median(values),
            "p95": percentile(values, .95), "p99": percentile(values, .99)}


def rss_bytes(pid):
    try:
        for line in pathlib.Path(f"/proc/{pid}/status").read_text().splitlines():
            if line.startswith("VmRSS:"):
                return int(line.split()[1]) * 1024
    except (FileNotFoundError, PermissionError, ProcessLookupError):
        pass
    return None


def tree_size(path):
    p = pathlib.Path(path)
    if p.is_file():
        return p.stat().st_size
    if not p.is_dir():
        return None
    return sum(f.stat().st_size for f in p.rglob("*") if f.is_file() and not f.is_symlink())


def run(command, env, output, kind, timeout):
    events, lines = [], queue.Queue()
    start = time.monotonic()
    env = dict(os.environ, **env, EDITORA_PERF_T0=str(time.time_ns() // 1_000_000))
    # PrintStream.printf can make several writes; merging a grammar warning between those writes
    # corrupts a JSON line. Keep all warnings, but outside the editor's machine-readable stdout.
    error_path = output.with_suffix('.stderr.log') if kind == 'editor' else None
    error_log = error_path.open('w') if error_path else None
    proc = subprocess.Popen(command, env=env, stdout=subprocess.PIPE, stderr=error_log or subprocess.STDOUT,
                            text=True, bufsize=1, start_new_session=(os.name == "posix"))

    def read():
        for line in proc.stdout:
            lines.put((time.monotonic(), line))
        lines.put(None)

    reader = threading.Thread(target=read, daemon=True)
    reader.start()
    peak = 0
    timed_out = False
    protocol_error = None
    ended = False
    try:
        with output.open("w") as log:
            while not ended:
                now = time.monotonic()
                if now - start > timeout:
                    timed_out = True
                    break
                rss = rss_bytes(proc.pid)
                if rss is not None:
                    peak = max(peak, rss)
                try:
                    item = lines.get(timeout=.02)
                except queue.Empty:
                    continue
                if item is None:
                    ended = True
                    continue
                stamp, line = item
                log.write(line)
                log.flush()
                event = None
                if line.startswith('{"kind":'):
                    try:
                        event = json.loads(line)
                    except json.JSONDecodeError as error:
                        protocol_error = str(error)
                        break
                    event["received_ms"] = (stamp - start) * 1000
                    event["rss_bytes"] = rss_bytes(proc.pid)
                match = re.match(r"\[perf\]\s+([\w-]+)\s+(\d+)\s+\(", line)
                if match:
                    event = {"kind": "startup-ms", "name": match[1], "bytes": 0,
                             "value": int(match[2])}
                if event:
                    events.append(event)
        if protocol_error is None:
            proc.wait(timeout=max(1, timeout - (time.monotonic() - start)))
    except subprocess.TimeoutExpired:
        timed_out = True
    finally:
        if proc.poll() is None:
            if os.name == "posix":
                os.killpg(proc.pid, signal.SIGKILL)
            else:
                proc.kill()
            proc.wait()
        proc.stdout.close()
        reader.join(timeout=1)
        if error_log:
            error_log.close()
    passed = any(e["kind"] == "result" and e["name"] == "PASS" for e in events)
    if kind == "startup":
        passed = any(e["kind"] == "startup-ms" and e["name"] == "first-paint" for e in events)
    return {"ok": passed and proc.returncode == 0 and not timed_out and protocol_error is None,
            "exit_code": proc.returncode, "timeout": timed_out, "events": events,
            "protocol_error": protocol_error, "stderr_log": str(error_path) if error_path else None,
            "peak_rss_bytes": peak or None, "wall_ms": (time.monotonic() - start) * 1000,
            "log": str(output)}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("config", type=pathlib.Path)
    p.add_argument("--output", required=True, type=pathlib.Path)
    p.add_argument("--runs", type=int, default=5)
    p.add_argument("--warmups", type=int, default=1)
    p.add_argument("--timeout", type=float, default=600)
    args = p.parse_args()
    if args.runs < 1 or args.warmups < 0:
        p.error("runs must be positive; warmups cannot be negative")
    config = json.loads(args.config.read_text())
    args.output.mkdir(parents=True, exist_ok=False)
    (args.output / "config.json").write_text(json.dumps(config, indent=2) + "\n")
    results = []
    modes = config["modes"]
    names = list(modes)
    for iteration in range(-args.warmups, args.runs):
        # Rotate order each round to reduce temperature/order bias. Do not evict OS page caches.
        shift = iteration % len(names)
        for name in names[shift:] + names[:shift]:
            mode = modes[name]
            for kind in ("startup", "editor"):
                if not mode.get(kind):
                    continue
                tag = f"{name}-{kind}-{iteration}"
                fresh = args.output.resolve() / (tag + "-config")
                fresh.mkdir()
                command = [arg.replace("{config_dir}", str(fresh)) for arg in mode[kind]]
                env = dict(mode.get("env", {}))
                env["EDITORA_CONFIG_DIR"] = str(fresh)
                if kind == "startup":
                    env.update(EDITORA_PERF="1", EDITORA_PERF_EXIT="1", EDITORA_CONFIG_DIR=str(fresh))
                result = run(command, env, args.output / (tag + ".log"), kind, args.timeout)
                result.update(mode=name, workload=kind, iteration=iteration, command=command)
                results.append(result)
                (args.output / "raw.json").write_text(json.dumps(results, indent=2) + "\n")
                print(f"{tag}: {'PASS' if result['ok'] else 'FAIL'}", flush=True)
    buckets = collections.defaultdict(list)
    checksums = collections.defaultdict(list)
    failures = []
    for r in results:
        if not r["ok"]:
            failures.append({k: r[k] for k in ("mode", "workload", "iteration", "exit_code", "timeout", "protocol_error", "log")})
            continue
        if r["iteration"] < 0:
            continue
        if r["workload"] == "editor":
            checksums[r["mode"]].append([(e["bytes"], e["name"]) for e in r["events"] if e["kind"] == "checksum"])
        for e in r["events"]:
            prefix = f"{r['mode']}/{r['workload']}/{e['bytes']}/{e['name']}"
            if e["kind"] in ("sample", "startup-ms", "heap-used-bytes"):
                buckets[prefix + "/" + e["kind"]].append(e["value"])
            if e["kind"] == "milestone":
                buckets[prefix + "/received-ms"].append(e["received_ms"])
            if e["kind"] == "heap-used-bytes" and e.get("rss_bytes") is not None:
                buckets[prefix + "/rss-bytes"].append(e["rss_bytes"])
        # Explicit late-burst subset; this is not a claim that all JIT activity has stabilized.
        bursts = collections.defaultdict(list)
        for e in r["events"]:
            if e["kind"] == "sample" and e["name"] in ("insert-dispatch", "undo-dispatch", "redo-dispatch"):
                bursts[(e["bytes"], e["name"])].append(e["value"])
        for (size, name), values in bursts.items():
            if len(values) > 100:
                buckets[f"{r['mode']}/{r['workload']}/{size}/{name}-after-100/sample"].extend(values[100:])
        if r["peak_rss_bytes"] is not None:
            buckets[f"{r['mode']}/{r['workload']}/peak-rss-bytes"].append(r["peak_rss_bytes"])
    sequences = [seq for mode in checksums.values() for seq in mode]
    equivalent = all(seq == sequences[0] for seq in sequences) if sequences else None
    report = {"host": platform.platform(), "cpu": platform.processor(), "runs": args.runs,
              "warmups": args.warmups, "config_sha256": hashlib.sha256(args.config.read_bytes()).hexdigest(),
              "checksum_sequences_equal": equivalent, "failures": failures,
              "metrics": {k: summarize(v) for k, v in sorted(buckets.items())},
              "distribution_bytes": {k: tree_size(v) for k, v in config.get("distributions", {}).items()},
              "limitations": ["Warm filesystem cache; no forced GC; direct-process Linux RSS excludes children.",
                              "Probe milestones are receipt times, including pipe/scheduler delay.",
                              "Dispatch samples include FX queueing but exclude later layout; layout samples include two pulses.",
                              "Headless pulses are not desktop paint latency. No project/LSP readiness inferred from first-paint.",
                              "Missing modes and failed runs have no performance conclusion. Small-n tails are descriptive only."]}
    (args.output / "summary.json").write_text(json.dumps(report, indent=2) + "\n")
    return 1 if failures or equivalent is False else 0


if __name__ == "__main__":
    raise SystemExit(main())
