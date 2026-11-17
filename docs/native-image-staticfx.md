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

## Isolation and initialization

`-Pnative` adds only runtime StaticFX dependencies, Native Image build integration, and
`src/native/resources`. `-Peditor-probe` adds only `src/experiment/java`; it is independent of Native
Image so exactly the same workload runs on HotSpot. No production Java source is modified, no native
conditionals are introduced, no compiler exclusions remove features, and the existing fork is unchanged.
Always use a **clean build when changing profiles**: Maven does not remove stale optional classes or
resources from an earlier profile automatically. Do not combine `native` with `dist` or `fatjar`.

The profile requests exact reachability metadata, G1, `-march=compatibility`, and
`-H:+AddAllCharsets`. The latter keeps the Windows host charset (`Cp1252` on the hosted runner)
available when Java's filesystem provider starts; without it, the native executable exits before
the editor opens. It sets
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
  add WebView or promise WebView support. JavaFX control and Windows theme resource bundles, plus
  exact XML/DNS service-resource lookups, support the full window. The conditional HTTP-server provider
  resource lookup supports the existing MCP automation endpoint. The `java.logging` locale resources keep JavaFX
  startup warnings visible under strict missing-registration mode on hosted runners. JavaFX's
  software Marlin renderer also needs the JDK's aligned-int segment VarHandle method queries;
  this is conditional on Marlin's off-heap path. FXML also needs its module-resource lookup and the
  `Orientation`, `Pos`, and `Priority` enum factories used by the actual markup.
- `fxml/reachability-metadata.json`: only the constructor, 33 injected fields and named no-argument
  handlers from `main.fxml`, plus imported JavaFX element constructors/bean methods and query-only
  entries for the layout/control superclasses that FXML's `BeanAdapter` inspects. Regenerate with `java scripts/nativeimage/FxmlMetadata.java`; `--check`
  detects drift and changed signatures. No whole-controller/package registration.
- `config/reachability-metadata.json`: explicitly named persistence DTOs and Jackson's Java7 support
  constructor, record-base introspection, and the XML factory service-resource lookup. These DTOs use bean/field introspection; registering their public methods, constructors
  and fields supports both read and write, unlike a first-launch trace containing only getters.
  Named JDK scalar/collection supertypes, including `Iterable` on the Intel macOS JDK 25 build,
  need query metadata during Jackson introspection.
  `HistoryStore`/`HistoryRevision` cover the real save workflow's local history persistence.
  Snippet files need no entry: `SnippetManager` reads them as a JSON tree and writes plain maps, with
  no data-bound class (it had a `Dto` until the load became per-entry).
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
The hosted Apple Silicon runner's virtual Metal GPU throws an `AppleParavirtDevice` exception during
startup. The experimental macOS arm64 archive therefore launches with `-Dprism.order=sw`; the smoke
test checks that exact launcher. Hardware rendering on a physical Apple Silicon Mac remains untested.

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
java scripts/nativeimage/RunProbe.java
java scripts/nativeimage/RunProbe.java --desktop --sizes=102400
```

Then on Native Image, with the same assertions and workload:

```sh
mvn clean -Pnative,editor-probe -DskipTests \
  -Dnative.mainClass=com.editora.experiment.EditorProbeLauncher \
  -Dnative.imageName=editora-editor-probe package
java scripts/nativeimage/RunProbe.java --native target/editora-editor-probe
java scripts/nativeimage/RunProbe.java --native target/editora-editor-probe --desktop
```

The default probe uses JavaFX's built-in Headless backend and software Prism. It needs no TestFX,
Monocle or display. Native build prerequisites still apply. `--desktop` uses the real display and
platform pipeline. Keep its results separate from headless mechanics/queue/layout measurements.
`--sizes` accepts comma-separated byte sizes; `--edits` is 200–300 to fit Editora's 300-step history.
The process exits nonzero for assertions, asynchronous uncaught exceptions, or per-operation timeout.

Reviewable agent collection, on the GraalVM **JVM**, after compiling the probe:

```sh
java scripts/nativeimage/RunProbe.java --sizes=102400 --trace target/native-agent-editor
java scripts/nativeimage/FxmlMetadata.java --check
java scripts/lib/RunTests.java nativeimage
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

`scripts/nativeimage/Benchmark.java` accepts JSON containing explicit command **arrays**, never shell strings:

```json
{
  "modes": {
    "jvm": {"editor": ["java", "scripts/nativeimage/RunProbe.java"]},
    "native": {"editor": ["java", "scripts/nativeimage/RunProbe.java", "--native", "target/editora-editor-probe"]},
    "aot": {"startup": ["/absolute/path/to/AOT-image/bin/Editora", "--no-session", "/absolute/path/to/Example.java"]}
  },
  "distributions": {
    "native": "/absolute/path/to/native-staging-directory",
    "jvm-aot": "/absolute/path/to/AOT-image"
  }
}
```

Prepare/stage both probe artifacts before `clean` removes `target`. Use `RunProbe.java --print-command`
to emit argv JSON, replace `target/classes` with an immutable jar snapshot when comparing across builds,
and use the **same dependency classpath and input sizes**. An `env` map per mode can carry launcher
options; `{config_dir}` in argv expands to a fresh per-run configuration directory. Startup mode also
sets `EDITORA_CONFIG_DIR`, `EDITORA_PERF`, `EDITORA_PERF_EXIT` and an external epoch-millisecond T0.

```sh
java scripts/nativeimage/Benchmark.java /path/to/commands.json --output /new/output/directory --runs 5 --warmups 1
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

`scripts/nativeimage/AppProbe.java` launches the **unchanged main entry point** and drives the existing
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
java scripts/nativeimage/AppProbe.java /path/to/application-commands.json \
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
| Terminal integration | Not qualified; external process-launch integration remains a platform test. The integrated terminal's JNA and pty4j natives (ADR 0014) are not configured for the native image. |
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
`xvfb-run`; macOS uses the runner desktop; Windows uses StaticFX's headless toolkit because the
hosted runner lacks an interactive desktop. The JVM release requirements remain unchanged.
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

## Results

The experiment's measured results, its acceptance checklist and the architecture it inspected (with the
commit and dependency versions it started from) are a dated record, kept in
[reference/native-image-staticfx-results.md](reference/native-image-staticfx-results.md). In short: the
prototype passed the bounded headless editor correctness gate and basic full-application workflows but failed
the editor-performance screen, so it is not an accepted alternative to the JVM/AOT distribution.

## Sources

- [StaticFX repository and version constraints](https://github.com/HebiRobotics/jfx-static-feature).
- [GraalVM downloads](https://www.graalvm.org/downloads/) and
  [agent documentation](https://www.graalvm.org/reference-manual/native-image/Agent/).
- [GraalVM reachability metadata and strict reporting](https://www.graalvm.org/latest/reference-manual/native-image/metadata/).
- [GraalVM runtime filesystem-provider initialization](https://github.com/oracle/graal/blob/master/substratevm/CHANGELOG.md).
- Local [packaging guide](building-and-packaging.md), [plugin API](plugins.md),
  [testing guide](testing.md), [LSP/DAP](subsystems/lsp-and-dap.md).
