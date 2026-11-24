# GitHub integration (`gh` CLI)

How `github/GitHubService` and `ui/GitHubCoordinator` decide that `gh` is usable, how calls are scheduled
and stopped, and the rules the tool window, the review tab and the status bar's checks segment depend on.
The slice-by-slice history is in the [architecture catalog](../reference/architecture-catalog.md); where the
two differ, this page and the code are right.

Editora never handles a token: `gh` holds the credentials, Editora only shells out through
`process/ProcessRunner`, with `GH_PROMPT_DISABLED` / `GH_NO_UPDATE_NOTIFIER` / `GH_PAGER=cat` / `NO_COLOR`.
The minimum is **gh 2.50** (`GhVersion`): everything else exists since 2.16, but `pr checks --json` arrived
in 2.50. An older gh loses only the checks segment — it is not asked or polled, `github.showChecks` says
which gh it needs, and Settings and the Doctor name it.

## Is gh usable? (`GitHubService.detect` → `Availability`)

- The probe is `gh --version`, then **`gh auth status --json hosts`** (gh 2.81+, parsed by the pure
  `GhAuthStatus`). The exit code of plain `gh auth status` is 1 both for "never logged in" and for "could
  not reach GitHub", so it cannot be the gate; the JSON still lists the accounts when offline. Before 2.81
  the probe falls back to that exit code, where a failure can only be read as *unverified* unless gh says
  outright that there is no login.
