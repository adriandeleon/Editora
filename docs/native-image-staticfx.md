# StaticFX / GraalVM experiment

This is an opt-in feasibility experiment, **not a replacement for the JVM/AOT distribution**.
The question is whether the actual Editora editor can retain its behavior and responsiveness as a
closed-world executable. Opening a window is not the acceptance gate. The normal build, `dist`,
AOT trainer, editor source, and dependency versions are unchanged. Tagged releases attempt
separately labelled experimental Linux x64, macOS x64/arm64, and Windows x64 archives;
see [the release guide](release.md).

The JDK AOT cache preserves normal HotSpot/JVM behavior, dynamic class loading and JIT optimization.
StaticFX/GraalVM Native Image instead links a closed-world executable with different compiler,
GC, reflection and class-loading tradeoffs; it is not another way to package the same JVM cache.

## Architecture inspected

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
- SSH is Apache MINA SSHD 2.16.0 (`sshd-osgi`, `sshd-sftp`), with host verification and SFTP `Path`s
  through `RemoteFileSystems`. Its service providers caused a measured build-time heap failure.
- Terminal integration is external OS terminal launching in `DesktopActions`, not an embedded PTY/JNI
  terminal. Run/output tools use subprocesses.
- LSP and DAP use LSP4J 1.0.0 JSON-RPC over child-process streams/sockets. `ProcessRegistry` owns
  lifecycle cleanup. A native Editora does not eliminate the external JDK needed by JDT LS/debuggers.
- HTTP and AI use JDK HTTP APIs, Jackson, and external ACP adapters. HTML preview serves loopback HTTP
  and launches an external browser. **There is no `javafx.web` dependency / embedded WebView**.
- Spell checking uses Lucene 10.5.1 Hunspell and dictionary resources, not native Hunspell.
- Existing coverage includes `EditorBufferFxTest`, multi-caret editing/movement, folding gaps,
  semantic-token staleness, lazy overlays and inlay rendering. Existing Java typing/cost probes and
  `scripts/measure-startup.sh` remain available. JUnit/TestFX tests are JVM tests, not native tests.

## Isolation and initialization

`-Pnative` adds only runtime StaticFX dependencies, Native Image build integration, and
`src/native/resources`. `-Peditor-probe` adds only `src/experiment/java`; it is independent of Native
Image so exactly the same workload runs on HotSpot. No production Java source is modified, no native
conditionals are introduced, no compiler exclusions remove features, and the existing fork is unchanged.
Always use a **clean build when changing profiles**: Maven does not remove stale optional classes or
resources from an earlier profile automatically. Do not combine `native` with `dist` or `fatjar`.

The profile requests exact reachability metadata, G1, and `-march=compatibility`. It sets
`jfx.static.gui=false` so probe/failure logs remain observable on Windows too. Runtime benchmark commands
use `-Xmx2g -Xms64m`; G1 is also the normal JVM collector. There is no PGO training in this experiment.

The only initialization option is
`--future-defaults=run-time-initialize-file-system-providers`. Without it, GraalVM put
`RootedFileSystemProvider` instances into the image heap from its build-time `installedProviders()`
implementation, while SSHD's classes were runtime-initialized. Moving discovery to runtime preserves
runtime filesystem/environment semantics. No SSHD package was build-time initialized, no provider was
removed, and this does **not** establish that SSH/SFTP works. There are no application, RichTextFX,
Flowless or ReactFX build-time initialization rules, nor global `--initialize-at-build-time` flags.

### Reviewed metadata

Under `src/native/resources/META-INF/native-image/com.editora/`:

- `editora/reachability-metadata.json`: owned app resources, RichTextFX CSS, AtlantaFX themes,
  conditional jcodings `tables/**`; the single Gson JavaTime adapter constructor; a negative
  `javafx.scene.web.WebView` class lookup from FXML's JavaFX feature check. This last entry does not
  add WebView or promise WebView support. JavaFX control resource bundles and exact XML/DNS
  service-resource lookups support the full window. The conditional HTTP-server provider resource
  lookup supports the existing MCP automation endpoint. FXML also needs its module-resource lookup and the
  `Orientation`, `Pos`, and `Priority` enum factories used by the actual markup.
