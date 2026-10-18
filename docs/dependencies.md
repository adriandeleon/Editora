# Dependencies (the tricky ones)

Most dependencies are ordinary. A few are vendored, forked, or repackaged in ways that will
confuse you the first time — this is the "why is this dep weird" reference. Versions live in
`pom.xml` properties.

## RichTextFX — a personal fork (`m2-repo/`)

The editor uses a fork of RichTextFX, `io.github.adriandeleon:richtextfx`, **vendored in the
in-project `m2-repo/`** (source: github.com/adriandeleon/RichTextFX). It adds VS Code–style
**multiple cursors + Alt+drag column/box selection** via a self-contained
`org.fxmisc.richtext.multi.MultiCaretController.install(area)` add-on (a layered
`InputMap`, gated on `MultiCaretManager.hasExtras()` so it's transparent with one caret).

- `EditorBuffer` installs it on `area`/`area2` via `setMultiCaretEnabled(...)`
  (`Settings.multiCaret`, default on), wrapped in `editor/MultiCarets` — the Editora-side guards for
  four things the fork's `MultiCaretManager` gets wrong on its own (each is also a proposed fork fix):
  - **Stale anchors.** The manager keeps each extra caret's selection anchor as a plain `int` that only
    its own edits rewrite. After any other edit (Undo, a primary-caret command, an LSP edit, a reload)
    the next key typed over a phantom selection. `MultiCarets` notices an edit that did not come from the
    manager and rebuilds the extra carets from the caret/selection positions RichTextFX did keep right,
    before the manager is used again. **Go through `MultiCarets` for edits and moves, not the manager.**
  - **UTF-16 steps.** The manager moves and deletes one `char` at a time; `MultiCarets` takes
    Backspace/Delete/Left/Right and steps by code point.
  - **Add caret above/below** measured from the primary caret, so it never reached a third line;
    `MultiCarets.addCaretOnNextLine` starts from the outermost caret.
  - **Tab** typed a literal `\t` at every caret and Shift-Tab did nothing. `MultiCarets.tab` takes the key
    and applies the buffer's own single-caret Tab edit at each caret (planned by the pure
    `editops/MultiTab`, committed as one multi-change): block indent of selected lines, indent/dedent of
    the caret's line, or one indent unit.
- Editora's area-level KEY filters (auto-indent/close, snippets, completion, view paging) are
  capture-phase filters that early-return (no consume) via `multiCaretActiveOn(a)` when an area
  has extra carets, so editing fans out to all carets.
- **Known limitation:** Emacs movement chords (`C-f`/`C-n`/…) are resolved by the scene-level
  `KeyDispatcher` on the primary caret and don't fan out — use arrows for multi-caret movement.

- **0.11.7-inlay.2** is a performance release of the same source: `ParagraphBox` caches its preferred
  height (Flowless re-measures every visible cell on every edit, which was a text layout plus font strikes
  per visible line per keystroke; the cache is dropped by `requestLayout()`, which every size-relevant
  change in JavaFX funnels through), `Paragraph.getText()` returns a single segment's own `String` instead
  of a copy, and `ReadOnlyStyledDocument.getText()` builds the document text from segments without filling
  every paragraph's text cache. A plain-text file is no longer held twice by the model.

