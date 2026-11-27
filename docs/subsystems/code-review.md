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

## In the window

`ui/ReviewCoordinator` (one per window, built in `MainController` beside the Notes coordinator) ties buffers to
their repository and runs the commands:

- **Markers.** A thread is drawn after its last line as a review lens (`EditorBuffer.ReviewLens`), through the
  code-lens machinery in `editor/BufferInlays`: its own map beside the code lenses, shifted with them on each edit
  (`CodeLensShift.afterChanges`, one empty-map check per edit for a file without threads), merged after them on the
  line (style `review-lens`), clickable and lit on hover. The gutter is not used: a mark that follows edits there
  would repaint the gutter on every keystroke (docs/performance.md).
- **Where a file is.** Opening a file asks nothing of Git: `restore` matches it against the repositories that have
  threads (`placeAmong`, canonical paths, off the FX thread). Starting a thread asks the window's Git
  (`GitCoordinator.withRepositoryOf`), which reports a file outside any repository.
- **Following edits.** Between saves only the markers move. `saved` (from `FileWorkflowHost.sourceSaved`) finds each
  thread again in the saved text and stores where it is; `showThread` and the card find it again too.
- **The card.** `ui/ReviewThreadCard` is an in-scene overlay shown at the lines (`OverlayHost.showAt`, as
  `GitHunkPopup`); a thread off screen is scrolled to and the card opens a pulse later. Its buttons run after it
  closes, on the thread it showed. Apply Suggestion replaces the lines in the buffer as one undoable edit and
  resolves the thread, and is refused (status line) for an outdated thread or a read-only buffer.
- **Diffs.** `DiffCoordinator.buildDiffPane` hands every diff with an editable local side to `prepareDiff`, which
  sets a `DiffViewerPane.ReviewHook`: that side's gutter (side by side only) gets a slot marking the lines threads end
  on, a click opens the thread in the editor, and Add Review Comment there comments on the local file's lines
  (`localSelectionLines`, filler rows skipped). The anchor is taken from `editableBaselineText()`.
- **The tool window.** `ui/ReviewsPanel` (`tool.reviews`, stripe off by default) shows the active file's
  repository's threads by file, filtered by path, text or author, closed ones on request
  (`review.toggleShowClosed`). Its row commands (`review.openSelected`, `review.toggleSelectedResolved`,
  `review.deleteSelected`) are registered in `PanelRowCommands`.
- **Commands.** `review.addComment`, `review.suggestChange`, `review.showThread`, `review.next`,
  `review.previous`; the first two are also in the editor's context menu.

## Agents (MCP)

Four MCP tools and a resource reach the window's review threads through `McpBridge` (`listReviews`, `addReview`,
`replyReview`, `resolveReview`), implemented in `WindowMcpBridge` and passed through by `MainController`:

- The repository is the one the window's Git follows (`GitCoordinator.repoRoot`). Paths are made canonical and
  closed files read on the MCP worker; only the threads and open buffers are read on FX. A project file not yet in
  memory is read on the worker and handed over (`ReviewCoordinator.loaded`).
- `add_review_comment` keeps to `McpAccess.openRefusal`: a file open in the window or inside its project. Lines
  are 1-based and must be in the file; the anchor is captured from the buffer's text (unsaved changes included) or
  the file's. It starts an `AGENT` thread named after the client (`io.modelcontextprotocol/clientInfo` in a modern
  request's `_meta`, else "Agent"), with an optional severity and category (`ReviewCoordinator.agentStart`).
- `resolve_review_comment` takes a note, stored as "Resolved by <agent>: <note>"; a thread that is not open is
  refused.
- `editora://reviews` lists the open threads as `list_review_comments` does; the resource watch compares its text.
- The tools write only the review store, never a file; annotated `readOnlyHint: false`, `destructiveHint: false`.

## Keeping threads in the project

With `Settings.reviewsInProject` (schema 187, Settings → Git) the threads of a repository live in its
`.editora/reviews.json` (`config/ProjectReviewFile`, the same `ReviewStore` shape under the key `.`) instead of the
config folder. `ui/ReviewStorage` picks the place: reads come from memory, a repository's file is loaded off the FX
thread when a file of it opens or a thread starts there (`findPlace`, `whenLoaded`), and writes go to one writer
thread in order. Turning the setting on or off does not move threads already written.

## Not built yet

- Comments on the base side of a diff (they need its revision), and GitHub pull request threads (backlog item 8).
- The findings view (item 10), and a diff of the change under review for agents (they read it with `git`).
