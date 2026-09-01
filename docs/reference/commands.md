# Detailed command reference

Expanded command and release notes preserved from the former root agent guide. For the normal
workflow, start with [`AGENTS.md`](../../AGENTS.md) and
[`building-and-packaging.md`](../building-and-packaging.md).

- Run the app: `mvn javafx:run`
- Run tests: `mvn test`
- Build app image / native installer: **`mvn clean -Pdist package`** (`clean` is REQUIRED, not optional).
  - Produces `target/dist/Editora.app` (macOS); OS profiles auto-select DMG/MSI/DEB.
  - Quick unpackaged bundle: `mvn clean -Pdist -DskipTests -Djpackage.type=APP_IMAGE package` ⇒ `target/dist/Editora.app`.
  - **Always package from a clean `target/`.** The dist JAR (jlink's input for the `com.editora` module)
    is built from `target/classes`; an **incremental** compile does **not** regenerate a synthetic
    `$SwitchMap` inner class (e.g. `KeyDispatcher$1`, `QuickOpen$1`, `MarkdownViewToggle$1`, emitted for a
    `switch` over an *external* enum) once it's missing — javac sees the `.java` as up-to-date and skips
    it. Anything that leaves `target/classes` inconsistent — a background **jdtls/Eclipse** compiler
    writing there, an interrupted build, a partial clean — then makes `-Pdist package` ship a jimage
    **missing that class**, and the packaged app throws `ClassNotFoundException` on the **first keypress**
    (dead keyboard). A `clean` regenerates every class and avoids it. `release.yml`'s `-Pdist` step runs
    `clean` for this reason. (`mvn javafx:run` and the fat jar are immune — they don't jlink a stale JAR.)
  - **Both** also run the AOT-cache training step (a GUI window flashes for ~2.5 s, then the build
    injects `editora.aot`) — see the AOT-cache note under *Conventions → performance*. It's
    failure-tolerant, so on a display-less machine it just skips the cache.
- Build a runnable fat jar: `mvn -Pfatjar package` ⇒ `target/Editora-<version>.jar`, run with
  `java -jar`. It bundles JavaFX (classes + natives) for **the build host's platform only** and runs
  from the classpath via the non-`Application` `com.editora.Launcher` main class (which is now the main
  class for the **modular** paths too — see the startup-timing note under *Conventions → performance*
  for why). A single
  all-platforms jar is impossible (JavaFX's macOS/Linux x64 and arm64 natives share filenames and
  collide), so the release CI builds one fat jar per runner.
- Cut a release: set `<version>` in `pom.xml` to the release version, i.e. **drop the `-SNAPSHOT`** (the pom is the **single** source — `AppInfo.VERSION` derives from it via Maven-filtered `build-info.properties`, so no other file needs the number; update `CHANGELOG.md` too), push a `vX.Y.Z` tag (`-rcN` ⇒ pre-release). **Between releases `master` sits on `X.Y.Z-SNAPSHOT`** — `release.yml`'s final `bump` job reopens it at the next patch `-SNAPSHOT` automatically after a non-rc release (idempotent: it bumps only when the pom still reads the version just released), so the step above is the only manual version edit. The suffix makes a build **self-identifying**: `AppInfo.isSnapshot()` shows a **`snapshot` toolbar badge** beside the `--dev` one, and the suffix appears in `--version`/About/Welcome — so a test build off `master` is never mistaken for a release. Anywhere a version must be a plain dotted number uses **`AppInfo.releaseVersion()`** (the versioned docs URL in `CommandPalette`, which would otherwise 404) or the pom's **`jpackage.publicVersion`** — each OS profile's antrun strips `-SNAPSHOT` into it and derives `jpackage.appVersion` from that (mac additionally bumps a leading `0.`→`1.`), because **jpackage rejects a non-numeric app-version**; `aot_build.java` writes `publicVersion` into the macOS `Info.plist`. See [docs/release.md](../release.md).

Run Maven from the project root (`/Users/adriandeleon/src/adl/Editora-V2`).
