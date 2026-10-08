# Git Log

The commit list, its graph, history search, commit details and tags. Back to
[the docs index](../README.md).

## The pieces

- [`git/GitLog`](../../src/main/java/com/editora/git/GitLog.java) — the row model (`Entry`: hash,
  parents, refs), a `Page`, the `Request` a page is asked for with, the `git log` argv it becomes
  (`logArgs`) and the parsers. Pure.
- [`git/GitLogQuery`](../../src/main/java/com/editora/git/GitLogQuery.java) — the history search typed
  into the filter box, parsed into `git log` options. Pure.
- [`git/GitGraph`](../../src/main/java/com/editora/git/GitGraph.java) — lane layout from parent hashes.
  Pure and incremental.
- [`git/GitRefName`](../../src/main/java/com/editora/git/GitRefName.java) — `git check-ref-format` for a
  name the user types. Pure.
- `GitService.logPage` / `commitDetails` / `diffFiles` — the reads, on their own lane.
- [`ui/GitLogPanel`](../../src/main/java/com/editora/ui/GitLogPanel.java) — the view: rows, the graph
  strip, the footer, the details pane, the changed files.
- [`ui/GitWindowCoordinator`](../../src/main/java/com/editora/ui/GitWindowCoordinator.java) — what is
  listed (branch / all branches / file / search), paging, and every row action.

## Rules that are easy to break

**The order is `--date-order`.** The graph opens a lane when a child names a parent and closes it
when that parent is listed, so no commit may come before all of its children. `--date-order`
guarantees that and otherwise sorts by commit date, which keeps the list newest-first across branches
in the all-branches view. `--topo-order` would also be graphable but lists a whole side branch before
returning to the mainline; git's default order is not graphable at all.

**Paging is by `--skip`, anchored — except in a file history.** A page is `LOG_PAGE` (200) commits; one more is requested so the
page knows whether history goes on. The next page is asked for when a row within `LOAD_AHEAD_ROWS` of
the end is shown, or from the footer. `--skip=N` is only right while the history above it has not
moved, and a commit made in a terminal moves it without Editora hearing of it — so a continuation
request overlaps the loaded rows by one, and a page that does not begin with the last loaded commit
makes the coordinator reload instead of appending (`GitWindowCoordinator.continues`).

**A file history is paged from the top.** Under `--follow` git does not prune the walk by path; it
filters when it prints. `--skip=N` therefore skips N *walked* commits, touching the file or not, while
`-n` counts listed ones — so a `--skip` page began somewhere inside the rows already loaded, never
matched the anchor, and "Load More" reloaded the same first page for ever in any repository where other
files have commits in between. `loadMoreGitLog` asks a file history again from the top, one page deeper
(`-n loaded + page`), checks that row `loaded - 1` is still the last loaded commit
(`GitLog.continuesFromTop`) and appends the rest. The cost grows with the depth and is bounded by
`LOG_RELOAD_LIMIT`, like a reload. Test paging with commits to *another* file interleaved
(`GitFileHistoryFxTest`): a repository where every commit touches the file hides the difference.

**A reload keeps the depth.** The log reloads after every Git command. It asks again for as many
commits as were loaded (up to `LOG_RELOAD_LIMIT`), and `GitLogPanel.setLog` leaves the list, selection
and scroll position alone when the same commits come back.

**Loading on scroll stops while the filter box narrows the rows.** A filtered list is short, so its
end is always on screen; it would pull the whole history in page by page. Searching everything is
what Enter in the filter box is for.

**The graph exists only for a plain walk.** A file history, a search result and a locally filtered
list are subsets of the commits; their parents are not in the list. `Request.graphable()` and
`GitLogPanel.updateGraphColumn` hide the column there. `GitGraph` is fed pages in order and keeps its
open lanes between them, so the drawing does not depend on where the page boundaries fell
(`GitGraphTest.theLayoutIsTheSameHoweverTheHistoryIsPaged`).

**The graph strip is as high as the cell.** Each row draws its part of the graph on a canvas laid out
over the cell's full height (the cell keeps a pixel of inset around its content). The row height is
CSS (`.git-log-panel .git-tree .list-cell`); the strip asks for no height of its own.
`GitLogRowsFxTest` measures both.

**No user text is an option.** Every search value is attached to its option (`--grep=…`,
`--author=…`, `-S…`, `--since=…`) or follows `--`; patterns are fixed strings. A `path:` pattern is
written `:(top)<pattern>`, which uses up the pathspec's one magic prefix. A history file is a literal
pathspec. Tag names pass `GitRefName.isValid` before they reach a command line; an existing tag is
always addressed as `refs/tags/<name>`.

