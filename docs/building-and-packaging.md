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
mvn -Pdist package
```

Produces `target/dist/Editora.app` on macOS; the OS profiles auto-select DMG/MSI/DEB. There is
**no cross-building** — jpackage + JavaFX are host-specific, so each platform builds for itself.

Quick unpackaged bundle (skips the installer):

```
mvn -Pdist -DskipTests -Djpackage.type=APP_IMAGE package    # → target/dist/Editora.app
```

### What the dist profile does

- **moditect** injects `module-info` descriptors into the automatic-module dependencies so
  `jlink` can link them (see [dependencies.md](dependencies.md)).
- an antrun step strips `META-INF/*.SF,*.RSA,*.DSA,*.EC` from the **code-signed** tm4e jar —
  `jlink` rejects signed modular jars.
- `jlink` builds a stripped runtime (`--strip-debug --no-man-pages --no-header-files
  --compress=zip-6`). `--strip-native-commands` is deliberately **omitted** so `bin/java`
  survives for the AOT training step; the helper deletes `bin/` afterward.
- jpackage `<javaOptions>` (mirrored into `javafx:run` so dev == prod) set the heap/GC caps,
  the texture-pool safety net, and the per-OS Prism pipeline.

### AOT cache (JDK 25 Leyden)

The build is **two-phase**: phase 1 jlinks an `APP_IMAGE` with `-XX:AOTCache=$APPDIR/editora.aot`
baked into the launcher `.cfg`; then [`scripts/aot_build.java`](../scripts/aot_build.java) trains
a full-GUI cache against the image's own runtime (a real window renders, settles ~2.5 s, then
`System.exit` via `-Deditora.aotTrainExit`), writes `editora.aot`, strips `bin/`, and either
copies the image or wraps it into the installer.

It is **failure-tolerant** — on a display-less machine training is skipped and the build ships
without the cache (a missing `-XX:AOTCache` just starts normally under `AOTMode=auto`). On Linux
CI the helper auto-wraps training in `xvfb-run`. The win is ~28% / ≈300–480 ms faster cold start
(it's JavaFX scene/control/CSS class loading, which is why a *headless* trainer gives ≈0). Cost:
the cache is ~60 MB.

### Per-OS Prism pipeline

Set via the `${prism.pipeline}` property in the `os-mac`/`os-windows`/`os-linux` profiles and
passed as `-Dprism.order`:

- **macOS** = `mtl,es2,sw` — the **Metal** pipeline (JavaFX 26) fixes render-to-texture glitches
  on Apple silicon; es2/sw are fallbacks.
- **Windows** = `d3d,es2,sw` — must keep Direct3D.
- **Linux** = `es2,sw`.

JavaFX 26 runs on JDK 24+, so the JDK stays 25.

## Runnable fat jar (`-Pfatjar`)

```
mvn -Pfatjar package      # → target/Editora-<version>.jar, run with java -jar
```

Bundles JavaFX (classes + natives) for **the build host's platform only** and runs from the
classpath via the non-`Application` `com.editora.Launcher` main class. A single all-platforms jar
is impossible (JavaFX's macOS/Linux x64 and arm64 natives share filenames and collide), so the
release CI builds one fat jar per runner.

## App icon / branding

The source logo is `branding/editora-icon.svg`. Window-icon PNGs live in
`resources/com/editora/icons/`; native-installer icons `branding/editora.{icns,ico,png}` are
generated from the SVG and passed to jpackage via `${jpackage.icon}` (set per-OS in the
profiles). Regenerate after editing the SVG.

See also: [dependencies.md](dependencies.md) for the vendored/forked deps, and
[release.md](release.md) for cutting a release.
