---
title: GitHub
description: "Review, check out and create pull requests, submit reviews, watch CI checks, and read failed CI logs, all through your own gh CLI."
category: Version control
order: 3
---

Editora talks to GitHub through the [`gh` CLI](https://cli.github.com) you
already have installed and signed in. **Editora never handles a token.** It
shells out to `gh`, the same way the [Git integration](/docs/git) shells out
to `git`, so whatever `gh` can reach, Editora can reach, including GitHub
Enterprise, with no host configuration of its own.

It's **on by default** but self-gating, so it stays invisible until all of
these are true:

1. The GitHub integration is enabled in **Settings → Version Control → GitHub**,
   and [Git support](/docs/git) is on.
2. `gh` is on your `PATH` (or its location is set in **gh command / path** on
   that Settings page, or with **GitHub: Set gh Command…**, `github.setCommand`;
   a path with spaces in it works). When nothing runs at the path you set, the
   page's **Detected** row says **No gh at** that path.
3. `gh` is signed in (`gh auth status`).
4. One of the repository's remotes points at a GitHub host.
5. The repository has an open pull request, issue, or workflow run.

If any of those fail, the tool window and status-bar indicator don't appear, and
each command reports the reason.

### Requirements and hosts

- **`gh` 2.50 or newer** is needed for the [status-bar checks
  indicator](#ci-checks-in-the-status-bar) and **GitHub: Show Pull Request
  Checks**. With an older `gh` everything else works; Settings, Doctor and the
  checks command say which version is needed.
- **`gh` 2.81 or newer** is needed for the account name in the tool window's
  [info row](#the-info-row).
- **Any remote counts**, not only `origin`, and both URL forms are recognised,
  including the scp form without a user (`github.example.com:org/repo`).
- **SSH host aliases and port 443**: a remote such as `git@github-work:org/repo.git`,
  whose host is a `Host` alias in `~/.ssh/config` (two accounts on one site), is
  recognised by the host the alias stands for, as `ssh -G` reports it (on the
  machine the repository is on, for a remote folder). GitHub's
  SSH-over-443 address, `ssh.github.com`, counts as github.com. After you edit
  `~/.ssh/config`, or a file it pulls in with `Include`, the new aliases are
  picked up on their own; **GitHub:
  Refresh** asks `ssh` again.
- **GitHub Enterprise**: a host is treated as GitHub when `gh` has an account on
  it, so an Enterprise host you ran `gh auth login --hostname` for works like
  github.com.
- **Remote folders**: a repository in a [remote folder](/docs/remote) uses the
  host's own `gh`, signed in there (a per-host **GitHub CLI** command can be set
  in Settings → Remote). Links still open in your local browser.
- **Containers and WSL**: a project whose tools [run in a container](/docs/remote#tools-in-a-container)
  or a WSL distribution uses the `gh` found there, signed in there.

### When Git support is off, or GitHub can't be reached

GitHub rides on the Git integration. With Git support off, every GitHub command
says that GitHub features need Git support and names the command that turns it
on (**View: Toggle Git Support**, `view.toggleGit`).

Being offline doesn't disable the integration. If `gh` has an account but GitHub
can't be reached to check the sign-in, the commands and the tool window stay
available, Settings shows that the sign-in could not be checked, and a command
that still can't reach GitHub shows `gh`'s own network error. A sign-in that
GitHub rejects is reported as such, with the advice to run `gh auth refresh`.
With accounts on several hosts, a repository on the host whose sign-in was
rejected says so, with the command for that host (`gh auth refresh -h <host>`),
instead of the tool window just staying hidden. A fork with a remote on that host
and one on a host that is signed in keeps working, unless the remote `gh` would
use (`upstream` first, then `github`, then `origin`) is the rejected one. An organization that requires
single sign-on (SAML) for `gh`'s token is reported once, with the advice to
authorize `gh`; Editora does not keep asking until you run **GitHub: Refresh**.

## Signing in

Authentication happens outside the editor. Run:

```bash
gh auth login
```

Editora reads the result but never stores, prompts for, or transmits a
credential of its own. A missing or signed-out `gh` is checked again the next
time a GitHub command needs it, so installing `gh` or signing in takes effect
without a restart. **GitHub: Refresh** (`github.refresh`) re-detects it on
demand.

## Reviewing a pull request

**GitHub: Review Pull Request Diff…** (`github.viewPrDiff`) lists the open pull
requests and opens the one you pick as a **Files changed** tab, modeled on
GitHub's own review view:

- Every changed file is listed with its status letter and per-file `+` / `−`
  counts.
- The pull request description renders as **Markdown** in a card above the list.
  Long descriptions collapse behind a *Show more* toggle.
- Clicking a file opens that file's **read-only diff** (base against head).
- **Open all** opens every file at once. Past a dozen files it confirms first.

The tab also carries *Open on GitHub*, *Submit review…* and *Refresh* links
(`github.openReviewedPr`, `github.submitReview`, `github.refreshReviewedPr`),
and **Open all** is **GitHub: Open All Reviewed Files**
(`github.openReviewedFiles`) in the palette. A
single-file pull request opens the same tab, so its description and those links
stay reachable. Re-running the command on a pull request you already have open
re-selects and refreshes that tab rather than opening a second one.

![A Files changed tab for a pull request: the description rendered as Markdown in a card, Open on GitHub, Submit review… and Refresh links, and the changed files listed with their status letters and plus and minus counts](/screenshots/docs/github-pr-review.png "Review Pull Request Diff: the description, the file list, and a read-only diff a click away.")

Inside any read-only diff, `n` and `p` step to the next and previous change, so
you can read a file end to end without reaching for the mouse. Each hunk is shown
at its real line numbers, and a missing final newline is marked.

### Large pull requests

GitHub refuses to produce a single diff for a pull request with more than 300
files or 20,000 lines. Editora then builds the review from GitHub's file list
instead, and a notice in the review tab says so. Files whose changes are too
large for that list are counted in the notice and can be opened on GitHub. If the
list itself is cut short, the tab says how many files it shows.

The pickers behind **Review Pull Request Diff…** and **Check Out Pull Request…**
list the first 200 open pull requests and say so when there are more; the tool
window can load the rest.

### Submitting a review

**GitHub: Submit Pull Request Review…** (`github.submitReview`), or the *Submit
review…* link in the review tab, opens a small form: pick **approve**, **request
changes**, or **comment**, and add a body. The form starts on **comment**, so
submitting it with nothing typed does nothing; an approval has to be picked, and
only then may the body be empty. The form stays open while `gh` runs and keeps
what you typed if the submission fails.

A review submitted from the review tab applies to the commit the tab showed: if
new commits were pushed while you read, your approval is for the commit you
reviewed, not for them. Refresh the tab to review the newest commit. If the
refresh can't read the pull request's details again, the tab keeps the files it
showed and says so, rather than show new changes under the old commit; refresh
again before you submit. If commits are pushed while the tab is loading, the
files may belong to another commit than the details: the tab says so, and until
you refresh it a review from it is not tied to a commit. A review of a pull
request on a GitHub Enterprise address with a port number is not tied to a
commit either.

## Working with pull requests

- **GitHub: Check Out Pull Request…** (`github.checkoutPr`) picks an open pull
  request and checks out its branch. Open files that are unmodified reload from
  disk, and the Git surfaces refresh, the same as when you switch branches.
  From a detached HEAD, Editora first asks whether to leave the commits made
  there that no branch or tag keeps, as every other checkout does.
- **GitHub: Create Pull Request…** (`github.createPr`) creates one from the
  current branch. See [Creating a pull request](#creating-a-pull-request).
- **GitHub: Open File on GitHub** (`github.openOnGitHub`) opens the active file
  in your browser at the caret line, on the current branch. This routes through
  `gh browse`, so it stays correct on GitHub Enterprise and on repositories whose
  default branch has been renamed.

### Creating a pull request

**GitHub: Create Pull Request…** (also the *Create Pull Request…* button in the
tool window's toolbar) first checks the branch, then opens a form that is already
filled in.

Before the form opens:

- The repository's **default branch** is refused, with a message asking you to
  switch to the branch you want reviewed. So is a detached HEAD.
- If the branch **already has an open pull request**, Editora shows its number
  and title and offers **Open Pull Request** instead of a form `gh` would
  reject.

The form starts from what the branch contains:

- **Title and description** come from the branch's commits. One commit gives its
  subject and body. Several commits give a title made from the branch name and a
  bulleted list of the commit subjects, oldest first.
- If the repository has a **pull request template**, the template is the
  description instead.
- **From branch** shows the branch the pull request comes from.
- **Base branch** is a chooser listing the remote's branches, with the
  repository's default branch selected. You can also type a branch name; leaving
  it blank uses the default branch.
- **Reviewers**, **Assignees** and **Labels** take comma-separated values, and
  **Create as draft** opens the pull request as a draft.
- When the branch has no upstream, or is ahead of it, a **Push branch … to
  origin first** checkbox is offered, already ticked, so the pull request
  includes your newest commits.

The form stays open while `gh` runs. If creation fails, it keeps what you typed.

## The tool window

The **GitHub** tool window (`M-g p`, or `tool.github`) has three segments:

- **Pull requests** and **Issues**, where a double-click or `Enter` reviews the
  pull request's diff or opens the issue in your browser. Each row's right-click
  menu offers check out, review diff, open on GitHub, and copy URL.
- **Runs**, listing recent GitHub Actions workflow runs with a state glyph. The
  row menu offers view failure log, rerun, rerun failed jobs, cancel, open, and
  copy URL, each enabled only where it makes sense for that run's state. A run
  waiting for approval on GitHub (⏸, for example a first-time contributor's pull
  request) is not a failure: it has no log and nothing to re-run here; open it
  on GitHub to approve it.

**GitHub: Show Pull Requests** (`github.showPrs`), **GitHub: Show Issues**
(`github.showIssues`) and **GitHub: Show Workflow Runs** (`github.showRuns`)
open the window on a given segment. The row menus also open with the Menu key,
and the segment buttons can be reached with Tab. A spinner in the toolbar shows
while `gh` is working.

![The GitHub tool window on its Runs segment: Pull Requests, Issues and Runs buttons, an info row with the repository URL, the master branch and the adriandeleon account, a filter field, and recent workflow runs each with a state glyph, workflow name and commit subject](/screenshots/docs/github-tool-window.png "The GitHub tool window: pull requests, issues and workflow runs, straight from gh.")

The window is **repo-scoped, not file-scoped**, so it stays put as you switch
tabs, including onto the Welcome tab.

### The info row

A row under the toolbar shows what the window is looking at:

- The **repository's URL**, as `gh` resolved it for this folder. Click it to open
  the repository on GitHub; right-click to copy the URL. **GitHub: Open
  Repository on GitHub** (`github.openRepository`) and **GitHub: Copy
  Repository URL** (`github.copyRepositoryUrl`) do the same from the palette.
  The lists and every run action address this repository.
- The **branch** checked out.
- The **GitHub account** `gh` is signed in with on that host (needs `gh` 2.81 or
  newer).

### State, "Mine", and loading more

The pull request and issue lists are fetched for one state at a time. The state
chooser in the toolbar offers **Open** (the default), **Closed**, **Merged**
(pull requests only) and **All**. **Mine** (`github.toggleMine`) narrows the
list to the ones you opened; it applies to pull requests and issues together.
Runs have neither control. **GitHub: Refresh List** (`github.refreshList`)
fetches the shown list again.

A list shows the first 50 pull requests or issues, or the first 30 runs. When
there are more, the last row reads *Showing the first N — load more*; activating
it fetches the next batch.

### Filtering and keys

All three segments share **one filter box**. It matches on everything a row
shows or tooltips (number, title, author, branch, state, labels), and a leading
`#` is optional, so `42` and `#42` both find PR 42. The filter clears when you switch
segment (a query typed against pull requests means nothing for runs) and survives
a refresh.

The filter searches the rows already loaded. When it finds none of them and
GitHub has more, a row offers to **search GitHub** for the text (pull requests
and issues), which lists what GitHub finds, descriptions included. On the pull
requests, a number such as `#1234` offers to open that pull request instead.
Changing the filter goes back to the plain list.

The window opens with focus in the filter and the first row selected. `C-n` /
`C-p` move the selection **without leaving the box**, Down enters the list (where
bare `n` / `p` also move, since the list holds no text input), and Enter opens.

### Acting on the selected row from the palette

These commands act on the row selected in the tool window, so they can be run
from the palette or bound to keys:

- **GitHub: Open Selected Row on GitHub** (`github.openSelected`) opens the
  selected pull request, issue or run in the browser.
- **GitHub: Review Selected Pull Request** (`github.reviewSelectedPr`) and
  **GitHub: Check Out Selected Pull Request** (`github.checkoutSelectedPr`)
  review or check out the selected pull request.
- **GitHub: View Failure Log of Selected Run** (`github.viewSelectedRunLog`)
  shows the failed jobs' log of the selected run.

- **GitHub: Rerun Selected Workflow Run** (`github.rerunRun`) re-runs the
  selected run. A run still in progress has to finish or be cancelled first.
- **GitHub: Rerun Failed Jobs of Selected Run** (`github.rerunFailedJobs`)
  re-runs only the failed jobs of a failed run.
- **GitHub: Cancel Selected Workflow Run** (`github.cancelRun`) cancels a queued
  or running run, after asking: a cancelled run can't be resumed, only run again
  from the start.
- **GitHub: Copy URL of Selected Row** (`github.copyUrl`) copies the GitHub URL
  of the selected pull request, issue or run.

With no row selected, each one says so and asks you to select a row first.

## Reading a failed CI run

Double-clicking a failed run, or
running **GitHub: View CI Failure Log…** (`github.viewRunLog`), pulls that run's
failure log into a **CI** tab of the shared
[Output](/docs/build-tools) console, with errors and warnings colored.

**The stack frames are clickable.** A CI log prints paths as they
existed on the runner, like
`/home/runner/work/your-repo/your-repo/src/main/java/…`, which doesn't exist on
your machine. Editora maps those back onto your local checkout by matching
progressively shorter repository-relative suffixes, so clicking a frame in a red
build opens the file at that line.

Logs are fetched once, because a finished run's log doesn't grow. A very large
log is trimmed to its last 3,000 lines, which is where the failure is, with a
note that earlier output was omitted. The console's **Stop** button stops `gh`
while a log is still downloading, and so does asking for another log.

## CI checks in the status bar

When the current branch has a pull request, the status bar shows a compact checks
indicator (a check, a cross, or a circle, plus a failure count) that names the
pull request by number. It appears on its own: Editora fetches the checks once
per repository and branch, without waiting for a command. Its tooltip gives the
passed, failed, pending and skipped counts.

While a check is still pending, the indicator updates by itself. Editora asks
again after 15 seconds, then 30 seconds, 1 minute and 2 minutes, then every 5
minutes, and stops after about two hours. A poll that comes due while the window
is unfocused waits until it has focus again. Once nothing is pending, there is
**no background polling**; **GitHub: Refresh** fetches the checks again.

The indicator needs `gh` 2.50 or newer.

### The list of checks

Click the indicator, or run **GitHub: Show Pull Request Checks**
(`github.showChecks`), to open a list of the pull request's checks. Failed checks
come first, then pending ones. Each row shows the check's state, its name and
its workflow, with:

- **Open**, which opens that check on GitHub.
- **View failure log**, on a failed GitHub Actions job, which loads the log into
  the **CI** tab as described in [Reading a failed CI
  run](#reading-a-failed-ci-run). A check reported by an external service has no
  log `gh` can fetch, so it offers only the link.

The list has its own **Refresh** button and is usable from the keyboard (Tab,
Enter, Escape).

When `gh` can't look the checks up (offline, a rate limit, a rejected sign-in),
that isn't taken to mean the branch has no pull request: the indicator keeps what
it showed, the list says why the lookup failed, and Editora tries again after a
few minutes (three times at most, then on **GitHub: Refresh**). A retry that
comes due while the window is unfocused waits until it has focus again.

### What Editora asks GitHub on its own

Besides the checks, Editora calls `gh` unprompted in one case. To decide whether
to show the tool window, it checks that `gh` is signed in and, once per
repository, asks GitHub whether there is an open pull request, an open issue, or
a workflow run. These background calls don't spin the tool window's spinner and
aren't written to the Output console's **GitHub** transcript.

## The Git menu

**Git ▸ GitHub** is a submenu holding the GitHub commands: the tool window and
its three segments, create, review, check out and submit review, the checks list
and the CI failure log, the run actions, **Open File on GitHub**, **Copy URL of
Selected Row**, and **Refresh**.

## Commands

| Command | Id |
| --- | --- |
| GitHub: Review Pull Request Diff… | `github.viewPrDiff` |
| GitHub: Submit Pull Request Review… | `github.submitReview` |
| GitHub: Check Out Pull Request… | `github.checkoutPr` |
| GitHub: Create Pull Request… | `github.createPr` |
| GitHub: Open File on GitHub | `github.openOnGitHub` |
| GitHub: Show Pull Requests | `github.showPrs` |
| GitHub: Show Issues | `github.showIssues` |
| GitHub: Show Workflow Runs | `github.showRuns` |
| GitHub: Show Pull Request Checks | `github.showChecks` |
| GitHub: View CI Failure Log… | `github.viewRunLog` |
| GitHub: Rerun Selected Workflow Run | `github.rerunRun` |
| GitHub: Rerun Failed Jobs of Selected Run | `github.rerunFailedJobs` |
| GitHub: Cancel Selected Workflow Run | `github.cancelRun` |
| GitHub: Copy URL of Selected Row | `github.copyUrl` |
| GitHub: Refresh | `github.refresh` |
| Tool Window: GitHub | `tool.github` (`M-g p`) |
| GitHub: Toggle Support | `view.toggleGithub` |

## Limitations

The integration can only do what `gh` can. Inline and threaded review
comments, and GitHub's "suggested changes", have no `gh` subcommand, so they
aren't supported yet. Neither are notifications, job-level drill-down into a run,
or artifact downloads. Pull request diffs are fetched whole rather than per file
(see [Large pull requests](#large-pull-requests) for the exception), and a file
with no text changes, such as a rename, a mode change or a binary file, has no
diff to show.

The integration is also disabled in [Simple UI mode](/docs/workspace), along with
the other heavier features.
