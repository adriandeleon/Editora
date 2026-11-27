---
name: review-in-editora
description: Review code changes and leave the findings in Editora, as review comments on their lines that the user reads, fixes, or dismisses there; or work through the review comments the user left. Use when the user asks for a code review, to "review this", to check a change before committing, or to fix or answer the review comments in Editora.
allowed-tools: mcp__plugin_editora_editora__list_review_comments mcp__plugin_editora_editora__read_buffer mcp__plugin_editora_editora__get_diagnostics mcp__plugin_editora_editora__git_status mcp__plugin_editora_editora__list_open_files mcp__plugin_editora_editora__get_selection
---

# Reviewing code in Editora

Editora is the user's editor. It keeps **review threads**: comments on lines of a
file, from the user or from you, shown after those lines in the editor and listed
in its Review Comments window. Use them instead of a long review in the chat: the
user sees each finding where it applies and can act on it there.

## Reviewing a change

1. Find what to review: `git status` and `git diff` (`git diff --cached` for staged
   changes), or the files the user named. `git_status` gives Editora's view.
2. Call `list_review_comments` with `state: "all"` first. A thread that is
   **dismissed** says the user does not want that finding: do not raise it again
   (Editora refuses a finding on lines where the user dismissed one).
3. Read each changed file as the editor has it with `read_buffer` when it is open
   (it includes unsaved changes), else from disk.
4. For each real problem, call `add_review_comment` with:
   - `path` (absolute), `start_line` and `end_line` (1-based, the file as it is now);
   - `body`: what is wrong and why, in a few sentences;
   - `severity`: `blocker` (wrong or unsafe), `important`, `minor`, or `nit`;
   - `category`: e.g. `correctness`, `security`, `performance`, `tests`, `style`;
   - `suggestion`: the replacement for exactly those lines, when you can write it.
     The user applies it with one click, so it must be complete and compile.
5. Do not edit files while reviewing. End with a short summary in the chat: how
   many findings, the most serious first.

Prefer a few findings that matter over many nits. Put each on the narrowest lines
that show the problem.

## Working through review comments

1. `list_review_comments` (open ones) gives each thread's id, lines (where they
   are now), comments and any suggestion. A thread marked `outdated` is on lines
   that changed since: read the file before acting on it.
2. For each thread, make the change it asks for. If you disagree, change nothing
   and say why with `reply_to_review_comment`.
3. When a thread is done, call `resolve_review_comment` with its `thread_id` and a
   `note` saying what you changed. Do not resolve threads you did not act on.

## Rules

- Nothing here posts to GitHub. Review threads stay in Editora.
- Never resolve or dismiss the user's threads to hide them; resolve only what you fixed.
