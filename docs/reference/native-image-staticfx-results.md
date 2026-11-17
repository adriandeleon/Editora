# StaticFX / GraalVM experiment: results

The dated record of the [StaticFX / GraalVM experiment](../native-image-staticfx.md): the architecture it
inspected when it started, and what it measured. The guide holds how to build, run and measure the native
profile; this file keeps the evidence as it was recorded. Where it and the code disagree, the code wins.

## Architecture inspected at the start

The starting point is commit `c75201ac`, Editora `0.18.6-SNAPSHOT`, one Maven jar and one JPMS module
`com.editora`. There is no application multi-module split.

| Component | Actual version / implementation | Native implication |
| --- | --- | --- |
| JDK | Compiler release 25 | Use a GraalVM based on JDK 25 or newer; do not downgrade source level. |
| JavaFX | `org.openjfx:27`, controls, graphics, base, FXML, Swing | Static archives must match **27 exactly**. |
| RichTextFX | `io.github.adriandeleon:richtextfx:0.11.7-inlay.1` | Existing vendored multi-caret/inlay fork is retained byte-for-byte. No new fork. |
| Flowless | `org.fxmisc.flowless:flowless:0.7.4` | Virtualized viewport, paragraph cells, estimates and scrolling exercised. |
| ReactFX | `org.reactfx:reactfx:2.0-M5` | Real document subscriptions, change notifications and debounce paths exercised. |
| UndoFX / WellBehavedFX | 2.1.1 / 0.3.3 | Same transitive artifacts and undo manager as JVM. |
| StaticFX | feature 1.0, static libraries 27 | Build-time integration and JavaFX JNI/reflection/resources; not an editor replacement. |
| Native build tools | `native-maven-plugin:1.0.0` | Classpath image analysis; normal JPMS compilation stays intact. |

`Launcher → App → WindowManager → main.fxml/MainController → EditorBuffer` is the real boot path.
`EditorBuffer` constructs the vendored `CodeArea`, a `VirtualizedScrollPane`, the production
`FoldManager`, overlays, and the fork's `MultiCaretController`. `TextMateHighlighter` calls tm4e
`RELEASE260`, joni 2.2.7 and jcodings 1.0.64. `GrammarRegistry` loads bundled grammar JSON by scope;
its existing error fallback can hide failed highlighting. The probe therefore requires a real
Java grammar, nonempty keyword styles and an incremental/full-tokenization oracle.

Other inspected paths:

- `pom.xml`: normal jar, host `fatjar`, `dist` using moditect → jlink → jpackage; `os-mac`,
  `os-windows`, `os-linux`, and `apidocs` profiles. Native adds no dependency to these profiles.
- `scripts/aot_build.java`: one current GUI training configuration, against the packaged runtime,
  `--new-file`, about 2.5 seconds of settling, `AOTCacheOutput`, G1, 2 GiB maximum heap,
  `AOTAdapterCaching` disabled in training and launch. No alternative stronger shipped trainer exists.
- FXML: `WindowManager.buildWindow` loads `com/editora/ui/main.fxml` and reflectively injects
  `MainController` fields/events. Most secondary UI is constructed in Java.
- Reflection: FXML; Jackson configuration/stores/snippets/templates/plugin manifests; Gson and
  LSP4J protocol models/proxies; optional JavaFX capability checks; dynamic plugin entry classes.
- `ServiceLoader`: no direct application calls or plugin SPI discovery. Dependencies have service
  entries, including SSHD filesystem providers and logging. Graal's ordinary ServiceLoader feature
  remains enabled; no providers are excluded to make compilation succeed.
- Resources: grammars, six UTF-8 message catalogs (`Messages` uses `Properties`, not ResourceBundle),
  keymaps, CSS, fonts, icons, snippets, templates, dictionaries. jcodings loads Unicode binary tables
  from the jar root `tables/`, not `org/jcodings/tables/`.
- JNI/native code: JavaFX Glass/Prism/font backends and headless AWT/Java2D reachable through SVG,
  math and document rendering. No application JNI shim was added. StaticFX covers JavaFX, **not all
  possible AWT operations**. Shared AWT libraries emitted beside the executable are part of the output.
- Serialization: Jackson 2.22.2 JSON/TOML/YAML, Gson 2.14.0, SnakeYAML 2.5; POI/XMLBeans and PDFBox
  bring further reflective/resource surfaces. Successful startup does not certify all these formats.
