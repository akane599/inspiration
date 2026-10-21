---
description: Isolated agent worktrees, from base commit to cleanup
priority: 15
---
- Harness `isolation: worktree` agents start from `main`, and an unchanged worktree is deleted when its agent stops. When the integration branch is not `main`, give each worker the base SHA and have it create its own worktree under `.claude/worktrees/` from that SHA.
- Ask workers to rebase onto the integration branch before they report, so integration is a fast-forward or a clean cherry-pick.
- From the main session, never `cd` into a worker worktree (it moves the session's working directory); use `git -C <path>` and absolute paths.
- Once a worker's commits are integrated, remove its worktree and branch. First check that `git -C <path> status` is clean and that `git cherry <integration> <branch>` prints no `+` line. Then run `git worktree remove <path>` and `git branch -D <branch>`, and finish with `git worktree prune`. A worktree with uncommitted or unintegrated work stays, and you ask the user.