- `AuthState`: `SIGNED_IN`, `UNVERIFIED` (an account exists, GitHub was unreachable), `SIGNED_OUT`,
  `REJECTED` (HTTP 401 — `gh auth refresh`). With several hosts the overall state is `SIGNED_IN` as soon as one
  host accepts its token, so the refused ones are kept apart (`Availability.rejectedHosts`): a repository on one
  of them is told, once per repository and by every command, to run `gh auth refresh -h <host>`
  (`status.github.hostRejected`) instead of the window silently staying hidden. Only when gh would use the
  remote on that host (`GitHubRemote.refusedHost`: among the remotes on a host gh has an account on,
  `upstream`, `github`, `origin`, then the first other — gh's order; `gh-resolved` is not read), or when no
  remote is on a signed-in host: a fork whose other remote is on a signed-in host keeps working. **`Availability.ready()` is true for `UNVERIFIED`**: being
  offline at startup must not disable the integration for the session; a command that still cannot reach
  GitHub shows gh's own network error.
- A probe that did not answer in time is **never cached**. Concurrent `detect` calls share one probe.
  `setCommand` drops the cached answer only when the command tokens change (it runs on every settings
  save), and the previous answer stands while a re-probe runs.
- A command that meets a cached negative re-probes (`redetectIfOlderThan`, bounded), so installing gh or
  running `gh auth login` takes effect without `github.refresh`.
- The gh path setting goes through `process/ConfiguredCommand` (shared with git): an existing file is one
  executable whatever spaces its path has; anything else is tokenized quote-aware.
- GitHub rides on the Git integration. With Git support off every `github.*` command says so and names the
  command that turns it on (`GitHubCoordinator.disabledReason`).

## Tool-window gating

The stripe shows when GitHub is enabled, gh is ready, **one of the repository's remotes** is on a GitHub
host, and the repository has something to show.

- Host matching (`GitHubRemote.anyGitHub`): all remotes count, not only `origin`; the scp form needs no
  user (`host:org/repo`); a host is "GitHub" when gh has an account on it (`Availability.hosts()`), and the
  host-name heuristic decides only when those hosts are unknown.
- SSH hosts are resolved first (`git/SshHosts`, shared with the permalinks of `git/GitWebLinks`): GitHub's
  SSH-over-443 endpoint `ssh.github.com` is `github.com` (also `altssh.gitlab.com`, `altssh.bitbucket.org`), and
  an `~/.ssh/config` alias (`git@github-work:o/r.git`) is asked of `ssh -G <alias>` — off the FX thread
  (`GitHubService.resolveRemoteHosts`, its own lane), 3 s at most, through the repository's exec target (a
  remote, WSL or container project asks its own ssh) — falling back, for a local repository only, to reading
  `~/.ssh/config` (`Host` / `HostName` / `Port`, globs, `!` negation; not `Match` or `Include`) when ssh
  cannot be run. The alias goes to ssh as written (ssh's `Host` matching is case-sensitive); only the answer is
  lower-cased. An `ssh -G` answer is cached per (machine, alias) until `github.refresh` (`SshHosts.invalidate`)
  or until the local `~/.ssh/config`, or a file its `Include` lines pull in (globs, `~/`, relative to `~/.ssh`,
  followed into included files, at most 32 files), changes (path, modification time and size:
  `SshHosts.configFingerprint`); a fallback answer (ssh failed or timed
  out) for 60 s. The lookup does not hold the map's lock while ssh runs. `ssh -G`'s `port` also reaches
  `GitWebLinks`: a Bitbucket Server is recognised by port 7999 from the config as well as from the URL. Only `ssh://` and scp-style remotes are resolved; an HTTPS URL names its host outright. The
  gating runs first on the raw URLs and again when the resolved ones arrive.
- Activity (`GitHubService.openActivity`) answers `YES` / `NONE` / `UNKNOWN` / `DENIED`. A failed pull-request
  probe is `UNKNOWN` — never cached as "nothing here"; the coordinator keeps the previous answer and retries
  with back-off (and on the next gating). `DENIED` is GitHub refusing for SAML single sign-on: retrying cannot
  help, so it is not retried, and the status line says to authorize gh (`status.github.ssoRequired`) until
  `github.refresh`. Issues or Actions switched off in a repository only mean "none".

## Lanes, cancellation, the busy signal

Single-thread lanes, so one slow call cannot hold up the rest:

| lane | what runs there | counted as busy | in the command log |
| --- | --- | --- | --- |
| mutations (`github-service`) | `pr checkout / create / review`, `run rerun / cancel` — in order | yes | yes |
| reads (`github-read`) | lists, `pr view`, `browse` | yes | yes |
| downloads (`github-download`) | `pr diff`, the files API | yes | yes |
| logs (`github-log`) | the CI log (up to 5 min) | yes | yes |
| lookups (`github-lookup`) | `branchChecks` (the checks poll), `repoInfo`, `prCreateContext` | only `prCreateContext` | only `prCreateContext` |
| probes (`github-probe`) | `detect`, `openActivity` (20 s per call) | no | no |
| ssh (`github-ssh`) | `ssh -G` for the remotes' SSH aliases | no | no |

- Every read and lookup returns a `ProcessRunner.Cancellation`. Cancelling **kills gh** and the consumer is
  never called. A newer `listPrs` / `listIssues` / `listRuns` / `branchChecks` does that to the older one of
  the same kind; the `…Once` variants (pickers) neither supersede nor are superseded.
- The lookup lane exists so a checks poll or the create-PR form's two small queries never wait behind a
  diff or a log download, and never delay a list. It is not the probe lane: a poll there would hold up the
  "is gh usable" answer that commands wait for.
- `GitHubService.activeCallsProperty()` (`GitHubCoordinator.callsInFlightProperty()`) is the number of the
  user's calls queued or running; `GitHubPanel.setBusy` follows it. Background polls and probes do not spin
  it and are not written to the Output ▸ GitHub transcript.
- A review tab's load runs in order: `pr view`, then `pr diff` (or the files API after an HTTP 406), then
  `pr view --json headRefOid` again (`GitHubService.prHead`). When the head moved meanwhile
  (`GitHubCoordinator.headMovedWhileLoading`) the diff may be another head's than the detail's: the tab opens
  with `github.review.headMoved` and its detail unpinned (`PrDetail.unpinned`). A head that cannot be read
  again keeps the pin. The load is cancelled by the next
  load of the same pull request (Refresh pressed again) and by closing the tab. A Refresh whose `pr view` fails
  keeps the title, description and head commit on screen and says so in the tab's notice.
- The command log records what ran: on a remote host (SSH, WSL, a container) the line starts with
  `[<host>]` and shows that host's gh, not this machine's (`GitHubService.loggedArgv`).
- `shutdown()` interrupts reads, downloads, logs, lookups and probes but lets a running `gh pr checkout` finish.

## Output that can be large

`ProcessRunner` captures 10 MB. Being cut there is a reported condition, not a silent one:

- **CI log** (`runFailedLog`): streamed through the live runner into `TailLines`, which keeps the *last*
  3,000 lines whatever the log's size — the failure is at the end. The console's Stop, and asking for
  another log, kill gh.
- **PR diff** (`prDiff`): `DiffResult.truncated`; the file the capture stopped in is dropped
  (`wholeFileSections`) and the review tab and status bar say the list is incomplete.
- **Files API** (`prFiles`, the fallback when GitHub refuses the whole diff with HTTP 406):
  `PrFilesResult.truncated`, added to the tab's fallback notice.

## Lists, checks, forms

- Lists take a `GitHubListQuery` (state, "mine", limit **+1** so a "load more" row can appear; paging raises
  the limit). A context row under the toolbar
  (`GitHubPanel.Context`, pushed by `GitHubCoordinator.panelContext`) shows the URL of the repository gh
  resolved (`repoInfo`, cached per directory; a link), the branch checked out (told again on
  `repositoryChanged`), and the account gh uses on that repository's host (the `login` of
  `gh auth status --json hosts`, kept in `Availability.accounts`; absent with a gh older than 2.81).
