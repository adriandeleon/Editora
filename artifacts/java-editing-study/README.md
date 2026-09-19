# Java editing follow-up measurements

Study date: 2026-09-19. Linux x86-64, Intel Core i7-13700K, Temurin JDK 25.0.4,
JavaFX 27 headless/software rendering, JDT LS `1.61.0.202607301809`.

The sustained fixtures are disposable copies of Editora's real Maven project and
RichTextFX's real Gradle multi-project build. Project-local completion proves that
the relevant classpaths loaded before timing starts. RichTextFX uses its Gradle 8.5
wrapper with JDK 21; the editor and language-server processes use JDK 25.

## Large-file component costs

Thirty measured edits per size after five warm-up edits, outside JaCoCo. Each edit
is near the end of a multiline document. The probe measures synchronous key handling,
the first text snapshot, cached snapshot access, and production sync diff/range work
separately. It does not include JSON transport, server response or a painted frame.
The probe deliberately keeps normal editor mode; production large-file mode can disable LSP and
other expensive features, so these measurements are component costs rather than a file-size policy.

| Source characters | Snapshot median, before / after | Diff + range median, before / after |
| ---: | ---: | ---: |
| 16,422 | 0.050 / 0.051 ms | 0.093 / 0.090 ms |
| 262,167 | 0.457 / 0.476 ms | 0.372 / 0.235 ms |
| 1,048,594 | 2.287 / 3.007 ms | 1.533 / 0.930 ms |
| 4,194,302 | 10.135 / 9.263 ms | 6.197 / 3.771 ms |

The change replaces the per-character range scan with the JDK's optimized newline
search. The whole diff/range component improves about 39% at 4 MB. An isolated scan
comparison measured roughly 3.7 → 0.48 ms for Latin-1 lines and 7.9 → 0.79 ms for
UTF-16 lines. Snapshot code was not changed; its variation reflects runtime noise.
Raw component summaries: [before](large-file-before.txt), [after](large-file-after.txt).

Key handling remains below 1 ms median at these sizes. Cached snapshot access is
microseconds. Uncached snapshots remain O(document size) on FX, with a 4 MB median
around 9–10 ms. The 1 MB after-run snapshot maximum was 26.5 ms, demonstrating why
median measurements alone are insufficient.

Moving snapshots/diffs off FX requires an ordered synchronization design: capture
an immutable RichTextFX document on FX, flatten/diff it on a per-session worker,
and queue every dependent request behind that document version. Merely dispatching
`didChange` asynchronously allows completion, formatting or diagnostics to overtake it.
Current immediate version/snapshot consumers also need a defined queued-versus-sent
contract. This architectural change is not disguised as a small threading patch.

## What the typing harness measures

The current launcher fires actual JavaFX `KEY_TYPED`/`KEY_PRESSED` events through
an `EditorBuffer` in a Stage, with the production coordinator/client and real JDT LS.
It repeats `System.out.pr`, `Str`, `new ArrayLi`, a method chain with a snippet argument,
and backspace correction at 35–80 ms character intervals. Every fourth round adds
6,000 fields (178,890 characters), stressing structural replacement and reconciliation.
It records item rank, FX dispatch/key-handler/pulse intervals and completion trace stages.
Failures retain a bounded request/cancellation/result history and the generated current
paragraph; no user source files are changed.

The first long run used the editor's macro typing path, which shares typing-assist
logic, and real pressed events for navigation/acceptance. Its `key-to-popup` field
starts after the final inter-character delay: interpret it as **remaining wait after
typing**, not total keystroke latency. The current harness timestamps the actual last
event and removes that trailing delay. Protocol and FX component trace measurements
are unaffected by this correction. Runtime copies are immutable throughout each run.

## Sustained outcomes and unresolved failures

The [30-minute baseline](macro-baseline-30min.txt) completed 15 timed minutes per
project: **1,793 attempted sequences**, with 832 successful Maven selections,
956 successful Gradle selections and **five Maven `Str` popup timeouts**. The process
exited nonzero as intended. Its Maven request-future maximum was 1.53 seconds, so the
15-second popup wait does not establish a 15-second server RPC. The initial harness
did not retain enough request/state history to distinguish an empty result, client
suppression or a harness problem. No root cause or fix is claimed for those failures.
The baseline reused the smoke run's imported workspace; subsequent runs used fresh
disposable copies, so these are not controlled before/after comparisons.

