#!/usr/bin/env bash
#
# Task worktrees for Editora — one isolated checkout per task/session.
#
# Multiple agents/sessions sharing a single working tree caused commits to land
# on the wrong branch (a parallel session ran `git checkout` under us). Giving
# each task its own `git worktree` gives it an independent HEAD/branch/working
# directory while sharing the same .git object store, so sessions can't disturb
# each other.
#
# Worktrees live in a sibling directory (../<repo>-worktrees/<slug>) so the main
# repo stays clean and nothing needs to be gitignored.
#
# Usage:
#   scripts/worktree.sh new <branch> [base]   Create a worktree on a new branch
#                                             (base defaults to origin/master)
#   scripts/worktree.sh list                  List all worktrees
#   scripts/worktree.sh rm <branch>           Remove a task worktree + its branch
#   scripts/worktree.sh prune                 Clean up stale worktree metadata
#
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
repo_name="$(basename "$repo_root")"
wt_root="$(dirname "$repo_root")/${repo_name}-worktrees"

usage() { sed -n '2,/^set -euo/p' "$0" | sed 's/^# \{0,1\}//; /^set -euo/d'; }

slug_of() { printf '%s' "${1//\//-}"; }

cmd="${1:-}"; [ $# -gt 0 ] && shift || true
case "$cmd" in
  new)
    branch="${1:?branch name required, e.g. feat/my-task}"
    base="${2:-origin/master}"
    git -C "$repo_root" fetch --quiet origin 2>/dev/null || true
    dir="$wt_root/$(slug_of "$branch")"
    if [ -e "$dir" ]; then
      echo "error: $dir already exists" >&2; exit 1
    fi
    git -C "$repo_root" worktree add -b "$branch" "$dir" "$base"
    echo
    echo "Worktree ready on branch '$branch' (off $base):"
    echo "  cd \"$dir\""
    ;;
  list)
    git -C "$repo_root" worktree list
    ;;
  rm|remove)
    branch="${1:?branch name required}"
    dir="$wt_root/$(slug_of "$branch")"
    git -C "$repo_root" worktree remove "$dir"
    git -C "$repo_root" branch -D "$branch" 2>/dev/null || true
    echo "Removed worktree $dir and branch '$branch'."
    ;;
  prune)
    git -C "$repo_root" worktree prune -v
    ;;
  *)
    usage; exit 1 ;;
esac
