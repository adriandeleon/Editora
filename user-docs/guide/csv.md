---
title: CSV & TSV
description: Rainbow columns, a field readout, and an editable CSV Grid preview with sort, filter, and export, plus align/shrink and Markdown-table interop.
category: Workspace
order: 7
---

`.csv` and `.tsv` files open with spreadsheet-style tooling on top of proper
CSV/TSV syntax highlighting.

![people.csv in the CSV Grid preview: a header row, six rows of names, departments and salaries, a filter field and the row and column count](/screenshots/features/csv.png "The CSV Grid preview of a small file.")

## In the editor

- **Rainbow columns**: each column is colored distinctly (cycling every eight),
  so rows line up at a glance. On by default; toggle in **Settings → Editor →
  CSV** or with *View: Toggle Rainbow CSV Columns*.
- **Field readout**: the status bar shows *Field N of M* for the caret's column.
- **Align / shrink**: *CSV: Align Columns* (`csv.align`) pads fields with spaces
  so the delimiters line up in a monospace editor; *CSV: Shrink Columns*
  (`csv.shrink`) removes that padding. Both are reversible and preserve quoted
  fields.

## The CSV Grid

The **CSV Grid** shows the active file as a spreadsheet. It isn't a tool
window: it's the file's preview, embedded in the editor with the same 3-mode
view (Editor / Split / Preview) as Markdown. Switch modes from the floating
control at the top right of the editor, or with `view.togglePreview` and
`view.toggleSplitPreview`.

- Content-fit column widths, a **filter box**, **column sort**, and
  inconsistent-row highlighting.
- **Editable** cells and headers, written straight back to the file.
- Right-click to **Export to PDF / Print / Excel (`.xlsx`) / ODF (`.ods`)**.

The grid's actions are commands too, acting on the focused cell:

| Action | Command |
| --- | --- |
| Sort by the focused column, smallest or largest first | `csv.sortAscending` / `csv.sortDescending` |
| Back to the file's row order | `csv.clearSort` |
| Rename the focused column (as a double-click on its header) | `csv.renameColumn` |
| Read the first row as column titles, or not | `csv.toggleHeaderRow` |
| Move the editor's caret to the focused cell's text | `csv.revealFocusedCell` |

It's on by default for CSV/TSV files (**Settings → Editor → CSV**, or *View:
Toggle CSV Grid Preview*).

## Print and export

| Action | Command | Emacs key |
| --- | --- | --- |
| Print the table | `csv.print` | (palette) |
| Export the table to PDF | `csv.exportPdf` | (palette) |
| Export to Excel (`.xlsx`) | `csv.exportExcel` | (palette) |
| Export to an ODF spreadsheet (`.ods`) | `csv.exportOds` | (palette) |

The same four actions are on the grid's right-click menu, and *File: Print
Rendered Preview…* / *File: Export Preview to PDF…* print or export the
table on a CSV file.

While the grid is showing, all four **output what the grid shows**: only the
rows the filter leaves visible, in the displayed sort order, with the grid's
*First row is a header* setting. When a filter is active the status bar gives
the row count, so a partial export is not mistaken for the whole file. From
source mode, with the grid hidden, they output the **whole file** with row 1 as
the header.

A printed or exported table that is taller than a page splits between rows and
repeats its header on each page; a cell taller than a page continues on the
next one, and a very wide table is condensed to fit. Pages are always light.
Page size, orientation, margins and the footer are set in **Settings → Editor →
Export & Print**; see [Print & PDF export](/docs/print-pdf).

## Markdown-table interop

- *CSV: Copy as Markdown Table* (`csv.copyAsMarkdownTable`) copies the whole file
  as a GFM table.
- From a Markdown table, convert to and from CSV (`markdown.tableFromCsv` /
  `markdown.tableToCsv`) or export it to CSV, Excel, or ODF (see the
  [Markdown guide](/docs/markdown#tables)).
