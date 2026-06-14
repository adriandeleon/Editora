---
title: "Diff & merge"
group: "Git & diff"
order: 2
beta: true
summary: "Side-by-side and unified diff (vs HEAD, a commit, or another file) with word-level highlights and apply-hunk arrows \u2014 plus a merge-conflict resolver."
---

Compare files in a dedicated tab — **side-by-side** or **unified** — with per-line backgrounds and intra-line **word-level** highlights.

Diff a file against **HEAD** (`C-x v =`), a **commit**, or **another file**, and apply changes hunk-by-hunk with gutter arrows (or apply-all) — all undoable. Open diffs **refresh live** when the underlying files change.

When a file has Git conflict markers, the **merge resolver** lists each conflict with Accept Ours / Theirs / Both and writes the result back.
