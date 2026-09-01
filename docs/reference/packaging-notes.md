# Packaging notes

Low-level dependency, shaded-jar, jlink, and platform packaging notes. Start with
[`building-and-packaging.md`](../building-and-packaging.md) before changing this machinery.

RichTextFX is a **personal fork** (`io.github.adriandeleon:richtextfx`, vendored in the in-project
`m2-repo/`, source at github.com/adriandeleon/RichTextFX) that adds VS Code–style **multiple cursors +
Alt+drag column/box selection** via a self-contained `org.fxmisc.richtext.multi.MultiCaretController.install(area)`
add-on (a layered wellbehaved `InputMap`, gated on `MultiCaretManager.hasExtras()` so it's transparent with
one caret). **`EditorBuffer`** installs it on `area`/`area2` via `setMultiCaretEnabled(...)` (gated by
`Settings.multiCaret`, default on; pushed by `MainController.applyMultiCaret`), exposes
`hasMultipleCarets()` + delegating `addCaretNextOccurrence`/`addCaretAbove`/`addCaretBelow`/`collapseCarets`
(palette commands `edit.addCaret*`/`edit.collapseCarets`, `view.toggleMultiCaret` — **no Emacs keymap bindings** (gestures are mouse + Esc,
native to the fork; the `vscode` keymap does bind `C-d`/`C-M-up`/`C-M-down`)). **Select all occurrences** (`edit.selectAllOccurrences`, VS Code `selectHighlights` Ctrl+Shift+L in the vscode/sublime keymaps) places a caret at every literal, case-sensitive occurrence of the selection — or, with none, the word under the caret (pure/unit-tested `editops/SelectOccurrences.wordAt`) — via `EditorBuffer.placeOccurrenceCarets(ranges, anchorStart)`: `collapseToPrimary` then one `MultiCaretManager.addCaretWithSelection(start,end)` per match, the match containing the anchor kept primary (pure `SelectOccurrences.primaryIndex`), capped at `MAX_OCCURRENCE_CARETS`=10k. **`find.selectAllMatches`** (Alt+Enter in the find field, handled in the field’s key handler + palette) reuses the same placement over the find bar’s live `currentMatches()` (query + case/regex/whole-word toggles), then closes the bar. Both gated by `withMultiCaret` (off in Simple mode); the resulting carets inherit the movement-chord fan-out limitation above. Because Editora's area-level KEY filters (auto-indent/close,
snippets, completion, view paging) are capture-phase *filters* that run before the fork's node InputMap,
each one early-returns (no consume) via `multiCaretActiveOn(a)` when that area has extra carets, so editing
fans out to all carets. **Movement chords fan out too (#635):** the Emacs movement commands are resolved by
the scene-level `KeyDispatcher` on the primary caret, so the fork's node-level movement InputMap never sees
them — instead the `nav.*` command handlers branch through `MainController.multiCaretMove(op)` when the
active buffer `hasMultipleCarets()`, calling `EditorBuffer.multiMoveHorizontal`/`multiMoveVertical`/
`multiMoveLineBoundary` (thin wrappers over the fork's `MultiCaretManager.moveHorizontal(amount, byWord,
select)`/`moveVertical(down, select)`/`moveLineBoundary(toEnd, select)`, each returning false when there are
no extras so the caller falls through to its single-caret motion; `select` = `markActive`, mirroring
`selPolicy()`). This covers `nav.charForward`/`charBackward` (`±1` char), `wordForward`/`wordBackward` (`±1`
word), `lineUp`/`lineDown` (via `moveLine`), and `lineStart`/`lineEnd`. *Still primary-only* (the fork has no
multi-caret API for them): `docStart`/`docEnd`, paragraph/sentence motion, page up/down, `backToIndentation`,
and subword nav; and the fork's `moveVertical` is best-effort about scrolling out-of-view carets into view.
The fork also adds **`Inlay`** — inline decorations occupying layout width without being part of the
document, backing the LSP inlay hints (#824). A `TextFlow` counts a non-`Text` managed child as **one
character**, so a decoration cannot both displace the glyphs after it and leave indices alone; the fork
therefore carries a document↔flow **`InlayIndex`** translation applied at `TextFlowExt`'s index-taking
methods, exposed as `GenericStyledArea.inlayFactoryProperty()` (an `IntFunction`, mirroring
`paragraphGraphicFactory`, which is what makes cell recycling work).
**Build the fork with JDK 21, not 25** — its Gradle 8.5 cannot compile the build script on 25
(`Unsupported class file major version 69`). This bites only once `build.gradle` itself changes, because a
warm script cache hides it until then:
`JAVA_HOME=~/.sdkman/candidates/java/21.0.2-open ./gradlew :richtextfx:jar`.
To update the fork: rebuild it, copy the new `richtextfx-<version>.{jar,pom}` into `m2-repo/`, bump
`<richtextfx.version>`. RichTextFX and its transitive deps (`reactfx`, `flowless`, `undofx`, `wellbehavedfx`)
are **automatic modules**, which `jlink` cannot link. The `moditect-maven-plugin` in
the `dist` profile injects explicit `module-info` descriptors into them. If you bump
RichTextFX or add a dep it uses, you may need to adjust those descriptors' `requires`
(e.g. several of them need `javafx.controls` for `IndexRange`). Use RichTextFX
**0.11.7+** — earlier versions are incompatible with JavaFX 25.

tm4e (the syntax engine) ships as the NetBeans repackaging
`org.netbeans.external:org.eclipse.tm4e.core-0.14.0:RELEASE260` (tm4e is not on
Maven Central). Its Oniguruma backend (`org.jruby.joni:joni`, `org.jruby.jcodings:jcodings`)
and `com.google.code.gson:gson` are already proper modules, but tm4e core is an
automatic module, so `moditect` injects a `module-info` for it too. The NetBeans
jar is also **code-signed**, and `jlink` rejects signed modular jars — the `dist`
profile's antrun step strips `META-INF/*.SF,*.RSA,*.DSA,*.EC` before linking.

**Headless FX testing — built-in platform (no Monocle).** As of **JavaFX 26** the headless Glass backend for the
FX test harness is the **built-in Headless platform** that ships inside `javafx.graphics` (`-Dglass.platform=Headless`,
set in the surefire `<systemPropertyVariables>`). **No jar, no native libs, no separate download, nothing vendored** —
it's part of the JavaFX runtime, so it can never go stale on a JavaFX bump. This **replaced** the previously
self-built **Monocle** backend (`io.github.adriandeleon:openjfx-monocle`, which had to be rebuilt from the OpenJFX
sources on every JavaFX bump and vendored in `m2-repo/`) — that whole rebuild ritual and its vendored artifacts are
gone. The new platform is officially a **prototype** in 26, but the harness only uses TestFX's `FxToolkit` to **boot
the toolkit** and never drives the robot (input/clicks) or renders/snapshots in tests, so the prototype's
rendering/input limitations don't apply (and TestFX needs no changes despite not yet adopting it — see
[TestFX #819](https://github.com/TestFX/TestFX/issues/819)). No `module-info`/jlink impact — it's test scope only and
never on the runtime path. *(If a future need ever requires real headless rendering or robot input under the
prototype, Monocle could be re-vendored, but as of 26 it isn't needed.)*
