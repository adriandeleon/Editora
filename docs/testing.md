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
coordinators) is covered end-to-end by a **TestFX** harness running over **JavaFX 26's built-in
Headless Glass platform** (`-Dglass.platform=Headless`, part of `javafx.graphics` since 26), so
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
  changes, the fixture/seam must track it.**

### The surefire config that makes it work

In `pom.xml`:

- `<useModulePath>false</useModulePath>` — classpath mode.
- headless system properties: `glass.platform=Headless`, `prism.order=sw`.
- `<argLine>@{argLine}</argLine>` — **the `@{argLine}` token is mandatory** so JaCoCo's injected
  coverage agent (set via the `argLine` property) survives. A plain `<argLine>` would clobber it.

Because the backend ships inside JavaFX, it can never go stale on a JavaFX bump — unlike the
previously self-built Monocle backend it replaced (see
[dependencies.md](dependencies.md#the-headless-test-backend-no-vendored-dependency)).

## Coverage

`mvn test` runs the JaCoCo agent and writes `target/site/jacoco/index.html` (+ `jacoco.xml`).
`mvn verify` additionally runs a JaCoCo **`check`** that enforces a per-package **line-coverage
floor** on the well-covered pure packages (e.g. `config`/`migration`/`diff` ≥ 0.85/0.80,
`template`/`editorconfig` ≥ 0.80, `completion`/`http`/`pdf` ≥ 0.68 — see the `jacoco-check`
execution in `pom.xml`).

- The floors sit **below** current levels — they're a regression net, not a target. **When you
  raise a package's coverage, ratchet its floor up.**
- `ui`/`editor` are deliberately **ungated** (the FX harness covers only a few percent of `ui`
  so far). Add a floor for them only once coverage is meaningful — and the cheapest way to get
  there is still to extract pure helpers.

The dev loop (`mvn javafx:run`/`compile`) is unaffected; the check runs only at
`verify`/`package`.

## What to test for a typical change

- A pure helper → a dedicated `*Test` with positive/negative/edge cases (front matter, empty,
  null, multi-byte, …).
- A config-schema change → a migration test if it's not additive-identity; otherwise the
  round-trip is covered by the read path.
- New i18n keys → `MessagesTest` already enforces six-catalog parity and command/desc pairing;
  just keep the catalogs complete.
- Controller-visible behavior → a `@Tag("fx")` test via `FxWindowFixture` if it can't be reduced
  to a pure helper.
