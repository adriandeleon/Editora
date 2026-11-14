# Conventions

The rules a change must follow to be mergeable. Most PR review comments come back to one of
these. (See also [performance.md](performance.md), which is a convention important enough to
get its own page.)

## Every feature is a Command

Editora is command-driven. Every user-facing action is a registered
`command/Command` (id + title + runnable) through `WindowCommandRegistrar.registerCommands()`. The command
palette, the keybinding editor, and toolbar buttons all dispatch through the registry, so a
properly registered command is discoverable for free.

- **Never wire a UI handler straight to logic** when a command fits — register the command and
  have the button/menu run it by id.
- If it needs a key, add a binding to the bundled keymap(s) (see
  [extending.md](extending.md#add-a-command)). A keymap only changes accelerators; a command
  must work from the palette without one.

## Every setting also needs a palette command

A Settings-window control is not enough on its own. Add a command that flips/prompts the
**same** `Settings` field, persists (`requestSave()`), re-applies the feature (e.g.
`applyMarkdownLint()`), and keeps an open Settings window in step (`SettingsWindow.syncAll()`).

- Toggles → `view.toggle*` (or `appearance.`/`debug.` prefixes so the palette's feature-gating
  filter never hides the master toggle).
- Values → a `promptText` prompt or a `QuickOpen` picker; group many per-item settings under
  one picker command.
- Reuse the generic helpers: `toggleSetting` / `promptStringSetting` / `promptIntSetting` /
  `chooseSetting`.
- The command applies the change in its own window only. The other windows follow on their own:
  the shared save notices that preferences changed and re-applies them there (see
  [config-and-migrations](subsystems/config-and-migrations.md#changes-reach-every-window)).

## Adding a setting to the Settings window

A preference touches five places; miss one and it is applied but never saved, or saved but never shown.

1. **Model**: the field, default and getter/setter in `Settings` (with a schema bump and migration, see
   [Config and schema](#config-and-schema)).
2. **Control**: built once in `SettingsWindow` or in the page's own class (`SettingsLspPage`, `SettingsAiPages`,
   `SettingsSyncPage` and the other `Settings*Page`/`Settings*Pages` classes, each holding one area's controls,
   layout and load), its listener writing the field and calling `apply()` — unless `loading` is set. Text fields
   commit on Enter or focus loss (`commitOnEnterOrBlur`), numbers use `IntSpinners.editable` (invalid text is
   marked, never committed). A control's field name is its row in `settings-controls.properties`; a new page
   class goes into `SettingsWindow.controlOwners()`, where the modified marks and the settings tests find it.
3. **Row**: placed on its page with `checkRow` / `controlRow` / `cardRow` and English search keywords. The
   search also reads the row's title, description, prompts and a combo's choices; a list inside a row is not
   searched (see `SettingsSearch`).
4. **Load**: one line in `load()` (or the page's own load) setting the control from `Settings`. Another
   window's change re-runs it, so it must be safe to run at any time.
5. **Palette command and strings**: see the next sections.

`SettingsRoundTripFxTest` changes every switch, number and choice field, restarts on the same config folder
and checks each one reads back — a new control is covered the day it is added; list it there only when it
cannot round-trip (it asks first, or is not a preference). Changes apply as they are made: there is no Save
button on a page (Macros' Save/Revert is the one exception).

## Localize every user-facing string

Never hand a raw English literal to a JavaFX control. Use
`import static com.editora.i18n.Messages.tr;` then `tr("some.key")` (or `tr("key", args…)` for
`MessageFormat` parameters like `Saved {0}`). `NoLiteralUiTextTest` scans the sources for a literal in a
control's constructor, a text setter or a tooltip, and fails on one with two letters side by side. A literal
that is not a word of a language (a program's name as a prompt, the `Aa` glyph, a sample URL) goes in that
test's `NOT_LANGUAGE` list with its reason.

- **Add the key to all six** `resources/com/editora/i18n/messages[_<lang>].properties` files
  (English base + `it/es/fr/pt/de`) with a real translation in each. `MessagesTest`'s
  key-parity check fails the build if any locale is missing a key.
- A new `Command` gets its title for free from `command.<id>`, and its palette description from
  `command.<id>.desc` — **every** `command.<id>` must have a matching `.desc` (enforced by
  `MessagesTest`).
- The only deliberately-untranslated tokens are technical identifiers (`UTF-8`, `LF`/`CRLF`,
  `Ln`/`Col`, git verbs, language names, example URLs) and the pre-GUI `--help`/`--version`
  text.
- **Apostrophes follow the value.** A value with a placeholder (`{0}`) is a `MessageFormat`
  pattern: double every literal apostrophe (`l''onglet {0}`). A value without one is shown as
  written: a single apostrophe. `MessagesTest` enforces both in all six catalogs.
- **Identifiers are not quantities.** A plain `{0}` groups an integer's digits by locale
  ("Line 12,345"). For a line or column, a port, a pull-request number or an exit code write
  `{0,number,#}` and add the key to `MessagesTest.IDENTIFIER_ARGUMENTS`.
- **A count picks its words.** Write a message with a count as a `{0,choice,…}` pattern with a limb for
  each form the language needs, never "file(s)", "Datei(en)" or "immagine/i":
  `{0,choice,0#{0} files|1#{0} file|1<{0} files}`. French says "0 fichier", so its limbs are
  `0#{0} fichier|2#{0} fichiers`. The caller passes the count as a number, not as text: a `choice` cannot
  read a string. A word that does not change (`{0} Vorkommen`, `{1} file` in Italian) needs no limbs.
  `MessagesTest` fails a value with a placeholder and a bracketed ending in any catalog.
- **Modifier keys keep their names.** `Ctrl`, `Alt`, `Shift`, and on a Mac `Control`, `Option` and the
  glyphs, read the same in every language: "Shift+Entrée", never "Maj+Entrée" or "Strg+D". The key caps, the
  keymaps and the docs use one spelling. Other keys (Enter, Esc, Home) may be translated. `MessagesTest`
  looks for the translated names.
- **Dates and numbers follow the system's region, not the bare language.** `Messages.locale` decides the
  locale that formats them, and `App.start` makes it the JVM's default: the system's locale whole when no
  language is chosen or the chosen one is the system's own (an Austrian keeps `de-AT`, and an English
  interface on a French system keeps French dates), and the bare language only when another language is
  chosen. Format with `Locale.getDefault(Locale.Category.FORMAT)` or a formatter that uses it by default
  (`MessageFormat.format`, `DateTimeFormatter.ofLocalizedDate`); never build a locale from
  `Messages.current()`. Since the default can now be any locale, text a program reads (an id, a number in
  a file) is made with `Locale.ROOT`.
- A key used by literal (`tr("some.key")`) must exist: `tr` falls back to the key itself, so a
  missing one ships as visible gibberish. `MessagesTest` scans `src/main/java` for them.

## Keyboard, contrast and assistive technology

A control that works with the mouse but cannot be reached, seen or named from the keyboard is a defect.

- **Icon-only buttons** are built with `Icons.button` / `Icons.toolbarButton` (or re-named with
  `Icons.name`), which set the tooltip **and** the accessible text. Never hand-roll `new Button()` +
  `setTooltip`.
- **On/off settings rows** use `checkRow` / `switchFor`, which yield a `SettingSwitch` (Space/Enter,
  `TOGGLE_BUTTON` role, named by the row title). A bare AtlantaFX `ToggleSwitch` is mouse-only.
- **Keyboard focus** is drawn by the one `:focus-visible` block at the end of `app.css`. `app.css` is an
  author sheet: a base-state `-fx-background-color` there erases the theme's `:focused` variant of the same
  control, so restate the states you override and keep that block last.
- **Colour**: text people must read uses `-color-fg-muted` or stronger (`-color-fg-subtle` is ~3:1 —
  disabled/decorative only); ink on an `-emphasis` fill is `-color-fg-emphasis`, never `white`; state
  colours come from the `-state-*` tokens, never a hex. `StyleContrastTest` pins the ratios.
- **Failures** go to the error channel. A message whose key is `status.…Failed` / `…Error` is routed
  there automatically wherever it is reported (`StatusSeverity`); `…cannot…` / `…invalid…` are warnings.
  Name new failure keys accordingly, or call `setError`. The name decides, with no list of exceptions: a
  message that mentions a failure without being one is named for what it says
  (`status.testrunner.filterNotPassed` for "Showing failed tests only").
- **Native dialogs** are created through `Dialogs.styled(new Alert(…))`, at the point they are built
  (`DialogsSourceTest` scans for one that is not): a dialog has its own scene and does not inherit `app.css`.
  `styled` also gives every dialog one button order on every OS, Cancel left of every action, and words the
  stock OK, Cancel, Yes, No, Apply and Close from the message catalog instead of the JVM's locale.
- **A question before something is lost** (removed, deleted, discarded, overwritten, reset, a running program
  ended, a command that cannot be taken back) goes through `Confirm.ask(owner, title, message, verb)`: the
  confirming button carries the action's own verb from the catalog (`confirm.action.delete`, …), never OK or
  Yes, in red, and Cancel is the default button, so Enter keeps what the user has. A question that needs a
  layout of its own (a checkbox, a third answer) builds its dialog with `Dialogs.action(verb)` and
  `Dialogs.cancel()` and calls `Dialogs.destructive(dialog, action)` once its buttons are final; where it also
  offers a safe action (Save beside Discard), `Dialogs.defaultTo(dialog, safe)` makes that one the default.
  Use `Dialogs.cancel()`, not `ButtonType.CANCEL`, beside a named action. A removal that can be taken back
  (a plain bookmark toggle) asks nothing. In a test, `Confirm.answerWith(question -> true)` answers without
  showing a dialog (reset it with `null` afterwards), and a shown dialog is answered by button role
  (`FxDialogs`), never by looking up a stock `ButtonType`.
- A row that can run out of width **wraps** (`WrapRow`, `SettingRowPane`) rather than ellipsizing a label
  or a button to "…"; check German at 1024 px.

## Sizes follow the UI zoom

The UI zoom (`Settings.uiZoom`, 80–200%) scales the whole interface, and it can only scale a size it is told
about. [`UiZoom`](../src/main/java/com/editora/editor/UiZoom.java) has both halves.

- **Stylesheets are written at 100%, in pixels or bare numbers.** At any other zoom every length in a sheet
  is multiplied (`UiZoomCss`: paddings, radii, font sizes, minimum and maximum sizes, a shadow's radius and
  offsets; a 1 px border stays 1 px; `em` and `%` are relative already). A scene or a node takes a sheet
  through `UiZoom.sheet(url)`, never by its bare URL, and the theme's user-agent sheet through
  `Themes.userAgentStylesheetFor`. A zoom change re-points the sheets of every open window, and a window is
  re-pointed as it shows, so a sheet added by its bare URL is caught late rather than never.
- **A size the Java code fixes goes through `UiZoom`**, never a plain `setPrefWidth(280)` or
  `new Insets(8)`: `UiZoom.padding(node, 8)`, `UiZoom.spacing(new HBox(a, b), 6)`, `UiZoom.prefWidth(node,
  280)` and the other shortcuts, or `UiZoom.follow(node, () -> …)` with `UiZoom.px(n)` inside for anything
  else (a margin, a fixed cell size, an image's fit size, an inline style through `UiZoom.style`). They set
  the size now and again whenever the zoom changes. A follower is keyed by its place in the code, per node:
  in a loop over one node, pass a key (`UiZoom.follow(grid, column, () -> …)`).
- **Not scaled:** what is not a length (`0`, `-1`, `Double.MAX_VALUE`, `USE_PREF_SIZE`, percentages, column
  counts), what is measured from another node or the screen, what comes from saved state (window geometry,
  split positions, a Project Map card's place and size), and content with a zoom of its own: the editor's
  text and everything drawn in the editor font, a terminal's grid, the Markdown preview's own zoom.
- **Paper stays at 100%.** A scene that lays a page out for print or export, and the Print Preview window
  that shows those pages, is pinned with `UiZoom.pin(scene)`: it keeps the 100% sheets whatever the zoom, or
  a page paginated at one size would be drawn at another.
- The text zoom (`Settings.fontZoom`, the editor's font) is a different setting with its own three commands;
  the keymaps bind Ctrl/Cmd with `=`, `-` and `0` to it, and the UI zoom's commands ship unbound.

## Which operating system

[`com.editora.Os`](../src/main/java/com/editora/Os.java) is the one class that reads `os.name`. Every other
class asks it: `Os.isWindows()`, `Os.isMac()`, `Os.isLinux()`, or `Os.current()` for the `Os.Kind` to pass on.

- **Never read `os.name` anywhere else**, and never infer the OS another way: a path separator compared with
  `'\\'` or `';'`, an environment variable only one system sets. `OsTest` scans the sources for both.
  (`File.pathSeparator` used to split a `PATH` is its proper use, not a test for the OS.)
- **A pure function that depends on the OS takes an `Os.Kind`**, not a name to parse
  (`Trash.forPlatform(Os.Kind, …)`, `ElevatedSave.supportedOnOs(Os.Kind)`), so its test passes the kind and
  runs on any machine. The caller passes `Os.current()`.
- **Ask when the answer is needed.** A `static final boolean MAC = Os.isMac()` holds the OS the class loaded
  under, which a test that changes the override afterwards will not see. Keep one only for a hot path, and
  never to decide what is registered, shown or offered.
- **Tests run as any OS** with `Os.override(Os.Kind.MAC)` (reset with `null` in `@AfterEach`), or a whole run
  with `-Deditora.os=mac`. `SameOnEveryOsFxTest` builds a window as each of the three and checks that the
  commands, the menu bar and the Settings rows are the same: a command, a menu row or a setting is never
  added inside an OS check. Where the OS cannot serve one, it stays, disabled, and says why: the command's
  reason comes from `Chrome.osReason(id, os)` (which the palette and the menu bar both read, ahead of any
  feature gate), and its Settings control calls `setDisable` with the reason in the row's note. Save as
  Administrator on Windows is the worked example.
- For display (About, Doctor, a problem report) use `Os.description()`, or `Os.name()`, `Os.version()` and
  `Os.arch()`.

## Config and schema

Preferences live in `settings.json` (`Settings`, a Jackson POJO); session state in
`workspace-state.json` (`WorkspaceState`); other stores in their own JSON files. See
[architecture.md](architecture.md#multi-window-model) for the `SharedConfig`/`ConfigManager`
split.

**When you change a config schema** (add/rename/restructure a field in any versioned file):

1. Bump that POJO's `SCHEMA_VERSION` constant (`Settings`, `WorkspaceState`, `BookmarkStore`,
   `ProjectManager.Index`, `RecentFiles`, …).
2. Add a `v→v+1` entry to that file's `steps` map in
   [`config/migration/ConfigSchema.java`](../src/main/java/com/editora/config/migration/ConfigSchema.java).
   A purely additive field uses `ConfigMigrations::identity` (additive-identity); the new field
   just defaults.
3. A file newer than the running build is backed up to `<name>.v<n>.bak` and defaults are
   loaded — an older Editora never clobbers a newer config. The read path, version stamping,
   and downgrade backup are automatic once the step is registered.

If you add a Jackson-serialized type, remember the `opens com.editora.<pkg> to
com.fasterxml.jackson.databind;` in `module-info.java`.

## Code style: Palantir Java Format via Spotless

All Java is auto-formatted with [Palantir Java Format](https://github.com/palantir/palantir-java-format)
(a 120-col google-java-format fork) through the `spotless-maven-plugin`.

- **Run `mvn spotless:apply` before committing.** `spotless:check` runs at the `verify` phase,
  so `mvn verify`/`package`/CI fail on unformatted code. The `mvn javafx:run`/`compile` dev
  loop is left untouched.
- Import order: JDK (`java`/`javax`) → `javafx` → third-party + `com.editora` → static last.
  Longest-prefix wins, so `javafx` is not swallowed by `java`.
- Escape hatch for hand-aligned code: wrap it in `// spotless:off` … `// spotless:on`.
- **Format on JDK 25.** Spotless runs Palantir inside Maven's own JVM, and Palantir reads a javac
  internal (`JCCompilationUnit.endPositions`) that JDK 27 removed
  ([JDK-8372948](https://bugs.openjdk.org/browse/JDK-8372948)); on JDK 27 every file fails with a
  `NoSuchFieldError`. The repo's `.sdkmanrc` pins JDK 25 (`sdk env`), and on JDK 26+ the
  `formatter-needs-jdk25` profile fails `verify` early with that explanation unless
  `-Dspotless.check.skip=true` is passed (as CI's JDK 27 lane did; that lane is disabled for now). `mvn spotless:apply` is a direct
  goal that bypasses that check, so run it on JDK 25 yourself. Drop the pin and profile once a Palantir
  release fixes both [#1785](https://github.com/palantir/palantir-java-format/issues/1785) (this field,
  fix in PR [#1786](https://github.com/palantir/palantir-java-format/pull/1786)) and
  [#1799](https://github.com/palantir/palantir-java-format/issues/1799) (JDK 27 also breaks formatting
  local `var` declarations).

## Null annotations: JSpecify, checked by NullAway

Packages opt in one at a time. A package whose `package-info.java` says `@NullMarked` is checked by
[NullAway](https://github.com/uber/NullAway) (an Error Prone plugin) during the main compile. `mvn compile`
then fails on any null that can reach a type not marked nullable. Unmarked packages and libraries are trusted
and not checked.

- **A new package starts `@NullMarked`.** Give it a `package-info.java`:

  ```java
  @NullMarked
  package com.editora.example;

  import org.jspecify.annotations.NullMarked;
  ```

  `NullMarkedPackagesTest` fails for a package that is neither marked nor listed in
  `src/test/resources/com/editora/packaging/unmarked-packages.txt`. That list held the packages written
  before the rule and has been empty since 2026-10-20, when `ui`, the last of them, was marked. Keep it empty:
  the test also fails when a listed package is marked or deleted and its line is still there.
- **In a marked package everything is non-null unless it says `@Nullable`** (`org.jspecify.annotations`).
  Mark what really may be null: a field set later, a parameter callers pass null to, a "not found" return.
  JSpecify annotations are type-use, so they go next to the type: `private @Nullable Foo foo`,
  `Outer.@Nullable Inner`, `byte @Nullable []` (the array may be null), `List<@Nullable String>`
  (its elements may be).
- **Prefer a shape that is not null over `@Nullable`** when the null is an accident of the code, not
  part of its meaning. Pass a value as a parameter instead of re-reading a nullable field
  (`SingleInstance.startAcceptLoop`). Narrow a type with `instanceof` (`JvmDebugAdapter.variable`). For state
  that must exist by the time it is used, write an accessor that fails with a clear message
  (`JvmDebugAdapter.vm()`).
- **NullAway does not follow a boolean that holds a null check.** After
  `boolean kotlin = name != null && name.endsWith(".kt"); if (kotlin) …`, `name` still counts as nullable.
  Write the check inline.
- **Every package is marked** (since 2026-10-20). They were marked in layers: nine pilot packages, then the
  58 smaller ones, then `editor`, then `ui`. A package added later starts marked, so nothing needs migrating.
  A real bug found on the way got its own fix; behaviour did not change otherwise.
- **An object finished by a method after its constructor** marks that method `@Initializer`
  (`com.editora.ui.Initializer`; NullAway honours any annotation of that name). A field the method always
  assigns then counts as non-null, as if the constructor had set it. `MainController.init` (FXMLLoader builds
  the controller, `WindowManager` calls `init` straight after), `NavigationCoordinator.setupJumpPickers` (which
  `init` calls) and `SettingsWindow.build` (the window builds its controls on first show; the methods that can
  run earlier check first) use it, as do the Settings page classes' `buildControls` (which `build` calls). It is a
  promise NullAway cannot check, so keep it to that lifecycle; a field set by a setter or a later step stays
  `@Nullable`. Fields injected by FXML (`@FXML`) are not checked
  for initialization (`ExcludedFieldAnnotations`).
- **JavaFX returns null more often than it looks**: `Node.getScene()`, `Scene.getWindow()`, `Control.getSkin()`,
  `Node.getParent()`, a selection model's selected item, a `ComboBox` value. Guard where the value is used.
- **Watch for over-wide annotations.** One `null` passed to a method that hands its argument back unchanged
  makes the result `@Nullable`, and then every reader of it. When many dereferences of one value are
  flagged, look at where the null comes from before guarding each one (`ProcessRunner.Result.err` was
  never null; a helper's parameter only looked as if it could be). When a caller has a null for a parameter
  that does not take one, make the parameter `@Nullable` only if the method already copes with null (it
  checks, or hands the value on to something documented to take null); NullAway then checks that it does.
  Otherwise check at the caller. Widening one method after another until the error moves out of sight is
  how a whole API turns `@Nullable`.
- **Native bindings are `@NullUnmarked`.** A JNA `Library` interface or `Structure` follows the C API's
  nullness, so mark the binding type `@NullUnmarked` instead of annotating every pointer
  (`secrets.LinuxSecretServiceStore`). The Java field that holds a library that may not have loaded is
  `@Nullable`. A `loaded()` accessor that throws hands each method a non-null local.
- **Suppress rarely.** Put `@SuppressWarnings("NullAway")` on the narrowest element, with a comment saying
  why. Usually that is a one-line helper rather than a whole method: `RecoveryCodec.written` keeps a null
  from a hand-edited header, because refusing the record would lose its text.
- **Plumbing.** Only NullAway runs (`-XepDisableAllChecks -Xep:NullAway:ERROR`), and only in the main compile,
  not on tests. `.mvn/jvm.config` exports the javac internals that Error Prone needs. JSpecify is a `provided`
  dependency read through `requires static org.jspecify`, so nothing ships. It runs on JDK 25 and JDK 27.
  Cost of a clean `mvn -DskipTests compile` (two runs each): about 9.5 s with no package or nine marked,
  11.5 s with all but `ui` and `editor` (2026-10-19), 12.3 s with all but `ui`, and 14.2 s with every
  package (both 2026-10-20; 12.2 s for the previous layer on the same run).

## Worktrees: one per task

This repo is worked on by multiple sessions in parallel. **Each task gets its own
`git worktree`** so sessions don't share a working tree:

```
scripts/worktree.sh new <branch>     # creates ../Editora-worktrees/<slug> off origin/master
scripts/worktree.sh list
scripts/worktree.sh rm <branch>      # after merge; keeps an unmerged branch unless --force
```

**Never `git checkout` a different branch in the main checkout** while other sessions may be
active — keep it on `master` and operate on a worktree by path. Spawn subagents with
`isolation: "worktree"` for the same reason.

## Tests

Prefer extracting a **pure decision helper** and unit-testing it (fast, no toolkit) over
testing through the UI. Real controller behavior is covered by the headless-FX harness. See
[testing.md](testing.md). `mvn verify` enforces JaCoCo per-package line-coverage floors on the
well-covered pure packages — when you raise a package's coverage, ratchet its floor up.

Before a push, run the narrow tests for the change and the guard tests (`mvn test -Dgroups=guard`), then
open the pull request and move on without waiting for CI
([testing.md](testing.md#guard-tests), [`AGENTS.md`](../AGENTS.md)).

## Docs in the same PR

A feature PR updates `CHANGELOG.md` and `TODO.md` in the same change, not as a follow-up.
User-facing documentation lives in the separate website repo; **this `docs/` folder is for
developers**. `README.md` is a short landing page that points at the website: it does not carry a
feature catalog, so change it only when its highlights, install, build, command-line or
configuration facts change. Keep `AGENTS.md` concise and operational. Put durable explanations in the
focused guides and preserve established implementation detail in the
[reference catalogs](reference/README.md).

## Source file size

Keep production Java files below 10,000 lines, enforced by `SourceFileSizeTest`. This is an upper
bound; prefer small classes with cohesive responsibilities. Extract feature state and behavior
behind explicit callbacks before a file approaches the limit. See [window coordinators](subsystems/window-coordinators.md).
