# Building & packaging

Run Maven from the project root. The bundled `./mvnw` wrapper is fine.

## Everyday

| Command | What it does |
| --- | --- |
| `mvn javafx:run` | Run the app from the classpath. |
| `mvn test` | Run the test suite (pure + the headless-FX harness — see [testing.md](testing.md)). |
| `mvn spotless:apply` | Auto-format (run before committing). |
| `mvn verify` | Full gate: tests + `spotless:check` + the JaCoCo coverage floors. |

The `javafx:run`/`compile` dev loop deliberately skips the Spotless check (it runs only at
`verify`/`package`), so iterating is fast.

## Native installer (`-Pdist`)

```
mvn clean -Pdist package
```

Produces `target/dist/Editora.app` on macOS; the OS profiles auto-select DMG/MSI/DEB+RPM. There is
**no cross-building** — jpackage + JavaFX are host-specific, so each platform builds for itself.

**`clean` is mandatory, and the profile enforces it.** An incremental compile can leave a synthetic
enum-switch class (`KeyDispatcher$1`) out of `target/classes`; jlink then ships an app whose keyboard
dies on the first keypress (commit b9748039 did). The `dist` profile binds `maven-clean-plugin:clean`
to the `initialize` phase, so `mvn -Pdist package` now starts from an empty `target/` even when the
`clean` is forgotten; typing it remains the documented form.

Quick unpackaged bundle (skips the installer):

```
mvn clean -Pdist -DskipTests -Djpackage.type=APP_IMAGE package    # → target/dist/Editora.app
```

### What the dist profile does

- it consumes the **moditect**-patched jars. The `moditect-maven-plugin` execution
  (`patch-automatic-modules`) lives in the **main build**, not in this profile: every `package` writes
  `module-info` descriptors for the automatic-module dependencies into `target/modules`, and the
  profile's antrun step overlays them onto the module path so `jlink` can link them (see
  [dependencies.md](dependencies.md)). A broken descriptor therefore fails a plain `mvn verify` too.
- an antrun step strips `META-INF/*.SF,*.RSA,*.DSA,*.EC` from the **code-signed** tm4e jar —
  `jlink` rejects signed modular jars.
- `jlink` builds a stripped runtime (`--strip-debug --no-man-pages --no-header-files
  --compress=zip-6`). `--strip-native-commands` is deliberately **omitted** so `bin/java`
  survives for the AOT training step; the helper deletes `bin/` afterward.
- jpackage `<javaOptions>` (mirrored into `javafx:run` so dev == prod) set the heap/GC caps,
  the texture-pool safety net, and the per-OS Prism pipeline.

### AOT cache (JDK 25 Leyden)

The training launch uses a seeded config directory (a `settings.json` and a session naming three generated
files) and passes one of them as the FILE argument, so settings deserialization, file load, a grammar and
first paint are all in the cache; it no longer launches `--new-file` on an empty config. The effect on the
packaged build has not been measured yet.

The build is **two-phase**: phase 1 jlinks an `APP_IMAGE` with `-XX:AOTCache=$APPDIR/editora.aot`
baked into the launcher `.cfg`; then [`scripts/aot_build.java`](../scripts/aot_build.java) trains
a full-GUI cache against the image's own runtime (a real window renders, settles ~2.5 s, then
`System.exit` via `-Deditora.aotTrainExit`), writes `editora.aot`, strips `bin/`, and either
copies the image or wraps it into the installer.

It is **failure-tolerant by default** — on a display-less machine training is skipped and the build
ships without the cache (a missing `-XX:AOTCache` just starts normally under `AOTMode=auto`) — but
never *silently*: `aot_build.java` reports the trainer's exit code on stderr, raises a GitHub
`::error` annotation naming the target, and writes a row into the job summary. **`EDITORA_REQUIRE_AOT=1`
turns that report into a build failure**, and `release.yml` sets it, so a release tag never ships an
uncached target. On Linux CI the helper auto-wraps training in `xvfb-run`. The win is ~28% /
≈300–480 ms faster cold start (it's JavaFX scene/control/CSS class loading, which is why a *headless*
trainer gives ≈0). Cost: the cache is ~72 MB.