**Log reads have their own lane, and only the newest page request is answered.** A pickaxe search
(`content:` / `-S`) reads file contents and runs for seconds; on the shared lane every status and
gutter refresh would wait behind it. `logPage` drops a request still queued when a newer one arrives
without calling it back, so the coordinator never waits on a callback: `loadGitLog` bumps
`gitLogGeneration` and resets the load-more flag, and `loadMoreGitLog` is not sent while a full load
is in flight. A superseded search that is already running is not cancelled; it holds the lane until it
finishes or times out (`HISTORY_SEARCH`).

**A file history follows renames, one path at a time.** `--follow` takes exactly one path, so a
`path:` term cannot narrow a file history: `loadGitLog` takes it out of the search
(`GitLogQuery.withoutPathTerms`) and says so, rather than show it in the header as searched for. The page carries the file's path in each commit
(`Page.followed`, from `--name-status -z`); the panel selects that file in the commit's file list and
the coordinator compares it with the history file under its present name
(`historyWorkingFile`).

**A file history is never searched with `--follow`.** git learns a file's old name only when it diffs
the commit that renamed it, and `--grep`, `--author` and `--until` drop that commit before the diff —
every commit under the old name was silently lost unless the rename commit happened to match. A
searched file history is two reads: the unsearched `--follow` listing (up to `LOG_RELOAD_LIMIT` rows),
then the search over every name the file has had (`GitLog.followedPaths`, a `Request` with literal
`paths` and no `--follow`); the rows shown are the first listing's that the second found
(`Page.keep`), so their order and the file's path in each commit stay those of the followed history.
It is one page — nothing follows it.

**A file history belongs to the repository it was opened in.** The log lists the active repository, so
every entry point for another file's history goes through `GitCoordinator.activatingRepositoryOf` (the
Project tree and the tab menu), and `repositoryChanged` drops the file filter whenever the root changes.
A path-prefix test is not enough: a nested repository's file lies under the outer root too.

**A diff or review tab stays in its repository.** Such a tab has no file, and in a window without a
project that used to leave the Git engine with no context: opening a diff from the log dropped the
repository, closed the log and forgot the file history. `GitCoordinator.contextPath` falls back to the
active repository while a tab pane shows a diff, patch or commit-review tab
(`GitWindowGate.showsGitView`). `GitWindowGate.allows` alone only gates the windows' availability; test
this through a real `git.refresh()`, not by setting availability by hand.

**In a file history a row is a version of the file.** Enter or a double-click on a commit opens what it
changed in that file (commit against parent, the parent read at the old path across a rename); the
row's menu starts with Show Diff and Compare with Working Tree, and still has Review Commit for the
whole commit. The lower list's Enter compares the revision with the working file, or — when the file no
longer exists — shows the commit's change instead of only reporting that it is gone. The header is a
chip whose ✕ (or Escape in the commit list) returns to the branch's log. An empty file history says the
file has no commits yet, and a shallow clone says under its last row that older history is not local
(`GitWindowCoordinator.shallow`).

**Reverting a merge needs a mainline.** `git revert` of a commit with two parents fails without `-m`.
`revertIn` asks which parent to keep (`mainlineChooser`) and passes `-m N`.

## Search syntax

Blank-separated terms; double quotes keep blanks in one term.

| Term | Meaning |
| --- | --- |
| `author:name` | author name or e-mail contains `name` (several: any) |
| `content:text`, `-Stext` | commits that add or remove `text` (pickaxe; one term) |
| `since:date`, `until:date` (`after:`, `before:`) | anything git reads as a date |
| `path:glob` (`file:`) | commits touching a matching path, from the repository root |
| anything else | message text; every such term must match |

Matching ignores case.

## Not done

- The graph column is capped at `MAX_GRAPH_LANES`; wider rows are clipped, not scrolled.
- Deleting a tag is local only; there is no "delete on remote".

## Since the first version

- **A history search is cancellable.** `logPage` reads with a cancellation handle; a newer listing, a
  cleared search or a repository change calls `GitService.cancelHistoryRead`, which kills the process
  (`GitHistorySearchCancelFxTest`). A cancelled or superseded listing never reaches its callback.
- **A row action no longer hides the log.** The Output console shares the bottom panel; it now comes
  forward only when a network command starts or a command fails (`GitConsoleLog.raisesConsole`), so
  New Tag, Revert or Cherry-Pick from a row leave the log where it is.
- **Create Patch…** on a row (`git.log.createPatch`) hands the commit to `GitPatchCoordinator`.
- **The commit message in the details pane is selectable**: a read-only `TextArea` sized to its text
  (`fitMessageHeight`), so the pane still scrolls as a whole.
