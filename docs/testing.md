# Testing

```
mvn test                          # everything (pure + FX harness)
mvn test -DexcludedGroups=fx      # pure suite only (fast, no toolkit)
mvn verify                        # tests + Spotless check + JaCoCo coverage floors
```

## Prefer pure logic

The bulk of the suite is **pure logic** — keymap resolution, config merge, highlighting spans,
the editing helpers (`Indenter`, `Commenter`, `MarkdownLint`, `MarkdownLintFix`, …), parsers
(`StatusParser`, `DiffParser`, `MaidOutput`, …). These are fast and need no toolkit.

The cheapest way to cover the toolkit-bound packages (`ui`, `editor`) is to **extract a pure
decision helper and unit-test that** rather than drive the UI: effective-visibility/gating
predicates, caret-navigation math, path keying, and the like. See `ui/Chrome`, `editor/TextNav`,
`config/PathKeys` for the pattern. When you write a feature, factor the decision out of the
JavaFX code so it can be tested directly.

## The headless-FX harness

Real controller behavior (Zen toggling chrome, Simple-mode stripping, tab/window lifecycle, the
coordinators) is covered end-to-end by a **TestFX** harness running over JavaFX's built-in
**Headless Glass platform** (`-Dglass.platform=Headless`, part of `javafx.graphics` since 26), so
FX tests run headless on CI with **no display/xvfb** — no Monocle jar, no native libs, nothing
vendored. The harness only uses `FxToolkit` to boot the toolkit; it never drives the TestFX robot
(no `clickOn`/key simulation — everything goes through `runOnFx`/`callOnFx` + reflection), so the
prototype platform's input/rendering limitations don't apply.

- All FX tests are tagged `@Tag("fx")`. Run the pure suite alone with
  `mvn test -DexcludedGroups=fx`.
- **`FxTestSupport`** boots the toolkit once (the Headless platform + `Fonts.load`/`Messages.init`
  + the AtlantaFX UA sheet + the classloader pin) and exposes `runOnFx`/`callOnFx` + reflection
  helpers (`field`/`invoke`/`call`) to read private `@FXML` nodes and call private methods. Tests
  run on the **classpath** as the unnamed module, so `setAccessible` is unrestricted.
- **`FxWindowFixture`** builds a real window via `WindowManager.buildWindowForTest()` — a
  package-private seam mirroring `buildWindow` — against a temp config dir. **If that boot path
  changes, the fixture/seam must track it.** Its idempotent `dispose` force-releases controller,
  plugin, shared-config, and temporary-file resources without opening user-facing close prompts.
- For tests with workers or deferred FX callbacks, own the fixture and any test threads with
  **`AsyncTestScope`**. Use its worker/Future, latch, and FX barriers instead of `Thread.sleep`; the
  scope reports exceptions from its threads and uncaught FX callbacks before closing every owned
  resource.
- Persistence tests should hold work at a real commit boundary and assert the surviving bytes and editor
  state, rather than merely checking that an exception was thrown. The save-ordering, atomic-write, Replace
  in Files, and Local File History restore tests use the app-wide document-write sequencer and injected I/O
  boundaries to cover stale completion, concurrent changes, and failed replacement without timing sleeps.

### The surefire config that makes it work

In `pom.xml`:

- `<useModulePath>false</useModulePath>` — classpath mode.
- `--enable-native-access=ALL-UNNAMED` — JavaFX loads its test natives from that unnamed module;
  declaring the access keeps the JDK 27 lane free of restricted-native-access warnings.
- headless system properties: `glass.platform=Headless`, `prism.order=sw`.
- `<argLine>@{argLine} …</argLine>` — **the `@{argLine}` token is mandatory** so JaCoCo's injected
  coverage agent (set via the `argLine` property) survives. A plain `<argLine>` would clobber it.

