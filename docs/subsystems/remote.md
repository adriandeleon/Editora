# Remote folders, remote projects and remote execution

Editora can open a folder on another machine over SFTP, treat it as a project, and (work in progress, off by
default) run the tools it drives on that machine. This page is the map; the SFTP file layer itself is
described in the `vfs/` entry of the [architecture catalog](../reference/architecture-catalog.md).

## Files

A remote file is an ordinary `java.nio.file.Path` on a non-default filesystem (Apache MINA SSHD's
`SftpFileSystem`), owned by the window's `vfs/RemoteFileSystems`. `Vfs.isLocal(path)` is the predicate every
local-only feature gates on; `Vfs.toStorableString` / `parseStorable` round-trip a remote path as an
`sftp://user@host:port/path` URI. Each window has its own engine and its own SSH client.

`Remote: Disconnect` closes the SSH session, not only the SFTP channel: the session also carries commands and
forwarded ports.

## Projects

A remote folder is a `config/Project` whose `root` is its `sftp://` URI.

- `Project.rootPath()` is the only way to turn a stored root into a path. It is `null` while the host is not
  connected. `Path.of(project.root())` is forbidden (a scan in `RemoteProjectRootTest` enforces it): for a
  remote root it silently yields a relative local path.
- `Project.contains(file)` refuses a file on another filesystem; paths of two providers cannot be compared.
- `TrustStore` keys a remote folder by its URI and only matches folders on the same filesystem, so a remote
  `/srv/app` and the local `/srv/app` are trusted separately.

