# 0012 — Settings sync: Git carries the data, Editora merges it

**Status:** Accepted

## Context

Users run Editora on several computers and operating systems and want their snippets, abbreviations,
templates and personal dictionary to follow them. The only existing options were the one-way
"Export Configuration" zip and symlinking config files into a dotfiles repository by hand.

A Git repository the user owns is an attractive store: it needs no Editora service, works with any
host, keeps history, and uses credentials the user already has. The obvious way to use it — make the
config directory (or part of it) a working tree and pull — has three problems:

- The config directory also holds locks, a live MCP token, API keys in `settings.json`, machine paths,
  and hundreds of megabytes of downloaded language servers.
- A textual merge of JSON stores produces conflict markers inside files the editor loads at startup.
- A background pull that stops on a conflict leaves a repository in a state only a terminal can fix.

## Decision

- Editora keeps a private clone at `<configDir>/sync/repo`. The stores keep reading and writing their
  usual files; the sync copies the four kinds of data between the two.
- Git is used as transport and history only: fetch, commit, push. Git never merges. Each round sets
  the clone's working tree to the remote branch, writes the files Editora merged itself, commits and
  pushes; a rejected push repeats the round.
- The merge is three-way and entry-wise (one snippet, abbreviation, template, word), against the
  commit this machine last finished a sync at (`refs/editora/base`). The base moves only after the
  push succeeded and the live files were written.
- An entry changed differently on two machines keeps the version of the machine that is syncing. The
  other version is already in the repository's history. Nothing asks the user a question in the
  background.
- Only the four data categories are synced in this version. `settings.json` is excluded.
- Editora stores no credentials. Automatic rounds run git with every prompt disabled.

## Consequences

- No conflict markers, no stuck rebase, and an interrupted round is repeated rather than recovered.
- "This machine wins" can discard an edit made on the other machine to the same entry; it is
  recoverable from the repository history but there is no UI for that yet.
- A removal travels like any change. A guard stops a round that would remove most of a category, and
  replaced files are backed up locally, because a truncated file would otherwise empty every machine.
- Hand edits in the repository are supported: they arrive as "their" changes.
- Syncing preferences later needs a list of machine-local keys (paths, the `sync*` keys) and must
  strip API keys; the repository layout mirrors the config directory so that only adds paths.
