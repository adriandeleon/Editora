// Mathematics — Typst treats math as a first-class citizen (no package needed).
#set page(paper: "a5", margin: 2cm)
#set heading(numbering: "1.")

= Mathematics

Inline math flows with the text, like the Pythagorean theorem $a^2 + b^2 = c^2$ or
Euler's identity $e^(i pi) + 1 = 0$. Display equations are centered on their own line:

$ sum_(k=1)^n k = (n (n + 1)) / 2 $

== Matrices and systems

$ A = mat(1, 2, 3; 4, 5, 6; 7, 8, 9) quad
  cases(x + y = 2, 2x - y = 0) $

== Calculus

$ integral_0^1 x^2 dif x = 1/3 quad
  lim_(x -> 0) sin(x) / x = 1 quad
  (dif) / (dif x) e^x = e^x $

== Symbols

Greek and operators render by name: $alpha, beta, gamma, Delta, nabla, partial, infinity, approx, times$.
