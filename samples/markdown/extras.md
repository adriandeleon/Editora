---
title: Markdown extras
author: Editora
tags: [sample, markdown]
---

# Markdown extras

Everything `gfm.md` leaves out: front matter (above), footnotes, strikethrough, inserted text,
autolinks, nested lists, block quotes, a local image, and enough headings for the outline and a
table of contents.

## Inline extensions

- ~~Struck through~~ text and ++inserted++ text.
- An autolink: https://example.com/autolink becomes clickable. <!-- markdownlint-disable-line MD034 -->
- A footnote reference[^first], a second one[^note], and an inline footnote^[written in place].
- Hard line break: this line ends with a backslash\
  so this one starts on a new line.

## Lists

1. First ordered item
2. Second ordered item
   - A nested bullet
   - Another, with a nested task list:
     - [x] done
     - [ ] not done
3. Third ordered item, with a code block inside it:

   ```python
   print("indented under the list item")
   ```

## Block quotes

> A block quote with **bold** text.
>
> > A nested quote.
>
> - A list inside the quote

## Images

A local image, so the preview needs no network:

![The sample image](../images/sample.png)

A reference-style image: ![The same image, as a JPEG][photo]

[photo]: ../images/sample.jpg "Sample JPEG"

## Tables

| Left | Center | Right |
| :--- | :----: | ----: |
| `a`  | **b**  |     1 |
| c    | _d_    |   200 |

## Code

```json
{ "fenced": true, "language": "json" }
```

    An indented code block.

---

### A third-level heading

#### A fourth-level heading

Use these to check the Structure outline and *Markdown: Insert Table of Contents*.

[^first]: The first footnote.
[^note]: A longer footnote, with `code` and a [link](https://example.com).
