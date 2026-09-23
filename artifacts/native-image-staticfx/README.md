# StaticFX / Native Image measurement evidence

See [the experiment report](../../docs/native-image-staticfx.md) for prerequisites, exact commands,
architecture, acceptance criteria and interpretation. The performance gate **fails** despite faster
useful startup. These measurements do not qualify Native Image as a replacement distribution.

- `environment.json`: host/toolchain/dependency versions, regression-test results, source/metadata/
  tooling hashes, executable/cache hashes and sizes.
- `application-summary.json`: five measured runs per mode, one warmup; unchanged full app, actual
  existing GUI-trained AOT cache, 30 MCP edit/undo/redo cycles plus save/history/search/Git assertions.
- `editor-headless-summary.json`: three measured runs per mode, one warmup; real EditorBuffer at
  100 KiB, 1/5/10 MiB, 300 edits, 300 undo/redo where the production policy permits, and viewport,
  highlighting, multi-caret, decoration and long-line checks. `probe-aot` is a separate cache.
- `editor-desktop-summary.json`: three attempts per mode, no warmup, 60-second deadline. Two timeouts
  remain failures. Successful-subset percentiles are descriptive, **not** a qualified comparison.
- `runs.json`: per-run operation summaries, startup/memory marks and text checksums. Warmups and
  failures are retained. Raw individual timings are aggregated here to avoid a large generated log dump.
- `commands.json`: exact recorded argv/configs. Paths describe this host; update their common staging
  root and JDK/Maven dependency paths when reproducing. Arrays go directly to subprocesses, not a shell.
- `anomalies.json`: the earlier partial native desktop stall and reasons for recollecting two harness
  measurement groups. Metadata-discovery/Warn and build-contended smoke runs are not accepted timings.

All nine protocol tests pass (`python3 -m unittest discover -s scripts/native -p 'test_*.py'`).
Benchmarks ran sequentially without image compilation, tracing agents or JaCoCo. Filesystem caches
were warm, mode order rotated, and no forced GC was used. RSS excludes child processes; heap-used
metrics are separately labeled. Three/five-run tails are small-sample descriptions, not confidence
bounds. The first-editable milestone is a verified full-app edit via the existing MCP API; it includes
polling/HTTP/FX-queue overhead. Project-wide background readiness and live Java LSP were not measured.

The complete local stdout/stderr logs, individual sample JSON, generated disposable configs/projects,
immutable jars, caches and binaries are retained at `/tmp/editora-staticfx-evidence/` for this session.
They are not committed as generated build output. This tracked directory contains the durable numerical
results and reproducibility metadata. Fresh runs should use new output directories; the runners refuse
to overwrite an existing directory. All native acceptance commands require strict missing-registration
exit behavior; diagnostic `Warn` runs must never be mixed into a reported study.