**Adapter caching is deliberately off** (`-XX:+UnlockDiagnosticVMOptions -XX:-AOTAdapterCaching`, on
the packaged launcher *and* the trainer — `AotTrainerOptionsTest` fails the build if either side drops
them). The cache archives generated machine code, and the call adapters in it are compiled for the CPU
that trained it: v0.13.0 shipped adapters carrying AVX-512 (EVEX) register spills from a Xeon runner,
which any CPU without AVX-512 refuses with `SIGILL` — 4 launches in 6 on a Raptor Lake desktop, on a
different thread each time. The JVM maps such an archive without complaint, so **CI cannot catch this**;
the instructions are legal on the machine that generated them. Turning the adapters off costs nothing
measurable (first paint 1024 ms without them, against 1704 ms with the whole cache disabled).

### Per-OS Prism pipeline

Set via the `${prism.pipeline}` property in the `os-mac`/`os-windows`/`os-linux` profiles and
passed as `-Dprism.order`:

- **macOS** = `mtl,es2,sw` — the **Metal** pipeline, JavaFX 27's default, fixes
  render-to-texture glitches on Apple silicon; es2/sw are fallbacks.
- **Windows** = `d3d,es2,sw` — must keep Direct3D.
- **Linux** = `es2,sw`.

JavaFX 27 requires JDK 25+, matching Editora's JDK baseline.

## Runnable fat jar (`-Pfatjar`)

```
mvn -Pfatjar package      # → target/Editora-<version>.jar, run with java -jar
```

Bundles JavaFX (classes + natives) for **the build host's platform only** and runs from the
classpath via the non-`Application` `com.editora.Launcher` main class. The profile deletes
`target/classes` first (the same stale-class hazard as `-Pdist`) but deliberately leaves the rest of
`target/` alone: the release workflow runs it right after `-Pdist` and still needs `target/dist`. A single all-platforms jar
is impossible (JavaFX's macOS/Linux x64 and arm64 natives share filenames and collide), so the
release CI builds one fat jar per runner.

## Licences in the artifacts

Every build carries the licence material; nothing has to be added by hand.

- **Inside the application jar** (so in the jlink image behind every installer, and in the fat jar):
  `META-INF/editora/LICENSE` and `META-INF/editora/NOTICE` — copied from the repository root by a
  `<resource>` entry in `pom.xml`, not duplicated in `src/` — plus
  `META-INF/editora/licenses/Apache-2.0.txt` and each font family's
  `com/editora/fonts/<family>/OFL.txt`.
- **Fat jar:** the shade profile drops the per-dependency `META-INF/LICENSE` duplicates, merges every
  dependency `NOTICE` into one `META-INF/NOTICE` (Apache-2.0 §4(d)) and puts Editora's MIT licence at
  `META-INF/LICENSE`. The texts of the other third-party licences (BSD, EPL, GPL+CE) are not bundled
  as files; `NOTICE` names each library, its licence and where to find it.
- **Plain files:** the tarball (`LICENSE`, `NOTICE` beside `install.sh`, copied into the install
  directory), the AppImage (`usr/share/licenses/editora/`) and the experimental Native Image
  archives.
- **Installers:** `aot_build.java` passes `--license-file LICENSE` for `.deb` (it becomes
  `/opt/editora/share/doc/copyright`), `.rpm` (with `--linux-rpm-license-type MIT`) and `.msi` (a
  licence page in the wizard). Not for `.dmg`: jpackage would turn it into a click-through agreement
  on the disk image, which cannot be tested off a Mac.

`NOTICE` must name every runtime dependency declared in `pom.xml` by `groupId:artifactId`
(`NoticeCoverageTest`). Transitive dependencies are listed by hand — run
`mvn dependency:list -DincludeScope=runtime` when you bump one that brings new ones.

## App icon / branding

The source logo is `branding/editora-icon.svg`. Window-icon PNGs live in
`resources/com/editora/icons/`; native-installer icons `branding/editora.{icns,ico,png}` are
generated from the SVG and passed to jpackage via `${jpackage.icon}` (set per-OS in the
profiles). Regenerate after editing the SVG.

See also: [dependencies.md](dependencies.md) for the vendored/forked deps, and
[release.md](release.md) for cutting a release.

## Experimental closed-world build

`-Pnative` is an isolated [StaticFX/GraalVM experiment](native-image-staticfx.md), with its own
metadata and acceptance evidence. Its separate best-effort release jobs do not alter jlink,
jpackage, the AOT trainer or the required JVM release matrix.
Do not combine it with `dist` or `fatjar`; use clean builds when changing profiles.