- Git is **external `git`**, via `GitService`/`ProcessRunner`, not JGit. There is no JGit metadata to add.
- SSH is Apache MINA SSHD 2.20.0 (`sshd-osgi`, `sshd-sftp`), with host verification and SFTP `Path`s
  through `RemoteFileSystems`. Its service providers caused a measured build-time heap failure.
- Terminal integration is external OS terminal launching in `DesktopActions`. The integrated terminal's PTY
  stack (pty4j over JNA, ADR 0014) is configured for the JVM build only; `--check-terminal` is reachable from
  `App.main` but unqualified here. Run/output tools use subprocesses.
- LSP and DAP use LSP4J 1.0.0 JSON-RPC over child-process streams/sockets. `ProcessRegistry` owns
  lifecycle cleanup. A native Editora does not eliminate the external JDK needed by JDT LS/debuggers.
- HTTP and AI use JDK HTTP APIs, Jackson, and external ACP adapters. HTML preview serves loopback HTTP
  and launches an external browser. **There is no `javafx.web` dependency / embedded WebView**.
- Spell checking uses Lucene 10.5.1 Hunspell and dictionary resources, not native Hunspell.
- Existing coverage includes `EditorBufferFxTest`, multi-caret editing/movement, folding gaps,
  semantic-token staleness, lazy overlays and inlay rendering. Existing Java typing/cost probes and
  `scripts/measure-startup.sh` remain available. JUnit/TestFX tests are JVM tests, not native tests.

## Results and acceptance status

Measured on 2026-09-22 on the Linux host recorded in
[`environment.json`](../../artifacts/native-image-staticfx/environment.json). **The prototype passes the
bounded headless editor correctness gate and basic full-application workflows, but fails the stated
editor-performance screen.** Further peripheral qualification stopped at that finding. No attempt was
made to hide it with editor changes, a new fork, unsafe initialization, PGO or removed features.

Evidence and per-run results are under [`artifacts/native-image-staticfx`](../../artifacts/native-image-staticfx/README.md).
All values below come from quiet sequential runs, not image compilation or tracing. App: five measured
processes per mode plus one warmup; editor: three measured processes per mode plus one warmup. All 18
application runs and all 12 headless editor runs passed. The headless checksums and final application
file hashes agree across runtimes. Nine tooling protocol tests also pass.

### Useful application startup and current AOT

Milliseconds, **median / p95 / p99**, n=5 per cell. Fresh config and a disposable Maven/Git project,
100 KiB Java file (mostly line comments, one edited field), MCP enabled, LSP/update checks disabled.
The normal jpackage entry point and **existing GUI-trained AOT cache** are used.

| Process start → | JVM | Current AOT | Native |
| --- | ---: | ---: | ---: |
| JavaFX initialized | 582.00 / 604.00 / 604.00 | 498.00 / 536.00 / 536.00 | 101.00 / 116.00 / 116.00 |
| Window shown | 1,658.00 / 1,746.00 / 1,746.00 | 971.00 / 988.00 / 988.00 | 251.00 / 278.00 / 278.00 |
| File loaded | 2,083.00 / 2,228.00 / 2,228.00 | 1,275.00 / 1,336.00 / 1,336.00 | 327.00 / 361.00 / 361.00 |
| First file paint | 2,290.00 / 2,512.00 / 2,512.00 | 1,467.00 / 1,564.00 / 1,564.00 | 369.00 / 434.00 / 434.00 |
| First file edit verified via MCP | 2,431.91 / 2,638.13 / 2,638.13 | 1,549.74 / 1,661.69 / 1,661.69 | 430.66 / 481.85 / 481.85 |

Native's measured first-editable time is **72.2% lower than current AOT** (3.60×),
and 82.3% lower than plain JVM. This includes the automation round trips and is not a
keypress-to-paint measurement. Project-wide background readiness and Java-LSP readiness were not timed.
Small-n p95/p99 equal the slowest run and must not be treated as stable population tails.

The full-app edit API also favors native in this short fixture: edit request median/p95 is
1.70/3.85 ms native versus
4.98/10.72 ms current AOT
(n=150 each). Those HTTP/controller requests have a different workload from the synthetic key events
and syntax-heavy files below; they do not establish long-session keyboard responsiveness.

### Editor workloads

Milliseconds, **median / p95 / p99**. `Probe AOT` is the separately trained standalone cache, **not**
the shipped cache. The same production editor, dependency versions, generated input and assertions run
in every mode. The first 100 operations of each burst are omitted in the explicitly labeled rows.