- `fxml/reachability-metadata.json`: only the constructor, 33 injected fields and named no-argument
  handlers from `main.fxml`, plus the imported JavaFX element constructors and bean methods. Regenerate with `python3 scripts/native/fxml-metadata.py`; `--check`
  detects drift and changed signatures. No whole-controller/package registration.
- `config/reachability-metadata.json`: explicitly named persistence DTOs and Jackson's Java7 support
  constructor, record-base introspection, and the XML factory service-resource lookup. These DTOs use bean/field introspection; registering their public methods, constructors
  and fields supports both read and write, unlike a first-launch trace containing only getters.
  Named JDK scalar/collection supertypes need query metadata during Jackson introspection.
  `HistoryStore`/`HistoryRevision` cover the real save workflow's local history persistence.
  `SnippetManager$Dto` covers the Jackson load triggered by ordinary Java completion after an
  edit; the release smoke test caught this background path with strict missing-registration exit.
- `lucene/reachability-metadata.json`: conditional, query-only entries for the concrete structures
  inspected by `RamUsageEstimator` while Hunspell loads. The agent and strict startup both exposed
  these. Lucene still warns that size estimation/optimizations are unavailable on non-HotSpot VMs;
  registering queries does not establish accurate native object sizing or certify spell checking.

StaticFX supplies JavaFX reflection/JNI and platform library integration. No additional app JNI or
proxy entry was needed for the editor gate; live LSP/DAP proxy/DTO coverage is a separate, unproven gate.
The external reachability metadata repository is disabled for reproducibility, not missing-metadata
reporting. All native acceptance runs use `-XX:MissingRegistrationReportingMode=Exit`: existing
application fallback catches must not hide missing native registrations.

Tracing was run on a real `EditorBuffer` workload and real application startup in fresh config
directories. Generated files stay in `target/`, are not blindly merged or committed, and include JavaFX,
JDK, agent serialization probes and platform shared-library lookups that StaticFX already handles.
The editor trace did not require extra RichTextFX/Flowless/ReactFX reflection entries. A future workflow
needs another trace and strict rerun, not blanket registrations of all application packages.

## Build and run

Locally tested toolchain: Oracle GraalVM **25.4.4.1.1+1.1**, underlying JDK **25.0.4.1.1**,
Linux x86-64, GCC 14.2.0, Debian 13. JVM baseline: Temurin **25.0.4+7**. Only Linux has
comparative benchmark evidence. Release CI also attempts macOS x64/arm64 and Windows x64 builds
and requires the same extracted-archive app workflow before uploading each asset; this is a smoke
gate, not long-session qualification. StaticFX advertises desktop Linux/macOS x64/arm64 and Windows
x64; that upstream coverage alone is not evidence for Editora on those targets.

