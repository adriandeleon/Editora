// A plotted chart via the lilaq package (needs a one-time network fetch of @preview/lilaq).
#import "@preview/lilaq:0.4.0" as lq
#set page(width: auto, height: auto, margin: 1cm)

#let xs = lq.linspace(0, 2 * calc.pi, num: 100)
#lq.diagram(
  title: [Sine and cosine],
  xlabel: $x$,
  ylabel: $y$,
  lq.plot(xs, xs.map(calc.sin), label: [$sin x$]),
  lq.plot(xs, xs.map(calc.cos), label: [$cos x$]),
)
