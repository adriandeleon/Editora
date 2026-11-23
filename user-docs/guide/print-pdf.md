---
title: Print & PDF export
description: Print or export any file, a selection, a rendered preview, a CSV table, an image, or the Project Map, with a print preview and page settings.
category: Workspace
order: 7
---

Editora prints and exports to PDF in two forms: the **text** of a file, as you
see it in the editor, and the **rendered preview** of a file that has one
(Markdown, a diagram, a data tree, a CSV table). Printed pages and exported PDFs
are always light, whatever theme the editor uses.

![The print preview of a Java file: the first page with line numbers and wrapped lines, page navigation showing Page 1 of 5, and Print and Close buttons](/screenshots/features/export-pdf-print.png "The print preview, light whatever the editor theme.")

## Commands

| Action | Command | Default key |
| --- | --- | --- |
| Print the current file's text | `editor.print` | (palette) |
| Export the current file's text to PDF | `editor.exportPdf` | (palette) |
| Print the selected lines | `editor.printSelection` | (palette) |
| Export the selected lines to PDF | `editor.exportSelectionPdf` | (palette) |
| Print the rendered preview | `preview.print` | (palette) |
| Export the rendered preview to PDF | `preview.exportPdf` | (palette) |
| Cancel a running PDF export | `file.cancelPdfExport` | (palette) |
| Open the last exported file | `file.openLastExport` | (palette) |

The CUA keymap binds **Print…** to `Ctrl+P`.

**Print…** for the current file is in the **File** menu, and **Export to PDF…**
under **File ▸ Export** with the preview's export formats (Simple UI mode keeps
both directly in its File menu). Both are also on a tab's right-click menu and in the editor's
right-click menu. The editor's right-click menu also has **Print Selection…**
and **Export Selection to PDF…**.

In the palette the preview commands are *File: Print Rendered Preview…* and
*File: Export Preview to PDF…*. They are unavailable when the active
file has no preview that can be exported, when a JSON, YAML, TOML or XML file
does not parse, and for `.http` files. A preview's own right-click menu offers
the export too.

## What gets printed

- **Source text** prints monospaced with line numbers and syntax highlighting
  (both optional). A long line wraps at a space where there is one; the PDF marks
  a wrapped line with `↪`.
- **A selection** prints the selected lines, numbered as they are in the file.
- **Markdown** prints as a formatted document. See
  [Markdown export](/docs/markdown#export-and-print) for what it contains.
- **CSV and TSV** print as a table that splits between rows and repeats its
  header on every page. See [CSV & TSV](/docs/csv#print-and-export).
- **Image tabs** print and export the image. A wide image turns the PDF page to
  landscape, and a very wide one is tiled across pages.
- **Tree, timeline and summary previews** (JSON, YAML, TOML, XML, poms and the
  decoded config files) break pages between rows. An OpenAPI file outputs its
  documentation view when that is what the preview shows. A tree stops at 4,000
  rows and the status bar says so.
- **Mermaid, Graphviz, PlantUML and Typst** PDFs are produced by those tools,
  which choose the page size themselves.
- **The Project Map** prints and exports the whole map. See
  [Project Map](/docs/workspace#project-map).

A tab with no text, such as the PDF viewer or the hex viewer, has nothing to
print, and an empty document reports "Nothing to print".

## Print Preview

Printing opens **Print Preview** first, and what the preview shows is what
prints. The sheet is drawn at the paper's size with its margins, and the window
names the printer, the paper and the orientation in use.

- **Page Setup…** changes the paper, orientation and margins; the preview is
  laid out again for the new page. If the layout changes in the system print
  dialog, nothing is printed until you have seen the new preview and chosen
  **Print…** again.
- **Zoom**: Fit Page, Fit Width, or 50–400%, with `Ctrl` + mouse wheel and
  `Ctrl +` / `Ctrl −` / `Ctrl 0`.
- **Paging**: `Page Up` / `Page Down`, `Left` / `Right`, and `Home` / `End`, or
  type a page number into the page field.
- A page range chosen in the system print dialog is honoured.
- While the job runs the window shows "Printing page n of N…" with a **Cancel**
  button.

The preview blocks only the window it belongs to, and it keeps its size and zoom
for the rest of the session. Links in a printed document show their address,
since paper cannot be clicked.

With no printer installed, Print offers **Export to PDF…** in its place.

## Exporting to PDF

The Save dialog opens beside the document (or, for a file with no folder, in the
last export folder). It adds the `.pdf` extension when you leave it out and asks
before replacing an existing file. The same applies to the HTML, Word,
OpenDocument, spreadsheet and CSV export dialogs.

The status bar shows the page being written. A second export started while one
is running is queued, and the status bar says how many are ahead of it. *File:
Cancel PDF Export* stops the running export and any queued behind it. *File:
Open Last Exported File* opens the file the window exported last: a PDF or an
HTML page in a tab, a Word, OpenDocument or spreadsheet file in the application
your system opens it with.

An exported PDF carries:

- **Clickable links**: web and mail addresses, and `#heading` links inside the
  document.
- **Bookmarks** built from the document's headings, for the reader's outline
  panel.
- **Metadata**: a title, a creator and a date.

A PDF that is open in a tab reloads when an export replaces its file.

## Settings

**Settings → Editor → Export & Print** holds the page options. Printing takes
its paper, orientation and margins from Page Setup, so several of them apply to
PDF only.

| Setting | Applies to |
| --- | --- |
| **Include line numbers** | Source text, printed and exported |
| **Syntax highlighting** | Source text, printed and exported |
| **Page footer** (document name and "Page n of N") | Source text, Markdown and CSV tables, printed and exported |
| **PDF page size** (Letter or A4) | PDFs Editora lays out itself: source text, Markdown, CSV tables, images and tree previews |
| **PDF orientation** (Portrait or Landscape) | Source text, Markdown and CSV table PDFs |
| **PDF margins** (Normal, Narrow 0.5 in, Wide 1 in) | Source text, Markdown and CSV table PDFs |
| **PDF code font size** (7–12 pt) | Source text PDFs |

- The **PDF page size** of a fresh installation follows the region's paper:
  Letter where that is the everyday size, A4 elsewhere.
- Image and tree PDFs turn each page to fit the picture and keep their own
  margin, so orientation and margins do not apply to them, and they carry no
  footer.
- Mermaid, Graphviz, PlantUML and Typst PDFs are sized by their tools and ignore
  these settings.

The three checkboxes are also palette commands (`view.togglePdfLineNumbers`,
`view.togglePdfSyntaxHighlighting`, `view.togglePageFooter`), and
`editor.setPdfPageSize` picks the page size.