- The checks segment is fetched once per (repository, branch) and polled while pending with the back-off of
  the pure `ChecksPoll` (capped; a poll — or a failed lookup's retry — due in an unfocused window waits for
  focus) — the one bounded exception to "no background polling". The key's branch is the one checked out in the
  repository asked about. Another repository than the active one (`checksElsewhere`) is looked up only when a
  command waits to be told, and then told its real result (`status.github.checksElsewhere`); it is neither
  cached, shown, nor polled, and it leaves the active repository's poll, `checksGen` and pending announcement
  (`checksAnnounce`, the active fetch's only: `github.showChecks` calls `checksElsewhere` directly) alone.
- `BranchChecks.failed()` tells a failed lookup (gh error, rate limit, 401, single sign-on, timeout) from "no
  pull request" (`noPullRequest`: gh's "no pull requests found") and "no checks" (`noChecks`). A failure is not
  cached; the roll-up on screen stays, the open checks card and a `github.showChecks` that asked say why
  (`status.github.checksFailed`), and it is tried again after 1, 2 and 5 minutes, then on request only.
- The create-PR and submit-review forms stay open until gh succeeds (`OverlayInput.showSubmitting`);
  create-PR first looks up the default branch, the branch's existing pull request and the template.
- Bodies never travel as arguments (a long one could pass the command-line limit, and every one would show
  in the process list): `gh pr create --body-file -` and `gh pr review --body-file -` read them from standard
  input (`GhInvocation`, `PrCreateArgs.invocation`, `PrReviewArgs.invocation`). The title stays an argument.
- The submit-review form starts on **Comment**, which needs a body; Approve (the one action that may have an
  empty body) has to be chosen (`PrReviewArgs.canSubmit`). From a review tab the review is **pinned to the head
  commit the tab loaded** (`headRefOid` of `pr view`): `gh api --hostname <host> --method POST
  repos/<owner>/<repo>/pulls/<n>/reviews --input -` with `commit_id`, `event` and `body` as JSON on standard
  input, so commits pushed while the reviewer read are not approved with them. Host and repository come from
  the pull request's own `url` (`PrReviewArgs.target`): `gh api` goes to `github.com` without `--hostname`
  whatever the remote, and `{owner}/{repo}` may name another remote's repository. Without a readable `url`, or
  with one that names a port (`gh api --hostname` takes a bare host and rejects `host:port`), the
  review falls back to `gh pr review`, unpinned, as does one picked from a list (`github.submitReview`). A
  refresh of the review tab is all or nothing (`keepsShownReview`): when the details (and so the head commit)
  could not be read again, the diff on screen is kept too (`github.review.notRefreshed`).
- `gh pr checkout` goes through `GitLeaveDetachedHead.thenMove` (target `#<n>`): from a detached HEAD the
  question is asked first. The pull request's head is usually not fetched, so git cannot count and the question
  says it could not tell.
- `gh run cancel` asks first (`Confirm`, `GitHubCoordinator.cancelRunQuestion`): a cancelled run cannot be
  resumed.
- A run whose conclusion is `action_required` is `RunState.AWAITING_APPROVAL` (⏸), not a failure: no failure
  log, no re-run, no cancel — its page on GitHub, where it is approved, is the action.
- The filter box filters the rows listed. When it matches none of them and gh has more (a "load more" row), a
  row offers to search GitHub for the text (`GitHubListQuery.search` → `--search`; pull requests and issues —
  `gh run list` has no search); `#123` on the pull requests opens that pull request's review instead. The
  answer is shown unfiltered; changing the filter text goes back to the plain list.
- `gh browse` gets its flags first, then `--`, then the path; `gh repo create` gets its flags, then `--`, then
  the name, and Settings Sync refuses a name starting with `-` or `.` (`GitHubService.validRepositoryName`).

## Tests

Three fake-`gh` shell scripts (POSIX only, skipped on Windows), each steered by marker files:
`GitHubServiceFxTest` (probe states, tails, truncation, cancellation, lanes), `GitHubReadinessFxTest`
(gating, re-probe, Git-off, Stop, the spinner, an old gh), `GitHubUiFlowsFxTest` and
`GitHubRepositoryBindingFxTest` (the flows). A fake must answer what the window asks at startup:
`--version`, `auth status [--json hosts]`, the one-row activity lists, `pr view`, `repo view`.

## Commands behind the rows and buttons

The GitHub window's Only Mine toggle and its last row are `github.toggleMine` and `github.loadMore`; a pull
request's review tab's links are `github.openReviewedPr`, `github.openReviewedFiles`, `github.submitReviewedPr` and
`github.refreshReviewedPr`.