| Workload | JVM | Probe AOT | Native |
| --- | ---: | ---: | ---: |
| 100 KiB full tokenization (n=3) | 286.83 / 482.04 / 482.04 | 335.06 / 393.77 / 393.77 | 440.31 / 451.54 / 451.54 |
| 1 MiB full tokenization (n=3) | 2,045.95 / 2,056.89 / 2,056.89 | 2,034.10 / 2,052.63 / 2,052.63 | 2,707.47 / 2,719.48 / 2,719.48 |
| 1 MiB incremental highlight settled (n=3) | 186.95 / 188.86 / 188.86 | 186.77 / 189.65 / 189.65 | 196.89 / 200.13 / 200.13 |
| 100 KiB insert after 100 (n=600) | 4.04 / 10.84 / 14.65 | 2.69 / 6.52 / 8.40 | 6.34 / 13.79 / 15.56 |
| 1 MiB insert after 100 (n=600) | 1.84 / 4.55 / 7.17 | 1.75 / 4.39 / 5.67 | 5.04 / 9.40 / 12.79 |
| 5 MiB insert after 100 (n=600) | 0.80 / 2.62 / 3.71 | 0.91 / 3.22 / 6.56 | 1.16 / 7.60 / 10.32 |
| 10 MiB insert after 100 (n=600) | 0.69 / 2.90 / 4.49 | 0.74 / 2.69 / 3.97 | 0.88 / 6.80 / 9.63 |
| 1 MiB undo after 100 (n=600) | 3.49 / 6.03 / 8.77 | 3.62 / 5.32 / 8.50 | 5.44 / 12.79 / 14.34 |
| 1 MiB redo after 100 (n=600) | 1.59 / 2.80 / 4.27 | 1.64 / 2.38 / 3.39 | 2.50 / 8.31 / 10.32 |
| 10 MiB open + layout (n=3) | 307.93 / 327.08 / 327.08 | 294.01 / 342.59 / 342.59 | 332.43 / 350.50 / 350.50 |
| 10 MiB large paste + layout (n=3) | 29.43 / 33.26 / 33.26 | 48.86 / 48.97 / 48.97 | 37.62 / 54.75 / 54.75 |
| 10 MiB delete region + layout (n=3) | 30.62 / 31.84 / 31.84 | 27.17 / 29.79 / 29.79 | 34.16 / 34.91 / 34.91 |
| 10 MiB scroll + two pulses (n=72) | 31.74 / 31.97 / 33.55 | 31.77 / 31.96 / 32.05 | 31.73 / 31.90 / 33.88 |
| 300 simultaneous replacements + undo (n=3) | 114.98 / 140.89 / 140.89 | 123.73 / 128.90 / 128.90 | 135.16 / 156.27 / 156.27 |
| Search decoration update (n=72) | 0.05 / 0.16 / 0.39 | 0.04 / 0.15 / 2.30 | 0.05 / 11.42 / 14.12 |

The performance stop condition is met: 1 MiB full tokenization is **32.3% slower** than JVM, while
1 MiB late-burst insert p95 rises **4.55 → 9.40 ms**, undo **6.03 → 12.79 ms**, and redo
**2.80 → 8.31 ms**. All exceed the chosen 20% + 2 ms input screen. 5/10 MiB input tails also regress,
although their median dispatch times remain near one millisecond and headless scroll pulse timing is
similar. At 5/10 MiB the real product policy disables syntax and undo; those results cannot certify
fully highlighted/undoable documents of that size. Full results, including every size and heap mark,
are in the JSON, rather than selecting only favorable metrics.

These are short-process/late-burst measurements, not proof of hours-long HotSpot steady state. They
already justify stopping further qualification under the request's performance rule.

### Memory and distribution

Median **RSS in MiB**, direct application process only; child Git/LSP processes excluded. No forced GC.

| Full application phase | JVM | Current AOT | Native |
| --- | ---: | ---: | ---: |
| File/project painted | 653.3 | 524.0 | 363.4 |
| First file edit verified | 672.1 | 543.6 | 365.5 |
| After editing and save | 724.2 | 583.3 | 548.6 |

| Headless editor after sized-document editing | JVM | Probe AOT | Native |
| --- | ---: | ---: | ---: |
| 100 KiB | 872.9 | 910.7 | 569.6 |
| 1 MiB | 953.1 | 984.1 | 742.1 |
| 5 MiB | 960.0 | 1054.9 | 712.3 |
| 10 MiB | 1022.1 | 1132.2 | 733.9 |

