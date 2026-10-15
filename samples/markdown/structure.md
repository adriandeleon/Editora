+++
description = "Structure sample"
tags = ["outline", "folding"]
+++

# Structure

TOML front matter (above, between `+++` lines) is folded, skipped by lint and spell check, and not
shown as text. Open the Structure tool window, watch the heading breadcrumb as the caret moves, try
*Project: Go to Markdown Heading…*, and fold the sections, lists, quotes and fences below.

<!-- markdownlint-disable-next-line MD003 -->
Setext heading text
-------------------

The line above is a heading because of the dashes under it; the source styles it like `##`.

## Lists and quotes fold

- A list item that spans
  two lines folds.
- Another item
  - With a nested item

> A block quote
> over three lines
> folds too.

## Fences of every kind

````markdown
A four-backtick fence holding a three-backtick one:

```js
console.log("not the end of the outer fence");
```

# Not a heading: this line is inside a fence
````

<!-- markdownlint-disable-next-line MD048 -->
~~~python
# A comment in a tilde fence, not a heading
print("tilde fences fold too")
~~~

## Hidden headings

<!--
# A commented-out heading
It is not in the outline, the TOC or the fold regions.
-->

<details>
<summary>Folded HTML</summary>

### A heading inside details, after a blank line, is Markdown again

</details>

## Source styling

- [ ] An open box and [x] a ticked box are styled differently.
- A bare URL such as https://example.com/bare is styled as a link. <!-- markdownlint-disable-line MD034 -->

### Deeper

#### Deepest

Use the heading levels above for *Markdown: Number Headings* and *Remove Heading Numbers*.
