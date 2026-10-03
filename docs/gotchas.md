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

## Enabling `setWrapText` does not wrap until stale cell widths are re-measured

**Symptom:** word wrap is switched on, `area.isWrapText()` is true, but every line stays on one row and the
horizontal scrollbar remains. Scrolling back over earlier long lines (or restarting with wrap on) fixes it;
resizing the window does not.

**Why/fix:** Flowless's `SizeTracker` memoizes the minimum breadth of every cell it has laid out and lays
all visible cells out at `max(viewport, widest memoized breadth)`. When a cell's width changes it only
forgets the entries of cells that are currently realized *and* need layout, so a long line measured
unwrapped and since scrolled away keeps its old width forever. Neither Flowless nor RichTextFX exposes a
way to clear that cache. `EditorBuffer.setWordWrap` therefore realizes every non-empty paragraph once after
enabling wrap (`getParagraphLinesCount(i)`), in time-budgeted slices on an `AnimationTimer` so a large
document never blocks a frame; each pulse's layout re-measures the slice and drops the cells again. The
cost is linear in the paragraph count, so on a very large file wrapping appears a moment after the toggle.
`WordWrapToggleFxTest` pins the behaviour.

## An edit made inside a `plainTextChanges` subscriber runs before the outer edit places the caret

**Symptom:** an assist that edits the document in response to a keystroke leaves the caret a few characters
short, so the *next* keystroke lands in the wrong place. Auto-rename-tag turned `</div` + `xy` into
`</divyx>` (and renamed the opener to match).

**Why/fix:** RichTextFX's `replace(start, end, text)` changes the document, notifies subscribers, and only
then moves the caret to `start + text.length()` — an offset computed before the subscribers ran. A
subscriber that edits *above* the caret shifts the text under that offset. (Editing *below* the caret
from a subscriber happens to be safe, which is why this hides.) Apply such an edit after the outer
`replace` has returned: both editor areas are a `TagRenameMirror.Area`, whose `replace` override reports
each single-range replace once text and caret have settled. A `Platform.runLater` fix-up is not a
substitute — nothing guarantees it runs before the next queued key event. `AutoRenameTagFxTest` pins it.

## A RichTextFX area can pin its whole window after close

**Symptom:** memory grows by about 20 MB for every closed project window (and the test JVM runs out of heap
on a 4 GB CI runner partway through the FX suite). A closed window's `MainController` stays reachable.

**Cause, two routes, both through the caret:**

- `setShowCaret(CaretVisibility.OFF)` (or `ON`) makes `CaretNode` flat-map onto a **static** stream
  (`CaretNode.ALWAYS_FALSE` / `ALWAYS_TRUE`). The static stream's observer list then holds the caret, its
  area, the area's panel and, through the panel's callbacks, the window. The default `AUTO` uses a per-area
  stream instead and already hides the caret of a read-only area.
- An area that has focus runs a caret **blink timer** — a JavaFX animation, which is a GC root while it
  runs. Closing a window does not stop it; `GenericStyledArea.dispose()` does.

**Fix / rule:** never call `setShowCaret` (`CaretVisibilityPolicyTest` enforces it), and dispose the area
when its owner goes: `EditorBuffer.dispose()` calls `area.dispose()` for both views. A new long-lived
editable area outside `EditorBuffer` needs the same call from its owner's close path.
`WindowReleasedOnCloseFxTest` holds a weak reference to a closed window's controller and fails if either
route comes back.

## Never ask `getCharacterBoundsOnScreen` for an *empty* range

**Symptom:** typing (or some repeated action) gets slower the longer the editor is open, and never
recovers — the slowdown outlives the window that caused it. Nothing looks wrong in a profile of any
single operation.

**Why/fix:** for an **empty** range (`getCharacterBoundsOnScreen(x, x)`) `GenericStyledArea` allocates a
throwaway `CaretNode` to measure with, and a `CaretNode` starts a 500 ms `restartableTicks` blink timer
that **nothing ever stops**. Each call therefore registers a running `Timeline` as a JavaFX pulse receiver
**permanently**. Measuring a *one-character* range takes a different path and allocates nothing.

Measure a **one-character** range instead: `(abs, abs + 1)`, and at end-of-paragraph measure the last
character and take its right edge — the same x the caret sits at. See `EditorBuffer.caretBounds` (root-local,
for overlays) and `EditorBuffer.caretAnchorBounds` (screen, for caret-following popups).

**Anchoring a popup needs a fallback chain, and this is the part that bites.** The leaking form measured
through a caret it had just created, so it answered even when nothing was rendered; a *character* can only be
measured once its paragraph is laid out, so it comes back empty whenever the flow has no cell for that line —
the caret scrolled out of view, or the area not laid out yet. Return `null` there and the popup **silently
does not open**. `caretAnchorBounds` therefore falls back: the character, then `getCaretBounds()`, then the
area's own top-left. A popup at the corner of the editor beats no popup.

`CodeArea.getCaretBounds()` is second, not first, and is **not a substitute** for measuring: it reports the
*rendered* caret and is empty whenever the area isn't focused.

Both failure modes are easy to miss because they are **order-dependent** — `CodeActionPopupFxTest` passes on
its own (the area happens to be laid out) and fails only in the full suite. Verify a change here against
`./mvnw test`, not the one test class.

The 80-column ruler did this on every edit and leaked **+2 timers per keystroke**, degrading typing from
5.6 ms to 28 ms over 2000 keystrokes. The completion popup, its LSP variant and the quick-fix list each
leaked **exactly one per open**. Guarded by `TypingLatencyBenchmarkTest`, which counts pulse receivers for
both typing and popup opens.

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