**One window per project, and that window holds the connection.** An SSH session belongs to the SSH client
of the window that opened it and cannot be handed to another window. So when the connect form succeeds in
window A for a folder that is not A's project, `RemoteCoordinator.connected` creates the project, closes A's
connection (unless A's tabs still use that host) and calls `WindowManager.openRemoteProject`, which opens the
project's window and has it log in again with the same credentials (`connectForWindowProject`). The secret
is copied for that second login and wiped after it. With projects turned off, the folder is mounted in the
current window as before.

**Not connected.** A remote project's window whose host is not connected shows "Not connected to …" with a
Reconnect button in place of the Project tree (`ProjectPanel.showDisconnected`, state held by
`RemoteCoordinator` so it survives the panel being built later). That happens when the window is reopened
from the project list or at startup (`promptReconnectIfNeeded`, which also brings up the connect form filled
in for the host), when a login handed to it fails, when the connection is lost
(`RemoteFileSystems.addConnectionListener`), and after Disconnect.

**Tabs.** A saved tab on a host that is not connected is kept in the session
(`SessionScan.unreachableRemote`), but only by the window of a project on that host
(`SessionEntries.retainUnreachable`); other windows drop it. On connect,
`WindowSessionCoordinator.reopenUnreachable` scans the waiting entries off the FX thread and reopens the
readable ones with their caret and pin. There are no placeholder tabs while disconnected.

## Execution (off by default)

`process/ExecTarget` is where a command runs. `ExecTargets.forPath(dir)` answers the local machine for a
local or null directory, and otherwise the `vfs/SshExecTarget` of the connection that owns the path, but only
while `Settings.remoteExecution` is on. `ProcessRunner` dispatches on its working directory.

- A command is one string built by `process/PosixShell`: `exec /bin/sh -c '<script>' editora '<argv>'…`, parsed
  once by the login shell. The script prints a pid line on stderr, sets directory and environment, and
  `exec`s the program. No PTY.
- `vfs/SshExecProcess` exposes the exec channel as a `RemoteProcess` (a `Process` with no local pid or
  `ProcessHandle`). Its exit comes from the channel's close notification.
- Stopping a tree is a `kill -s TERM -- -PID` on a second channel (`PosixShell.killCommandLine`). `dash`
  rejects `kill -TERM -- -N`; the `-s` form works in dash and bash.
- A long-lived child starts a watchdog that kills its process group when the server's per-connection process
  dies: an SSH server signals nobody when a connection drops.
- `vfs/RemoteEnv` asks the login shell once per connection for `PATH`, home, OS and locale.
- Channels are budgeted against the server's `MaxSessions` (three short commands at once, five long-lived).
- `ExecTarget.forwardLocal` forwards a loopback port; sessions send keep-alives every 30 s.

### Who may run what

Three things have to hold before anything runs on a host, all checked by `ExecTargets.forPath`:

1. `Settings.remoteExecution` is on (the user's global switch, off by default).
2. The user has agreed for *that host*: `ConnectionStore.exec[<user@host:port>].allowed`. The question is put
   once per host by `RemoteCoordinator.offerExecution` after a folder is mounted (and when the setting is
   turned on with a folder mounted); "Not Now" is respected for the session.
3. The connection is open.

`ConnectionStore.exec[…].tools` holds a command per tool id (`git`, …) for a host whose `PATH` does not have
the right one; `ExecTargets.remoteTool(path, id)` reads it. Local tool settings are never used remotely: they
are paths on this machine. There is no UI for `tools` yet; it is edited in `connections.json`.

A feature gates on `ui/RemoteExec.runsFor(path)`: any local file, or a remote one whose host passes the three
checks. A feature that only works on this machine keeps gating on `Vfs.isLocal`.

### What runs remotely so far

- **Git:** status, the gutter diff, tab colouring, log, blame and blob lookups, and every command that changes
  a repository (stage, commit, checkout, stash, merge, rebase, fetch / pull / push, hunk staging). `GitService`
  keeps git's availability and version per host (`gitAvailable(Path)`), uses the host's `git` (or its
  configured tool), and never probes a host from the JavaFX thread. User commands get a host-neutral
  environment (`REMOTE_USER_ENV`: nothing from this machine's locale). Credentials for fetch and push are the
  host's own; nothing prompts (`GIT_TERMINAL_PROMPT=0`).
- **Hunk staging** writes a private index on the host and publishes it over `index.lock` with a server-side
  replacement (`AtomicFileWrite.replaceAtomically`, `posix-rename@openssh.com`), never a delete-then-rename.
- **GitHub:** `gh` for a remote repository is the host's own (or its configured `gh` tool), signed in there or
  not. `GitHubService.setLocation` points the service at the window's repository; availability and sign-in are
  kept per host (`byHost`), so moving between a local and a remote repository never borrows the other
  machine's answer. URLs are printed by `gh` (`--no-browser`) and opened by this machine's browser.
- **Find in Files:** for a remote folder, with the "use ripgrep" preference on, the host's own `rg` (or its
  configured `rg` tool) searches it in one command (`search/RemoteFiles.hasRipgrep`, probed per host; a
  missing `rg` is believed for five minutes). Without it, the built-in search walks the folder over SFTP, as
  before. Whether this machine has `rg` does not matter.
- **Go to File:** a remote project is listed by its host (`RemoteFiles.list`: `rg --files`, else
  `git ls-files`), capped like a local walk. There are no symbols for a remote project: finding them means
  reading every source file over the network, so Go to Symbol has nothing to offer there.
- **Run and Build:** a remote file's Run, a script / make / npm run configuration in a remote project, and
  Maven / Gradle / npm / Cargo / Go builds and tests run on the host (`RunService.startRemote`: user locale,
  long-lived, so Stop and a lost connection end the tree). Typed input goes as UTF-8 lines ending in `\n`.
  This machine's JDK setting and PATH are never sent; a host's JDK is its own `java`, or `jdk.home` from its
  tools (sent as `JAVA_HOME`). Build-wrapper detection on a host is POSIX (`./mvnw`, `./gradlew`) and reads
  the execute bits over SFTP. Test reports are polled every 2 s instead of 750 ms. Run is enabled for a remote
  file whatever the host allows; a refusal is reported when it is launched.
- **Not yet for Run:** a Java *project* main class (it needs jdtls or a Maven classpath file on the host), the
  main-class gutter, and Makefile detection for the run-configuration toolbar.
- **Not yet:** cloning or `git init` onto a remote host (`GitService.REMOTE_NOT_YET`), auto-fetch, the Project
  tree's per-path repository lookup, `blame.ignoreRevsFile` handling (left to the host's git), Run, Build,
  tests, language servers and debugging.

Checked against OpenSSH 10.0 with a dash `/bin/sh`. Not checked: dropbear, macOS's sshd.

Tests: `EmbeddedSftpFixture` is an in-process MINA server; `startWithShell` makes it run command lines with a
real `/bin/sh`. Tests that use it need the JavaFX toolkit (the connect result is posted to the FX thread), so
they are tagged `fx`.
