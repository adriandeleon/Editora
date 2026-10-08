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

- A question a save puts to the user is answered through its real dialog, every way it can be answered
  (`SaveDecisionsFxTest.answerDialog` presses a button of the dialog with a given header, or closes it).
  What cannot run in a test is injected at the boundary instead: `FileWorkflowCoordinator.saveAsTargetChooser`
  stands in for the native file chooser, and `elevationProcess` for `pkexec`/`osascript` — a test never
  launches a real elevation helper; `AdminSaveDecisionsFxTest` runs the same script without privileges.

### Every command is run

`CommandSweepFxTest` builds a real window and runs **every** id in its `CommandRegistry` through
`CommandRegistry.run`, in five states (a fresh window, an unsaved new buffer, a text file, a Markdown file,
and a project window on a Git repository). It fails when a command throws, leaves an uncaught exception on
the FX thread or a worker, starts a program other than `git`, or reaches for the network. A `*.toggle*`
command runs twice, so both directions are exercised and the setting is back where it was.

- **A new command is covered without touching the test.** The feature under it still needs its own tests;
  this one covers the binding from the id to the action and the guard in front of it.
- A command that cannot run headless goes in `EXCLUDED` with a one-line reason. One that ends at a native
  file chooser — which the Headless platform refuses with an exception — goes in `NATIVE_CHOOSER`: it runs
  up to the chooser. Both lists fail the test when an entry is stale.
- Modal dialogs are answered with Cancel/No as they appear, so confirmations are exercised and never
  accepted. Printing answers "no printer" (`ExportCoordinator.printJobs`): no job reaches a real printer.
- The sweep window's external tools (`gh`, `rg`, `typst`, `dot`, Maven, npm, …) are pointed at a path that
  does not exist, so the run is the same on a developer's machine as on CI. A command that needs one of them
  to do anything is only covered up to its "not installed" guard.
- The same class checks that every chord of every bundled keymap, and every menu-bar entry, names an id the
  window really registers (`KeymapsTest`/`MenuBarModelTest` check them against the message keys only).

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

### The suite-wide timeout

`src/test/resources/junit-platform.properties` sets `junit.jupiter.execution.timeout.default = 5 m`:
no test method and no lifecycle method (`@BeforeAll`, `@BeforeEach`, …) may run longer. A test that
waits forever is then reported by name instead of holding its CI job until the job's own 30-minute
limit kills it. The slowest whole test class in CI takes about a minute, so the limit only ever
catches a hang.

- The timeout **interrupts** the thread running the test. A wait that ignores interruption is only
  reported once it returns, and a test that leaves the FX thread itself stuck (a dialog nobody
  answers) still stalls every FX test after it — each for five minutes.
- A test that is meant to run longer says so with its own `@Timeout`, which wins over the default. The
  long opt-in probes (`JavaTypingSoakProbeTest`, `JavaProjectEditingProbeTest`,
  `JavaEditingCostProbeTest`) do.
- Timeouts are off while a debugger is attached (`junit.jupiter.execution.timeout.mode =
  disabled_on_debug`). To run without them otherwise, pass
  `-Djunit.jupiter.execution.timeout.mode=disabled`.

## Download and install tests

Code that downloads, verifies and unpacks things (`install/InstallService`, `plugin/PluginRegistry`,
`plugin/PluginInstaller`, and the `ui` coordinators over them) is tested without the network and without
installing anything outside a JUnit temp dir.

- **`io/LoopbackDownloads`** (test tree) is a JDK `HttpServer` on `127.0.0.1:0` plus an `HttpClient` that
  sends every request to it. Production code is still given real-looking URLs
  (`https://download.eclipse.org/…`), so its own rules — HTTPS only, the install catalog's host list — run
  as they do for a user; the client rewrites `https://host/path` to the loopback server only after that.
  It can answer with a body, a status, a redirect, a transfer cut off part-way, or a body held until a
  latch is released, and it records what was requested.
- **`io/TestArchives`** builds zips and tarballs in memory, including entries no honest archive has
  (`..` segments, absolute names, links that point out of the tree).
- The services take the client through a package-private constructor and expose a synchronous form of
  their worker job (`installSync`, `fetchSync`, `installFromUrlSync`) so the pure lane needs no toolkit.
  `InstallTestAccess` / `PluginTestAccess` hand those seams to tests in other packages.
- Tarball extraction is the system `tar`'s, not Editora's. `InstallServiceTarTest` asserts only what must
  hold for both GNU tar and bsdtar — nothing outside the install folder is created or changed — and is
  disabled on Windows.
- Never trigger an npm/pip/toolchain install step from a test: those run the real package manager.

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
`mvn verify` additionally runs a JaCoCo **`check`** that enforces a **line-coverage floor** per
package, and per class where a package is too large for its own number to protect one class. The
`jacoco-check` execution in `pom.xml` is the source of truth; `BuildHygieneTest` fails when the
table below and the pom disagree.

Measured on 2026-10-08 the full suite covers 93.4% of lines, 92.9% of methods and 81.9% of branches.
The build as a whole may not fall below **0.90 lines, 0.90 methods and 0.79 branches**. The pure suite
alone (`-DexcludedGroups=fx`) covers under half of that, which is all the Windows lane exercises.

