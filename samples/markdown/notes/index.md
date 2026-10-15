# Notes index

Wiki links: open *Markdown: Show Backlinks* on any note to see what links to it, follow a link with
Ctrl/Cmd-click, and type `[[` to complete a note name (then `#` for its headings). Rename a note in the
project tree and the links to it are offered for updating. *Markdown: Convert Wiki Link* turns one into
an ordinary Markdown link and back.

- [[ideas]] — a note beside this one.
- [[ideas|My ideas, with a label]].
- [[ideas#Later]] — a heading in that note.
- [[ideas#Later|A heading, with a label]].
- [[old-plan]] — found by name in a sub-folder, because it is the only note called that.
- [[archive/old-plan]] — the same note, with its folder written out.
- [[#Embeds]] — a heading in this file.

## Embeds

An embedded image: ![[../../images/sample.jpg]]

## Not wiki links

- In code: `[[ideas]]`.
- Glued to a word: x[[1]].
- In a table, the label bar is written `\|`:

| Note | Link |
| ---- | ---- |
| Ideas | [[ideas\|the ideas note]] |
