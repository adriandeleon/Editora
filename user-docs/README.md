# User guide

This folder is the source of Editora's user guide: the pages people read on
[editora-project.dev/docs](https://editora-project.dev/docs) and, from **Help ▸ Documentation**, in the copy that
ships inside the app. It is written for the person using Editora. Contributor guides are in [`../docs/`](../docs/README.md).

| Folder | What it holds | On the website |
| --- | --- | --- |
| `guide/` | One Markdown page per feature area, with `title`, `description`, `category` and `order` frontmatter (the sidebar follows them) | `src/content/docs/` → `/docs/v-<version>/<slug>/` |
| `commands/` | Optional long-form notes for one command, named after its id (`view.toggleReadOnly.md`); shown as *Details* on that command's page | `src/content/command-docs/` → `/docs/v-<version>/commands/<id>/` |
| `screenshots/docs/` | The pictures the pages embed, each with its `.dark` twin | `public/screenshots/docs/` |

## Writing a page

- Link other pages with plain `/docs/<slug>` paths, never a version: the website's build adds this release's
  `/docs/v-<version>` prefix. `/commands`, `/keybindings`, `/features/…` and `/news` are website pages; the
  copy in the app has `/commands` and `/keybindings` and sends the rest to the website.
- Embed a picture as `![What it shows](/screenshots/docs/<name>.png "Caption")`; the dark twin
  `<name>.dark.png` is picked by theme. Take pictures with the screenshot harness, never by hand
  ([screenshots](../docs/screenshots.md)).
- Quote command ids in backticks (`` `edit.addCaretNextOccurrence` ``); the website's `check:docs` fails on an id
  the app does not have.
- Don't rename a heading the app links to (Settings' *Help for* links, the palette's per-command help).
- The Pauta conformance page is not here: the website generates it from `pauta.json`.

## How it reaches readers

The website mirrors these folders with `java scripts/SyncDocs.java <Editora checkout>` and commits the copy, like
its other generators; its `check:generated` holds the copy to the release it documents. The archived docs of
past releases stay in the website (`src/content/docs-archive/`).

The release workflow renders this folder with the website's own build (`node scripts/build-app-docs.mjs` there)
and packages the HTML into every installer; Help ▸ Documentation serves it from `127.0.0.1`
([building and packaging](../docs/building-and-packaging.md#bundled-user-guide)).
