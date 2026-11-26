# Backlog: Claude Code integration and first-class code review

Status: 2026-11-26. Decisions D1–D6 answered. Items 1 and 2 shipped (#1823); items 6, 7 and 9 (the comment model, line comments in the editor and diffs, and the MCP review tools) ship together; `get_review_diff` was left out, agents read the diff with `git`. See [code review](subsystems/code-review.md).

**Goal.** Editora becomes the place where you review code, whoever wrote it: you, a teammate's pull
request, or Claude Code. Claude can see what you see, propose changes you accept or reject hunk by
hunk, read the comments you leave, and leave its own as review findings.

## Where things stand

| Area | Today | Gap |
| --- | --- | --- |
| MCP protocol | `2024-11-05` (`McpTools.PROTOCOL_VERSION`); POST-only JSON, no SSE stream (`McpServer`) | Two-plus revisions behind; the server can never push an event |
| MCP surface | 14 tools; no resources, prompts, tool annotations or structured output | Claude Code can't tell a read from a write; no slash commands from Editora |
| Connecting | Port and token change on every start; user re-pastes `claude mcp add` | Connection breaks after every restart or toggle |
| Agent panel | ACP over stdio, default `claude-code-acp` (`AcpAgentRegistry`) | Writes are undoable buffer edits, but not shown as a reviewable change set |
| Diff review | `PatchReviewPane` (multi-file), `DiffViewerPane` (side-by-side/unified), staged/unstaged, branch since fork (`GitService` `sinceFork`), stash, directory | No comments of any kind |
| GitHub review | Review tab, one whole-review body pinned to the head commit (`PrReviewArgs`) | No line comments, threads, suggestions, resolve, or "viewed" |
| Local History | Revisions with labels (`history.labelSelected`) | Not tied to agent turns |

## Rules every item keeps

- The existing agent-write rules in `docs/subsystems/ai.md` (one path, project confinement, no
  writes to the config folder or `.git/`, digest check against stale overwrite, honest reports)
  apply to every new tool. New tools get the same argument validation.
- **Nothing outward-facing on an agent's say-so.** An agent may draft GitHub comments; only the
  user submits a review. No MCP tool posts to GitHub, pushes, or merges.
- Each PR: regression tests for fixes, perf check against master (the >10% rule), user-docs page,
  website feature page, Pauta rules (keyboard reachable, commands in the palette, i18n).
- One PR per feature, as listed below.

## Ranked backlog

Size: S ≈ a day, M ≈ a few days, L ≈ a week or more. "Needs" lists the items it builds on.

### Tier 1: foundation

**1. Stable connection: `editora --mcp-stdio` (S)**
A stdio MCP server inside the launcher that reads `mcp-endpoint.json` and relays JSON-RPC to the
running window's HTTP endpoint. Re-reads the file when the port/token changes, and answers with a clear
tool error when Editora isn't running or MCP is off. Setup becomes one permanent command:
`claude mcp add editora -- editora --mcp-stdio`. Settings → MCP Server shows that command instead of the
token-bearing one. *Done when:* restart Editora, toggle MCP, rotate the token, and Claude Code keeps
working without being reconfigured. *Code:* `App` arg parsing (beside `--report`), `com.editora.mcp`.

**2. MCP protocol update (M)**
- Negotiate the current revision (2025-11-25 at least; check the 2026-07-28 changelog first), keeping
  `2024-11-05` for old clients.
- Streamable HTTP: a GET SSE stream, so the server can send notifications (`notifications/tools/list_changed`,
  resource updates, and the review events below).
- Tool annotations (`readOnlyHint`, `destructiveHint`, `idempotentHint`) on all 14 tools.
- `outputSchema` + `structuredContent`; argument-validation failures returned as tool errors
  (`isError`) rather than protocol errors (SEP-1303), so the model can correct itself.
- Resources: open buffers (`editora://buffer/<path>`), diagnostics, git status, with subscribe.
*Done when:* the MCP Inspector passes against both revisions, and Claude Code shows the read tools as read-only.

**3. Spike: Claude Code's IDE protocol (M, risk)**
The VS Code/JetBrains plugins use a WebSocket MCP variant found through a lockfile at
`~/.claude/ide/<port>.lock` (or `$CLAUDE_CONFIG_DIR/ide/`). It gives auto-discovery (`/ide`), live
selection context, @-mentions, and `openDiff` so Claude's proposed edits show in the editor's own diff.
**It is not officially documented**: what's known comes from reverse-engineering (coder/claudecode.nvim
`PROTOCOL.md`), and an issue there reports drift in Claude Code 2.0.42. Spike first: implement
discovery + `getDiagnostics` + `openDiff` behind an experimental setting, and measure against the
current CLI. Build it out (item 3b, L) only if it holds up. Items 1–2 are the documented route and don't wait on this.

