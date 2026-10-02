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
#   scripts/worktree.sh rm <branch> [--force] Remove a task worktree + its branch
#                                             (the branch is kept if it is not fully
#                                             merged, unless --force is given)
#   scripts/worktree.sh prune                 Clean up stale worktree metadata
#
# It can be run from the main checkout or from inside any task worktree: the
# worktrees always go beside the MAIN checkout.
#
set -euo pipefail

# The MAIN checkout, wherever this is run from. `git rev-parse --show-toplevel`
# answers with the CURRENT worktree, so running this from inside a task worktree
# used to create a nested `<slug>-worktrees/` beside that worktree. The common
# git directory is shared by every worktree and lives in the main checkout
# (<main>/.git), so its parent is the directory we want.
common_dir="$(cd "$(git rev-parse --git-common-dir)" && pwd -P)"
repo_root="$(dirname "$common_dir")"
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
    force=0
    branch=""
    for arg in "$@"; do
      case "$arg" in
        --force|-f) force=1 ;;
        *) branch="$arg" ;;
      esac
    done
    [ -n "$branch" ] || { echo "error: branch name required" >&2; exit 1; }
    dir="$wt_root/$(slug_of "$branch")"
    git -C "$repo_root" worktree remove "$dir"
    # `branch -d`, not `-D`: -D deleted the branch even when its commits existed nowhere else, so a
    # mistyped `rm` threw unmerged (and unpushed) work away with no way back but the reflog.
    if [ "$force" = 1 ]; then
      git -C "$repo_root" branch -D "$branch"
      echo "Removed worktree $dir and branch '$branch' (forced)."
    elif git -C "$repo_root" branch -d "$branch" 2>/dev/null; then
      echo "Removed worktree $dir and branch '$branch'."
    else
      echo "Removed worktree $dir. Branch '$branch' is NOT fully merged and was kept;" >&2
      echo "delete it with: git branch -D $branch   (or re-run with --force next time)" >&2
      exit 1
    fi
    ;;
  prune)
    git -C "$repo_root" worktree prune -v
    ;;
  *)
    usage; exit 1 ;;
esac
