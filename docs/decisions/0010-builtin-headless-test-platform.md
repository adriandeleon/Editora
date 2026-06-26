# 0010 — JavaFX 26 built-in Headless test platform, drop self-built Monocle

**Status:** Accepted (supersedes the previous self-built-Monocle approach)

## Context

The FX test harness needs a headless Glass backend so `@Tag("fx")` tests run on CI with no
display. The long-standing option was **Monocle**, but there is no upstream Monocle build for
current JavaFX (`org.testfx:openjfx-monocle` stops at 21), so it had to be **self-built from the
OpenJFX sources and vendored in `m2-repo/`, and rebuilt on every JavaFX bump** — a stale-version
jar fails to link against the internal `com.sun.glass.ui` APIs.

## Decision

Use **JavaFX 26's built-in Headless Glass platform** (`-Dglass.platform=Headless`, part of
`javafx.graphics` since 26, set in the surefire `<systemPropertyVariables>`). Remove the vendored
Monocle artifact and the `<monocle.version>` property.

## Consequences

- **Nothing is vendored** — no jar, no native libs, no rebuild ritual. The backend ships inside
  the JavaFX runtime, so it can never go stale on a JavaFX bump.
- The built-in platform is officially a **prototype** in 26. That's fine here because the harness
  only uses TestFX's `FxToolkit` to **boot the toolkit** — it never drives the robot (input/clicks)
  or renders/snapshots in tests, so the prototype's limitations don't apply (and TestFX needs no
  changes despite not yet adopting it).
- If a future need ever requires real headless rendering or robot input under the prototype,
  Monocle could be re-vendored — but as of 26 it isn't needed.
- Surefire still needs `<useModulePath>false</useModulePath>`, `prism.order=sw`, and the
  `<argLine>@{argLine}</argLine>` token (so JaCoCo's agent survives).

See [testing.md](../testing.md) and
[dependencies.md](../dependencies.md#the-headless-test-backend-no-vendored-dependency).
