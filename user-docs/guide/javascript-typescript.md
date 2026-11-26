---
title: "JavaScript & TypeScript"
description: "The TypeScript server, ESLint and formatting, running Node.js and Deno scripts, Jest, Vitest, Deno and node:test tests, and debugging Node.js, Deno and browser code."
category: Languages
order: 11
---

JavaScript and TypeScript get code intelligence from `typescript-language-server`, run under Node.js (or Deno) from the gutter, run tests with Jest, Vitest, Deno or `node --test`, and debug with VS Code's js-debug, in Node.js or in a browser. What every language shares is on the [language servers](/docs/lsp), [running](/docs/run-debug) and [debugging](/docs/debugging) pages; this page is what is particular to JavaScript and TypeScript.

## Language server

JavaScript and TypeScript files use **typescript-language-server**, which runs
TypeScript's own `tsserver`. **Install…** on its card (or *Install: TypeScript
/ JavaScript (Node.js) Language Support*, `install.jsSupport`) installs
typescript-language-server **5.3.0** and TypeScript **5.9.3** with npm into
`plugins/lsp/typescript` under Editora's config folder. Nothing is installed
globally, so your own `tsc` stays as it is. With the server's command left
blank, Editora runs that copy. The server runs on the same Node.js as
[Run](#running).

**Why TypeScript 5.9.** TypeScript 6 leaves out the installed `@types`
packages, such as `@types/node`, unless a tsconfig lists them. Node.js code
then shows false errors such as *Cannot find name 'process'*, and plain
JavaScript gets no types for Node's APIs. TypeScript 7 is a new compiler
written in Go and has no `tsserver`, so typescript-language-server cannot run
on it. TypeScript 5.9 has neither problem.

**Which TypeScript runs**, in this order:

1. The folder's own `node_modules/typescript`, in a
   [trusted folder](/docs/workspace#trusted-folders).
2. Editora's own TypeScript 5.9.3, when it is installed, even for a server
   you installed yourself or a command you set.
3. The TypeScript installed beside the server.

The card's **TypeScript** line shows the one in use, such as *Editora's own,
…*. While a JavaScript or TypeScript file is active, the status bar's LSP
segment names it too, for example **LSP: TypeScript 5.9.3**.

When the only TypeScript found is 6 or newer, the card's TypeScript line and
[Doctor](/docs/troubleshooting#typescript-features-are-off-or-show-false-errors)
warn about it: *TypeScript 6.0.3: leaves out @types packages*, or *TypeScript
7.0.2 has no tsserver*. With no TypeScript that has a `tsserver` (only a
TypeScript 7, or none at all), Editora does not start the server. The status
bar says so, the fix first: *Install TypeScript 5.9 (Settings → LSP): TypeScript
7.0.2 has no tsserver*. The file's install banner offers **Install**, which adds
Editora's own TypeScript 5.9.3 and typescript-language-server 5.3.0 to its own
folder with npm; your own TypeScript is not changed. The banner's **Not now**
and the install-prompts switch work as they do for a missing server. Editora
does not try again each time you open another tab.

**A folder's own TypeScript runs only once you trust the folder.** Many projects
have their own `node_modules/typescript`. That is code from the folder, so
Editora uses it only in a trusted folder, and the trust prompt asks about it
(see [the prompt](#a-folders-own-typescript-and-the-trust-prompt)). Until then,
Editora's own TypeScript is used, else the one beside the server. When the
folder's own TypeScript has another major version than the one running, the
status bar says so once, when the server is ready: *This folder has TypeScript
5.4.5, but Editora runs 5.9.3 here until the folder is trusted*, since results
can differ from the folder's own `tsc`. When the folder's own copy is the only
one, the server waits until you run *Project: Trust This Project's Settings*
(`lsp.trustProjectSettings`). Without a project, the status bar says that only
a project can be trusted, and points to **Install…**. On a remote host, the
server still picks its TypeScript itself.

**Type definitions are not downloaded.** Left to itself, the server downloads
`@types` packages from the npm registry for the libraries your JavaScript uses.
Editora turns that off. To turn it on, use **Download type definitions
automatically** on the TypeScript / JavaScript card in **Settings → LSP**. When
a folder has no Node.js types, the status bar suggests
`npm install --save-dev @types/node`, so hover and completion know Node's APIs.
The hint replaces the server's ready line and stays in the status bar.

**What it adds:**

- **Organize Imports** (`lsp.organizeImports`) sorts the imports and removes
  unused ones. On a line with a problem, the code-action menu also offers
  **Fix all**, **Add all missing imports** and **Remove unused**, and quick
  fixes follow the file's own indentation.
- **Imports follow moved files.** Moving or renaming a `.ts` or `.js` file in
  the Project tree updates the imports that name it, in open and closed files,
  and saves them. A renamed folder does the same, unless a Java server serves
  it.
- **Completion details**: the documentation pane beside a completion shows
  the signature, such as the method's parameters or where an auto-import
  comes from.
- **Inlay hints** for parameter names, return types and enum values, when
  [inlay hints](/docs/lsp#reading-the-code) are on.
- **Code lenses**: a references count on each function, when code lenses are
  on.

A `tsserver` that dies restarts the server, within the
[crash cap](/docs/lsp#while-the-server-works), and the status bar says it stopped
unexpectedly. JavaScript and TypeScript files keep their server up to
**50,000 lines**, even when the **Turn the language server off above** limit is set lower.

**Workspace roots.** Without a project open, a file's server root is the
nearest folder with a `tsconfig.json` or `jsconfig.json`, then one with a
`package.json`, then the Git repository. A monorepo still gets **one server**:
the root widens to the outermost folder above that is a workspace's root, one
with a `package.json` that lists `"workspaces"`, a `pnpm-workspace.yaml`, or a
`tsconfig.json` with `"references"`. It never widens to your home folder or
above, and never outside an open project. Scripts with none of these above
them share one server, wherever they are, instead of starting one each.

### A folder's own TypeScript and the trust prompt

When a folder brings nothing to trust but its own TypeScript, the trust prompt
asks about that alone: **Run the folder *name*'s own TypeScript?** It says that,
once trusted, the JavaScript and TypeScript features run that copy instead of
Editora's, and that its code runs with your privileges. Each copy is listed on
one line, for example *TypeScript 5.9.3 in node_modules/typescript, run by the
TypeScript / JavaScript server*. When the folder also has an
`.editora/settings.json` with settings to trust, the prompt is the usual
**Use the settings of the folder *name*?**, with the TypeScript lines in its
list.

The Astro server loads a TypeScript too. Its line (*… loaded by the Astro
server*) appears only when the folder has an `astro.config.*` or an `.astro`
file is open. Trust is remembered for the folder and the folders inside it;
revoke it in Settings → Advanced, under Trusted folders.

### ESLint

A project's own **ESLint** checks JavaScript and TypeScript files (`.js`,
`.jsx`, `.mjs`, `.cjs`, `.ts`, `.tsx`, `.mts` and `.cts`) when the project has an ESLint
configuration (`eslint.config.*`, an `.eslintrc*` file, or an `eslintConfig`
key in `package.json`) and ESLint installed in `node_modules`. It checks a
file when its tab is shown, 2 s after you stop typing, and on save. Its
problems join the TypeScript server's in the squiggles, the problem counter and
the Problems window, with **eslint** as their source. ESLint runs from its
configuration's folder, on the project's Node.js, and only from the project's
`node_modules`: a global or `PATH` copy is never used.

- **Code Actions** lists each problem's fixes and suggestions, such as *Fix
  this no-var problem*, and *Fix all auto-fixable ESLint problems*. They show
  while the TypeScript server runs; the lightbulb does not light for a line
  with only ESLint problems.
- **ESLint: Fix All Problems** (`eslint.fixAll`) applies every automatic fix
  to the active file. The status bar says how many problems are left. A fix
  from a check older than your last edit is refused with *The file changed
  since ESLint checked it*; try again a moment later.

ESLint loads the project's configuration and plugins, which are code, so it
runs only in a [trusted folder](/docs/workspace#trusted-folders). In an
untrusted one, the first JavaScript or TypeScript file of the project says so
once per window, with **Trust Folder** and **Not now**. A project with a
configuration but no ESLint installed stays quiet; Doctor's **ESLint** row
says *configuration found, but ESLint is not installed*.

ESLint's problems are shown only while **Enable LSP (language servers)** is on.
**ESLint: Fix All Problems**, fixes on save and Prettier or Biome work with it
off. To stop ESLint, turn off **Check with ESLint** (see
[the settings](#eslint-and-formatting-settings)).

### Formatting JavaScript and TypeScript

**Format Document** (`lsp.formatDocument`) on a JavaScript or TypeScript file
uses the **Formatter** setting:

- **Automatic** (the default): the project's **Prettier** when the project uses
  it (a Prettier configuration file, a `prettier` key in `package.json`, or
  Prettier among its dependencies) and has it installed; else its **Biome**
  when it has a `biome.json` or `biome.jsonc` and Biome installed; else the
  TypeScript server.
- **Prettier** or **Biome**: that tool, when it is installed in the project,
  whatever the configuration says.
- **TypeScript server**: always the server.

Prettier and Biome run from the project's `node_modules` on the project's
Node.js, and only in a trusted folder. With **Automatic**, an untrusted folder
or a missing tool leaves the file to the server. With **Prettier** or
**Biome**, a tool that cannot run shows why in the status bar and leaves the
text as it is. The result is one undoable edit, and the status bar names the
tool, for example *Formatted with Prettier*. When the tool fails, the status
bar shows its first error line and the text stays as it was.

With a selection, Format Document gives Prettier the selection's range. Biome,
or no tool, leaves the range to the server.

### Actions on save

Three switches tidy a JavaScript or TypeScript file after you save it, in this
order: **Organize imports on save**, **Apply ESLint fixes on save** and
**Format on save**. They run after an explicit save, never after autosave.
When they change the file, Editora saves it again, and one undo takes all
their changes back. A step is skipped when you type in the file while it runs.
Organize imports needs the TypeScript server. All three are off by default.

### ESLint and formatting settings

The **ESLint and formatting** group on the TypeScript / JavaScript card in
**Settings → LSP** holds:

| Setting | Values | Default |
|---|---|---|
| **Check with ESLint** | On · Off | On |
| **Formatter** | Automatic · Prettier · Biome · TypeScript server | Automatic |
| **Organize imports on save** | On · Off | Off |
| **Apply ESLint fixes on save** | On · Off | Off |
| **Format on save** | On · Off | Off |

They apply to every window at once.

## Running

<a id="running-nodejs-scripts"></a>A `.js`, `.mjs`, `.cjs`, `.ts`, `.mts` or `.cts` file runs under Node.js, and
so does a script whose shebang names `node`. A type declaration file (`.d.ts`)
has no code, so it gets no ▶. JSX and TSX files need a bundler, so Run does not
start them.

**Which Node.js runs.** Run, run configurations, Debug, npm scripts and the
TypeScript language server all use the same Node.js. The first of these wins:

1. a run configuration's **Node.js** field;
2. the version that a `.nvmrc` or `.node-version` file names, when nvm has that
   version installed. The file is looked for from the script's folder up to the
   project root, so a monorepo's root `.nvmrc` counts for each of its packages
   (with no project open, the search stops at the package folder). nvm's
   versions are looked for in `$NVM_DIR` (or `$XDG_CONFIG_HOME/nvm`), then in
   `~/.nvm`. A version such as
   `20` or `20.11` picks the newest matching install; an alias such as `lts/*`
   is not followed;
3. the **Node.js** field in the **Node.js** card under **Settings → Build
   Tools**;
4. `node` on your `PATH` (the login shell's `PATH`, so the default of nvm, fnm
   or Volta counts).

When a `.nvmrc` asks for a version that nvm does not have, the run goes on with
the next Node.js in the list, and the Run console says so first, for example
*.nvmrc asks for Node.js 18, which nvm does not have: using the Node.js setting
instead*.

On a remote host, in WSL or in a container, Run, a test file's ▶ and Debug
choose in the same order, with that host's answers. The `.nvmrc` or
`.node-version` is read on the host, from the script's folder up to the project
(or the package folder, with no project). The version comes from the host's
nvm (`$NVM_DIR`, else `$XDG_CONFIG_HOME/nvm`, then `~/.nvm` there). Step 3 is
the **Node.js** set for that host in Settings → Remote → This Host, and step 4
the host's `node`. A version the host's nvm does not have, or an alias such as
`lts/*`, falls back to those two, and the Run console says so.

Editora's other npm language servers (HTML, CSS, JSON, YAML, Bash, Pyright…)
skip step 2: they run on the **Node.js** setting, else `node` on your `PATH`, so
an old Node.js a project pins cannot break them.

The tooltip of the Run window's runtime chip ends with where that Node.js was
chosen from, such as *Chosen from: .nvmrc, via nvm*, on a host too.
[Doctor](/docs/doctor) has a **Node.js** row that names the
Node.js in use and why it was chosen, for example *Node.js setting* or
*.nvmrc, via nvm*.

**Where it runs.** A script runs in its package folder: the nearest folder above
it with a `package.json`, never one inside `node_modules` and never your home
folder. Without a `package.json`, it runs in the script's own folder.

**How it runs.** Editora adds `--enable-source-maps`, so a stack trace from
compiled code names the original source, and sets `FORCE_COLOR=1`, so the Run
console shows the program's colours (unless you set `NO_COLOR`). A `node`
shebang keeps its flags, such as `#!/usr/bin/env -S node --no-warnings`, but
runs under the Node.js chosen above. A shebang that names `deno`, `bun`, `tsx`
or `ts-node` runs with that program, as written.

**TypeScript** runs the first way that works for the file:

1. **The project's tsx.** When `node_modules/tsx` is in the file's folder or a
   folder above it (up to the project root), Node.js runs the file with
   `--import tsx`. It is still the Node.js chosen above, with its flags.
2. **The compiled file.** When the nearest `tsconfig.json` has an `outDir` and
   the file it compiles to exists (`src/index.ts` → `dist/index.js`), that file
   runs, with source maps. The console's first line says so: *Running the
   compiled dist/index.js (built from src/index.ts)*. When the compiled file is
   older than its source, the line says to build again to run your latest
   changes.
3. **Plain Node.js** 22.18 or newer, which removes the types itself. On an
   older Node.js, Run says that the file needs 22.18 or newer (or tsx) instead
   of showing a syntax error. Plain Node.js cannot map an import of `./greet.js`
   to `greet.ts`, which is how a `"module": "NodeNext"` project writes its
   imports; when that import fails, a hint follows the error: add tsx to the
   project (`npm install -D tsx`), or build it so Run uses the compiled file.

A TypeScript test file's ▶ takes the same route.

**Test files.** The ▶ of a test file runs its tests, under Jest, Vitest, Deno
or `node --test`; see [Tests](#tests).

**Deno projects.** A file whose nearest project file above it is a `deno.json`
or `deno.jsonc` (before any `package.json`) runs under Deno, as
`deno run --allow-all`, from the folder of that `deno.json`. Every permission
is allowed because the Run console can't answer Deno's permission prompts. A
shebang that names a program, or a Node.js set in a run configuration, still
wins. Editora finds `deno` on your `PATH`, else in `$DENO_INSTALL/bin`, else in
`~/.deno/bin`; without it, Run says the file is in a Deno project and to
install Deno from deno.com.

While the program runs, the Run window names its runtime, such as
**Node.js 24.18.1** (see
[Which runtime a program runs under](/docs/run-debug#which-runtime-a-program-runs-under)).

## Tests

A test file is a `*.test.*` or `*.spec.*` file, or any script under a `test/`,
`tests/` or `__tests__/` folder. Its ▶ and Run File run its tests with the
package's own runner, and **Run Test at Caret** (`test.runAtCaret`) runs only
the test or `describe` block the caret is in. The test at the caret is found
from the `describe`, `context`, `suite`, `it` and `test` calls around it (with
`.only`, `.skip`, `.each` and the like); when a name on the way isn't a plain
string, such as a template with `${…}`, the whole file runs. The results fill
[Test Results](/docs/build-tools#test-runner), and **Rerun Failed** and
**Rerun This Test** run them again with the runner's own filter.

**Jest and Vitest.** A package uses Vitest when it has a `vitest.config.*`
file or lists `vitest` in its dependencies, else Jest when it has a
`jest.config.*` file, a `jest` or `jest-cli` dependency, or a `"jest"` key in
its `package.json`. The runner must be installed in `node_modules` (in the
package or a folder above it, as in a workspace); before `npm install`, the
file runs under `node --test`. Editora runs the runner's own script under the
Node.js chosen [as for Run](#running), from the package folder:
Jest with `--runInBand`, Vitest with `vitest run`, which runs once rather than
watching. A test at the caret is passed by name (`-t`).

**Deno.** A test file in a [Deno project](#running) (`test.ts`,
`*_test.ts`, `*.test.ts` and the like) runs under `deno test --allow-all`; a
test at the caret runs alone with `--filter`. A top-level `Deno.test(…)` or
`describe`, `it` or `test` call is the test at the caret, since Deno's filter
matches only top-level tests.

**node:test.** Without either runner, a test file runs under `node --test`.
The Run console shows node's own readable report, while Test Results reads a
TAP report of the same run. A test file that fails to load, for example on a
missing module, is a failed row named after the file, with the error and its
stack. A skipped or todo test shows as skipped under its own name, with or
without a reason. Rerun Failed on a file that failed to load runs the whole
file again.

## Debugging

**Debug** on a JavaScript or TypeScript file (`.js`, `.mjs`, `.cjs`, `.ts`,
`.mts`, `.cts`, or a `node` shebang script) runs it under vscode-js-debug,
started exactly as Run starts it: the same Node.js, flags, environment and
folder, and for TypeScript the project's tsx or the compiled file (see
[Node.js scripts](#running)). That Node.js is chosen as described in
[Which Node.js runs](#running): a configuration's own, then a project's
`.nvmrc` or `.node-version`, then the **Node.js** card under **Settings → Build
Tools**, then `node` on your `PATH`. The Debug console's first line is the command that runs. When that
is a compiled file older than its source, a red line says so: build the project
first to debug your latest changes. The **JavaScript** card under **Settings →
Debugging** has a link to the Node.js card, and its **Detected** line names the
vscode-js-debug version and the Node.js it runs on, such as *found (1.117.0 ·
Node.js v24.18.1)*.

- **TypeScript** files without tsx or a compiled file are debugged on plain
  Node.js 22.18 or newer. With an older Node.js, Debug says so. In a project
  that compiles its TypeScript, a breakpoint in a `.ts` source stops there when
  the compiled JavaScript runs, through its source maps.
- **JSX and TSX** files can't be run by Node.js. Debug the compiled JavaScript
  instead; a breakpoint in the `.jsx` or `.tsx` file still stops there through
  its source map.
- **Output and input.** All output reaches the Debug console in the order the
  program wrote it, with its colours, including `process.stdout.write` and
  `readline` prompts. Output to stderr is not shown in red, because it shares
  stdout's stream to keep that order. The program can read lines you type there
  (see
  [Program input in the Debug console](/docs/debugging#program-input-in-the-debug-console)).
- Node.js's own modules are skipped, so Step Into doesn't enter them. In the
  call stack, Node.js's own frames fold into one row, such as *… 7 frames of
  runtime or library code (click to show)*; click it to see them.
- Code run through `eval` opens read-only where it stops, and takes
  breakpoints for the session (see [Code without a file](/docs/debugging#code-without-a-file)).
  **Debug: Loaded Sources…** lists the program's scripts
  ([Loaded Sources and Modules](/docs/debugging#loaded-sources-and-modules)).
- [Lambda breakpoints](/docs/debugging#lambda-breakpoints) stop inside arrow functions and
  function expressions each time they run.
- Every breakpoint is placed before the program starts to run, so one on the
  program's first lines, or in a function it calls right away, stops on the
  first run too.
- A breakpoint condition that isn't valid JavaScript marks the breakpoint as
  rejected, and the console says why, once.
- Child processes and worker threads show all their threads. The status line
  names the file a thread stopped in, such as *Suspended in worker.js —
  parent.js*.
- **Debug Test at Caret** (`test.debugAtCaret`) runs the test at the caret
  under the package's runner (see [Tests](#tests)) and stops at its
  breakpoints: Jest in one process (`--runInBand`), Vitest with its workers
  attached as they start, or `node --test` with a name pattern.
- **Deno.** Debug on a file of a [Deno project](#running) runs it under
  `deno run` (`deno test` for a test, or Debug Test at Caret) with
  `--inspect-wait`, and vscode-js-debug attaches to it.
- **Node.js script** and **npm script** [run configurations](/docs/run-debug#run-configurations)
  can be debugged, started the way Run starts them.

**Electron apps.** In a package whose dependencies name `electron`, with
Electron installed in `node_modules`, Debug on the app's main file (the
package's `main`) or on any file that loads `electron` (a window module, a
preload script) debugs the whole app, as VS Code's *Electron: Main* and
*Electron: Renderer* configurations do together: the main process under the
debugger, and the app's windows attached to, in a second session. Breakpoints
stop in the main process's files and in the pages' scripts alike, and **Stop**
ends both sessions and the app. The arguments set by **File: Run with
Arguments…** go to Electron. *Run File* on the same files starts the app, as
`npm start` (`electron .`) does. Electron downloads its program the first time
it starts, not on `npm install`; until then Editora says so, and
`npx install-electron` in the app's folder (or one `npm start`) fetches it.

**Attach to a running program.** Start Node.js with `--inspect` (for example
`node --inspect server.js`), then run **Debug: Attach to Running Program…**
(`debug.attach`) on a JavaScript or TypeScript file. It asks for the
inspector's `host:port`, such as `localhost:9229`. Once attached, the console
names the address, such as *Attached to 127.0.0.1:9229.* A program started with `--inspect-brk` waits
before its first line; attaching does not start it, so press Continue. The
list of earlier addresses is for a JVM only: on a JavaScript or TypeScript
file, Attach and **Debug: Reattach** both ask for the inspector's address.
[Attach to Process](/docs/debugging#attach-to-process) lists the Node.js programs started with
`--inspect` instead, so you don't need the address.

vscode-js-debug itself needs Node.js 18 or newer to run. An older Node.js
chosen for a project still runs the program, while vscode-js-debug runs on the
newest Node.js 18 or newer that nvm has, else `node` on your `PATH`. When
vscode-js-debug can't start, or no Node.js new enough is found, Debug says so
instead of stopping without a word. **Install…** on the JavaScript card
installs only vscode-js-debug v1.117.0, a version tested with Editora.

## Debugging in a browser

**Debug: Debug in Browser…** (`debug.browser`, in **Run ▸ Launch and Attach**)
opens a web page in Chrome, Edge or another Chromium browser (Chromium, Brave,
Vivaldi) under vscode-js-debug, or in Firefox under vscode-firefox-debug, as VS
Code does. Breakpoints in the page's
scripts stop it, with its variables, call stack, console output, stepping and
evaluation as for a Node.js program, and through source maps a breakpoint in
your TypeScript or framework source stops too. The list offers the HTML file in
the editor (*This page: …*), the pages you debugged before, and **Another
page…**, which asks for a URL, such as your dev server's `localhost:5173`.

**Debug: Start / Continue** (`debug.start`) on an HTML file serves its folder the way the
[live preview](/docs/html#live-preview) does and opens the page in the
browser. An HTML file takes breakpoints in its inline scripts.

**Debug: Attach to Browser…** (`debug.attachBrowser`, also in **Run ▸ Launch
and Attach**), or **Debug: Attach to Running Program…** on an HTML file,
attaches to a Chrome or Edge started with `--remote-debugging-port=9222`; it
asks for the port. With Firefox chosen, it attaches to a Firefox started with
`-start-debugger-server 6000`, whose `about:config` allows remote debugging
(`devtools.debugger.remote-enabled` and `devtools.chrome.enabled` on,
`devtools.debugger.prompt-connection` off). A
[launch.json](/docs/debugging#launchjson-configurations) `chrome`, `msedge` or `firefox`
configuration launches or attaches as it does in VS Code.

The browser runs with a profile of Editora's own, kept between sessions, so a
page you signed in to stays signed in and your everyday profile is left alone.
Stop closes the browser. **Debug web pages in**, on the JavaScript card of
**Settings → Debugging**, chooses the browser; **Automatic** takes the HTML
preview's browser when it is a Chromium browser, else the first of Chrome, Edge,
Chromium, Brave and Vivaldi that is installed, and Firefox when it is the only
one. When the chosen browser isn't found, another one is used and the status
bar says so. Safari can't be debugged this way.

**Firefox** needs its debugger, vscode-firefox-debug (the adapter of VS Code's
*Debugger for Firefox*), which runs on Node.js: the **Firefox** card of
**Settings → Debugging** finds it as set there, else in Editora's own install,
else in VS Code's extension, and its **Install…** downloads version 2.15.0.
Firefox runs with a profile of Editora's own too, kept between sessions. A
`debugger;` statement stops in Firefox as it does in Chrome; its exception
breakpoints are *All Exceptions*, *Uncaught Exceptions* and *Debugger
Statements*, the last on by default.

The browser has 30 seconds to start; a launch.json configuration's own
`timeout` sets another limit. When the debugger gives up on the browser, or the
browser exits while starting, the status bar says so and the Debug console
shows the debugger's own error.
