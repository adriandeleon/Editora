---
title: "HTML, CSS & JSON"
description: "The HTML, CSS and JSON language servers from vscode-langservers-extracted."
category: Languages
order: 4
---

HTML, CSS and JSON share one set of language servers. Editing HTML itself (tag completion, the preview, Emmet) is on the [HTML](/docs/html) page.

## Language servers

HTML, CSS and JSON files use **vscode-html-language-server**,
**vscode-css-language-server** and **vscode-json-language-server**, the servers
taken out of VS Code and published together in the npm package
**vscode-langservers-extracted**. They are Node.js programs, so they need
**Node.js**. The first HTML or CSS file a window opens asks **Turn on HTML code
intelligence?** (or CSS), with **Turn on** and **Not now**, like the
[Python offer](/docs/python#language-server).

**Install** on the banner, **Install…** on any of the three cards in Settings →
LSP, Doctor's **Install…** or **Install: Language Server…** installs
vscode-langservers-extracted **4.10.0**, the version Editora is tested with,
with npm into `plugins/lsp/web` under Editora's config folder rather than
globally. It also links the TypeScript libraries the HTML server needs for the
DOM and standard library in `<script>`. With the three commands left blank,
Editora runs that copy first, then one on your `PATH`.
[Doctor](/docs/troubleshooting#doctor) checks the installed version against
4.10.0, and warns, with **Update**, about a copy that cannot load the
TypeScript libraries; the first HTML page a window opens offers the same
**Update** on a banner.

The three servers run on the Node.js chosen on the **Node.js** card under
**Settings → Build Tools**, else `node` on your `PATH`. A project's `.nvmrc` or
`.node-version` does not change them; it picks only the TypeScript server's
Node.js. When a Node.js is chosen there, Install uses the npm beside it.

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

What the HTML server adds, its settings on the HTML card (wrap column,
attribute wrapping, content kept as written, checking embedded scripts and
styles), the `html` object a project can commit, and what
it does not do (markup validation, Emmet) are on the
[HTML](/docs/html#the-html-language-server) page. The quotes an attribute gets
after its `=` are chosen in Settings → Editor, not on the card (see
[Attribute quotes](/docs/html#editing-tags)). The HTML and CSS servers also
draw a small swatch before each colour.
