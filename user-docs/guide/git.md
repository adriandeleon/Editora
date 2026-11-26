---
title: Git
description: "The built-in Git integration: branches, change bars, commits, conflicts, pull and push, log, blame, stashes, tags, and patches."
category: Version control
order: 1
---

Editora's Git support shells out to your installed `git`, with no bundled
library. It's **on by default**; turn it off in **Settings → Version Control →
Git** or with `view.toggleGit`. If `git` isn't installed, the integration stays
inert.

## Git integration

- The **status bar** shows the current branch with ahead/behind counts. Click it
  for a dropdown to switch or create branches, pull, fetch, and push. Outside a
  repo it reads "No VCS" and offers to clone one or to initialize a new one. While a merge,
  rebase, cherry-pick, or revert is in progress it names it (`main · MERGING`).
  A repository Git refuses to work in (for example "dubious ownership") is
  reported with Git's reason in the status bar and the Commit window.
- **Gutter change bars** mark added and modified lines against HEAD; deleted
  lines show as a corner flag at the top of the line that follows them. Hover a
  bar for that hunk's diff, or click it for a card with actions (see
  [Changes in the editor](#changes-in-the-editor)). Editor tabs are tinted by
  their file's Git status, and the minimap marks changed lines.
- The **Project tree marks files by Git status**, IntelliJ-style: a single-letter
  prefix (M / A / D / R / U) and a color, added (green), modified (blue), deleted
  (gray), renamed (violet), untracked (olive), with changed folders tinted. The
  Commit window's rows use the same letters and colors, so the two read
  identically. It updates as you edit, stage, commit, or switch branches.
- The **Commit** tool window (`M-4` in Emacs, Alt+0 or Ctrl+K in IntelliJ
  IDEA) groups staged, changed, and untracked files
  with stage, unstage, discard, and a commit box (Ctrl/Cmd+Enter to commit).
  See [The Commit window](#the-commit-window).
- A **Git Log** tool window (`C-x v S-l`), the active file's Git history
  (**Show Git History**, `git.fileHistory`, `C-x v l`), **blame annotations** in
  a gutter column (toggle with `git.toggleBlame`), and **stashes**
  (`git.stashes`).

In a file's Git history, double-click a commit or press Enter to open what that
commit changed in the file. **Compare with Working Tree** in the row's menu
opens the editable comparison instead: the full diff viewer, including line,
hunk, whole-file, Result, Undo, and Save controls. See
[Git history of one file](#git-history-of-one-file). Revisions Editora records
itself on every save are a separate feature,
[Local History](/docs/workspace#local-file-history).

| Action | Command | Emacs key | Other keymaps |
| --- | --- | --- | --- |
| Commit (open the Commit window) | `git.commit` | `C-x v v` | VS Code: Ctrl+Shift+G |
| Switch branch (dropdown) | `git.switchBranch` | `C-x v b s` | IntelliJ: Ctrl+Shift+' |
| New branch | `git.newBranch` | `C-x v b c` | — |
| Pull | `git.pull` | `C-x v S-=` | IntelliJ: Ctrl+T |
| Push | `git.push` | `C-x v S-p` | IntelliJ: Ctrl+Shift+K |
| Fetch | `git.fetch` | (palette) | — |
| Next / previous change in the file | `git.nextChange` / `git.previousChange` | `C-x v ]` / `C-x v [` | VS Code: Alt+F5 / Alt+Shift+F5; IntelliJ: Ctrl+Alt+Shift+Down / Ctrl+Alt+Shift+Up |
| Git Log | `tool.gitLog` | `C-x v S-l` | IntelliJ: Alt+9 |
| Show Git History (the active file's commits) | `git.fileHistory` | `C-x v l` | CUA, Sublime Text, VS Code, IntelliJ: Ctrl+Alt+Shift+H |
| Toggle blame annotations | `git.toggleBlame` | `C-x v g` | — |
| Clone a repository | `git.clone` | (palette) | — |
| Create a repository in a folder | `git.init` | (palette) | — |
| Refresh | `git.refresh` | (palette) | — |

The **Emacs key** column is the default Emacs keymap; **Other keymaps** gives
the chords the CUA, Sublime Text, VS Code and IntelliJ IDEA keymaps bind (— where
none does). On macOS those mostly use ⌘ for Ctrl; the
[keybindings reference](/keybindings) lists every keymap.

The **Git** menu keeps the daily actions at the top level (Commit, Commit and
Push, Push, Pull, Fetch, Switch Branch, Git Log, and Continue / Skip / Abort)
and groups the rest into submenus: Changes, Branches, Remotes & Worktrees, Tags,
Stash, Patches, History & Blame, Local History, Compare, and GitHub.

Cloning asks for a URL and a destination, then opens a file from the clone (its
README if present) so Git activates without creating a project. The clone form
also takes a branch (blank is the remote's default), a shallow depth (blank is
all history), and an option to clone submodules. The destination can start with
`~` or be relative; missing parent folders are created, and an existing empty
folder is accepted.

Branch switch, pull, and push reload unmodified open buffers whose files changed
on disk. Branch switches, discard, and stash never silently replace a dirty open
copy; deleted and edited buffers remain available for recovery. Rolling back or
discarding a file's changes keeps its current text in
[Local History](/docs/workspace#local-file-history) first, and is refused if that
copy cannot be made. Git paths are
passed literally, including names that look like options or contain unusual
characters, and conflicted entries remain visible while a merge is unresolved.
History mutations and refreshes also revalidate the active file and working-tree
state before applying their result.

Editora uses the `git` on your `PATH`; **Git: Set Git Command**
(`git.setCommand`) or the **Git command** field on Settings → Version Control →
Git points it somewhere else, and a blank value goes back to the `PATH` one. The
**Detected** row on that page shows the version found, or, when nothing runs at
the path you set, **No git at** that path. **Enable Git** stays usable either
way. While Git is off, the page's blame, **Pull mode** and **Fetch
automatically** rows are greyed out; the command path stays editable.

### Credentials

Editora never asks for a password, a key passphrase, or a host-key
confirmation itself, and Git commands never start an external editor or wait on a
terminal: a command that would need an answer typed there fails with Git's reason
instead. Set up authentication outside Editora first: a credential helper for HTTPS
remotes, an SSH agent holding your key, and one connection from a terminal so the
host key is known. A graphical askpass program (`SSH_ASKPASS`) still asks in its
own window when you clone, fetch, pull or push. When a remote refuses to sign you
in, the error says what to set up.

Signed commits and tags (`commit.gpgSign`, `tag.gpgSign`) need a graphical
pinentry, such as pinentry-gnome3, pinentry-qt or pinentry-mac. A terminal-only
pinentry (pinentry-curses or pinentry-tty) may ask in the terminal gpg-agent was
started from, where nobody is looking, and the commit then waits until it times
out.

## The Commit window

The Commit window (`tool.commit`; `M-4` in Emacs, Alt+0 or Ctrl+K in IntelliJ
IDEA, ⌘K on macOS) lists **Staged**, **Changes**, and
**Untracked** files, each row with its added and removed line counts. Its header
shows how many commits are waiting to be pushed or pulled, or that the branch
has not been published yet. When there is nothing to commit but the branch has
commits its upstream lacks, the list says so under *Nothing to commit — working
tree clean* (*2 commits to push to origin/main*) with a **Push** button; a
branch with no upstream keeps the header's `↑ publish` alone.

![The Commit tool window beside the editor: Shelf.java under Changes with +3 −2 line counts, two untracked entries, Amend and Sign off checkboxes and a commit message being typed; the editor gutter marks the changed lines](/screenshots/docs/git-commit.png "The Commit window lists changes and untracked files with their line counts; the editor gutter shows the same changes.")

- **Space** stages or unstages the selected rows; on a group header it acts on
  the whole group. Group menus offer **Stage All in Group**, **Unstage All**
  (`git.unstageAll`), and **Discard All Changes…** (`git.discardAll`), which
  returns tracked files to the last commit and deletes untracked files after a
  confirmation. Both keep a [Local History](/docs/workspace#local-file-history)
  copy of each file first, and nothing is discarded if a copy cannot be made.
- **Amend** (`git.commitAmend`) makes the next commit replace the last one.
  Nothing needs to be staged to change only its message. If the commit is
  already on the remote, the window says that amending it will need a force
  push.
- **Commit and Push** (`git.commitAndPush`) commits and then pushes the branch.
  A failed commit does not push.
- **Sign off** (`git.toggleSignOff`) adds a `Signed-off-by` line
  (`git commit -s`). The choice is remembered for the repository until Editora
  closes.
- **Commit Now** (`git.commitNow`) commits the staged changes with the box's
  message, as the Commit button does, from wherever the keyboard is.
- A **length guide** under the message shows the subject length and counts long
  body lines, following the 50/72 convention. It is advice only and never blocks
  a commit.
- **Recent Commit Messages…** (`git.commitMessageHistory`) puts a message you
  committed earlier in this session back in the box. When the repository has a
  `commit.template`, the box starts from it and its comment lines are left out
  of the commit.
- **Undo Last Commit…** (`git.undoLastCommit`) takes the last commit off the
  branch, keeps its changes staged, and puts its message back in the commit box
  (`git reset --soft HEAD~1`). It asks first, and warns when the commit is
  already on the remote. A merge commit or a branch's first commit is not
  undone this way.

Ctrl/Cmd+Enter does nothing when nothing is staged or while a commit is
running. The list keeps its selection, collapsed groups, and scroll position as
the status updates.

## Operations in progress and conflicts

A merge, rebase, cherry-pick, or revert that stops part-way is shown in the
status bar and as a banner at the top of the Commit window, with **Continue**,
**Skip**, and **Abort**.

| Action | Command | Emacs key |
| --- | --- | --- |
| Continue once the conflicts are resolved | `git.continueOperation` | (palette) |
| Skip the commit a rebase, cherry-pick, or revert stopped at | `git.skipOperation` | (palette) |
| Abort and go back to the state before it | `git.abortOperation` | (palette) |
| Open the resolver for the active file | `merge.resolve` | (palette) |

Any Git command that stops on conflicts (pull, merge, rebase, revert,
cherry-pick, stash pop, or a 3-way patch) opens the Commit window with the
conflicted files in their own **Conflicts** group. Each has:

- **Resolve Conflicts…**, which opens the
  [three-way resolver](/docs/diff-merge#merge-conflicts). Applying a complete
  resolution saves the file and stages it, which clears it from the group.
- Actions that keep one side whole. They are named for what the sides mean in
  the current operation: **Accept Ours** / **Accept Theirs** in a merge,
  **Keep the Upstream Version** / **Keep My Commit's Version** in a rebase, and
  **Accept the Commit's Version** in a cherry-pick or revert.
- **Mark Resolved**, for a file you fixed by hand.

With several conflicted files selected, **Accept Ours for Selected Conflicts…**
(`git.acceptOursSelected`) and **Accept Theirs for Selected Conflicts…**
(`git.acceptTheirsSelected`) resolve them all with one side, after asking.

Committing is blocked while conflicts are unresolved. When a merge is ready to
conclude, the commit box is prefilled with the message Git prepared.

## Pulling and pushing

**Pull mode** (Settings → Version Control → Git, or `git.setPullMode`) decides
how `git.pull` brings in the remote commits:

- **Fast-forward only** (the default) never rewrites or merges anything. When
  the branch and its upstream have diverged, the pull offers **Rebase** or
  **Merge** for that one pull.
- **Rebase** replays your local commits on top of the remote ones.
- **Merge** joins the two histories with a merge commit.

`git.pullRebase` and `git.pullMerge` pull one way regardless of the setting.
Rebase and merge pulls carry uncommitted changes across with `--autostash` and
report what happened to them; if applying them back conflicts, they stay in the
stash and the conflicts appear in the Commit window.

| Action | Command | Emacs key | Other keymaps |
| --- | --- | --- | --- |
| Push the current branch | `git.push` | `C-x v S-p` | IntelliJ: Ctrl+Shift+K |
| Push to another remote or under another branch name | `git.pushTo` | (palette) | — |
| Push every local tag the remote lacks | `git.pushTags` | (palette) | — |

The first push of a branch publishes it to the push remote configured in Git
(the branch's `pushRemote`, then the repository's `pushDefault`) or to the
repository's only remote. A branch whose upstream has another name (`feat`
tracking `origin/main`) is pushed to a branch of its own name on that remote,
which then becomes its upstream; it is never pushed onto the other branch. A push
rejected because the remote has commits you do not offers **Pull, then Push**,
**Force with Lease**, or Cancel; there is no separate force-push command. The
forced push always uses `--force-with-lease` (and `--force-if-includes` with Git
2.30 or later) and names the one branch it replaces: it is refused if the remote
branch has moved since your last fetch, or if a background fetch brought commits
you never had locally.

Clone, fetch, pull and push never wait for an answer in a terminal, and on
Linux Git runs without a controlling terminal. When a graphical askpass program
is set (`SSH_ASKPASS`, with a display on Linux), ssh asks for a key passphrase or
a new host key in its window. Otherwise ssh runs in batch mode unless you set an
ssh command of your own (`core.sshCommand` or `GIT_SSH_COMMAND`): a host key that
is not yet known, or a key whose passphrase is not in your ssh agent, fails at
once with ssh's message ("Host key verification failed.") — connect once from a
terminal, or add the key to your agent, then try again. On Windows, batch mode
uses the ssh that comes with Git for Windows. A remote whose push refspecs
(`remote.<name>.push`) all push `HEAD`, such as Gerrit's `HEAD:refs/for/main`,
keeps them, forced pushes included. Other push refspecs (notes, one fixed branch,
`refs/heads/*`) do not decide where the current branch goes: Editora names the
branch, so a push never sends only notes or force-pushes every branch.

### Automatic fetch

**Fetch automatically** (Settings → Version Control → Git, or
`git.toggleAutoFetch`) fetches the active repository in the background so
incoming commits show without asking. It is off by default; the interval is 10
minutes unless you change it (`git.setAutoFetchInterval`). The background fetch
never prompts, and runs only in a trusted folder or in a repository you have
fetched, pulled, or pushed in during the session. A repository on a
[remote host](#repositories-on-a-remote-host) is fetched on that host, by its
`git`, under the same rule.

## Branches

The branch dropdown has a search field over three sections: **Actions** (New
Branch, Pull, Fetch, Push, and the like), then **Local** branches with the
current one first, then **Remote** branches, grouped by remote when there are
several. It opens with the current branch selected (with a detached HEAD, or
**Local** folded, nothing that Enter would run is selected). Clicking a row
checks the branch out. Choosing a remote branch switches to a local branch
that tracks it, whatever that branch is called (the one of the same name
first). A branch checked out in another [work tree](#remotes-and-worktrees) is passed
over, since Git will not check out the same branch twice. When no free local
branch tracks it, a new tracking branch is created under the remote branch's
name; if a local branch of that name already exists (it tracks something
else, tracks nothing, or is checked out in another work tree), it asks for a
name for the new tracking branch instead. A row's `⋯` button, a
right-click, or the Menu key opens its actions; the same actions are in the
palette and under **Git ▸ Branches**.

Each section folds to its header: click the header, or select it and press
Enter, ← or →. A folded header shows how many rows it hides, and the choice is
remembered with the workspace. Typing in the search field looks through every
section, folded or not.

![The Branches popup over a Java file: a search field, an Actions section with New Branch, Update Project (Pull), Fetch, Push, Stash Changes, Unstash and Commit with their chords, then Local with master ticked and its origin/master upstream, and Remote with origin/master](/screenshots/docs/git-branches.png "The branch popup: actions first, then local and remote branches, each section foldable.")

| Action | Command |
| --- | --- |
| New branch from any branch or tag, with or without switching to it | `git.newBranchFrom` |
| Check out a tag, branch, or commit by name | `git.checkoutRevision` |
| Rename a local branch | `git.renameBranch` |
| Merge a branch into the current one | `git.mergeBranch` |
| Rebase the current branch onto another | `git.rebaseOnto` |
| Review the files that differ from the current branch | `git.compareBranch` |
| Set or unset the upstream | `git.setUpstream` / `git.unsetUpstream` |
| Delete a local branch | `git.deleteBranch` |
| Delete a branch on its remote | `git.deleteRemoteBranch` |

Deleting a local branch always asks first; when it has commits that are not
merged into the current branch, the question states how many. A branch checked
out in another worktree is not offered for deletion, and its Delete row says
where it is checked out. Deleting on the remote always asks. A local branch
whose upstream was deleted is marked "(gone)".

A new branch made from another (**New Branch From**) never tracks where it
started, even from a remote branch such as `origin/main`; set its upstream when
you push it. Merging and rebasing carry uncommitted changes across with
`--autostash`, as pulls do; if applying them back conflicts, they stay in the
stash and the conflicts appear in the Commit window. Checking out a tag or a
commit reports the detached HEAD. Leaving a detached HEAD that has commits no
branch or tag holds — by switching branch, checking out a revision, a tag, or a
remote branch, making a new branch and switching to it, or turning a stash into
a branch — asks first, since
afterwards only the reflog leads to them.

## Remotes and worktrees

**Manage Remotes…** (`git.remotes`) lists the repository's remotes with their
fetch and push URLs and can add, rename, re-point (Set URL), fetch, prune, and
remove them; each action is also a command (`git.addRemote`,
`git.renameRemote`, `git.setRemoteUrl`, `git.pruneRemote`, `git.removeRemote`)
that acts on the remote selected in the manager while it is up, or on one you
pick. **Git ▸ Remotes & Worktrees** has the pull variants, Push To, Push Tags,
Fetch Remote, automatic fetch, and the two managers. **Fetch Remote…**
(`git.fetchRemote`) fetches one remote and prunes its deleted branches; a fetch
or prune started from the manager reports in the status bar and leaves the
manager closed.

**Manage Worktrees…** (`git.worktrees`) lists the repository's worktrees and can
add one (for an existing branch or a new one; a relative folder is created
beside the repository), open one in a new window, remove one, and prune entries
whose folder is missing (`git.addWorktree`, `git.openWorktree`,
`git.removeWorktree`, `git.pruneWorktrees`). Adding one needs a branch: the new
branch's name, or the existing branch or commit to check out. Removing a worktree
deletes its folder and keeps its branch and commits; one that holds modified or
untracked files asks again before deleting them. A worktree opened in a new
window becomes a project; when Editora removes or prunes that worktree in the
same session, the project it made goes too (a project you had already is kept).
In a repository on a [remote host](#repositories-on-a-remote-host), worktrees
are added and removed on that host, and Open in New Window says it opens local
worktrees only.

## Tags

| Action | Command |
| --- | --- |
| New tag at the commit selected in the Git Log, or at HEAD | `git.tag.create` |
| Check out a tag | `git.tag.checkout` |
| Push one tag | `git.tag.push` |
| Delete a tag | `git.tag.delete` |

The same actions are on a commit row's context menu in the Git Log. A new tag
with a message is annotated; leaving the message empty makes a lightweight tag.
Deleting a tag removes it locally only.

## Changes in the editor

The gutter marks are also the way to work on one change at a time, compared
with HEAD.

| Action | Command | Emacs key | Other keymaps |
| --- | --- | --- | --- |
| Go to the next / previous change | `git.nextChange` / `git.previousChange` | `C-x v ]` / `C-x v [` | VS Code: Alt+F5 / Alt+Shift+F5; IntelliJ: Ctrl+Alt+Shift+Down / Ctrl+Alt+Shift+Up |
| Peek the change at the caret | `git.peekChange` | (palette) | — |
| Revert the change at the caret | `git.revertHunk` | (palette) | — |
| Stage the change at the caret | `git.stageHunk` | (palette) | — |
| Stage / unstage the whole file | `git.stageFile` / `git.unstageFile` | (palette) | — |
| Discard the file's changes | `git.discardFile` | (palette) | — |

Next and previous wrap around at the ends of the file and report the position
("Change 2 of 5"). Clicking a change bar, or **Peek Change at Caret**, opens a
card showing the old and new lines with **Revert Hunk**, **Stage Hunk**, **Copy
Old Text**, **Open Diff**, and Previous / Next. While the card is open, **Peek
Next Change** and **Peek Previous Change** (`git.peekNextChange`,
`git.peekPreviousChange`) move it, and `git.copyPeekedOldLines` copies its old
lines.

![Shelf.java with two changed lines marked in the gutter and the peek card for the one at the caret: Added, change 2 of 2, the working-tree line in green, and Previous, Next, Revert Hunk, Stage Hunk, Copy Old Text and Open Diff buttons](/screenshots/docs/git-peek-change.png "Peek Change at Caret shows one hunk against HEAD with the buttons to revert or stage it.")

**Revert Hunk** puts the HEAD lines back into the buffer as an ordinary
undoable edit; nothing is written to disk. **Stage Hunk** saves the file first
and then stages only that change. Change bars and blame annotations follow
unsaved insertions and deletions as you type.

## Git Log

The Git Log (`tool.gitLog`, `C-x v S-l`) lists the current branch's commits and
loads more as you scroll, a page at a time, so it reaches the whole history
(`git.log.loadMore` does the same from the keyboard).

![The Git Log maximized: a commit graph beside each row's hash, branch and tag labels, subject, author and date, and below it the selected merge commit's details and changed files](/screenshots/docs/git-log.png "The Git Log with its commit graph; the selected commit's details and files are underneath.")

- **All branches** (`git.log.toggleAllBranches`) lists the commits of every
  branch, remote, and tag.
- A **commit graph** is drawn beside the rows when the list is a plain walk of
  the history. It is hidden for a file history, a search result, and a filtered
  list.
- The **details area** shows the selected commit's full message, author, date,
  hash, parents, and refs, above the files it changed.
- **Enter** on a commit (`git.log.reviewCommit`) opens everything it changed as
  one multi-file review. With two commits selected, **Compare Selected Commits**
  (`git.log.compareSelected`) reviews the difference between them.
- A commit's context menu has Copy Hash, Checkout, New Branch from Here…, New
  Tag…, Revert Commit, Cherry-Pick, Reset Current Branch to Here, and Create
  Patch…. Reverting or cherry-picking a merge commit first asks which parent is
  the mainline. The matching `Git Log: …` palette commands act on the commit selected
  in the visible log; when the log is hidden they open it and ask for a commit.
- **File history** follows the file across renames.

### Git history of one file

**Show Git History** (`git.fileHistory`; `C-x v l`, or Ctrl+Alt+Shift+H in the
other keymaps, ⌃⇧⌘H on macOS) lists the commits that changed the active file, in the Git Log
tool window under a **Git history: name** chip. It is also in a tab's menu, the
Project tree, and **Git ▸ History & Blame**.

- **Enter** or a double-click on a commit opens what it changed in that file.
  The row's menu starts with **Show Diff** and **Compare with Working Tree**,
  and still has **Review Commit** for the whole commit.
- The chip's **✕**, or Escape in the commit list, returns to the branch log.
- **Load More** continues through the file's whole history, a page of 200
  commits at a time.
- A search finds commits from before a rename too. It covers the newest 5,000
  commits of the file's history, and the list says so when older ones were not
  searched. A `path:` term is ignored in a file history, and the status bar says
  so.
- A file that is untracked or not yet committed says it has no commits, and a
  shallow clone says under its last row that older history is not available
  locally.
- In the list of files under the commit, Enter compares that revision with the
  working file. When the file no longer exists in the working tree, it shows
  the commit's change instead.

Typing in the filter box narrows the commits already loaded. Pressing **Enter**
there (or **Git: Search Whole History**, `git.log.searchHistory`) searches the
whole history for what the box says; `git.log.search` asks for the terms
first:

| Term | Meaning |
| --- | --- |
| any other word | message text; every word must match |
| `author:name` | author name or e-mail contains `name` |
| `content:text` | commits that add or remove `text` |
| `since:date`, `until:date` | anything Git reads as a date |
| `path:glob` | commits touching a matching path, from the repository root |

Terms are separated by blanks, double quotes keep blanks inside one term, and
matching ignores case. A running search is cancelled when you replace or clear
it.

**Reset** (Soft, Mixed, or Hard) states how many commits leave the branch and
how many of them are not on the upstream, and asks before stranding any.
Reverting a merge commit asks which parent is the mainline.

## Blame

`git.toggleBlame` (`C-x v g`) shows each line's last commit, author and date,
in a gutter column; **Annotate Current File** (`git.annotate`) turns it on. `git.blameShowCommit` opens the diff of the commit that last
changed the caret line.

![A Java file with the blame gutter on: each line shows its author and date, and the three changed lines show You · Uncommitted changes](/screenshots/docs/git-blame.png "Blame annotations in the gutter; lines you have not committed yet say so.")

- **Annotate Previous Revision** (`git.blamePreviousRevision`) opens the file as
  it was before the commit the caret line is blamed on, annotated in turn, so
  you can walk a line back through history.
- **Ignore Whitespace Changes** (`git.blame.ignoreWhitespace`) looks through
  commits that only changed whitespace (`git blame -w`).
- **Detect Moved and Copied Lines** (`git.blame.detectMoves`) follows lines
  moved or copied within and between files (`git blame -M -C`).
- A `.git-blame-ignore-revs` file at the repository root is used automatically
  when Git's `blame.ignoreRevsFile` setting names no file. A `blame.ignoreRevsFile`
  in your global Git configuration that names a file this repository lacks is
  passed over, so it does not stop blame here. The same holds on a
  [remote host](#repositories-on-a-remote-host), where the host's Git
  configuration is the one read.

The two toggles apply to the window they are set in.

## Permalinks

**Copy Permalink** (`git.copyPermalink`, in **Git ▸ History & Blame**) copies
the web address of the current file at the HEAD commit, with the selected lines
(or the caret's line) marked, ready to paste in a chat or an issue. On the
[Project Map](/docs/project-map) it is in a code card's menu and in a
file's or folder's menu (`projectMap.copyPermalink`).

- It works with GitHub, GitLab, Bitbucket (Cloud, and Server / Data Center),
  Gitea, Forgejo, Codeberg, sourcehut and Azure DevOps remotes, on the remote the
  branch tracks (else `origin`, else the first). An SSH remote whose host is an
  `~/.ssh/config` alias (`git@github-work:org/repo.git`) links to the host the
  alias stands for, and `ssh.github.com` to github.com.
- On GitHub, a link with lines into a file GitHub shows rendered (Markdown, a
  notebook, SVG, CSV) opens its source view, where the lines are marked.
- The lines stay right when the file has unsaved or uncommitted edits above
  them. When the selected lines are all uncommitted, the link is to the whole
  file.
- The status bar shows the link it copied, says when HEAD is not pushed yet
  (the link works once it is), and says why there is none: no repository, no
  commit, no remote on a web host, or a file not committed yet.

## Stashes

**Stash Changes…** (`git.stash`) takes an optional message and three options:
include untracked files, stash the staged changes only (Git 2.35 or later), and
keep the staged changes in the index.

**Stashes…** (`git.stashes`) lists every stash with the files it holds. Enter
shows a stash's changes as a review, and each stash can be applied, popped,
dropped, turned into a branch, or have its name copied. `git.stashPop` applies
and removes the latest stash, `git.unstash` applies one you pick, and
`git.stashDrop` deletes one. The other actions each pick a stash first: **Pop
Stash…** (`git.stashPopChosen`), **Show Stash Changes…** (`git.stashShow`),
**Branch from Stash…** (`git.stashBranch`) and **Copy Stash Name…**
(`git.stashCopyName`).

A stash that applies with conflicts is kept, and the conflicted files appear in
the Commit window. A stash that would overwrite local changes is not applied.

## Patches

**Create Patch…** (`git.createPatch`) writes the staged changes, the unstaged
changes, or a commit you pick as a patch, to a file or to a new untitled buffer.
**Create Patch…** on a Git Log row (`git.log.createPatch`) does the same for
that commit.

**Apply Patch…** (`git.applyPatch`) applies a `.patch`/`.diff` file, or the
active buffer if it is a patch, to the working tree or to the index. The patch
is checked first: one that does not fit changes nothing, shows Git's message,
and offers **Try 3-Way Merge**, which applies what fits and leaves conflict
markers where it does not. Those conflicts appear in the Commit window like any
others.

To read a patch before applying it, see
[Diff & merge](/docs/diff-merge#diff-viewer).

## Repositories on a remote host

A repository in a [remote folder](/docs/remote) works as a local one does, once
its host [may run commands](/docs/remote#allowing-a-host-to-run-commands):
branch and ahead/behind counts, changed files, change bars, log and blame,
staging whole files or single hunks, committing, branches, stashes, and fetch,
pull and push. Every command is the host's own `git`, run over the same SSH
connection with the host's credentials (its SSH keys and credential helpers);
nothing prompts, so an HTTPS remote that needs a password fails rather than
asking for it. To use a `git` other than the one on the host's `PATH`, set
**Git** for that host in Settings → Remote; *Git: Set Git Command* is for this
machine only.

A remote project can hold other repositories: a nested repository, a submodule
or another worktree. The Project tree's and the tab menu's Git actions on their
files act on the repository the file belongs to, as they do locally. Editora asks
the host where the project's repositories are when the project opens, after a
Clone or Init, and when the Project tree is refreshed. Until the host has
answered, a file inside the current repository's folder counts as part of it.

In a remote project's window, **Clone** clones on that host into a folder you
type as the host names it (`~` is the account's home there); the form proposes
the project's parent folder, and the Browse button, which picks a local folder,
is hidden. **Create a repository** (`git.init`) creates it
on the host too. The [GitHub](/docs/github) tool window and commands use the
host's own `gh`, signed in there.

A repository in a project whose tools [run in a container](/docs/remote#tools-in-a-container)
or [in a WSL distribution](/docs/remote#tools-in-wsl-windows) works the same way,
with the `git` found there and its configuration and credentials. Blame's
ignore-revs settings work there as on a host.

## What runs automatically

Some Git commands run without you asking: status, the gutter diff, log, blame
and file-content lookups happen as you open files and switch tabs. Editora runs
those background reads so that a repository's own configuration cannot start a
program through them. `core.fsmonitor` and hooks are overridden, external diff
and textconv drivers are switched off, and Git never waits on a terminal prompt.
A partial clone is also told not to fetch missing objects on demand; that needs
Git 2.45 or newer (or a 2.39.4–2.44.1 maintenance release), and older versions
ignore it.

One thing is not covered: filter drivers selected through `.gitattributes`
(Git LFS, for example) still run when Git has to re-hash a modified file.

Commands you start yourself (commit, checkout, pull, push and the rest) are
not restricted, so your hooks run as they do in a terminal. A revision name
that starts with `-` is refused rather than passed to Git, where it would be
read as an option.

None of the automatic reads contact a remote. The only network command Editora
starts on its own is the optional [automatic fetch](#automatic-fetch), which is
off by default.

## Seeing what it ran

The **Output** console (`tool.buildOutput`) has a **Git** tab holding a
transcript of what Editora ran on your behalf: the command line, its output, and
its exit code and duration.

It logs the commands you *asked for*: commit, push, pull, fetch, checkout,
stash and clone. It leaves out the `status` and `diff` reads it re-runs on every
tab switch, focus change and save, which would bury them.

Clone, fetch, pull, and push open the Git tab as soon as they start and show the
command running: the `git` command line, then Git's own progress (counting,
receiving, resolving, writing) updating in place, then the exit line. While one
runs, the tab's **Stop** button or `git.cancel` cancels it. A command that fails
also brings the tab forward. A local command that succeeds does not, so a Git
Log action leaves the log in view and the transcript is there when you open the
window.

A push, pull, commit or other Git command still running when you close its
window finishes on its own. If it then fails, no dialog opens, since nobody could see it. Another open
Editora window says so instead, in its status bar and message log as an unread
error naming the project, with Git's output on one line: *Push failed (in the
window of site, now closed): remote: rejected by policy · …*. With no other
window open, the failure goes to the
[Application Log](/docs/troubleshooting#seeing-errors-in-a-packaged-build).

After a pull, the transcript lists the files it changed. Clicking a file's
change graph (`4 ++--`) opens a diff of that file between the commit you were on
and the one the pull brought; clicking the file name opens the file.

For comparing files and resolving merge conflicts, see
[Diff & merge](/docs/diff-merge). For pull requests, reviews, and CI runs, see
[GitHub](/docs/github).