| Package | Floor | Measured |
|---|---|---|
| `cron` | 0.96 | 99.7% |
| `systemd` | 0.96 | 99.6% |
| `csv` | 0.95 | 98.2% |
| `diff` | 0.94 | 97.5% |
| `editops` | 0.94 | 97.1% |
| `config` | 0.93 | 96.3% |
| `mcp` | 0.93 | 96.3% |
| `office` | 0.93 | 96.8% |
| `run` | 0.93 | 96.4% |
| `template` | 0.93 | 96.1% |
| `test` | 0.93 | 96.1% |
| `todo` | 0.93 | 96.2% |
| `agent` | 0.92 | 95.5% |
| `completion` | 0.92 | 95.2% |
| `config.migration` | 0.92 | 95.1% |
| `git` | 0.92 | 95.9% |
| `github` | 0.92 | 95.7% |
| `logviewer` | 0.92 | 95.5% |
| `lsp` | 0.92 | 95.4% |
| `editor` | 0.91 | 94.2% |
| `editorconfig` | 0.91 | 94.0% |
| `http` | 0.91 | 94.6% |
| `index` | 0.91 | 94.4% |
| `markdown` | 0.91 | 95.0% |
| `search` | 0.91 | 94.4% |
| `build` | 0.90 | 93.1% |
| `command` | 0.90 | 93.7% |
| `pdf` | 0.90 | 93.6% |
| `install` | 0.89 | 92.6% |
| `sync` | 0.89 | 92.1% |
| `ui` | 0.89 | 92.8% |
| `maven` | 0.88 | 91.9% |
| `plugin` | 0.88 | 91.9% |
| `snippet` | 0.88 | 91.5% |
| `dap` | 0.87 | 90.9% |
| `print` | 0.87 | 90.0% |
| `process` | 0.86 | 89.4% |
| `recovery` | 0.86 | 89.0% |
| `history` | 0.85 | 89.0% |
| `io` | 0.84 | 87.4% |
| `ai` | 0.83 | 86.7% |
| `web` | 0.80 | 83.8% |
| `ui.LspCoordinator` (class) | 0.90 | 93.8% |
| `ui.WindowCommandRegistrar` (class) | 0.95 | 98.7% |

- Each floor sits **three points below** the measured level — a regression net, not a target. **When
  you raise a package's coverage, ratchet its floor up.**
- Every package of 300 lines or more that measures 80% or better has a floor. Left out on purpose: the
  entry point, and the diagram, Typst and Mermaid packages, whose tests need tools the CI runner does
  not install.
- Branch coverage is the counter with room left. What remains is mostly the null/absent halves of
  compound conditions, stale-reply guards, and failure arms with no boundary to inject the failure at.
  Lines that cannot run in a test at all are native file choosers and drag-and-drop, real printing,
  OS-specific code, and anything that would start an external program.
- The command-registrar class floor is held up by `CommandSweepFxTest`. If it drops, the sweep has
  stopped reaching commands.

CI writes the two totals to the job summary and uploads the HTML report from the JDK 25 lane as the
`jacoco-report` artifact.

The dev loop (`mvn javafx:run`/`compile`) is unaffected; the check runs only at `verify`.

### Skipped tests are checked

A test guarded by an assumption reports "skipped" when its tool or filesystem feature is missing, so a
runner that loses the tool stays green while the feature goes untested. After the Linux lanes run,
`scripts/check_skips.py` compares every skipped test with `scripts/expected-skips-linux.txt` and fails
on one that is not listed. To run it locally after `mvn test`:

```
python3 scripts/check_skips.py target/surefire-reports scripts/expected-skips-linux.txt
```

If you add a test that legitimately skips on the runner, list it there with the reason. If it skips
because the runner lacks a tool, prefer installing the tool in `ci.yml`, as is done for ripgrep.

## Script tests

The release and packaging scripts have their own tests — Python `unittest` files beside the scripts,
run by the `scripts` CI job (not by Maven):

```
for d in scripts scripts/release scripts/packaging scripts/native; do
  python3 -m unittest discover -s "$d" -p 'test_*.py'
done
```

They run the real shell scripts against throwaway directories: the tarball installer in user mode and,
through `--destdir`, in system mode; the `.deb` `postinst`/`postrm` against a scratch root
(`DPKG_ROOT`); `build-appimage.sh` with a stand-in `curl` (so the verify-before-run logic is tested
without the network); and the release-asset manifest. The Java side has matching guards in
`com.editora.packaging` (`ReleaseSupplyChainTest`, `BuildHygieneTest`, `NoticeCoverageTest`,
`SshdModuleDescriptorTest`, `WindowsFileAssociationsTest`) that read the workflows, the pom and the
packaging files as text.

CI also runs the pure suite (`-DexcludedGroups=fx`) on `windows-latest` and `macos-15`. A separate
advisory job runs the FX suite (`-Dgroups=fx`) on `macos-15`; it does not block a merge until it has a
track record there.

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

## Optional cross-runtime editor gate

The [StaticFX experiment](native-image-staticfx.md) adds `-Peditor-probe`, a standalone workload
using the production `EditorBuffer` on HotSpot and Native Image. It covers generated 100 KiB–10 MiB
documents, multi-caret undo, syntax/style staleness and viewport geometry. It is not part of the default
JUnit run; native success does not replace `mvn verify`. See that guide for exact commands and limits.
