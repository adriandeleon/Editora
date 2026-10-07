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
  `REJECTED` (HTTP 401 — `gh auth refresh`). **`Availability.ready()` is true for `UNVERIFIED`**: being
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
- Activity (`GitHubService.openActivity`) answers `YES` / `NONE` / `UNKNOWN`. A failed pull-request probe is
  `UNKNOWN` — never cached as "nothing here"; the coordinator keeps the previous answer and retries with
  back-off (and on the next gating). Issues or Actions switched off in a repository only mean "none".

## Lanes, cancellation, the busy signal

Four single-thread lanes, so one slow call cannot hold up the rest:

| lane | what runs there | counted as busy | in the command log |
| --- | --- | --- | --- |
| mutations (`github-service`) | `pr checkout / create / review`, `run rerun / cancel` — in order | yes | yes |
| reads (`github-read`) | lists, `pr view`, `pr diff`, the files API, the CI log, `browse` | yes | yes |
| lookups (`github-lookup`) | `branchChecks` (the checks poll), `repoInfo`, `prCreateContext` | only `prCreateContext` | only `prCreateContext` |
| probes (`github-probe`) | `detect`, `openActivity` (20 s per call) | no | no |

- Every read and lookup returns a `ProcessRunner.Cancellation`. Cancelling **kills gh** and the consumer is
  never called. A newer `listPrs` / `listIssues` / `listRuns` / `branchChecks` does that to the older one of
  the same kind; the `…Once` variants (pickers) neither supersede nor are superseded.
- The lookup lane exists so a checks poll or the create-PR form's two small queries never wait behind a
  diff or a log download, and never delay a list. It is not the probe lane: a poll there would hold up the
  "is gh usable" answer that commands wait for.
- `GitHubService.activeCallsProperty()` (`GitHubCoordinator.callsInFlightProperty()`) is the number of the
  user's calls queued or running; `GitHubPanel.setBusy` follows it. Background polls and probes do not spin
  it and are not written to the Output ▸ GitHub transcript.
- `shutdown()` interrupts reads, lookups and probes but lets a running `gh pr checkout` finish.

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
  the limit). The toolbar names the repository gh resolved (`repoInfo`, cached per directory).
- The checks segment is fetched once per (repository, branch) and polled while pending with the back-off of
  the pure `ChecksPoll` (capped; a poll due in an unfocused window waits for focus) — the one bounded
  exception to "no background polling".
- The create-PR and submit-review forms stay open until gh succeeds (`OverlayInput.showSubmitting`);
  create-PR first looks up the default branch, the branch's existing pull request and the template.
- `gh browse` gets its flags first, then `--`, then the path.

## Tests

Three fake-`gh` shell scripts (POSIX only, skipped on Windows), each steered by marker files:
`GitHubServiceFxTest` (probe states, tails, truncation, cancellation, lanes), `GitHubReadinessFxTest`
(gating, re-probe, Git-off, Stop, the spinner, an old gh), `GitHubUiFlowsFxTest` and
`GitHubRepositoryBindingFxTest` (the flows). A fake must answer what the window asks at startup:
`--version`, `auth status [--json hosts]`, the one-row activity lists, `pr view`, `repo view`.
