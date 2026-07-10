// A slide deck via the polylux package (needs a one-time network fetch of @preview/polylux).
#import "@preview/polylux:0.4.0": *
#set page(paper: "presentation-16-9", margin: 2cm)
#set text(size: 24pt)

#slide[
  #set align(center + horizon)
  #text(size: 48pt, weight: "bold")[Editora × Typst]
  #v(0.5em)
  Package-based slides (polylux)
]

#slide[
  = Why packages?
  - Reusable, versioned building blocks
  - Fetched once, then cached
  - Charts, diagrams, slides, and more
]

#slide[
  #set align(center + horizon)
  #text(size: 40pt, weight: "bold")[Thank you]
]
