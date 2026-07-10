// Visualizations — Typst's native drawing primitives (rect / circle / line / polygon / path), no package.
// (Rich charts/diagrams use packages like cetz; these samples stay package-free so they always render.)
#set page(paper: "a5", margin: 1.5cm)
#set heading(numbering: "1.")

= Shapes and color

#stack(
  dir: ltr,
  spacing: 1cm,
  rect(width: 3cm, height: 1.6cm, radius: 6pt, fill: rgb("#4c8bf5"))[
    #set text(fill: white)
    #align(center + horizon)[Rounded rect]
  ],
  circle(radius: 0.8cm, fill: rgb("#e8543f")),
  polygon.regular(size: 1.6cm, vertices: 6, fill: rgb("#34a853")),
)

== A hand-drawn bar chart

#let bar(h, c) = rect(width: 1cm, height: h, fill: c)
#align(bottom)[
  #stack(
    dir: ltr,
    spacing: 6pt,
    bar(1.2cm, rgb("#4c8bf5")),
    bar(2.4cm, rgb("#4c8bf5")),
    bar(1.8cm, rgb("#4c8bf5")),
    bar(3.0cm, rgb("#4c8bf5")),
    bar(2.1cm, rgb("#4c8bf5")),
  )
]

== A curve

#curve(
  stroke: 2pt + rgb("#8e44ad"),
  curve.move((0pt, 0pt)),
  curve.line((2cm, -1cm)),
  curve.line((4cm, 0.5cm)),
  curve.line((6cm, -0.8cm)),
)
