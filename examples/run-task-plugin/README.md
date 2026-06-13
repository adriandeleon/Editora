# Task Runner plugin

A task-runner **tool window** (docks bottom): type a shell command (`npm run build`, `make`,
`./gradlew test`…), **Run** it in the active file's directory via `bash -lc` (a login shell, so PATH
resolves npm/make), and watch combined stdout/stderr stream into the output area. **Stop** kills the
process. The command runs off the FX thread. Build with `./build.sh`; install via *Settings → Plugins →
Install from file…*. See [`../../docs/plugins.md`](../../docs/plugins.md).
