---
title: "Plugins"
group: "Customization & extensibility"
order: 2
beta: false
summary: "Extend the editor with commands, tool windows, and integrations \u2014 install from a built-in registry of 19 plugins, or write your own (Java or a simple manifest)."
---

Extend Editora without forking it. A plugin adds commands, keybindings, tool windows, editor-menu items, and status-bar segments — written in Java against a small exported API, or declared in a simple `plugin.json` manifest (with snippets and templates folders).

Install from the built-in **registry** of 19 plugins (*Browse plugins…*), or from a `.zip` on disk; downloads are sha-256-verified and the registry index is Ed25519-signed. See the [plugins catalog](/plugins) and the [docs](/docs/plugins).

Off by default — and plugins aren't sandboxed, so only install ones you trust.
