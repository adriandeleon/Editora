---
title: "CSS & JSON"
description: "The CSS and JSON language servers from vscode-langservers-extracted, installed with the HTML server."
category: Languages
order: 9
---

CSS and JSON files get code intelligence from the CSS and JSON language
servers. HTML, its language server and its live preview are on the
[HTML](/docs/html) page.

## Language servers

CSS and JSON files use **vscode-css-language-server** and
**vscode-json-language-server**, two of the servers taken out of VS Code and
published together, with the HTML server, in the npm package
**vscode-langservers-extracted**. They are Node.js programs, so they need
**Node.js**. If language servers are off, the first CSS file a window opens
asks **Turn on CSS code intelligence?**, with **Turn on** and **Not now**, like
the [Python offer](/docs/python#language-server).

One install gives the CSS, JSON and HTML servers. **Install** on the banner,
**Install…** on the CSS, JSON or HTML card in Settings → LSP, Doctor's
**Install…** or **Install: Language Server…** puts vscode-langservers-extracted
**4.10.0** into Editora's own folder; where it goes, which npm and Node.js it
uses, and how an older copy is updated are under
[Installing it](/docs/html#installing-it) on the HTML page. The CSS and JSON
servers run on the same Node.js as the HTML server: the one chosen on the
**Node.js** card under **Settings → Build Tools**, else `node` on your `PATH`,
never a project's `.nvmrc`.

The CSS server draws a small swatch before each colour.

**JSON with comments.** These files allow comments and trailing commas:
`tsconfig.json` and `jsconfig.json` (and their `tsconfig.*.json` variants),
`.eslintrc`, `.eslintrc.json`, `.babelrc`, `.babelrc.json`, `.swcrc`,
`deno.json`, `bun.lock`, any `.json` file in a `.vscode` folder, and `*.jsonc`
and `*.code-workspace` files. Other `.json` files are strict JSON.

**package.json and tsconfig.json** are checked and completed against schemas
bundled with Editora, so they work offline. The JSON server flags a misspelt
compiler option such as `"strictt"`, a wrong value such as `"type": "modul"`,
or `"scripts"` that is not an object, and completes and explains the keys and
values inside `compilerOptions`. The tsconfig schema covers `tsconfig.json`,
`tsconfig.*.json`, `tsconfig-*.json`, `jsconfig.json` and `jsconfig.*.json`.
Unknown top-level keys in `package.json` are allowed, since tools add their own.
