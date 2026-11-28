# Editora for Claude Desktop

A [Claude Desktop Extension](https://github.com/modelcontextprotocol/mcpb) (`.mcpb`) that connects Claude Desktop
to the running Editora through its MCP server. Claude can then see what you have open (unsaved text included),
make edits you can undo or approve first, and read diagnostics, search results, review comments and Git status.

Editora is not bundled: the extension runs a small Node.js wrapper (`server/index.js`) that starts your installed
Editora's `editora mcp` and passes the MCP messages through. `editora mcp` finds the running editor by itself, so
nothing needs setting up again after a restart or a new token.

## Install

1. In Editora, turn on **Settings → MCP Server → Enable MCP server**.
2. Build the extension (below), or use a released `editora.mcpb`, and open it with Claude Desktop
   (double-click it, or drag it onto **Settings → Extensions**).
3. When Claude Desktop asks for the **Editora command**, keep `editora` if that runs Editora from a terminal.
   Otherwise give the full path that **Settings → MCP Server → Copy Connect Command** shows (on Windows,
   `editora-cli.exe`).

Editora must be running for its tools to answer. `editora mcp` needs an Editora release after 0.29.0.

## Build

From the repository root:

```bash
npx @anthropic-ai/mcpb validate integrations/claude-desktop
npx @anthropic-ai/mcpb pack integrations/claude-desktop editora.mcpb
```

`src/test/java/com/editora/mcp/ClaudeDesktopExtensionTest.java` checks the manifest and the wrapper.
