Every problem below is deliberate: this file exists to trip the Markdown linter once per rule.
Open it and check the squiggles, the Problems list and the quick fixes. Do not tidy it up.

# Lint sample

### MD001: this heading skips a level

Trailing whitespace ends this line (MD009).   
	A hard tab starts this line (MD010).


Two blank lines sit above this paragraph (MD012).

#MD018: no space after the marker

##  MD019: two spaces after the marker

Text directly above a heading (MD022).
## MD022: no blank line around this heading
Text directly below it.

  ## MD023: this heading is indented

# MD025: a second top-level heading

## MD026: this heading ends in punctuation.

Text directly above a fence (MD031).
```
A fence with no language (MD040), and no blank line above or below it (MD031).
```
Text directly below the fence.

A bare URL: https://example.com/bare (MD034).

A [reference link][missing] with no definition (MD052), and one [that resolves][ok].

[ok]: https://example.com/ok

This line is long on purpose, so that it runs well past the column where the line-length rule starts to complain, when that rule is switched on (MD013, off by default).

<!-- markdownlint-disable MD034 -->
Suppressed by the directive above: https://example.com/quiet
<!-- markdownlint-enable MD034 -->

The file ends without a final newline (MD047).