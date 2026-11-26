# Code review

Editora is becoming a code review tool: comments on lines of a repository's files, written by the user or by an
agent, that follow their lines as the files change. The plan, its order and the decisions taken are in
[the backlog](../claude-code-review-backlog.md) (items 6 to 10). This page describes what exists.

## The model

A **thread** (`config/ReviewThread`) is about a range of whole lines of one file and holds its comments, oldest
first (`config/ReviewComment`). It records:

- `path`, relative to the repository root with forward slashes, and `side`: `NEW` for the file as it is (a diff's
  right side) or `OLD` for the base it is compared with, named by `baseRevision` (a commit id; `null` for the index);
- `anchor`, a `TextAnchor` like a Personal Note's: the lines' text (capped at `TextAnchor.MAX_TEXT`), up to
  `MAX_CONTEXT` characters of context on each side, and the full length;
- `state` (`OPEN`, `RESOLVED`, `DISMISSED`) and a `stateNote`: what resolved it, or why it was dismissed. A
  dismissed finding stays in the store, so the next review can leave it out;
- for an agent's finding, a `severity` (`BLOCKER`, `IMPORTANT`, `MINOR`, `NIT`) and a free `category`.

A comment has an author (`config/ReviewAuthor`: `USER`, `AGENT` with its name, or `GITHUB` with a login), a
Markdown body (cut at `ReviewThreads.MAX_BODY`), when it was written and last edited, and an optional
**suggestion**: the text proposed for the thread's lines, as a GitHub `suggestion` block.

## Following the lines

`review/ReviewAnchors` captures an anchor on whole lines (`capture`) and finds them again (`locate`) with
`NoteAnchors.relocate`, the Personal Notes relocator: the saved place if the text and context still match there,
else the occurrence whose context matches best, then the nearest. The result says whether the lines `moved`; a
thread whose text can no longer be found is **outdated**. Outdated is worked out against the current text each
time, never stored, so a thread comes back when its lines do (an undo, a revert).

`applySuggestion` replaces the thread's lines with a suggestion, keeping the line ending after them, and refuses
(returns `null`) for an outdated thread. `ReviewThreads.reanchored` moves the threads of one file's `NEW` side to
where their lines are now; the caller saves the result.

## Changing threads

`review/ReviewThreads` holds every change as a pure function from one list of threads to the next: `start`,
`reply` (which reopens a resolved or dismissed thread), `edit`, `deleteComment` (the last comment takes the thread
with it), `delete`, `resolve`, `dismiss` and `reopen`. Each stamps `updatedAt` from the clock it was built with,
keeps the order, and leaves the list as it was for an id that is not in it.

## Where threads are kept

`reviews.json` in the config folder (`config/ReviewStore`, `ConfigSchema.REVIEWS`), private to this computer:
backlog decision D1. `byRepo` maps a repository key (`review/ReviewKeys.repoKey`: the root's canonical path) to its
threads; `ReviewKeys.relativePath` names a file in it. `SharedConfig.reviewThreads`, `setReviewThreads` (which
writes the file through the store writer, merged with another process's write) and `allReviewThreads` are the
accessors. An empty repository is dropped from the file. A configuration import puts the file with bookmarks and
notes (`ConfigImport.Part.BOOKMARKS_NOTES`): paths of another computer, not ticked by default.

## Not built yet

- The editor and diff UI: adding, showing and answering comments (backlog item 7), and the setting that keeps a
  repository's threads in the project instead.
- GitHub pull request threads (item 8), MCP tools for agents (item 9), and the findings view (item 10).
