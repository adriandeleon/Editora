---
title: Code review
description: Comment on lines of your repository's files, suggest changes, and answer, resolve or dismiss review threads, in the editor and in diffs.
category: Version control
order: 3
beta: true
---

Editora lets you review code where you read it. A **review thread** is a
comment on one or more lines of a file in a Git repository, with the replies
to it. Threads follow their lines as the file changes, and say when the code
they were about is gone. They are yours and stay on this computer unless you
choose to [keep them in the project](#keeping-threads-in-the-project).

## Commenting on lines

Select the lines (or put the caret on one) and run **Add Review Comment**
(`review.addComment`), from the command palette or the editor's right-click
menu. Write the comment in the box that opens; it is Markdown. **Comment**
saves it.

**Suggest Change** (`review.suggestChange`) starts the comment with the
selected lines in a suggestion block, as on GitHub:

````markdown
Use a clearer name.

```suggestion
    int retryCount = 3;
```
````

Edit the lines inside the block into what they should be. Any comment can
carry a suggestion this way, a reply too.

## Reading and answering a thread

A thread shows as a marker after its last line, like a language server's code
lens: `💬 2 · Use a clearer name.` gives the number of comments and the start
of the first. Click it, or run **Show Review Thread** (`review.showThread`) with
the caret on its lines, to open its card under the lines. The card shows every
comment with its author and time, and what a suggestion proposes. From it you
can:

| Button | What it does |
| --- | --- |
| **Reply** | Adds what you wrote in the box. Replying to a resolved or dismissed thread opens it again. |
| **Resolve** / **Reopen** | Marks the thread done, or not done any more. |
| **Dismiss…** | Closes the thread without acting on it, with the reason you give. The reason is kept. |
| **Apply Suggestion** | Replaces the thread's lines with the latest suggestion, as one edit that Undo takes back, and resolves the thread. Nothing is saved until you save. |
| **Edit** | Changes your latest comment in the thread. |
| **Delete** | Deletes the thread and all its comments, after asking. |

**Next Review Comment** (`review.next`) and **Previous Review Comment**
(`review.previous`) move the caret from thread to thread in the file, and
wrap around at the ends.

## When the code changes

A thread is anchored to the text of its lines and a little of what surrounds
them, as a [personal note](/docs/bookmarks-notes) is. Edits move its marker
with the lines; when you save, the thread is kept where its lines are now.
When its lines are changed or deleted, the thread is **outdated**: its card
says so, and **Apply Suggestion** is refused, since the lines it would replace
are not there. If the lines come back, an undo or a revert, so does the
thread.

## In a diff

In a side-by-side diff whose side is your file as it is on disk (a file
compared with HEAD, a branch or a revision, or a working-tree change), that
side's gutter marks the lines with a thread. A click on the mark opens the
file at the thread with its card. **Add Review Comment** and **Suggest
Change** work there too, with the caret or the selection on your file's side.

## The Review Comments window

**Tool Window: Review Comments** (`tool.reviews`) lists the threads of the
active file's repository, by file. Type to filter by path, text or author.
**Resolved and dismissed** shows those too. **Enter** or a double click opens
a thread at its lines with its card, **Delete** deletes it, and each row's
menu has **Open**, **Resolve** or **Reopen**, and **Delete**.

## Agents

An agent connected over [MCP](/docs/mcp#review-comments), such as Claude Code,
can read the threads, add its own on lines it reviewed, reply, and resolve a
thread once it has done what the thread asks, with a note that says so. Its
comments carry its name; a finding it marks with a severity shows that first,
`◆ IMPORTANT · Off by one`, and its card says the category. You answer, resolve
or dismiss them as any other thread.

## Findings

A finding is a thread an agent marked with a severity: blocker, important,
minor or nit. **Findings** in the Review Comments window
(`review.toggleFindings`) lists only those, of every file together, the most
severe first. Each row's menu, like the card, has:

| Row | What it does |
| --- | --- |
| **Fix with Agent** | Sends the [AI Agent](/docs/ai#ai-agent) panel what to fix: the file and its lines as they are now, every comment, and the suggestion if there is one. Resolve the thread once it is done. |
| **Copy Fix Prompt** | Copies the same text, to paste into an agent in a terminal, such as Claude Code. It includes the thread's id, so an agent with Editora's MCP tools can resolve the thread itself. |
| **Dismiss…** | Closes the finding with your reason. |

**Fix Review Thread with Agent** (`review.fixWithAgent`) and **Copy Fix
Prompt** (`review.copyFixPrompt`) do the same for the thread at the caret.
They work on any thread, a comment of yours too.

A dismissed finding stays dismissed: an agent that tries to add a finding on
the same lines again is refused and told your reason.

## Keeping threads in the project

Threads are kept in `reviews.json` in Editora's
[config folder](/docs/configuration), per repository: private to you and this
computer. Turn on **Settings → Git → Keep review comments in the project** to
keep each repository's threads in its own `.editora/reviews.json` instead,
which you can commit and share. Threads already written stay where they were
written.
