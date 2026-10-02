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
  (`Settings.multiCaret`, default on).
- Editora's area-level KEY filters (auto-indent/close, snippets, completion, view paging) are
  capture-phase filters that early-return (no consume) via `multiCaretActiveOn(a)` when an area
  has extra carets, so editing fans out to all carets.
- **Known limitation:** Emacs movement chords (`C-f`/`C-n`/…) are resolved by the scene-level
  `KeyDispatcher` on the primary caret and don't fan out — use arrows for multi-caret movement.

**To update the fork:** rebuild it, copy the new `richtextfx-<version>.{jar,pom}` into `m2-repo/`,
record each file's SHA-256 beside it (`sha256sum richtextfx-<version>.jar | cut -d' ' -f1 >
richtextfx-<version>.jar.sha256`, likewise for the `.pom`), delete the previous version's directory,
and bump `<richtextfx.version>`. `BuildHygieneTest` fails when the jar no longer matches its
recorded hash, when the version in the pom has no directory, or when an unused version is left behind. Use **0.11.7+** (earlier is incompatible
with JavaFX 25+).

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

If you add a dependency that turns out to be an automatic module, expect to add a moditect entry.

## Bundled fonts & grammars

Plain resources, no `module-info`/moditect change, picked up by the dist build automatically:
five monospace families + **Inter** (Markdown preview / PDF prose) under
`resources/com/editora/fonts/`, and the TextMate grammars under `resources/com/editora/grammars/`
(the grammar package needs the `opens … to org.eclipse.tm4e.core` — see
[extending.md](extending.md#add-a-language--textmate-grammar)). All are attributed in `NOTICE`.
