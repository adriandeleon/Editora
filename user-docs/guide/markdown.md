---
title: Markdown, Mermaid & preview
description: The live Markdown preview, formatting commands, wiki links, linting, LaTeX math, Mermaid, and export to PDF, HTML, Word or OpenDocument.
category: Editing
order: 3
---

Editora renders Markdown natively (no embedded browser) and gives Markdown files
a full set of editing aids on top. Files ending in `.md`, `.markdown`, `.mdown`,
`.mkd`, `.mkdn`, `.mdwn`, `.mdx`, `.Rmd` and `.qmd` all open as Markdown.

This page covers the [preview](#live-preview), [editing](#editing),
[tables](#tables), [formatting](#formatting),
[completion and navigation](#completion-and-navigation),
[links, wiki links and backlinks](#links-wiki-links-and-backlinks),
[linting](#linting-math-and-editing-aids), [Mermaid](#mermaid-diagrams),
[export](#export-and-print) and the [settings](#settings).

## Live preview

Markdown files get an IntelliJ-style 3-mode view from a floating control at the
top-right of the editor:

- **Editor**: just the source.
- **Split**: source on the left, preview on the right.
- **Preview**: the rendered page only.

![A Markdown file in Split mode: the source on the left and the rendered preview on the right with a heading, a task list, a table and a code block, and the Editor / Split / Preview control at the top right](/screenshots/docs/markdown-preview.png "Split mode: source on the left, the live preview on the right.")

A file opens in the view it was last shown in. A file with no remembered view,
and a new untitled Markdown buffer, opens in the default view set in
**Settings → Markdown** (*Open Markdown files in*, Editor by default).

In Split the two panes stay on the same block as you scroll either one (turn
this off with *View: Toggle Markdown Preview Scroll Sync*), and the block that
holds the caret is marked in the preview. Switching to Preview opens the page at
the place you were editing. The zoom and the position of the Split divider are
remembered per file.

The preview is built from CommonMark + GFM and aims to match GitHub: real
task-list checkboxes, inline-code pills, underlined headings, tables, and images
(local and remote, including SVG badges; see [Remote images](#remote-images)).
It updates live as you type, follows
the active theme or its own light/dark toggle, and remembers its mode per file.
An edit rebuilds only the block it touches, and a long document is built a
slice at a time.

Beyond GFM it also renders:

- **YAML front matter**, as a table at the top. *Hide front matter in the
  preview* leaves it out, of printouts too.
- **GitHub alerts**: `> [!NOTE]`, `[!TIP]`, `[!IMPORTANT]`, `[!WARNING]` and
  `[!CAUTION]` as coloured, titled boxes.
- **Footnotes**, numbered and linked both ways, including inline footnotes
  (`^[…]`).
- **Emoji shortcodes** (`:rocket:`), `==highlight==`, `^superscript^`,
  `~subscript~` and `++inserted++` text. A single tilde pair attached to the word
  before it is a subscript (`H~2~O`); standing apart, `~struck~` is
  strikethrough, as is `~~struck~~`.
- **Definition lists** and **heading anchors**.
- **LaTeX math** (see [LaTeX math](#latex-math)) and **Mermaid** diagrams (see
  [Mermaid diagrams](#mermaid-diagrams)).
- **A safe subset of HTML**: `<br>`, `<kbd>`, `<sub>` / `<sup>`, `<b>`, `<i>`,
  `<em>`, `<strong>`, `<code>`, `<a>`, `<img>`, `<details>` / `<summary>` and
  `align="center"`.

Code blocks show their language and scroll sideways rather than wrapping, as do
wide tables. Point at a fenced or indented code block and a copy button shows
in its top right corner, beside the language: one click copies the block's
code, and a check mark says it worked. A ` ```console ` or ` ```shell-session ` block is shown as a terminal
session: the prompt (`$ `, `# `, `user@host:~$ `, `(venv) $ `) is muted, the
command after it is highlighted as shell, and the lines it printed stay plain. A missing image shows its alt text, and a local image that changes
on disk is reloaded. An empty document says *Nothing to preview yet*. The
Structure tool window shows the document's `#`…`######` heading outline for quick
navigation.

A Markdown file longer than 2 million characters that has also opened in
[large-file mode](/docs/troubleshooting#performance) is too much for the preview to follow every
edit: a strip says so, and its **Refresh** button (`preview.refresh`) renders
it again. Up to 2 million characters the preview stays live, even in large-file
mode (a pasted `data:` image can make one line long enough for that).

The preview caps table cells and block and inline nesting. Text nested past the
limit shows as plain text. A table past the **table cap** (a 2.3 MB table
reaches it) is reported in the preview rather than leaving it blank.

### Working in the preview

Zoom with the `−` / `+` control or Ctrl+wheel. In Preview mode, **Space** /
**PageDown** and **Backspace** / **PageUp** scroll the page. The preview's
light/dark button steps from following the app theme to the other theme, then to
the app's own theme pinned, then back to following it.

A **link in the preview is clickable** and shows a hand cursor. `http`, `https`,
and `mailto` links open in your system browser or mail client; a relative or
`file:` link opens inside Editora when it points at a file in the project or the
document's folder; `#heading`, `other.md#heading`, reference and wiki links jump
to their target (see [Following links](#following-links)). Any other link is
refused with a status message. Right-clicking a link never opens it.

- **Task checkboxes** can be clicked to tick or untick the item in the source.
- **An image or diagram** opens enlarged on a click, with **View Larger** in the
  right-click menu, or with Enter or Space when it has the keyboard focus. Esc
  closes it.
- **Tab** moves through the links and pictures of the page.
- **Find** searches the rendered text when only the preview is showing, with the
  same case, whole-word and regex options as the editor.
- **Go to the source**: double-click beside the text of a block (or
  Ctrl/Cmd+double-click anywhere on it), or choose **Go to Source Line** from the
  right-click menu, and the editor's caret lands on the line the block came from.

While only the preview is showing, editing commands are refused with a status
message instead of changing the hidden source; switch to Editor or Split to edit.

### Selecting and copying

Text in the preview selects like text in a document: drag, double-click a word,
triple-click a block, Shift-click, Shift+arrows and Select All work across
paragraphs, headings, lists, tables, code blocks and alerts. Copy puts both plain
text and formatted HTML on the clipboard, so a paste into a word processor or a
mail keeps the formatting.

The right-click menu offers **Copy** (when there is a selection), **Copy All**,
**Select All**, **Go to Source Line**, **View Larger** (on a picture), **Copy
Link Address** (on a link), **Copy Code Block** (on a code block) and **Copy as
HTML**, then the exports and **Print…**.

| Action | Command |
| --- | --- |
| Copy the selection, or the whole rendered page as rich text | `preview.copy` |
| Copy the rendered page as HTML markup | `preview.copyHtml` |
| Copy the whole rendered text | `preview.copyAll` |
| Copy the code block or link the selection starts in | `preview.copyCode` / `preview.copyLink` |
| Show the picture or diagram the selection is in at full size | `preview.enlarge` |
| Move the editor's caret to the source of the selection | `preview.goToSource` |

### Preview to the side or in a tab

The preview can also live in a tab of its own:

| Action | Command |
| --- | --- |
| Open a preview tab in the next editor group | `view.markdownPreviewToSide` |
| Open a preview tab in this editor group | `view.markdownPreviewTab` |
| Lock the preview tab to its file, or let it follow again | `view.toggleMarkdownPreviewLock` |

A preview tab follows the active Markdown file (its header reads *Following*)
until you lock it to the file it shows (*Locked*). *Markdown: Open Preview to the
Side* is also in the **View** menu.

### Remote images

**Load remote images in the preview** in Settings → Markdown chooses what
happens to images from the web:

- **Always** loads them.
- **Ask for each document** (the default) fetches nothing when a document
  opens; a bar at the top of the preview says the document has remote images
  and offers **Load**. A README from a repository you just cloned can't fetch
  a tracking image the moment you preview it.
- **Never** shows their alt text and fetches nothing.

*Markdown: Set Remote Image Loading…* picks one of the three, and *View: Toggle
Remote Images in Markdown Preview* switches between Always and Never.

## The format bar

Select text in a Markdown file and a small format bar appears above the
selection with bold, italic, strikethrough, inline code, link, bulleted,
numbered and task lists, block quote, insert table, and a heading level picker.
The bar shows the current heading level and which styles are active, and its
tooltips name each command's key binding when the keymap has one. The same
actions are commands with Emacs `markdown-mode` chords:

![A Markdown file with three words selected and the format bar above them: bold, italic, strikethrough, code, link, bulleted, numbered and task lists, block quote, table, and a heading level picker reading Normal](/screenshots/docs/markdown-format-bar.png "Select text and the format bar appears above it.")

| Action | Command | Emacs key |
| --- | --- | --- |
| Bold / italic / strike / code | `markdown.bold` … | `C-c C-s b` / `i` / `s` / `c` |
| Bulleted list | `markdown.bulletList` | `C-c C-s u` |
| Insert link | `markdown.link` | `C-c C-a l` |
| Promote / demote heading | `markdown.headingPromote` / `Demote` | `C-c C-h p` / `d` |
| Open link under caret | `markdown.openLink` | `C-c C-o` |
| Reflow table | `markdown.reflowTable` | `C-c C-s t` |

With no selection, bold and italic toggle the word under the caret. List and
quote continuation happens automatically on Enter. Ctrl/Cmd-click opens a link.
The format bar can be turned off in **Settings → Markdown → Editing** (or with
`markdown.toggleFormatBar`).

You can also insert a **table of contents** (`markdown.toc`) and a **task list**
(`markdown.taskList`, `C-c C-s k`).

## Editing

### Editing commands

These are in the palette and in the **Markdown** submenu of the editor's
right-click menu. Markdown commands keep the caret where it was rather than
selecting what they changed, and with several carets they collapse to one and
say so.

| Action | Command | Emacs key |
| --- | --- | --- |
| Toggle a numbered list | `markdown.orderedList` | (palette) |
| Toggle a block quote | `markdown.blockquote` | (palette) |
| Wrap the lines in a fenced code block, or unwrap one | `markdown.codeFence` | (palette) |
| Tick or untick the task items | `markdown.toggleTaskDone` | (palette) |
| Number an ordered list in sequence again | `markdown.renumberList` | (palette) |
| Insert a footnote reference and its definition | `markdown.insertFootnote` | (palette) |
| Insert today's date (YYYY-MM-DD) | `markdown.insertDate` | (palette) |
| Insert a horizontal rule | `markdown.insertHorizontalRule` | (palette) |
| Pick an image, copy it to the assets folder and link it | `markdown.insertImage` | (palette) |
| Paste HTML or spreadsheet cells as Markdown | `markdown.pasteAsMarkdown` | (palette) |

A heading command on a list or quote line puts the heading behind the marker,
and promoting an H1 leaves it an H1.

### Lists, quotes and typing

- **Tab** / **Shift-Tab** indent and outdent a list item together with its
  children.
- **Enter** continues a list or quote; on an empty nested item it steps out one
  level, and on the empty last row of a table it leaves the table.
- An ordered list is renumbered after Enter, a moved line, or deleting or
  cutting and pasting whole items.
- `*` and `_` are typed in pairs like backticks, and typing `*`, `_` or `~` over a
  selection wraps it.
- With **Smart typography** on (Settings → Markdown, off by default), straight
  quotes, double hyphens and three dots become curly quotes, dashes and an
  ellipsis as you type prose.
- Lists and block quotes fold like other blocks.

The source itself is styled: setext headings, ticked and open task boxes,
strikethrough, quotes, list markers, table pipes, bare URLs, front matter and
inline HTML each get their own colour, and fenced code is highlighted by its
language. Shell fences can be marked `sh`, `bash`, `zsh`, `shell` or
`shellscript`.

### Images and pasting

Paste an image from the clipboard, or drop image files onto a saved Markdown
buffer, and the file is written into the document's assets folder with the
`![](…)` link inserted. The folder (`assets` by default, relative to the
document) and the file name (`pasted-image`) are set in **Settings → Markdown →
Editing**, which can also ask for the alt text on every paste. Without that, the
alt text is the file's name. An image dropped from under the document's own
folder is linked where it is instead of copied.

When a document is saved, an image pasted or dropped during this session that the
document no longer mentions is moved to the trash (*Move unused pasted images to
the trash on save*, on by default).

An ordinary paste turns copied HTML that holds a table, link, list or heading
into Markdown; *Markdown: Paste as Markdown* does the same for any HTML and for
spreadsheet cells, including merged cells, nested tables and definition lists.
When the clipboard holds both a picture and text, paste inserts the text.
Dragging a web link onto a document inserts a link.

### Word count and typewriter mode

For Markdown and plain-text files the status bar shows the **word count** and an
estimated **reading time** (at 200 words a minute), or those of the selection when
there is one. Turn it off with *Show the word count in the status bar* in
**Settings → Editor → Display** (or `view.toggleStatusBarWordCount`).

**Typewriter mode** (`view.toggleTypewriterMode`, same settings page, off by
default) keeps the line being typed in the middle of the window.

## Tables

Markdown's editing actions sit under a single **Markdown** submenu in the
editor's right-click menu, with the Table submenu nested inside it.

Beyond the format bar's reflow, the right-click **Table** submenu (and the
palette) edits the table under the cursor: **insert** a table
(`markdown.insertTable`), **add / delete** rows and columns
(`markdown.tableAddRow` / `tableAddColumn` / `tableDeleteRow` /
`tableDeleteColumn`), and Tab moves between cells with Enter adding a row. The
size picker of *Table: Insert…* opens at the caret and works from the keyboard.
Items that need a table are disabled when the caret is not in one, and a table
command that cannot apply says why.

![The Insert Table prompt over a Markdown file asking for a size in rows x columns, with 3x3 filled in, Cancel and OK buttons](/screenshots/docs/markdown-insert-table.png "Table: Insert… asks for the size and builds the table at the caret.")

| Action | Command | Emacs key |
| --- | --- | --- |
| Insert a row above | `markdown.tableAddRowAbove` | (palette) |
| Insert a column to the left | `markdown.tableAddColumnLeft` | (palette) |
| Move the row up / down | `markdown.tableMoveRowUp` / `tableMoveRowDown` | (palette) |
| Move the column left / right | `markdown.tableMoveColumnLeft` / `tableMoveColumnRight` | (palette) |
| Sort the rows by the caret's column | `markdown.tableSortAscending` / `tableSortDescending` | (palette) |
| Align the column | `markdown.tableAlignLeft` / `tableAlignCenter` / `tableAlignRight` | (palette) |
| Delete the whole table | `markdown.tableDelete` | (palette) |

Sorting compares numbers by value. Table keys leave code blocks alone, work in
tables inside a block quote, and align columns of CJK text.

Tables interoperate with CSV and spreadsheets:

- Convert **to and from CSV** (`markdown.tableToCsv` / `markdown.tableFromCsv`,
  delimiter auto-detected, RFC-4180 quoting).
- Export the table to a **CSV**, **Excel (`.xlsx`)**, or **ODF (`.ods`)** file
  (`markdown.tableExportCsv` / `tableExportExcel` / `tableExportOds`).

See [CSV & TSV](/docs/csv) for editing data files directly.

## Formatting

*Markdown: Format Document* (`markdown.formatDocument`) gives the document one
consistent form without changing what it says: list markers and numbering,
emphasis and strong style, ATX headings, rule style, blank lines around blocks,
aligned tables, and no trailing whitespace. Code, front matter and HTML are left
exactly as written, and formatting a formatted document changes nothing.
*Markdown: Format Selection* (`markdown.formatSelection`) does the same for the
selected lines, or the caret line.

*Markdown: Add/Update Heading Numbers* (`markdown.numberHeadings`) writes
section numbers (1., 1.1., …) into the headings or renumbers them; a single top
heading is the title and stays unnumbered. *Markdown: Remove Heading Numbers*
(`markdown.removeHeadingNumbers`) takes them off again.

*Markdown: Insert/Update Table of Contents* (`markdown.toc`) inserts a table of
contents at the caret, or regenerates the existing `<!-- toc -->` block. Its
links can be followed like any other. Settings → Markdown → Editing chooses the
heading levels it lists, whether it leaves the document title out, whether it is
numbered, and whether it is updated on every save.

## Completion and navigation

Completion in Markdown offers, besides dictionary words:

- **Relative paths** after `](`.
- **Heading anchors** after `#`, for this document or for another file
  (`guide.md#`).
- **Reference labels** after `[text][`.
- **Wiki notes** after `[[`, and their headings after `[[note#`.
- **Emoji** after `:`.
- **Languages** after an opening code fence, and **Mermaid keywords** inside a
  Mermaid fence.

Links in the completion's documentation popup can be followed.

The file breadcrumb shows the path of headings at the caret, and *Project: Go to
Markdown Heading…* (`project.gotoMarkdownHeading`) searches the headings of every
Markdown file in the project and jumps to the one you pick.

## Links, wiki links and backlinks

### Following links

*Markdown: Open Link* (`markdown.openLink`, `C-c C-o`), Ctrl/Cmd-click in the
source, and a click in the preview follow:

- Web and mail links, in the browser or mail client.
- Relative file links, inside Editora.
- `#heading` links within the document, `other.md#heading` links into another
  file, and reference links (`[text][label]`).
- Wiki links (below).

The jump is recorded, so *Go: Back* returns to the link. Link destinations with
parentheses or written in `<…>` form are read correctly.

| Action | Command | Emacs key |
| --- | --- | --- |
| Edit the link's text and address, or make the selection a link | `markdown.editLink` | (palette) |
| Replace the link by its text | `markdown.removeLink` | (palette) |
| Turn an inline link into a reference link | `markdown.linkToReference` | (palette) |
| Turn a reference link into an inline link | `markdown.linkToInline` | (palette) |
| Turn a wiki link into a Markdown link, and back | `markdown.convertWikiLink` | (palette) |

### Wiki links

`[[note]]`, `[[note|label]]`, `[[note#heading]]` and `![[image.png]]` are links,
in the preview and in every export. A name finds:

1. With a folder in it (`[[guides/setup]]`), that path, relative to the
   document's folder or else the project root.
2. Otherwise the file of that name beside the document, or else the only file of
   that name in the project. Two candidates make the link ambiguous, and it
   leads nowhere.

Names ignore case, and a Markdown file's extension is optional. Wiki links can
be followed from the source and the preview, complete after `[[`, are rewritten
when a note is renamed, and are checked by lint (`LINK003`). Turn them off with
**Wiki links** in Settings → Markdown (or *View: Toggle Markdown Wiki Links*).

### Backlinks

The **Backlinks** tool window (`markdown.showBacklinks`, or *Tool Window:
Backlinks*) lists every link in the project's Markdown files that points at the
active file, grouped by file.

![ideas.md open with the Backlinks tool window below: 6 links to this file, grouped under archive/old-plan.md and index.md, each with its line number and the link text](/screenshots/docs/markdown-backlinks.png "The Backlinks window lists every note that links to the open one.")

### When a file is renamed or moved

Renaming or moving a file in the project offers to update the Markdown links that
point to it, and a moved document's own relative links. Inline and reference
links, HTML `src` / `href` paths and wiki links are all rewritten. *Update links
when a file is renamed or moved* in Settings → Markdown chooses **Ask each time**
(the default), **Always** or **Never**.

Editora keeps an index of the project's links while it is open, so the update
does not re-read every file. A file that is not open gets a Local History entry
(*Before link update*) before it is changed, and a file open in another window is changed through that
window, as one undo step.

## Linting, math, and editing aids

- **Linting**: a broad slice of the markdownlint rule set plus three rules of
  Editora's own shows as inline squiggles with hover messages, **scrollbar and
  minimap stripes**, an issue count in the status bar, and a **Markdown Lint**
  tool window. **Auto-fix** (`markdownLint.fix`) corrects the safely-mechanical
  issues in one undoable edit and reports how many it fixed and how many need a
  manual change. Turn individual rules off in *Settings → Markdown →
  Linting* or with
  `markdownLint.toggleRule`, use inline `<!-- markdownlint-disable -->` comments,
  or a project markdownlint config. On by default; toggle with *View: Toggle
  Markdown Lint*.
- **LaTeX math**: see [LaTeX math](#latex-math) below.
- **Image paste and drag-drop**: see [Images and pasting](#images-and-pasting).
- **Smart link paste**: paste a URL over a selection to wrap it as
  `[selection](url)`.
- **Table editing**: **Tab** / **Shift-Tab** move between pipe-table cells, and
  **Enter** on the last row adds a row, reflowing as you go.

![lint.md with squiggles under its deliberate problems and the Markdown Lint tool window below: lint.md 18 issues, then rows such as Ln 1:1 MD041 First line in a file should be a top-level heading and Ln 6:1 MD001 Heading levels should increment by one level at a time; the status bar counts 18 lint issues](/screenshots/docs/markdown-lint.png "Lint problems as squiggles, a count in the status bar, and the Markdown Lint window with the rule behind each one.")

### Lint rules

| Rule | Checks | Rule | Checks |
| --- | --- | --- | --- |
| MD001 | Heading levels increment by one | MD031 | Fenced code surrounded by blank lines |
| MD003 | Heading style | MD032 | Lists surrounded by blank lines ✎ |
| MD004 | Unordered list marker style ✎ | MD033 | No inline HTML (off) |
| MD005 | Consistent list item indentation | MD034 | No bare URLs or e-mail addresses |
| MD007 | Nested unordered list indentation | MD035 | Horizontal rule style ✎ |
| MD009 | Trailing whitespace ✎ | MD036 | No emphasis used as a heading |
| MD010 | Hard tabs ✎ | MD037 | No spaces inside emphasis markers ✎ |
| MD011 | No reversed link syntax ✎ | MD038 | No spaces inside code spans ✎ |
| MD012 | Multiple consecutive blank lines ✎ | MD039 | No spaces inside link text ✎ |
| MD013 | Line length (off) | MD040 | Fenced code should specify a language |
| MD018 | No space after heading marker ✎ | MD041 | First line should be a top-level heading |
| MD019 | Multiple spaces after heading marker ✎ | MD042 | No empty links |
| MD022 | Headings surrounded by blank lines | MD045 | Images have alternate text |
| MD023 | Headings start at line beginning ✎ | MD046 | Code block style |
| MD024 | No duplicate headings (among siblings) | MD047 | File should end with a single newline ✎ |
| MD025 | Single top-level heading | MD048 | Code fence style |
| MD026 | No trailing punctuation in heading ✎ | MD049 | Emphasis style ✎ |
| MD029 | Ordered list numbering | MD050 | Strong style ✎ |
| MD030 | Spaces after list markers | MD051 | Link fragments match a heading |
| LINK001 | A relative link or image's file exists | MD052 | Reference links have a definition |
| LINK002 | A link's heading exists in the other file | MD053 | Reference definitions are used ✎ |
| LINK003 | A wiki link leads to a note | MD055 | Table pipe style |
| | | MD056 | Table column count |
| | | MD058 | Tables surrounded by blank lines ✎ |

✎ marks the rules auto-fix can correct. MD013 and MD033 are off until you enable
them. The `LINK` rules are Editora's own. A front-matter `title:` counts as the
document's top-level heading, as in markdownlint. Lint, and every fix, leave
code (fenced, indented, or nested in lists and quotes), HTML blocks and comments,
and YAML or TOML front matter exactly as written.

Each rule has a **severity** (Warning by default, or Error or Hint), set beside
it in Settings → Markdown → Linting or with **Markdown Lint: Set Rule
Severity…** (`markdownLint.setRuleSeverity`). **Fix lint issues on save** applies the safe
fixes whenever a Markdown file is saved.

### Acting on an issue

| Action | Command | Emacs key |
| --- | --- | --- |
| Move to the next / previous issue | `markdownLint.next` / `markdownLint.previous` | (palette) |
| Fix the issue at the caret | `markdownLint.fixAtCaret` | (palette) |
| Fix the issues in the selected lines | `markdownLint.fixSelection` | (palette) |
| Disable the rule for this line (a `disable-next-line` comment) | `markdownLint.disableLine` | (palette) |
| Disable the rule for this file (a `disable-file` comment) | `markdownLint.disableFile` | (palette) |
| Disable the rule in the project's markdownlint config | `markdownLint.disableProject` | (palette) |
| Turn the rule off in Settings | `markdownLint.disableRule` | (palette) |
| Open the rule's markdownlint documentation | `markdownLint.ruleHelp` | (palette) |
| Lint every Markdown file of the project | `markdownLint.lintProject` | (palette) |
| Re-lint the active file | `markdownLint.refresh` | (palette) |

The same actions are in the editor's right-click menu on an issue and in the
**Markdown Lint** tool window (`tool.markdownLint`), which follows the active tab,
shows the file name, has a **Fix** button, and offers **Copy** on each row's
menu. **Most Severe First** (`markdownLint.toggleBySeverity`) lists errors
before warnings and hints. The `Markdown Lint: … Selected Issue` commands
(Fix, Copy, Disable for its line, file or project, Open Rule Documentation) act
on the row selected there. *Lint Project* lists its results grouped by file. *Disable for This
Project* offers to create a config when the project has none, and edits JSON,
YAML and `.markdownlint-cli2` configs alike. With the minimap showing, the
scrollbar stripe steps aside, since the minimap carries the same marks.

### Project configuration

The nearest `.markdownlint.jsonc`, `.markdownlint.json`, `.markdownlint.yaml`,
`.markdownlint.yml`, `.markdownlint-cli2.jsonc` or `.markdownlint-cli2.yaml`,
searched up to the project or Git root, is read as markdownlint reads it:
comments, YAML, rule aliases and tags, `extends`, and the options of the rules
Editora implements. A rule the project switches on wins over the global "off",
and the Linting card in Settings says when a project config changes the rule
list. Open files are re-linted when a config file changes; outside a project the
change is noticed when the window regains focus.

Inline comments `markdownlint-disable`, `enable`, `disable-line`,
`disable-next-line`, `disable-file`, `enable-file`, `capture`, `restore` and
`configure-file` are honoured, naming a rule by its code or its alias.

### LaTeX math

Inline `$…$` and display `$$…$$` math render in the preview (and in every export)
via JLaTeXMath, with GitHub-style delimiter rules so prose dollar amounts and
shell variables such as `$HOME/bin:$PATH` are left alone. ```` ```math ````
fences and `\(…\)` / `\[…\]` delimiters work too, a display block may span
several lines, and math inside code is never taken for a formula. Formulas are
drawn for the preview's own light or dark theme. **On by default**; toggle it in
**Settings → Markdown → Preview & PDF** (*Render LaTeX math*) or with *View:
Toggle Math Rendering* (`view.toggleMath`).

## Mermaid diagrams

Mermaid renders inline. A fenced ` ```mermaid ` block in Markdown becomes a
diagram in the preview, and standalone `.mmd` files get the same 3-mode preview.
Inside a Mermaid fence, completion offers Mermaid keywords, and diagrams are
drawn for the preview's own light or dark theme.

![A Mermaid flowchart file in Split mode: the source on the left and the rendered flowchart on the right, with a decision node branching yes and no](/screenshots/features/mermaid.png "A Mermaid flowchart rendered in the preview.")

Mermaid is **on by default**, self-gating on detection, so it stays inert until
the `mmdc` (mermaid-cli) command is found. Install it with
`npm i -g @mermaid-js/mermaid-cli` (it needs Node.js) or the in-app
**Install…** button in **Settings → Mermaid** (`install.mermaidSupport`). The
same page holds the toggle and the two tool paths: a blank `mmdc` path resolves
`mmdc` on your `PATH`, and a blank `maid` path (the optional linter) runs it
as `npx -y @probelabs/maid`. Install maid with `npm i -g @probelabs/maid`.
With that default, Editora finds maid by its files and never runs `npx` to ask,
since `npx` would download a missing package. It looks for a global install
(beside the Node.js set in Settings, `node` on your `PATH`, or your npm
prefix), a `maid` on your `PATH` that is that package, and `npx`'s own cache.
A `maid` path of your own is run to check it. Under each path a **Detected**
line says whether the tool is there, for example
*mmdc: found (/usr/local/bin/mmdc)* or
*maid: found (0.0.29 · /usr/local/lib/node_modules/@probelabs/maid)*. Without
maid, Mermaid files are not linted; diagrams still render. Editora keeps that
answer until a path changes, so after installing a tool in a terminal press
**Check again**.

`mmdc` drives a headless Chrome. If rendering fails with *"Could not find
Chrome"*, install it once with
`npx puppeteer browsers install chrome-headless-shell`; the in-app installer
does this for you.

Rendering uses `mmdc` and is cached per diagram; `.mmd` files get live linting
that underlines errors with precise line and column messages. Export a diagram
to SVG, PNG, or PDF with `mermaid.export`.

## HTML live preview

The live preview of HTML files, which opens a page in your browser and reloads
it as you edit, is described on the [HTML](/docs/html#live-preview) page.

## Export and print

Export code to a syntax-highlighted PDF, or the Markdown / Mermaid preview to a
richly formatted PDF with headings, lists, tables, code blocks, and images as
native vector text. In the palette these are *File: Export to PDF…* for the
source text and *File: Export Preview to PDF…* for the formatted document, with
*File: Print…* and *File: Print Rendered Preview…* beside them. You can also
export a Markdown file's preview to a **standalone `.html` file** with *File:
Export Preview to HTML…*, which opens the result in a new tab, or to **MS Word
(`.docx`)** and **OpenDocument Text (`.odt`)** with *File: Export Preview to
Word (.docx)…* and *File: Export Preview to OpenDocument (.odt)…*. All of them
are in the **File ▸ Export** menu. Headings, styling, lists, tables, code, math,
Mermaid diagrams, and images all carry over. Or print either, with a
page-by-page preview first (what you preview is what prints). PDFs and printouts
are always light-themed, and all output is generated off the UI thread.

The document's title, in the HTML page and the Word and OpenDocument files, is
the front matter's `title:`, else the first heading, else the file name. Alerts
are coloured and titled and definition lists indented in PDF, Word and
OpenDocument alike, and a wiki link is written as a link.

**The exported HTML page** is self-contained: it carries its stylesheet, embeds
the document's local images (*Embed local images in exported HTML*; images from
the web stay links), highlights code blocks (*Highlight code in exported HTML*),
draws math as images and Mermaid diagrams when the Mermaid CLI is available, and
gives every heading an anchor. It follows the reader's light or dark preference,
code highlighting included, and forbids scripts. Both settings are in
**Settings → Markdown → HTML export** and are on by default.

**Word documents** get real heading styles, numbered and bulleted lists, table
alignment, inline images, working `#heading` links and a document title.
**OpenDocument** files get table alignment too. In both, a soft line break is
written as a space, so paragraphs reflow.

A Markdown PDF or printout contains:

- **Tables** with bold, code and links kept inside cells, their column
  alignment, columns sized to their content, and the header row repeated on
  each page.
- **Task lists** with their checkboxes, and `~~strikethrough~~` struck through.
- **Highlight**, **superscript** and **subscript**, and **emoji**, drawn with a
  bundled monochrome emoji font (Noto Emoji).
- **Fenced code** in a monospaced font, syntax-coloured by its language, with
  every line printed however long the block is.
- **Images**, including badges inside a line of text, plus math and Mermaid
  diagrams at the size of the surrounding text rather than blown up to a page.
  An image that cannot be loaded prints its alt text.
- In the PDF, **clickable links** (web, mail, and `#heading` links within the
  document), **bookmarks** built from the headings, and a title, creator and
  date. On paper, a link shows its address.

Commands: `editor.exportPdf`, `preview.exportPdf`, `preview.exportHtml`,
`preview.exportDocx`, `preview.exportOdt`, `editor.print`, `preview.print`. Line
numbers, syntax highlighting, the page footer, and the PDF page size,
orientation and margins live in **Settings → Editor → Export & Print**. The
print preview, the Save dialog and what each setting applies to are described in
[Print & PDF export](/docs/print-pdf).

### Long documents

Printed Markdown fills each page. A long list, quote, code block, table or
paragraph starts on the current page and continues onto the next rather than
jumping to a fresh page, and a heading stays with the text that follows it.
Long inline code wraps instead of running off the page. A table splits between
rows and repeats its header.

The text stays vector rather than being sliced as an image, so it is crisp on
paper. Long runs are cut at whitespace and no word is broken.

A document that is mostly one very long list therefore prints on as many pages
as its text needs, at full size. Nothing is shrunk to fit except a single block,
such as an oversized image, that cannot be split.

## Settings

**Settings → Markdown** has four cards. Each setting is also a palette command.

| Setting | Default | Command |
| --- | --- | --- |
| **Editing** | | |
| Show format bar on text selection | On | `markdown.toggleFormatBar` |
| Folder for pasted images | assets | `markdown.setAssetsFolder` |
| File name for pasted images | pasted-image | `markdown.setImageName` |
| Ask for alt text when an image is pasted | Off | `view.toggleMarkdownImageAltPrompt` |
| Move unused pasted images to the trash on save | On | `view.toggleMarkdownTrashUnusedImages` |
| Table of contents levels | 1–6 | *Markdown: Set Table of Contents Top Level…* / *Deepest Level…* |
| Leave the document title out of the table of contents | Off | `view.toggleMarkdownTocSkipTitle` |
| Number the table of contents | Off | `view.toggleMarkdownTocOrdered` |
| Update the table of contents on save | Off | `view.toggleMarkdownTocOnSave` |
| Smart typography | Off | `view.toggleMarkdownSmartTypography` |
| Wiki links | On | `view.toggleMarkdownWikiLinks` |
| Update links when a file is renamed or moved | Ask each time | `markdown.setUpdateLinksOnRename` |
| **Preview & PDF** | | |
| Open Markdown files in | Editor | `markdown.setDefaultViewMode` |
| Preview font size (pixels; 0 is the built-in size) | 0 | `markdown.setPreviewFontSize` |
| Preview text width (pixels; 0 is the built-in width) | 0 | `markdown.setPreviewWidth` |
| Scroll the editor and the preview together | On | `view.toggleMarkdownScrollSync` |
| Hide front matter in the preview | Off | `view.toggleMarkdownFrontMatter` |
| Preview theme (Follows the app theme / Light / Dark) | Follows the app theme | `view.toggleMarkdownPreviewTheme` |
| Load remote images in the preview | Ask for each document | `markdown.setRemoteImagePolicy` |
| Render LaTeX math ($…$, $$…$$) | On | `view.toggleMath` |
| **HTML export** | | |
| Embed local images in exported HTML | On | `view.toggleHtmlExportEmbedImages` |
| Highlight code in exported HTML | On | `view.toggleHtmlExportHighlightCode` |
| **Linting** | | |
| Lint Markdown files | On | `view.toggleMarkdownLint` |
| Fix lint issues on save | Off | `view.toggleMarkdownLintFixOnSave` |
| Each rule: on or off, and its severity | See [Lint rules](#lint-rules) | `markdownLint.toggleRule` |

The pasted-image file name is given without an extension and may use `{name}`
(the document's name), `{date}` and `{time}`; a number is added when the name is
taken. **Settings → Editor → Display** holds *Show the word count in the status
bar* (on) and *Typewriter mode* (off), which apply to Markdown too.
