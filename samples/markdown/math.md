# Math sample

Inline math: $a^2 + b^2 = c^2$.

Block math:

$$
\int_0^1 x^2 \, dx = \frac{1}{3}
$$

## Other ways to write math

Inline with backslash brackets: \(e^{i\pi} + 1 = 0\), and a display block:

\[
\sum_{k=1}^{n} k = \frac{n(n+1)}{2}
\]

A `math` fence:

```math
\begin{aligned}
a &= b + c \\
d &= e + f
\end{aligned}
```

## LaTeX that Markdown must not touch

- Braces and a thin space survive: $\{1, 2, 3\}$ and $x \, dx$.
- Stars inside a formula are not emphasis: $a*b*c$.
- The row break in the aligned block above keeps its two rows apart.

## Dollars that are not math

- Prices: it costs $5 and $10 is too much.
- Shell variables in prose: export PATH=$HOME/bin:$PATH.
- Inside code, never math: `echo $HOME $PATH` and `$x$`.
- An escaped dollar stays a dollar: \$a\$.
