// Tables — write them by hand and style them all at once (native #table, no package).
#set page(paper: "a5", margin: 1.5cm)
#set heading(numbering: "1.")

= Tables

A styled table with a shaded header row, per-column alignment, and a highlighted footer:

#table(
  columns: (auto, 1fr, auto),
  align: (left, left, right),
  stroke: 0.5pt + luma(180),
  fill: (_, row) => if row == 0 { luma(230) },
  table.header([*Item*], [*Description*], [*Price*]),
  [Coffee],  [House blend, medium roast], [\$3.50],
  [Tea],     [Loose-leaf green],          [\$2.75],
  [Cake],    [Flourless chocolate],       [\$4.25],
  table.cell(colspan: 2)[*Total*], [\$10.50],
)

== Zebra striping

#table(
  columns: 4,
  align: right,
  fill: (_, row) => if calc.odd(row) { luma(245) },
  table.header([Year], [Q1], [Q2], [Q3]),
  [2023], [12], [18], [21],
  [2024], [15], [22], [27],
  [2025], [19], [25], [31],
)
