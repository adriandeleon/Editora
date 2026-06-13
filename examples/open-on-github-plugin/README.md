# Open on GitHub plugin

`Git: Open File on GitHub` — opens the active file at the caret line on its remote's web UI. Shells out to
`git` (off the FX thread) for the repo root, `origin` remote, and current branch, builds a
`…/blob/<branch>/<path>#L<line>` URL (`GitHubUrl`, handling scp/ssh/https remotes), and opens it via the
plugin API's `openUrl`. Build with `./build.sh`; install via *Settings → Plugins → Install from file…*. See
[`../../docs/plugins.md`](../../docs/plugins.md).