### Tier 2: review core

**4. Agent changes as a review set (L)** — needs 2 for live updates
Every agent turn (ACP writes, MCP `edit_buffer`) becomes a change set: before the first write in a turn,
a Local History label "Before Claude turn N" on each touched file. An **Agent Changes** tab
(a `PatchReviewPane`) shows turn N against the label, with Accept / Reject per hunk and per file, and
**Revert Turn**. Optionally includes files Claude Code changed on disk from a terminal session (through
the file watcher, or through item 12's hook). *Done when:* a three-file agent edit can be reviewed,
partly rejected, and fully reverted, with undo intact.

**5. `propose_edit` MCP tool (M)** — needs 2
Instead of writing, the agent proposes: Editora opens the proposal in `DiffViewerPane` with the
Result editor, and the user accepts, edits or rejects. The tool result reports what happened
(`accepted`, `accepted_with_changes` + final text, `rejected` + optional reason). Long waits use a long
timeout first, MCP Tasks later. A setting picks "agent writes directly" or "agent proposes".

**6. Review comment model (M)**
`ReviewComment`: file, side (old/new), line range, anchor (the line texts plus a digest, so a comment
re-anchors after edits or turns *outdated*), body (Markdown), author (you / agent name / GitHub login),
severity and category for agent findings, state (open / resolved), thread replies, an optional
suggestion. Pure model + JSON store + re-anchoring tests; no UI. Where it lives is decision D1.

**7. Line comments in the UI (L)** — needs 6
Add Comment on a line or a selection in `DiffViewerPane`, `PatchReviewPane` and the plain editor
(gutter icon + `review.addComment`). Threads show inline under the line; reply, resolve, edit,
delete. ```` ```suggestion ```` blocks render as a mini diff with **Apply Suggestion** (an undoable edit).
A **Review Comments** tool window lists everything with filters (open, mine, agent, file).
Keyboard: next/previous comment, like next/previous change.

**8. GitHub inline review (L)** — needs 6, 7
In the PR review tab: draft comments become a pending review, submitted in one POST with `comments[]`
(`path`, `line`, `side`, `start_line`), still pinned to the loaded head commit. Existing threads load
through GraphQL `reviewThreads` (outdated marked as such); reply, resolve / unresolve, apply a
suggestion locally. Everything goes through `gh` as today (bodies on stdin).

### Tier 3: the Claude ↔ review loop

**9. Review MCP tools (M)** — needs 6
`get_review_diff` (staged, unstaged, branch since fork, PR, agent turn), `list_review_comments`
(filters), `add_review_comment` (agent-authored, with severity and category), `reply_to_comment`,
`resolve_comment` (only comments it wrote, or that it fixed, with a note). Agent comments on a GitHub
review stay local drafts until the user includes them.

**10. Findings view (M)** — needs 7, 9
Agent comments with a severity show as findings: gutter markers, a sortable list, and per finding
**Fix with Claude** (sends the finding to the agent), **Dismiss** (with reason, remembered so the
next review doesn't repeat it), **Promote to GitHub comment**. This is where `/code-review` output lands.

**11. MCP prompts (S)** — needs 2
Prompts appear in Claude Code as slash commands: `/mcp__editora__review_changes`,
`/mcp__editora__fix_open_comments`, `/mcp__editora__explain_selection`, `/mcp__editora__review_pr`.
Each one embeds the current diff or selection as context.

**12. Claude Code plugin (M)** — needs 1, 9
A plugin bundling the stdio MCP config (item 1), a skill ("review in Editora": how to read the
review, write findings with `add_review_comment`, fix open comments), and hooks: `PostToolUse` on
Edit/Write notifies Editora so the file joins the agent change set (item 4); `Stop` opens the Agent
Changes tab. Distributed through a marketplace repo (decision D5).

**13. Send to Claude (S/M)** — needs 7
From a comment, a finding, a selection or a diff hunk: **Send to Claude** puts it in the agent panel
prompt (ACP, works today), or as an @-mention over the IDE protocol if item 3 lands.

### Tier 4: review ergonomics

**14. Viewed files and progress (M)**
Per-file "Viewed" checkbox in `PatchReviewPane`, a progress count, viewed state reset when the file
changes. Stored per head commit; synced with GitHub's viewed state (GraphQL `markFileAsViewed`) on PR tabs.

**15. Changes since last review (M)** — needs 14
"Show only what changed since I last reviewed": interdiff between the reviewed head and the current
one, for a PR (force-push aware) and for agent turns.

**16. Code intelligence in the diff (M/L)**
On the working-tree side of a diff: hover, go to definition, find references (LSP), and "Show in
Map". Read-only sides can use the same LSP through a temporary document where the server allows it.

**17. Review order and walkthrough (M)**
Group files (source, tests, config, generated, lockfiles collapsed), order by dependency, and an
optional AI walkthrough through AI Actions: summary, risky spots, suggested order.

**18. More read tools for agents (M)** — needs 2
`lsp_definition`, `lsp_references`, `lsp_hover`, `workspace_symbols`, `test_results`,
`debug_state` (stack, variables when paused), `local_history`. All read-only and annotated so.

## Suggested order

1 → 2 → 6 → 7 → 4 → 9 → 10 → 11 → 8 → 12 → 5 → 14 → 15 → 13 → 16 → 17 → 18, with the 3 spike run beside
1–2. Rationale: 1–2 fix today's friction and unlock push events; 6–7 are the base every review feature
needs; 4 and 9–10 make the Claude loop work locally before GitHub (8) adds network flows.

## Decisions (adl, 2026-11-26)

- **D1. Local comments live in the config folder**, kept per repository (private). A setting can
  store them in the project (`.editora/reviews/`) instead.
- **D2. The IDE protocol is a trial only** (item 3), behind an experimental setting. The shipped
  integration uses documented MCP.
- **D3. Direct, undoable writes stay the default**; they become a change set (item 4). `propose_edit`
  is opt-in (item 5).
- **D4. Claude's disk writes from a terminal session are tracked through the plugin's hook only**
  (item 12); no guessing from the file watcher.
- **D5. The plugin ships from its own marketplace repo** under adriandeleon, versioned with Editora
  releases.
- **D6. Agents only add local drafts to GitHub reviews**; they never submit, reply on, or resolve
  GitHub threads.

## Sources

- MCP changelog 2025-11-25: <https://modelcontextprotocol.io/specification/2025-11-25/changelog>
- MCP changelog 2026-07-28: <https://modelcontextprotocol.io/specification/2026-07-28/changelog>
- Reverse-engineered Claude Code IDE protocol: <https://github.com/coder/claudecode.nvim> (`PROTOCOL.md`),
  drift report <https://github.com/coder/claudecode.nvim/issues/156>