Because the backend ships inside JavaFX, it can never go stale on a JavaFX bump — unlike the
previously self-built Monocle backend it replaced (see
[dependencies.md](dependencies.md#the-headless-test-backend-no-vendored-dependency)).

## JDK compatibility lanes

CI compiles and tests the project twice: with the supported Temurin JDK 25 baseline and with
Oracle JDK 27, using each JDK's matching `--release`. Oracle supplies the Java 27 GA build while
Temurin 27 binaries are not yet available. The JDK 27 lane is a blocking forward-compatibility
gate; release packaging remains on JDK 25 until the native five-platform matrix has been
qualified.

Formatting is a separate JDK 25 job. Palantir Java Format currently reaches into javac internals
that changed in JDK 27, so the two build lanes skip Spotless while the dedicated job preserves the
same formatting gate. Local `mvn verify` on the supported JDK 25 baseline remains the complete
one-command check.

## Coverage

`mvn test` runs the JaCoCo agent and writes `target/site/jacoco/index.html` (+ `jacoco.xml`).
`mvn verify` additionally runs a JaCoCo **`check`** that enforces a per-package **line-coverage
floor** on the well-covered pure packages (e.g. `config`/`migration`/`diff` ≥ 0.85/0.80,
`template`/`editorconfig` ≥ 0.80, `completion`/`http`/`pdf` ≥ 0.68 — see the `jacoco-check`
execution in `pom.xml`).

- The floors sit **below** current levels — they're a regression net, not a target. **When you
  raise a package's coverage, ratchet its floor up.**
- `ui`/`editor` are deliberately **ungated** (the FX harness covers only a few percent of `ui`
  so far). Add a floor for them only once coverage is meaningful — and the cheapest way to get
  there is still to extract pure helpers.

The dev loop (`mvn javafx:run`/`compile`) is unaffected; the check runs only at
`verify`/`package`.

## What to test for a typical change

- A pure helper → a dedicated `*Test` with positive/negative/edge cases (front matter, empty,
  null, multi-byte, …).
- A config-schema change → a migration test if it's not additive-identity; otherwise the
  round-trip is covered by the read path.
- New i18n keys → `MessagesTest` already enforces six-catalog parity and command/desc pairing;
  just keep the catalogs complete.
- Controller-visible behavior → a `@Tag("fx")` test via `FxWindowFixture` if it can't be reduced
  to a pure helper.

## Live Java editing probe

`JdtlsTypingProbeTest` is opt-in and uses the production session, initialization capabilities, and
completion mapper with a temporary Maven project. It covers basic/member completion, expected-type
contexts, overrides, method references, incomplete generics/calls, signature help, and resolved imports.
It prints sync, server, and mapping/ranking timings. It does not require a private fixture or fixed
Homebrew installation:

```
mvn test -Dtest=JdtlsTypingProbeTest -Dgroups=probe -Dlsp.java.probe.command=/absolute/path/to/jdtls
```

Use JDK 25 and a current JDT LS. Project readiness is checked through completion, not a fixed startup
sleep. The probe waits for its server process to exit before JUnit deletes the temporary workspace.
It reports known server gaps separately; add `-Dlsp.java.probe.strict=true` to fail on the reproduced
same-file type/import conflict when evaluating a JDT LS upgrade. See the
[Java editing review](subsystems/java-editing-review.md) for the exact reproduction and limits.
For the full client pipeline, add `-Deditora.completion.trace=true` to the editor JVM or to
`JavaTypingCompletionFxTest`; timings contain stage names and durations, never source text.

`JavaProjectEditingProbeTest` proves sibling-module resolution in generated two-module projects before
running chained completion in a 75 KB source file and repeated structural/import edits. Project and
Eclipse workspace must be siblings: putting the workspace inside the project prevents Maven import.

```
mvn test -Dtest=JavaProjectEditingProbeTest -Dgroups=probe -Dlsp.java.probe.command=/absolute/path/to/jdtls -Dlsp.java.probe.rounds=50
mvn test -Dtest=JavaProjectEditingProbeTest -Dgroups=probe -Dlsp.java.probe.command=/absolute/path/to/jdtls -Dlsp.java.probe.project=gradle -Dlsp.java.probe.gradleHome=/absolute/path/to/gradle
```

Both use the production lifecycle-joining default. For a controlled regression comparison with the
Python `jdtls` launcher, add `-Dlsp.java.probe.join=false`; the old configuration can fail ordinary
import-preservation assertions. `-Dlsp.java.probe.strict=true` additionally asserts the known same-file
name-conflict case. These probes print generated fixture source on failure, never user project text.

### Sustained typing and large-file measurements

`scripts/probes/java-typing-study.py` copies real projects to a new output directory and snapshots the
compiled runtime, so ongoing builds cannot change a running study. It never edits the source project.
First run `mvn test` on JDK 25 to compile the probes and generate the dependency classpath, then:

```sh
python3 scripts/probes/java-typing-study.py \
  --maven-project /path/to/Editora --gradle-project /path/to/RichTextFX \
  --jdtls /path/to/jdtls --java-home /path/to/jdk25 \
  --gradle-java-home /path/to/jdk21 --output /tmp/java-typing-study --seconds 1800
```

The current readiness assertions are specific to these two projects: `EditorBuffer.getArea` and
`CodeArea.getText` must resolve. Adapt those assertions and the source location for other projects.
The Gradle wrapper must support the selected Gradle JVM, which is independent of JDT LS's JVM.
The probe supplies the open project root explicitly; selecting only a child module does not prove
that the parent build and sibling dependencies loaded.

The study fires JavaFX typed/pressed events, accepts items with arrows/Enter, edits snippet arguments,
waits for resolved imports, and exercises backspace/chaining. Every fourth round adds 178,890 characters
of generated fields. Startup is measured separately; `--seconds` is split across the selected projects.
Stage samples include dispatch, key handler, protocol, mapping/filtering, popup model, and JavaFX pulse
intervals. These headless pulse intervals are **not** painted desktop frame latency. The selected rank
is logged across repetitions without changing server relevance. `--typing-mode macro` compares the
editor's macro replay path with the default physical-event path. Failures include a bounded history
of requests, cancellations, result sizes and the generated caret paragraph; normal CI does not run
these long probes.

`JavaEditingCostProbeTest` is an opt-in standalone runner for 16 KB–4 MB source snapshots and sync diffs.
Use the same JavaFX/classpath arguments as the launcher with `-Dlsp.java.cost.probe=true` and main class
`com.editora.ui.JavaEditingCostProbeTest`. Run outside JaCoCo for performance measurements. Its key and
snapshot measurements intentionally separate synchronous component costs; they exclude server time,
transport and subsequent layout/highlighting pulses.

The [study evidence](../artifacts/java-editing-study/README.md) records methodology and limitations.
The [standalone import reproduction](../artifacts/java-editing-study/jdt-import-conflict/REPORT.md)
uses only Python and JDT LS, independently of Editora.