The cases run sequentially in each editor process, so later RSS can include allocations from earlier
cases awaiting collection. Native RSS is lower in these measured phases; the full-app advantage narrows
after editing. `heap-used-bytes` in the JSON is separately reported `totalMemory - freeMemory`, **not
RSS or retained live heap**. Neither metric establishes long-session leak behavior.

Uncompressed logical file sizes, excluding symlink duplication and OS-provided GTK/X11/font/GL libraries:

| Payload | MiB |
| --- | ---: |
| Actual native Editora executable | 197.82 |
| Native executable + every emitted shared library | 202.51 |
| jlink/jpackage app image, AOT cache removed | 114.67 |
| Current jlink/jpackage image + GUI-trained AOT cache | 190.42 |

These are runtime payloads, not compressed installers. The native payload is **larger** than both JVM
payloads here, including the current AOT image. The much smaller standalone probe executable is not
substituted for Editora in the size comparison. JVM measurements use benchmark copies with only the
cache/AOT-mode changes described above; the extra strict-mode config line is included in their byte totals.

### Desktop observations

The final 100 KiB desktop series (three attempts per runtime, no separate warmup, 60-second
per-process deadline) completed **3/3 native, 2/3 JVM, and 2/3 probe-AOT** attempts. The first JVM
and probe-AOT attempts timed out; their later attempts passed. Successful runs have matching text
checksums, but the incomplete set is **not** presented as a clean desktop timing comparison.

An earlier uncontended native desktop attempt repeatedly took about one second per synthetic input
and was stopped. An earlier successful retry had suggested compilation contention might explain it;
its recurrence without compilation disproves that explanation as sufficient. The stalled trace is
retained in `anomalies.json`, not silently discarded as an outlier. Desktop behavior is intermittent,
and its cause has not been isolated to Native Image, Flowless, the probe, or this display environment.
It remains an unresolved qualification issue. The headless performance failure above is independently
reproducible and does not depend on attributing this desktop anomaly.

### Acceptance checklist and engineering assessment

| Criterion | Result |
| --- | --- |
| 1. Normal JVM unchanged and green | Pass: clean verify, 4,890 tests, zero failures/errors, 25 skips; coverage and formatting gates pass. |
| 2. Existing AOT path unchanged and green | Pass: clean `dist` build with `EDITORA_REQUIRE_AOT=1`; strict AOT-mode full-app runs pass. |
| 3. Actual native executable builds | Pass on tested Linux/toolchain; commands and pinned dependencies above. |
| 4. Main JavaFX UI starts | Pass through file paint, including the desktop full-app workflow. |
| 5. Real RichTextFX editor works | Pass within the documented automated workloads; desktop/long-session qualification incomplete. |
| 6. RichTextFX stress tests | Headless pass at all four sizes; desktop observations above limit the claim. |
| 7. Multi-cursor correctness | Pass: exact contents, insertion/deletion, 300 replacements and atomic undo. |
| 8. Undo/redo correctness | Pass for normal-sized documents and full-app cycles; large-file no-undo policy preserved. |
| 9. Syntax correctness | Pass for Java full/incremental/async style oracles; other language grammars not qualified. |
| 10. Large-file usability | Content/geometry pass through 10 MiB under production policy; native input tails regress. |
| 11. Flowless viewport reliability | Headless geometry passes; intermittent desktop behavior remains unresolved. |
| 12. Native-specific state corruption | None observed in successful oracle-checked runs; not an unbounded reliability claim. |
| 13. Native changes non-invasive | Pass: zero production Java edits; no new editor-library fork or native branches. |
| 14. JVM/current-AOT/native evidence | Present for actual app startup/edit/save/search/Git/memory; separate full stress comparison. |
| 15. Unsupported features explicit | Feature table and fundamental Java-plugin limitation above; no features removed to compile. |

RichTextFX, Flowless and ReactFX need no source changes or extra editor-library reflection registrations
for the tested paths. Their bounded behavioral results are encouraging; their unrestricted desktop
reliability and performance are **not established**. The native performance screen fails despite the
useful-startup and RSS gains, so this is **not a fully accepted native alternative**.

The profile is technically maintainable as an opt-in experiment: its integration is localized, FXML
member drift has a check, and tests fail on hidden metadata fallbacks. The maintenance cost remains
explicit metadata review at dependency/DTO/FXML upgrades and a large unqualified peripheral surface.
Dynamic post-build JVM plugins are a fundamental conflict; most other unqualified features are test/
metadata work with no fundamental incompatibility demonstrated yet. No plugin redesign or product
migration decision is made by this experiment.

