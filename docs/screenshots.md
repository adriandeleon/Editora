# Screenshots

The website's screenshots, the README's hero and any picture for a bug report are taken from the real
window, headlessly, at twice the scene's size (DOCS-18, Pauta 6.2.0). Nothing is drawn by hand: after a UI
change, run the script again.

## The pieces

| Piece | Where | What it does |
| --- | --- | --- |
| Harness | `src/test/java/com/editora/ui/Shots.java` | Builds the window through `FxWindowFixture` (the production boot path the FX tests use) and runs a command script against it. Not a test; it never runs in the suite. |
| Script parser | `src/test/java/com/editora/ui/ShotScript.java` | Checks every line of a script before anything runs and fills in `${…}` values. |
| 2x helper | `FxTestSupport.writeShot` / `snapshotAtShotScale` / `renderAtShotScale` | Sets the window's render scale to 2 (so canvases such as the Project Map and the terminal rasterise at 2x) and snapshots with `Transform.scale(2, 2)`. The harness and the `shot()` helpers of `SplitViewFxTest`, `SplitPaneOverlaysFxTest` and `ColumnRulerPlacementFxTest` all use it. |
| Runner | `scripts/screenshots/Shoot.java` | A JDK source-launcher script: prepares a clean config folder, a scratch home and a workspace, applies the script's fixtures, and starts the harness once per script and theme. |
| Command scripts | `scripts/screenshots/**/*.txt` | One per batch of pictures: `docs/` (docs pages and the hero), `docs2/`–`docs9/` (later docs rounds, most of them debugger pages), `features/` (the features gallery) and `features/map/` (the Project Map spotlight). `sample.txt` is the smallest one. |

## Taking them

```sh
# once, and after any change to the app or the tests
./mvnw -o -q test-compile dependency:build-classpath \
    -Dmdep.outputFile=target/shots-classpath.txt -Dmdep.includeScope=test

java scripts/screenshots/Shoot.java docs/settings.txt          # one script, light and dark
java scripts/screenshots/Shoot.java --theme dark features      # a whole folder, dark only
java scripts/screenshots/Shoot.java --out ../editora-website/public/screenshots docs features
```

Run it with JDK 25 or newer from any directory; it finds the repository itself. The pictures go to
`target/screenshots/out/` unless `--out` says otherwise, the harness's stderr to
`target/screenshots/logs/<script>-<theme>.err`. A failed command prints `FAIL line N: …` and the script goes
on, so one missing button costs one picture; the run exits 1 when any command failed. `--verbose` prints every
command. Run one batch at a time: the scripts share the scratch home and the workspace.

To take a single picture in an FX test, set `-Deditora.test.shots=<dir>`: the three `shot()` helpers above write
their PNGs there, at 2x, and `ShotsFxTest` runs `sample.txt`.

## Where they go

Each script writes under `${out}` in the website's layout, so `--out <editora-website>/public/screenshots`
puts them in place:

| Output | Website path | Embedded by |
| --- | --- | --- |
| `docs/<name>.png`, `docs/<name>.dark.png` | `public/screenshots/docs/` | `![alt](/screenshots/docs/<name>.png "caption")` in a docs page; the dark twin is picked by theme |
| `features/<slug>.png` (+ `.dark`) | `public/screenshots/features/` | the features gallery |
| `map/<name>.png` (+ `.dark`) | `public/screenshots/map/` | the Project Map spotlight |
| `hero/editor.png` (+ `.dark`) | `public/screenshots/hero/` | the README and the home page hero (the website makes the `.jpg`/`.webp`) |

The harness writes lossless 2x PNGs. The website crops and palette-compresses them in its own build step
(about 3x smaller); that step belongs to the website repository.

## Writing a script

One command per line; `#` starts a comment. The ops are listed in `ShotScript.OPS`; the common ones:

| Op | Meaning |
| --- | --- |
| `set <key> <json>` | a settings.json value, before `start` (`set lspSupport false` — always say whether language servers run) |
| `size <w> <h>`, `start` | the window's size, then build and show it |
| `project <dir>`, `open <file> [line]` | open a project (in its own window) or a file at a line |
| `run <command.id>` | run a palette command |
| `type <text>`, `key <KEY> [S][C][M] [count]` | keys into the focused node (`focuseditor`, `focuslast`, `focussel <css>` first) |
| `click <button text>`, `clicksel <css> [n]`, `menu <name>`, `menuitem <text>`, `popitem <text>` | press things |
| `wait <ms>` | let that much of the FX thread's pulses go by (for an outside process, such as a language server) |
| `waitfor <css>` | until a visible node matches, up to 60 s |
| `settext <css> [text]`, `retext <regex> => <text>` | blank or rewrite text that would date the picture |
| `shot <file> [x y w h]` | the main window with its popups and dialogs composited in, optionally cropped (logical pixels) |
| `winshot <file>`, `winshotp <file>` | the topmost other window (Settings, a dialog), without or with its popups |

Values the runner fills in: `${workspace}`, `${out}`, `${suffix}` (`""` or `.dark`), `${config}`, `${home}`,
`${jdk}`, `${plugins}` (your `~/.editora/plugins`), `${javaDebugJar}`, `${fixtures}` (the script's folder) and
`${env:NAME}`. `ShotScriptTest` parses every shipped script, so an unknown op or variable fails the build.

Lines starting `#@` are fixtures the runner applies to the workspace before the script (`ShootTest` checks
them): `write <file> <text>`, `insert <file> <line> <text>`, `replace <file> <regex> => <text>`, `readonly <file>`,
`copy <fixture> <file>`, plus `trust none` (no trusted folders, for the trust question), `trust <folder>`,
`timeout <seconds>` (240), `http <port>` (the order server the HTTP Client shot calls) and `needs <tool>`
(printed: something the script cannot provide itself).

## What makes two runs alike

- **A clean config folder** per script and theme, trusting the workspace and every `samples/debug/*` and
  `samples/build-tools/*` project.
- **A fixed home.** The app's home is `target/screenshots/home/ada`, its user name `ada`, and the harness
  rewrites that folder's real path to `/home/ada` in every label and text field before each picture. Most of
  the UI already shows the home as `~`.
- **A fixed workspace.** A clone of this repository at `home/ada/workspace/Editora` (on a `master` branch,
  origin pointed at GitHub, so Git Log has history), reset to its commit before every script; the debug
  sample's sources get a fixed modification time. Delete the folder to take a newer commit.
- **A release version.** A copy of `build-info.properties` without `-SNAPSHOT` goes first on the class path,
  so the toolbar shows no snapshot badge.

### What is not portable

- **Paths drawn outside a label or a text field** (the terminal's canvas, the Run console's command line) still
  show the scratch folder. The old harness hid it by running the app in a Linux user and mount namespace
  (`unshare -rm`, the scratch tree bind-mounted over `/home`); that only works on Linux, so the runner does not
  do it. For those few shots, crop the path out or retake them by hand on Linux the same way.
- **Tools** a script `needs` (jdtls, Delve, CodeLLDB, netcoredbg, Node.js with js-debug, the demo programs
  the Attach to Process shot attaches to) come from your machine, and their paths can show in the picture.
  `docs4/attach.txt` needs its demo programs started by hand before the run.
- **`${jdk}`** is the JDK the runner runs on; a Settings or Doctor shot that shows it shows your path.