After adding bounded failure capture and signature-popup assertions, an
[eight-minute event run](event-study-8min.txt) passed 506 Maven sequences and a
[13-minute macro reproduction](macro-study-13min.txt) passed 836 Maven sequences.
The final [three-minute event run](event-study-final-3min.txt), including the later
signature-request fix, passed **180 sequences** (84 Maven, 96 Gradle). The earlier
four-minute event run passed 245. These passing reruns are regression evidence,
not proof that the baseline timeout issue has disappeared.

Final ordinary verification: **4,826 tests, zero failures/errors, 24 skipped**;
coverage, packaging and formatting checks passed. The skipped opt-in probes were
run separately as described here.

## Event-run latency and ranking

The four-minute event run completed **245 sequences with zero assertion failures**
(115 Maven, 130 Gradle). It ran the final undo/range-scan implementation. Small-file
last-key-to-visible-item medians were 2.26 / 1.27 ms for `System.out.pr`, 184.90 /
167.54 ms for `Str`, and 188.07 / 176.73 ms for `new ArrayLi` (Maven / Gradle).
The near-zero member numbers reflect reuse of a list requested earlier at the dot;
they are not server round-trip times. Cold `String` search reached 12.32 seconds in
Maven and 3.35 seconds in Gradle. These tails remain a real difference from a warm,
indexed IDEA session.

| Event-run stage | Maven median / p95 | Gradle median / p95 |
| --- | ---: | ---: |
| Key handler | 1.80 / 5.93 ms | 1.08 / 2.01 ms |
| Sync call | 0.21 / 2.44 ms | 0.11 / 1.81 ms |
| Completion server request | 76.55 / 171.73 ms | 71.95 / 164.09 ms |
| Map/rank | 0.22 / 0.85 ms | 0.11 / 0.22 ms |
| Filter + FX dispatch | 0.11 / 1.49 ms | 0.09 / 0.85 ms |
| Popup model | 1.50 / 14.79 ms | 0.94 / 6.81 ms |

The trace's `server` stage measures the client request future's lifetime, including
cancelled/error futures, rather than server CPU time. Startup queries are included
in aggregate stage samples but timed project phases begin only after readiness.

The [event-run summary](event-study-4min.txt) includes sample counts, p99, maxima and
rank histories. The selected rank did not improve across repetitions: `println()`
stayed 12, `String` 5 (Maven) / 6 (Gradle), `ArrayList()` 1 and the chosen `substring`
overload 2. This supports further recency evaluation, not a claim that server semantic
scores should be replaced.

The subsequent [eight-minute Maven event run](event-study-8min.txt) completed **506
sequences with zero assertion failures**, adding explicit checks that signature help
appears after `substring` completion. It again recorded a cold `String` response near
12 seconds, while warm large-file `String` last-key latency had a 211.55 ms median and
231.01 ms p95. Repeated selected ranks stayed unchanged through 101–102 selections.

A separate 60-second JFR profile of the event harness collected 355 execution
samples on FX and 1,002 on the four highlighting workers. The most frequent FX top
frame was headless blitting (53); CSS matching/parsing/state changes and document
tree operations were also visible. Only one FX sample contained the completion
render callback; none showed mapping/sorting or JSON decoding. Sampling can miss
short operations, so this does not prove zero FX overhead. The profile confirms that
headless software rendering and repeated structural resets affect the study's pulse
measurements; it does not justify a speculative completion-width cache.

This is an automated stress study, not a 30-minute human usability trial. JavaFX
pulses do not measure painted desktop frames. Concurrent builds/probes and CSS
warning logging can affect the observed tails; these are indicative engineering
measurements, not controlled performance guarantees. The popup theme test verifies
that selected-row colors resolve under light/dark themes despite transient CSS warnings.

## Server evidence

The [upstream-ready report](jdt-import-conflict/REPORT.md) includes a standalone
Python JSON-RPC client, portable protocol transcript and compiler confirmation of
the same-file type/import conflict. It has not been published.

Selection feedback is sent via `java.completion.onDidSelect`. Inspection of the
installed JDT LS bytecode shows that `CompletionHandler.onDidCompletionItemSelect`
updates its selected proposal and calls registered ranking providers. The hook itself
does not implement persistent frequency/recency ranking. Any local policy should be
evaluated against expected-type, overload and receiver-context cases, rather than
promoting familiar names above semantic scores based on a few repetitions.
