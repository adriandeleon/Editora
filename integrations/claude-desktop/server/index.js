#!/usr/bin/env node
// Claude Desktop Extension entry point for Editora.
//
// Editora is installed on the user's machine, not bundled here, so this wrapper starts its own stdio MCP relay,
// `<editora command> mcp`, and passes standard input and output straight through. The relay finds the running
// editor itself (its endpoint file), so the extension needs no port or token and survives editor restarts.
// Nothing but JSON-RPC may go to standard output; messages for the user go to standard error.
"use strict";

const { spawn } = require("node:child_process");

const command = (process.env.EDITORA_COMMAND || "").trim() || "editora";

const child = spawn(command, ["mcp"], {
  stdio: ["pipe", "pipe", "inherit"],
  windowsHide: true,
  // A command with spaces in its path (C:\Program Files\...) is one argument: never a shell.
  shell: false,
});

process.stdin.pipe(child.stdin);
child.stdout.pipe(process.stdout);

child.on("error", (err) => {
  const why =
    err && err.code === "ENOENT"
      ? `Editora's command "${command}" was not found. Set the Editora command in this extension's settings ` +
        "to the full path Editora shows under Settings > MCP Server > Copy Connect Command."
      : `Could not start "${command} mcp": ${err && err.message ? err.message : err}`;
  process.stderr.write(`editora extension: ${why}\n`);
  process.exit(1);
});

child.on("exit", (code, signal) => {
  process.exit(code === null ? (signal ? 1 : 0) : code);
});

// Claude Desktop closing the extension closes standard input: let the relay end with it.
process.stdin.on("end", () => child.stdin.end());
for (const sig of ["SIGTERM", "SIGINT"]) {
  process.on(sig, () => {
    child.kill(sig);
  });
}
