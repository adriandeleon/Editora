---
title: Preview workout
tags:
  - preview
  - markdown
draft: false
---

A document for the rendered preview. The front matter above shows as a table and supplies the
document's title (*Hide front matter* in the Markdown settings hides it). Try Split and Preview,
scroll sync, zoom, the light/dark toggle, selecting text across blocks and copying it, and Find while
only the preview shows.

## Safe HTML

Press <kbd>Ctrl</kbd>+<kbd>C</kbd> to copy. A line<br>break, H<sub>2</sub>O and x<sup>2</sup>,
<b>bold</b>, <i>italic</i> and <code>code</code> written as HTML.

<p align="center">A centred paragraph, written as HTML.</p>

<details>
<summary>Click to expand</summary>

Hidden **Markdown** inside a `details` block.

</details>

<img src="../images/sample.png" alt="An HTML image" width="80">

<!-- An HTML comment never shows. -->

## Task boxes

Click a box in the preview to tick it in the source.

- [ ] An open task
- [x] A finished task
- [ ] Another open task

## Images

Click an image to enlarge it; Esc closes it.

![Sample PNG](../images/sample.png)

<!-- markdownlint-disable-next-line LINK001 -->
![A missing image shows its alt text](../images/does-not-exist.png)

A remote image (needs the network; with *Remote images: Ask* a bar offers to load it):

![badge](https://img.shields.io/badge/editora-preview-blue.svg)

## Long lines

Inline code that is too long for the column wraps instead of being clipped:
`./mvnw -q -o -DskipTests test-compile dependency:build-classpath -Dmdep.outputFile=cp.txt`.

A wide table scrolls sideways:

| Option | Default | Scope | Applies to | Since | Description |
| ------ | ------- | ----- | ---------- | ----- | ----------- |
| `markdownRemoteImagePolicy` | `always` | user | preview, print, exports | 0.22 | When remote images are loaded |
| `markdownWikiLinks` | `true` | user | parse, lint, completion | 0.22 | Recognise `[[wiki links]]` |

A long code line scrolls sideways too:

```java
var result = repository.findAllByOwnerAndStatusOrderByCreatedAtDesc(owner, Status.ACTIVE, PageRequest.of(0, 50));
```

## Footnotes

A sentence with a footnote[^1], another[^2], and an inline one^[written right here].

[^1]: Footnotes are numbered and linked both ways.
[^2]: The back arrow returns to the reference.
