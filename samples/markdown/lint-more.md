# More lint rules

Every problem below is deliberate: this file trips each Markdown lint rule added after the first
sixteen (those are in `lint.md`), once, where the text says so. Do not tidy it up. Line length
(MD013) and inline HTML (MD033) are off by default; switch them on in the Markdown settings to see
the long line and the HTML at the end flagged.

## Headings

Setext heading (MD003)
----------------------

## Duplicate

The next heading repeats this one under the same parent (MD024).

## Duplicate

**Emphasis used as a heading (MD036)**

Text after it.

## Lists

- a dash bullet
* a star bullet after dash bullets (MD004)

- an item
 - an item one space in, at the same level (MD005)

- an item
   - a nested item three spaces in (MD007)

1. one
3. out of sequence (MD029)

-  two spaces after the marker (MD030)

Text directly above a list.
- this list has no blank line above it (MD032)

## Links

(a reversed link)[https://example.com] (MD011)

[ spaces inside the link text ](https://example.com) (MD039)

[an empty link]() (MD042)

![](../images/sample.png) an image with no alt text (MD045)

[a heading that is not here](#no-such-heading) (MD051)

[a file that does not exist](no-such-file.md) (LINK001)

[a heading that is not in that file](extras.md#no-such-heading) (LINK002)

A wiki link to no note: [[no such note]] (LINK003)

## Emphasis and code

Some ** spaces inside strong ** text (MD037) and ` a leading space` in a code span (MD038).

*Star emphasis* first, then _underscore emphasis_ (MD049).

**Star strong** first, then __underscore strong__ (MD050).

```js
const fenced = "a fenced block comes first";
```

    An indented code block after a fenced one (MD046).

~~~js
const tilde = "a tilde fence after backtick fences (MD048)";
~~~

## Rules and tables

---

***

The second rule above uses a different style (MD035).

| a | b |
| - | - |
| 1 | 2 |

| a | b |
| - | - |
1 | 2

The last row of the table above drops the outer pipes its header has (MD055).

| a | b |
| - | - |
| 1 | 2 | 3 |

The table above has a row with too many cells (MD056).

---
| a | b |
| - | - |
| 1 | 2 |

The table above follows a rule with no blank line between (MD058).

## Off by default

This line is longer than eighty characters, so MD013 flags it once line length is switched on in settings.

Inline HTML such as <kbd>Ctrl</kbd> is flagged by MD033 once it is switched on.

[unused]: https://example.com/never-used "A definition no link uses (MD053)"
