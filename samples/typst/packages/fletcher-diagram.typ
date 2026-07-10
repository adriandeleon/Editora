// Arrow/node diagram via the fletcher package (needs a one-time network fetch of @preview/fletcher).
#import "@preview/fletcher:0.5.8" as fletcher: diagram, node, edge
#set page(width: auto, height: auto, margin: 1cm)

#diagram(
  spacing: 3em,
  node-stroke: 0.5pt,
  node-corner-radius: 4pt,
  node((0, 0), [Input]),
  edge("-|>"),
  node((1, 0), [Process]),
  edge("-|>"),
  node((2, 0), [Output]),
  edge((1, 0), (1, 1), "-|>"),
  node((1, 1), [Log]),
)
