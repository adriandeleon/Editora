# Editing playground

Scratch space for the Markdown editing commands: the format bar (select some text), the right-click
*Markdown* and *Table* menus, and the `Markdown:` / `Table:` commands in the palette. Every command
is one undo step, so try freely and undo.

<!-- toc -->
<!-- /toc -->

Run *Markdown: Insert/Update Table of Contents* to fill the block above.

## Inline

Select a word in this sentence and make it bold, italic, struck through or code. With nothing
selected, bold and italic act on the word under the caret. Type `*` or `_` with a word selected to
wrap it. Paste a URL over a selected word to turn it into a link.

## Lists

Enter continues a list; Enter on an empty item leaves it. Tab and Shift-Tab move an item with its
children. Moving or deleting items renumbers an ordered list.

1. First step
2. Second step
   - A detail of the second step
   - Another detail
3. Third step

- [ ] Toggle me with *Markdown: Toggle Task Done*
- [x] Already done
- A plain bullet: *Markdown: Task List* turns it into a task

## Headings

### Promote or demote this heading

A plain line to turn into a heading with the Style box.

## Tables

Tab and Shift-Tab move between cells, Enter adds a row (on an empty last row it leaves the table), and
*Table: Reformat* aligns the columns. Try sorting by a column, moving a row or a column, and adding a
row above.

| Name | Count | Note |
| :--- | ----: | ---- |
| pear | 12 | green |
| apple | 3 | |
| fig | 120 | 無花果 |
| banana | 7 | yellow |

## Blocks

Select these lines and wrap them in a code fence, a block quote, or a numbered list:

first line
second line

## Links and footnotes

*Markdown: Edit Link…* on [this link](https://example.com) changes its text and address;
*Convert to Reference Link* moves the address to the bottom. *Insert Footnote* adds a numbered
footnote at the caret.

## Smart typography

With *Smart typography* on in the Markdown settings, type "quotes", it's, a -- dash, a --- dash and
three dots ... here.
