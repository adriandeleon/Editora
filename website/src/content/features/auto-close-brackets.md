---
title: "Auto-close & bracket matching"
group: "Editing"
order: 3
beta: false
summary: "Typing <code>([{</code> or quotes inserts the closer, type-over to skip, wrap a selection, and Backspace clears an empty pair \u2014 and the bracket beside the caret is highlighted with its match."
---

Typing `(`, `[`, `{`, `"`, `'`, or `` ` `` inserts the matching closer and keeps the caret between them. Type the closer when it's already next to the caret and Editora types over it; type an opener with a selection and it wraps the selection; Backspace inside an empty pair deletes both halves.

Quotes aren't auto-paired next to a word character, so the apostrophe in `don't` is left alone. And whenever the caret sits next to a bracket, both it and its match are highlighted.
