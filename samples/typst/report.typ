// A small Typst document — toggle the 3-mode preview (Editor / Split / Preview).
// The preview renders one image per page, so this two-page document stacks two pages.
// Needs the `typst` CLI on PATH (brew install typst, or cargo install typst-cli).

#set page(paper: "a5", margin: 2cm)
#set text(size: 11pt) // use typst's bundled default font (New Computer Modern)
#set heading(numbering: "1.")

#align(center)[
  #text(size: 20pt, weight: "bold")[Editora Typst Sample]
  #v(4pt)
  A tiny document to exercise the multi-page rendered preview.
]

= Introduction

Typst is a modern typesetting system. This paragraph is here so the preview has
some prose to lay out. Edit any of this text and the preview updates in place —
the previous pages stay visible while the new render runs, so there's no flicker.

Inline math like $a^2 + b^2 = c^2$ renders too, as does a block:

$ sum_(k=1)^n k = (n (n + 1)) / 2 $

== A list

- First item
- Second item
- Third item, with some *bold* and _emphasized_ text
- Hola mundo

#pagebreak()

= Second page

This heading lives on the second page, so the preview stacks a second image
below the first. A short table:

#table(
  columns: 2,
  [*Feature*], [*Status*],
  [Preview], [rendered],
  [Export PDF], [native],
  [Print], [paginated],
)

That's all — a compact document that still spans two pages.