**To update the fork:** rebuild it (**with JDK 21** — see
[packaging-notes.md](reference/packaging-notes.md); `./gradlew :richtextfx:test` then
`./gradlew :richtextfx:publishToMavenLocal`, which writes the jar and pom under
`~/.m2/repository/io/github/adriandeleon/richtextfx/<version>/`), copy the new
`richtextfx-<version>.{jar,pom}` into `m2-repo/`,
record each file's SHA-256 beside it (`sha256sum richtextfx-<version>.jar | cut -d' ' -f1 >
richtextfx-<version>.jar.sha256`, likewise for the `.pom`), delete the previous version's directory,
and bump `<richtextfx.version>`. `BuildHygieneTest` fails when the jar no longer matches its
recorded hash, when the version in the pom has no directory, or when an unused version is left behind. Use **0.11.7+** (earlier is incompatible
with JavaFX 25+). The published pom declares JavaFX 21.0.5 at runtime scope with the classifier of the
platform that built it; `pom.xml` excludes `org.openjfx:*` from the fork, so that never reaches the path.

RichTextFX and its transitive deps (`reactfx`, `flowless`, `undofx`, `wellbehavedfx`) are
**automatic modules**, which `jlink` cannot link — the `moditect-maven-plugin` (in the main build,
at `package`; the `dist` profile only consumes its output) injects explicit `module-info` descriptors. If you bump RichTextFX or add a dep it uses,
you may need to adjust those descriptors' `requires` (several need `javafx.controls` for
`IndexRange`).

## tm4e — NetBeans repackaging (signed jar)

The syntax engine ships as `org.netbeans.external:org.eclipse.tm4e.core-0.14.0:RELEASE260`
(tm4e is not on Maven Central). Its Oniguruma backend (`org.jruby.joni:joni`,
`org.jruby.jcodings:jcodings`) and `gson` are already proper modules, but tm4e core is an
automatic module, so moditect injects a `module-info` for it too.

The NetBeans jar is **code-signed**, and `jlink` rejects signed modular jars — the `dist`
profile's antrun step strips `META-INF/*.SF,*.RSA,*.DSA,*.EC` before linking.

## The headless test backend (no vendored dependency)

The `@Tag("fx")` harness runs over JavaFX's **built-in Headless Glass platform**
(`-Dglass.platform=Headless`, part of `javafx.graphics` since 26 — set in the surefire
`<systemPropertyVariables>`). Nothing is vendored: no jar, no native libs, no rebuild on a
JavaFX bump.

This **replaced** a previously self-built **Monocle** backend
(`io.github.adriandeleon:openjfx-monocle`, once vendored in `m2-repo/`), which had to be rebuilt
from the OpenJFX sources on every JavaFX bump. That whole ritual and its artifacts are gone. The
built-in platform is officially a *prototype* in 26, but the harness only boots the toolkit and
never drives the robot or renders/snapshots, so the prototype's limitations don't apply. It's
test-scope only and never on the runtime path. See [testing.md](testing.md).

## Automatic modules + moditect (general)

Several real dependencies are automatic modules that `jlink` can't link, so the main build's
moditect step (`patch-automatic-modules`, bound to `package`; the `dist` profile only consumes its
output) injects jdeps-generated `module-info` descriptors (with `--ignore-missing-deps` where
needed): RichTextFX & friends, tm4e core, **PDFBox** (`pdfbox`/`pdfbox-io`/`fontbox`), **lsp4j** +
**lsp4j.debug**, and **java-diff-utils**. A couple need hand-maintained descriptors:

- **sshd-osgi** (SFTP) uses a hand-written `src/moditect/module-info-sshd-osgi.java` (jdeps drops
  a `requires org.slf4j` that every SSHD class needs). The combined `sshd-osgi` bundle is used
  instead of `sshd-common`+`sshd-core`, which split a package across two automatic modules
  (illegal under JPMS). `sshd-sftp` excludes `jcl-over-slf4j` (collides with PDFBox's
  commons-logging under jlink) and slf4j-api is forced to `${slf4j.version}` (2.x, a real module).
  The descriptor exports **every** package of the jar; `SshdModuleDescriptorTest` compares the two, so
  a version bump that adds or drops a package fails until the file is re-derived.
  **SSHD is on 2.20.0** (CVE-2026-94002, a client-side SFTP memory-exhaustion bug, is fixed there). One
  behaviour of 2.17+ matters to Editora: an `SftpFileSystem` no longer caches its default directory at
  construction — `getDefaultDir()` asks the live SFTP client and throws once the filesystem is closed —
  so a *relative* remote path cannot be made absolute after a disconnect. `RemoteFileSystems.connect`
  therefore resolves the connection root to an absolute path while the session is live
  (`RemoteFileSystemsHostKeyFxTest.reconnectResolvesAnOpenPathsDurableUriToTheNewFilesystem` pins it).
  The package set of 2.20.0 is identical to 2.16.0's, so the descriptor needed no change; the bump has
  not yet been exercised by a `-Pdist` jlink build on every platform or by the native-image lane.

- **Bouncy Castle** (`bcprov-jdk18on`) is there for MINA SSHD's ed25519 alone: MINA has no EdDSA of its
  own and does not use the JDK's, and enables `ssh-ed25519` keys and host keys only when Bouncy Castle (or
  the unmaintained `net.i2p.crypto:eddsa`, which has CVE-2020-36843) is on the module path. It is a real
  module (`org.bouncycastle.provider`, ~7 MB). Editora's `module-info` requires it so it is resolved, and the
  sshd descriptor `requires static` it so MINA's Bouncy Castle classes can link. Its jar is signed: `-Pdist`
  strips the signature like tm4e's and lsp4j's (OpenJDK does not require a signed provider, and MINA calls
  its EdDSA classes directly). `RemoteFileSystemsEd25519FxTest` pins the support.

If you add a dependency that turns out to be an automatic module, expect to add a moditect entry.

## The integrated terminal — JediTerm, pty4j, JNA, Kotlin

[ADR 0014](decisions/0014-integrated-terminal.md). The emulator is JetBrains' **JediTerm core**
(`org.jetbrains.jediterm:jediterm-core`), which parses VT/xterm escape sequences into a screen buffer and draws
nothing; the pseudo-terminal is **pty4j** (forkpty on macOS and Linux, ConPTY or winpty on Windows), which
reaches the OS through **JNA**. Both are written in Kotlin, hence **kotlin-stdlib**. The versions match
Termina's, where this stack came from.

- **JediTerm is vendored in `m2-repo/`.** JetBrains publishes it only to its own repository
  (`packages.jetbrains.team/…/intellij-dependencies`), not to Maven Central. Like the RichTextFX fork it sits
  beside a recorded SHA-256 of its jar and pom, and `BuildHygieneTest` checks both and that no other version is
  left behind. To update: download `jediterm-core-<v>.{jar,pom}` from that repository, compare the jar with its
  published `.sha1`, copy both into `m2-repo/org/jetbrains/jediterm/jediterm-core/<v>/`, record the SHA-256s,
  delete the old directory and bump `<jediterm.version>`.
- **Pinned on purpose.** jediterm wants Kotlin 2.4.0 and pty4j 2.1.21; pty4j wants JNA 5.14.0. The pom names
  the versions so nearest-wins does not choose. The `org.jetbrains:annotations` jars all three declare are
  compile-time only (CLASS retention) and excluded; version 13.0 (from kotlin-stdlib) has no module name and
  would be an automatic module jlink cannot link.
- **Four automatic modules, four moditect descriptors:** `com.sun.jna`, `com.sun.jna.platform`,
  `jediterm.core` and `pty4j`. The last two name `kotlin.stdlib`, `org.slf4j` (and `java.desktop`, for
  `TtyConnector`'s `java.awt.Dimension` overloads) explicitly and drop the jdeps-derived
  `org.jetbrains.annotations` (`!`). Editora's own `module-info` requires `kotlin.stdlib` and `org.slf4j` too:
  an automatic module reads every module in the graph but pulls none into it, so under `mvn javafx:run` they
  would otherwise not be resolved and the first emulator call would fail.
- **Native access.** JNA loads native code, so `--enable-native-access=javafx.graphics,com.sun.jna` in the
  javafx:run options, the dist launcher and `aot_build.java`'s trainer.
- **pty4j's natives** live in its jar under `resources/com/pty4j/native/<os>/<arch>/`. JPMS would encapsulate
  them if that folder were a valid package name; `native` is a reserved word, so it is not, and they stay
  readable. pty4j copies the ones for this OS to a fresh temp directory per JVM (`pty4j.tmpdir` overrides the
  location, and makes it shared — do not set it for parallel test forks). On Linux the window size is set just
  after the child starts, so a program that asks at once can read 0×0.
- **`editora --check-terminal`** (not in `--help`) starts a shell on an 80×24 pseudo-terminal, reads
  `stty size` (PowerShell's console size on Windows) back through JediTerm and exits 0 when it saw `24 80`. The
  **Terminal image** workflow (`.github/workflows/terminal-image.yml`) runs it inside the `-Pdist` app image on
  Linux, macOS and Windows for pull requests that touch these dependencies; `TerminalImageCheckTest` runs it from
  the class path in CI.
- **Size:** the five modules add 7.5 MB to the linked image (20.5 MB before jimage compression), measured on
  Linux x64: pty4j 2.8 MB (natives for every OS), JNA 1.9, Kotlin 1.5, jna-platform 1.1, JediTerm 0.2.
- No slf4j provider is bundled, as before (MINA SSHD logs through slf4j too): pty4j's and JediTerm's log lines
  go to slf4j's no-op logger, and slf4j prints a one-time warning to stderr when it first loads.

## Bundled fonts & grammars

Plain resources, no `module-info`/moditect change, picked up by the dist build automatically:
five monospace families + **Inter** (Markdown preview / PDF prose) + **Noto Emoji** (monochrome; the
PDF export's emoji glyphs, embedded as a subset only when a document has one) under
`resources/com/editora/fonts/`, and the TextMate grammars under `resources/com/editora/grammars/`
(the grammar package needs the `opens … to org.eclipse.tm4e.core` — see
[extending.md](extending.md#add-a-language--textmate-grammar)). All are attributed in `NOTICE`.
