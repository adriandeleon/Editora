# Lorem Ipsum plugin

A real Editora plugin that generates lorem-ipsum placeholder text. It adds two command-palette commands:

| Command | Action |
| --- | --- |
| **Lorem Ipsum: Insert Paragraph** | Inserts a fresh paragraph at the caret. |
| **Lorem Ipsum: Replace Selection** | Replaces the selected text with a paragraph (or inserts at the caret if nothing is selected). |

Each paragraph is randomly assembled from the classic lorem word bank (`LoremIpsum`, a pure generator), so
repeated runs give varied text; the first paragraph opens with the canonical *"Lorem ipsum dolor sit
amet…"* line.

## Build & install

```sh
./build.sh    # → lorem-ipsum.jar, lorem-ipsum.zip, and its sha-256
```

Then either:
- **Settings → Plugins → Install from file…** → pick `lorem-ipsum.zip`, or
- copy `plugin.json` + `lorem-ipsum.jar` into `<configDir>/plugins/lorem-ipsum/`.

Enable it in **Settings → Plugins** and restart. Open the command palette (`M-x`) and run
*Lorem Ipsum: Insert Paragraph*.

## How it's built

- `LoremIpsum` — the pure text generator (no editor/JavaFX deps).
- `LoremIpsumPlugin` — implements `com.editora.plugin.Plugin`; registers the two commands and edits the
  active buffer via the `ActiveEditor` facade (`insertAtCaret` / `selectedText` / `replaceSelection`).

Compiled on a plain classpath against Editora's exported API and loaded by a child `URLClassLoader`, so the
same jar works in dev and in the packaged installers. See [`../../docs/plugins.md`](../../docs/plugins.md).
