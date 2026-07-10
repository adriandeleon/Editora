// Slides — take content straight to a slideshow. Native multi-page deck (no polylux/touying package):
// each #pagebreak() is a slide, and the preview stacks them one image per page.
#set page(paper: "presentation-16-9", fill: rgb("#1e2a3a"), margin: 2cm)
#set text(fill: white, size: 24pt, font: "New Computer Modern")

#align(center + horizon)[
  #text(size: 54pt, weight: "bold")[Editora]
  #v(0.5em)
  #text(size: 28pt, fill: rgb("#8ab4f8"))[Typst slides — one file, no packages]
]

#pagebreak()

= Why Typst?

#set text(size: 26pt)
- Markup that reads like prose
- Math, tables, and code built in
- Compiles in milliseconds
- One binary, reproducible output

#pagebreak()

= A slide with math

Content scales straight from the page to the projector:

#align(center)[
  #text(size: 40pt)[$ e^(i pi) + 1 = 0 $]
]

#pagebreak()

#align(center + horizon)[
  #text(size: 44pt, weight: "bold")[Thank you]
  #v(0.5em)
  #text(size: 24pt, fill: rgb("#8ab4f8"))[Questions?]
]
