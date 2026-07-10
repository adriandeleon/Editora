// A hand-drawn diagram via the cetz package (needs a one-time network fetch of @preview/cetz).
#import "@preview/cetz:0.3.4"
#set page(width: auto, height: auto, margin: 1cm)

#cetz.canvas({
  import cetz.draw: *
  circle((0, 0), radius: 1, fill: rgb("#4c8bf5"), stroke: none)
  content((0, 0), text(white)[Root])
  line((0, -1), (-2, -3), mark: (end: ">"))
  line((0, -1), (2, -3), mark: (end: ">"))
  rect((-3, -4), (-1, -3), radius: 4pt, fill: rgb("#34a853"), stroke: none)
  content((-2, -3.5), text(white)[Left])
  rect((1, -4), (3, -3), radius: 4pt, fill: rgb("#e8543f"), stroke: none)
  content((2, -3.5), text(white)[Right])
})
