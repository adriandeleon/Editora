# Gotchas and known traps

The non-obvious failures that have bitten Editora before. Each is a symptom + the fix and why.
Back to [the docs index](README.md). Terms in **bold** are defined in the [glossary](glossary.md).
When this disagrees with the code, the code wins.

---

## The headless-AWT guard must be `App.main`'s first statement

**Symptom:** an intermittent deadlock/hang on macOS, more likely the more Markdown previews (SVG
badges, math) are open — the app freezes, mouse still works, restart required.

**Why/fix:** SVG and math rasterization touches `java.awt`/Java2D. On macOS the AWT/Java2D native
pipeline contends with JavaFX's Glass/Prism for the single AppKit run loop. So `App.main`'s very
first line is `System.setProperty("java.awt.headless", "true")` — headless Java2D rasterizes to a
`BufferedImage` in software with no AppKit, and the conflict disappears. It must run before any AWT
class loads, so keep it first in `main`. See
[`App.java`](../src/main/java/com/editora/App.java) and
[architecture.md](architecture.md#the-headless-awt-guard-dont-move-it).

## macOS FXMLLoader null context classloader → NPE

**Symptom:** an NPE inside `FXMLLoader.load()` when a window is built at runtime on macOS (the
FX/AppKit thread's context classloader is null).

**Why/fix:** `App` pins a classloader before building any window —
`FXMLLoader.setDefaultClassLoader(...)` + `Thread.currentThread().setContextClassLoader(...)` (see
[`App.java`](../src/main/java/com/editora/App.java) lines ~44). Without it, lazy class loading on
that thread also breaks. Don't remove the pin.

## JPMS `opens` pitfalls

JPMS encapsulation silently breaks reflection-based resource and field access at runtime on the
module path, even when classpath tests pass. Three to remember (all in
[`module-info.java`](../src/main/java/module-info.java)):

- **Grammar package.** `com.editora.grammars` must `opens ... to org.eclipse.tm4e.core`. Without it,
  tm4e's `Class.getResourceAsStream` returns null at runtime (module path) and grammars silently
  fail to load — but classpath tests pass, so it's invisible in CI. See
  [extending.md](extending.md#add-a-language--textmate-grammar).
- **Jackson-serialized config/DTO types.** Any TOML/JSON-serialized POJO needs
  `opens com.editora.<pkg> to com.fasterxml.jackson.databind;` (e.g. `opens com.editora.config to …`).
  Add the `opens` when you add a serialized type. See
  [conventions.md](conventions.md#config-and-schema).
- **The LSP DTO needs an *unqualified* `opens`.** `module-info` has `opens com.editora.lsp;` (not
  qualified to lsp4j.jsonrpc). Gson reflectively reads the `@JsonNotification` param DTO, and under
  `mvn javafx:run` it runs in the *unnamed* module — a qualified opens leaves it unable to set the
  DTO fields accessible.

## Prism texture pool exhaustion → black window (packaged build only)

**Symptom:** a black/garbled window, seen only in the packaged build (not `javafx:run`), as more
files open.

**Why/fix:** JavaFX's Prism texture pool has a fixed ceiling; exhausting it makes the render thread
NPE on a null texture. Don't let GPU-backed resources grow with the number of open files: a
background tab drops its minimap snapshot via `EditorBuffer.setRenderingActive(false)`; image caches
(`PreviewImageLoader`, `MermaidImages`) are LRU-bounded; a **Canvas overlay** releases its canvas to
1×1 when inactive. When you add any per-buffer `Canvas`/`Image`, make sure it's released or bounded.
See [performance.md](performance.md#6-bound-retained-gpu-textures).

## Signed modular jar → `jlink` rejects it

**Symptom:** the `dist` jlink step fails on the tm4e NetBeans jar (it is code-signed, and `jlink`
rejects signed modular jars).

**Why/fix:** the `dist` profile's antrun step strips `META-INF/*.SF,*.RSA,*.DSA,*.EC` from the jar
before linking (see the unsigned-jar repackaging in [`pom.xml`](../pom.xml)). If you add another
signed modular dependency, it needs the same strip. See
[dependencies.md](dependencies.md#tm4e--netbeans-repackaging-signed-jar).

## The headless FX test backend is JavaFX 26's built-in Headless platform (no Monocle)

**Symptom (historical):** the self-built **Monocle** Glass backend had to be rebuilt on every JavaFX
bump or the `@Tag("fx")` tests failed to link (a stale-version jar can't link against internal
`com.sun.glass.ui` APIs).

**Current state:** as of JavaFX 26 the harness uses the **built-in Headless Glass platform** that
ships inside `javafx.graphics` (`-Dglass.platform=Headless` in the surefire config — see
[`pom.xml`](../pom.xml)). No Monocle jar, no native libs, nothing to rebuild on a JavaFX bump. If you
ever need real headless *rendering* or robot input (the built-in platform is a prototype), Monocle
could be re-vendored, but it isn't needed now. See [testing.md](testing.md) and
[dependencies.md](dependencies.md#the-headless-test-backend-no-vendored-dependency).

## The surefire `@{argLine}` token must be preserved

**Symptom:** JaCoCo reports zero coverage after someone edits the surefire `<argLine>`.

**Why/fix:** JaCoCo injects its coverage agent by *setting the `argLine` property*. Surefire's
`<argLine>@{argLine}</argLine>` expands that property; a plain `<argLine>` with literal flags clobbers
it and the agent never loads. Keep the `@{argLine}` token (first). See
[testing.md](testing.md#the-surefire-config-that-makes-it-work) and [`pom.xml`](../pom.xml).

## `tab.getUserData()` is a `TabContent`, not always an `EditorBuffer`

**Symptom:** a `ClassCastException` on the Welcome tab when code casts `tab.getUserData()` to
`EditorBuffer`.

**Why/fix:** a tab's `userData` is a **`TabContent`** ([`TabContent.java`](../src/main/java/com/editora/editor/TabContent.java));
`EditorBuffer` and `WelcomePane` both implement it. Always read it through
`MainController.bufferOf(Tab)` (returns the buffer or `null`), and make tab-switch consumers
null-safe. See [architecture.md](architecture.md#tabs-are-tabcontent-not-always-buffers).

## The `NO_ROOT` sentinel `Path` must not contain a NUL

**Symptom:** a corrupt/throwing static initializer if the git repo-root cache sentinel were built
from a string with a NUL byte.

**Why/fix:** `GitService` caches "directory has no repo root" with a static sentinel
`Path.of("")` ([`GitService.java`](../src/main/java/com/editora/git/GitService.java) ~line 63), an
empty path that `rev-parse` never returns, compared by *identity*. Keep it the empty path — a `Path`
with a NUL throws on construction, and any non-empty value risks colliding with a real result.

## LSP/DAP server stderr must be drained; dispose must `killTree`

**Symptom (stderr):** a chatty server (jdtls logs heavily) deadlocks mid-startup — no diagnostics,
the loading bar spins forever.

**Why/fix (stderr):** an undrained child-process stderr PIPE fills its ~64 KB OS buffer and the
server blocks writing (LSP traffic is on stdout).
[`LanguageServerSession`](../src/main/java/com/editora/lsp/LanguageServerSession.java) drains it on a
daemon thread (capped, into the Debug Log so a failed launch is diagnosable); the DAP adapters use
`Redirect.DISCARD`. Either way, the stream must be consumed, never left a live unread PIPE.

**Symptom (dispose):** after closing a window, the next LSP session for the same root hangs — the old
server JVM is still running and holds its workspace `.lock`.

**Why/fix (dispose):** `jdtls` (and others) is a wrapper script (Homebrew `jdtls` → python → java);
destroying only the wrapper orphans the real server. `dispose()` calls
`ProcessRegistry.killTree(process)` ([`ProcessRegistry.java`](../src/main/java/com/editora/process/ProcessRegistry.java))
to kill the whole descendant tree (children first, escalating to a force-kill) and untrack it.

## Don't call `getCharacterBoundsOnScreen` synchronously inside a layout/viewport event

**Symptom:** layout thrash / re-entrancy when positioning an overlay or popup from a viewport
listener.

**Why/fix:** querying `getCharacterBoundsOnScreen` synchronously inside a layout/viewport event
forces work the [hot path](glossary.md#hot-path) can't afford. Defer it (e.g. read it on the next
pulse) rather than mid-event. See [performance.md](performance.md#3-work-incrementally-and-only-on-whats-visible).

## GUI-launched `.app` inherits a stripped PATH

**Symptom:** `mmdc`/`npx`/`git`/an LSP server "not found" only when Editora is launched from Finder
(a `.app`) or a `.desktop`, but found when launched from a terminal.

**Why/fix:** a Finder-launched app inherits a stripped `PATH` (`/usr/bin:/bin:…`) without
Homebrew/npm/Node/version-manager dirs. `ProcessRunner` builds an **augmented PATH** — the inherited
PATH plus the user's *login-shell* PATH (`$SHELL -l -i -c …`, which recovers version-specific bins
like nvm's `~/.nvm/versions/node/<ver>/bin`) plus the hardcoded `EXTRA_PATH_DIRS` — and rewrites a
bare command to its absolute path against it (Java's `ProcessBuilder` resolves the executable against
the JVM's own PATH, not the child env). Always spawn subprocesses through `ProcessRunner`
([`ProcessRunner.java`](../src/main/java/com/editora/process/ProcessRunner.java)); don't call
`ProcessBuilder` directly.