Install Maven 3.9.x and the pinned GraalVM (set `JAVA_HOME` and put its `bin` first on `PATH`).
Verify `java -version`, `native-image --version`, and `mvn -version`. The tested Linux archive was
[`graalvm-jdk-25i4-25.0.4.1.1_linux-x64_bin.tar.gz`](https://gds.oracle.com/download/graal/25i4/archive/graalvm-jdk-25i4-25.0.4.1.1_linux-x64_bin.tar.gz).
On Debian/Ubuntu:

```sh
sudo apt-get install build-essential zlib1g-dev libgtk-3-dev libxtst-dev libxxf86vm-dev libgl1-mesa-dev
```

Runtime needs GTK3, X11/font/GL libraries; the executable is not a completely static Linux binary.
Use native host builds, not cross-compilation. Keep all emitted shared libraries alongside the image.
macOS requires the matching Xcode tools; Windows the GraalVM-supported MSVC toolchain. Release CI
attempts builds and the application workflow on those runners, but device and long-session behavior
remain untested here. Use `Application.launch`, as these entry points do,
for StaticFX's macOS first-thread handoff.

Normal builds (unchanged):

```sh
mvn clean package
mvn clean -Pdist -DskipTests -Djpackage.type=APP_IMAGE package
mvn verify
```

The `clean` in `dist` is mandatory. Set `EDITORA_REQUIRE_AOT=1` to require successful AOT training;
it needs a display (or the existing trainer's `xvfb-run` support). Never use a headless probe cache
as evidence that the **shipped** GUI-trained AOT path was tested.

Native application:

```sh
mvn clean -Pnative -DskipTests package
./target/editora-native -XX:MissingRegistrationReportingMode=Exit -Xmx2g -Xms64m \
  --config-dir /tmp/editora-native-clean-config --no-session /path/to/Example.java
```

Use a disposable config directory; plugin and peripheral compatibility are not qualified. The Maven
`package` lifecycle already builds the image once. Do not append `native:compile` (its lifecycle fork
can build it twice). Skipping JVM tests here shortens native rebuilds; `mvn clean verify` is a separate
required regression check, never replaced by native success.

Core gate, first on JVM:

```sh
mvn clean -Peditor-probe compile dependency:build-classpath -Dmdep.outputFile=target/probe-classpath.txt
python3 scripts/native/run-probe.py
python3 scripts/native/run-probe.py --desktop --sizes=102400
```

Then on Native Image, with the same assertions and workload:

```sh
mvn clean -Pnative,editor-probe -DskipTests \
  -Dnative.mainClass=com.editora.experiment.EditorProbeLauncher \
  -Dnative.imageName=editora-editor-probe package
python3 scripts/native/run-probe.py --native target/editora-editor-probe
python3 scripts/native/run-probe.py --native target/editora-editor-probe --desktop
```

The default probe uses JavaFX's built-in Headless backend and software Prism. It needs no TestFX,
Monocle or display. Native build prerequisites still apply. `--desktop` uses the real display and
platform pipeline. Keep its results separate from headless mechanics/queue/layout measurements.
`--sizes` accepts comma-separated byte sizes; `--edits` is 200–300 to fit Editora's 300-step history.
The process exits nonzero for assertions, asynchronous uncaught exceptions, or per-operation timeout.

Reviewable agent collection, on the GraalVM **JVM**, after compiling the probe:

```sh
python3 scripts/native/run-probe.py --sizes=102400 --trace target/native-agent-editor
python3 scripts/native/fxml-metadata.py --check
python3 -m unittest discover -s scripts/native -p 'test_*.py'
```

Do not benchmark an agent-instrumented or JaCoCo-instrumented launch.

## Behavioral and performance gate

The standalone probe creates a real `EditorBuffer`/CodeArea and realizes it in a Stage. It generates
100 KiB, 1 MiB, 5 MiB and 10 MiB ASCII Java files, reads them from temporary files, and checks exact
text plus SHA-256 checkpoint sequences across runtimes. The load policy mirrors `FileWorkflowCoordinator`:
**at least 5 MiB** disables syntax/undo; 10,000-line heavy files suppress minimap/LSP; a 128 KiB line
uses the segmented, large-file policy. The 50 MiB truncation/read-only cap is outside this workload.
This probe does not exercise the controller's asynchronous file-loading/save workflow.

Coverage includes 300 individually undoable character edits, redo, select-all, word/page/caret movement,
Unicode clipboard paste/copy and deletion, full and incremental TextMate tokenization with an independent
full-pass comparison, real asynchronous syntax/semantic style assertions, clearing/reapplying spans,
stale semantic rejection, diagnostics/inlays/search/change bars/bookmarks, real fold/unfold, paragraph
graphics, repeated first/last viewport jumps and window resizes, 300 simultaneous replacements with
atomic undo, multi-selection deletion/undo, 128 KiB horizontal-scroll geometry and 10,000 short lines.
Large-file mode deliberately has no undo; smaller fixtures verify undo rather than changing that policy.
The JSON `bytes` field identifies the current size-case. Multi-caret/decorations/long-line phases then
replace its contents with bounded fixtures. `after-sized-document-editing` precedes that replacement;
`after-workload` memory describes the final long-line fixture, not the original document.

A valid automated comparison requires every correctness run to pass, matching checksum sequences and
no metadata fallback. Full desktop qualification additionally needs manual inspection of
scrolling/selection/clipboard across applications. Automated
visibility/geometry assertions cannot prove that every rendered pixel or OS clipboard interaction is
correct. Simulated LSP decorations do not prove a working Java language server.

Flag a native regression if late-burst input/undo p95 rises by **both 20% and 2 ms**, tokenization or bulk
editing median rises by **20%**, or equivalent-phase RSS rises by **20%**. Any visible scrolling defect,
state mismatch, missing highlights, or persistent UI stall fails regardless of a faster startup.
These are experiment screening thresholds, not product requirements or statistical confidence bounds.
Do not tune initialization, remove features or alter the editor to pass them.

## Benchmark tooling

`scripts/native/benchmark.py` accepts JSON containing explicit command **arrays**, never shell strings:

```json
{
  "modes": {
    "jvm": {"editor": ["python3", "scripts/native/run-probe.py"]},
    "native": {"editor": ["python3", "scripts/native/run-probe.py", "--native", "target/editora-editor-probe"]},
    "aot": {"startup": ["/absolute/path/to/AOT-image/bin/Editora", "--no-session", "/absolute/path/to/Example.java"]}
  },
  "distributions": {
    "native": "/absolute/path/to/native-staging-directory",
    "jvm-aot": "/absolute/path/to/AOT-image"
  }
}
```

Prepare/stage both probe artifacts before `clean` removes `target`. Use `run-probe.py --print-command`
to emit argv JSON, replace `target/classes` with an immutable jar snapshot when comparing across builds,
and use the **same dependency classpath and input sizes**. An `env` map per mode can carry launcher
options; `{config_dir}` in argv expands to a fresh per-run configuration directory. Startup mode also
sets `EDITORA_CONFIG_DIR`, `EDITORA_PERF`, `EDITORA_PERF_EXIT` and an external epoch-millisecond T0.

```sh
python3 scripts/native/benchmark.py /path/to/commands.json --output /new/output/directory --runs 5 --warmups 1
```

The runner rotates mode order, preserves each command and log (editor stdout and stderr separately), records failed/time-out
runs separately, compares text checksum sequences, and writes `raw.json` and `summary.json`. It reports
sample count, median, p95 and p99 (nearest-rank tails). Small-n startup tails are descriptive, not stable
population estimates. Filesystem caches are warm; no cold-disk claims. No forced GC is used.

The `*-after-100` subsets omit each burst's first 100 operations; they do not prove that HotSpot has reached an hours-long steady state.

Timing scope is explicit: `*-dispatch` includes FX queueing and synchronous work, not later rendering;
`*-with-layout` includes two pulses; tokenization isolates actual full computation;
`incremental-highlight-settled` includes the real debounce and style completion. Pulse completion on
Headless is **not** desktop frame latency. Probe `fx-ready`, `window-shown` and `file-editable` are
external pipe-receipt timestamps, including scheduler/pipe delay. `file-editable` requires a successful
insert/delete and document oracle, after layout. It is a **probe** startup metric, not the main app.

Real app startup uses the existing `Startup` marks: process → FX, window shown, file loaded, first paint.
The runner refuses to accept `Stage.show()` as completion. The application probe below measures first-editable separately. Project-wide background indexing and
Java-LSP-ready remain **unmeasured**, not inferred from paint or a successful project search.
RSS is Linux `/proc/<pid>/status`, directly sampled, excluding child LSP/DAP processes; Java
`totalMemory - freeMemory` is separately labeled heap-used and is not directly comparable to RSS.

### Full application workflow and the current AOT cache

`scripts/native/app-probe.py` launches the **unchanged main entry point** and drives the existing
loopback MCP server. Each run creates a disposable 100 KiB Java file, Maven/Git project, and config;
it enables MCP and disables automatic update checks and LSP. No user files/settings or production
hooks are involved. It waits for real file paint and an exact buffer read, applies an edit, verifies
that edit, and only then records `first-file-editable`. This includes polling, HTTP and FX queue delay.
It also checks 30 edit/undo/redo cycles by default (`--cycles` changes this) with full text oracles,
saves and waits for exact disk contents and local-history persistence,
searches the project, and checks that Git reports the modified file. MCP's ordinary adjacent-edit
merging is preserved; undo between cycles provides the boundary. The separate editor probe tests
hundreds of independent edits followed by hundreds of undo/redo operations.

Use a config with `application` argv arrays (just launchers/VM options, **no file arguments**):

```json
{
  "modes": {
    "jvm": {"application": ["/absolute/path/to/jvm-image/bin/Editora"]},
    "current-aot": {"application": ["/absolute/path/to/aot-image/bin/Editora"]},
    "native": {"application": ["/absolute/path/to/native/editora-native",
      "-XX:MissingRegistrationReportingMode=Exit", "-Xmx2g", "-Xms64m",
      "-Dprism.order=es2,sw", "-Dprism.maxvram=2G", "-Dprism.maxTextureSize=16384"]}
  }
}
```

```sh
python3 scripts/native/app-probe.py /path/to/application-commands.json \
  --output /new/application-results --runs 5 --warmups 1
```

Make two copies of the same clean `dist` app image. In the JVM copy only, remove `editora.aot` and its
`java-options=-XX:AOTCache=...` launcher-config line; append `java-options=-XX:AOTMode=off` to its
`[JavaOptions]` section. In the AOT copy append `java-options=-XX:AOTMode=on` so a missing/rejected cache
fails instead of silently falling back. Keep all other options identical. These are benchmark copies;
the repository's jpackage configuration and trainer remain unchanged. On Linux the config is
`lib/app/Editora.cfg`, the cache `lib/app/editora.aot`, and the launcher `bin/Editora`.

The standalone editor probe cannot reuse the shipped cache: changing `jdk.module.main`, exports and
module roots makes its archived full module graph incompatible (verified with `AOTMode=on`, which
fails). A separately trained, same-classpath **probe AOT cache** is therefore only a secondary control,
explicitly labeled `probe-aot`. It is not the shipped cache and not a stronger existing Editora trainer.
The full application workflow above measures the real shipped AOT cache with its original module graph.
Train the secondary cache against an immutable probe jar and dependency classpath using the same JDK:

```sh
java --enable-native-access=ALL-UNNAMED -Xmx2g -Xms64m -XX:+UseG1GC \
  -XX:+UnlockDiagnosticVMOptions -XX:-AOTAdapterCaching -XX:AOTCacheOutput=/absolute/probe.aot \
  -Dglass.platform=Headless -Dprism.order=sw -cp "$PROBE_CLASSPATH" \
  com.editora.experiment.EditorProbeLauncher --sizes=102400
```

Replay with the identical jar/classpath, replace `AOTCacheOutput` with `AOTCache`, and add
`-XX:AOTMode=on`. The full application data is the relevant JVM/current-AOT/native startup comparison.
The probes provide RSS after paint/project-open, after first edit, and after editing/save, plus separate
heap/RSS marks in the editor-only large-file workload. Search-responsive is a bounded operation, not a
claim that all project background work is complete. LSP is disabled in these measured runs.

## Plugin assessment

`PluginManager` collects `*.jar` and `lib/*.jar` into a child `URLClassLoader`, then calls
`Class.forName(manifest.main, true, loader)` and a reflective constructor. Arbitrary Java bytecode
installed after image creation is incompatible with Native Image's closed world. Metadata cannot
fix this. Prelinking a fixed plugin set would be a different distribution, not current runtime loading.
Nothing in this experiment redesigns or disables the plugin system to make compilation succeed.

Declarative keymaps, snippets, templates and external commands do not intrinsically require dynamic
bytecode; their parser/DTO/resource paths still require native verification. They are not automatically
certified by a plugin-free launch. JVM plugins could theoretically live in a separate JVM process with
JSON-RPC/MCP or sockets. Editora already has subprocess and MCP infrastructure, but its current
`PluginContext` can exchange JavaFX Nodes and live Java objects: that API is not remotely serializable.
An IPC protocol, capability model and replacement for in-process UI contributions would be future design
work, not a trivial compatibility fix. External command contributions already provide a limited clean
process boundary.

## Feature qualification boundaries

| Requested workflow | Evidence / status |
| --- | --- |
| Startup, main window, project, text/Java open | Strict full native app smoke checks; repeated main-app benchmark opens its generated Maven/Git project and Java file. |
| Edit Java, undo/redo, save | Existing MCP → controller/command registry/file workflow; exact live text and saved-byte oracles. Local history JSON is also checked. |
| Syntax, multi-caret, folding, decorations, Flowless, large files | Shared real-`EditorBuffer` probe; simulated LSP styles, not a live language server. |
| Project search, Git status | Full native app MCP workflow; finds the fixture and sees its saved modification in a disposable Git repository. |
| Java LSP startup/completion/diagnostics/definition | Not qualified; LSP disabled in measured runs. JSON-RPC proxies, protocol DTOs and subprocess lifecycle need a separate strict workflow. No fundamental closed-world blocker established for an external server. |
| Git diff UI/history | Not qualified. External Git status success does not certify these UIs. |
| DAP/debugging | Not qualified; DTO/proxy coverage and actual debugger startup remain. |
| SSH/remote editing | Not qualified. Runtime filesystem-provider discovery fixes image construction, not SSH behavior. |
| Terminal integration | Not qualified; external process-launch integration remains a platform test. |
| HTTP client | Not qualified. MCP's loopback HTTP **server** works; it does not certify HTTP editor requests. |
| Spell checking | Startup reaches Hunspell/Lucene; spelling/suggestions not qualified. Non-HotSpot size/optimization warning remains visible. |
| AI integrations | Not qualified; no provider credentials or external agent workflows exercised. |
| WebView | Not applicable: no embedded WebView; external-browser preview remains unqualified. |
| Plugins | Arbitrary post-build Java/JAR loading is fundamentally unsupported by this native model. Declarative contributions remain unqualified. |
| Other exports/serialization | Office/PDF/SVG/math, every preference DTO and every language grammar are not certified by Java-file success. |

All desktop claims here are automated checks on the local Linux display. They are not human inspection
of every pixel, cross-application clipboard behavior, accessibility, IME, or a multi-hour session.
Unqualified features are left in the source/build, rather than removed to make Native Image compile.

## CI

Native is deliberately not a required job. The release workflow runs independent experimental
jobs for Linux x64, macOS x64/arm64 and Windows x64, each building on its host OS. Every archive
is extracted and must pass the actual application workflow before upload. Linux runs it under
`xvfb-run`; other hosts use their runner desktop. The JVM release requirements remain unchanged.
The earlier Linux benchmark methodology still applies: stage the probe binary/libraries before
cleaning for the application build and retain logs, summary JSON and image-size inventory.
An automated smoke pass is not a human hardware-rendering or long-session trial.

Build warnings were inspected: build-tools 1.0.0 emits `--no-fallback`, which GraalVM 25.4 deprecates
because fallback no longer exists; this produces two deprecation notices. Maven also cannot derive
JPMS module names from StaticFX artifact filenames (the word `static` is a Java keyword); they are
runtime/classpath-only dependencies, so this does not change normal JPMS compilation. Neither warning
was disabled. The local machine lacked `libxxf86vm-dev`; it was unpacked under a temporary sysroot and
the native linker given its library directory. A runner with the documented apt prerequisites needs
no special flag. All image code-generation flags otherwise matched the profile.

## Results and acceptance status

Measured on 2026-09-22 on the Linux host recorded in
[`environment.json`](../artifacts/native-image-staticfx/environment.json). **The prototype passes the
bounded headless editor correctness gate and basic full-application workflows, but fails the stated
editor-performance screen.** Further peripheral qualification stopped at that finding. No attempt was
made to hide it with editor changes, a new fork, unsafe initialization, PGO or removed features.

Evidence and per-run results are under [`artifacts/native-image-staticfx`](../artifacts/native-image-staticfx/README.md).
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

## Sources

- [StaticFX repository and version constraints](https://github.com/HebiRobotics/jfx-static-feature).
- [GraalVM downloads](https://www.graalvm.org/downloads/) and
  [agent documentation](https://www.graalvm.org/reference-manual/native-image/Agent/).
- [GraalVM reachability metadata and strict reporting](https://www.graalvm.org/latest/reference-manual/native-image/metadata/).
- [GraalVM runtime filesystem-provider initialization](https://github.com/oracle/graal/blob/master/substratevm/CHANGELOG.md).
- Local [packaging guide](building-and-packaging.md), [plugin API](plugins.md),
  [testing guide](testing.md), [LSP/DAP](subsystems/lsp-and-dap.md).
