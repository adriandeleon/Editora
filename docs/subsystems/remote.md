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

## Execution

`process/ExecTarget` is where a command runs. `ExecTargets.forPath(dir)` answers the local machine for a
local or null directory, and otherwise the `vfs/SshExecTarget` of the connection that owns the path, but only
while `Settings.remoteExecution` is on. `ProcessRunner` dispatches on its working directory.

- A command is one string built by `process/PosixShell`:
  `exec /bin/sh -c '<guard>' editora '<script>' '<argv>'…`, parsed once by the login shell. The script prints a
  pid line on stderr, sets directory and environment, and `exec`s the program. No PTY.
- The guard makes the command lead a process group, which stopping it relies on. OpenSSH starts a command
  without a terminal in a session of its own; dropbear leaves it in its per-connection process's group, so
  there the guard re-runs the script under `setsid` (same pid: `setsid` forks only for a group leader). The
  test is `kill -0 -$$`, the one spelling dash, bash and BusyBox all answer correctly. A host without
  `setsid` runs the command where it is, and a stop then reaches only the command itself.
- `vfs/SshExecProcess` exposes the exec channel as a `RemoteProcess` (a `Process` with no local pid or
  `ProcessHandle`). Its exit comes from the channel's close notification.
- Stopping a tree is a `kill -s TERM -- -PID` on a second channel (`PosixShell.killCommandLine`). `dash`
  rejects `kill -TERM -- -N`; the `-s` form works in dash and bash.
- A long-lived child starts a watchdog that kills its process group (or itself, when it leads none) when the
  server's per-connection process dies: an SSH server signals nobody when a connection drops.
- `vfs/RemoteEnv` asks the login shell once per connection for `PATH`, home, OS and locale, and for every
  other variable its profile exports: the probe prints the environment a plain command gets, then the login
  shell's, and what the profile adds or changes (`JAVA_HOME`, `MAVEN_OPTS`, `GOPATH`, a version manager's) is
  exported to every command before `PATH`, the locale and the command's own variables, which win. Session
  variables (`SSH_*`, prompts, history, `SHLVL`, display, D-Bus, `EDITORA_*`), multi-line values and anything
  past 200 variables / 8 KB a value / 64 KB in all are left out.
- Channels are budgeted against the server's `MaxSessions` (three short commands at once, five long-lived).
- A command whose channel closes before its pid line, the host saying nothing, never ran, and is started again
  (`SshExecProcess.START_ATTEMPTS`, three in all, while the connection is up). dropbear 2022.82 to 2024.85 —
  Debian 12's and Ubuntu 24.04's — does this now and then when channels open and close at the same time (its
  "failure on concurrent channel open/close", fixed in 2024.86): 3 of 120 short commands beside five long ones
  against 2022.83, 0 of 360 with the retry. A host that printed something, or that Editora gave up on after
  the start timeout, is not asked again.
  Short commands queue; a long-lived one waits up to 5 s for a place (the one a program just stopped frees
  when its channel closes, a moment after the stop), then is refused with a message.
- `ExecTarget.forwardLocal` forwards a loopback port. Sessions send keep-alives every 30 s and give up after
  three go unanswered (`RemoteFileSystems.HEARTBEAT_MISSES`, like `ssh`'s `ServerAliveCountMax`), so a
  connection that goes silent is noticed in about 90 s. The host's side is the server's: its programs are
  ended when its `sshd` notices, which with `ClientAliveInterval` unset can take as long as TCP does.

### Who may run what

Three things have to hold before anything runs on a host, all checked by `ExecTargets.forPath`:

1. `Settings.remoteExecution` is on (the user's global switch; on by default since settings v122, whose
   migration turns a stored `false` on once — before that it was a switch with nothing behind it).
2. The user has agreed for *that host*: `ConnectionStore.exec[<user@host:port>].allowed`. The question is put
   once per host by `RemoteCoordinator.offerExecution` after a folder is mounted (and when the setting is
   turned on with a folder mounted); "Not Now" is respected for the session.
3. The connection is open.

`ConnectionStore.exec[…].tools` holds a command per tool id for a host whose `PATH` does not have the right
one; `ExecTargets.remoteTool(path, id)` reads it. Local tool settings are never used remotely: they are paths
on this machine. The ids: `git`, `gh`, `rg`, `jdk.home`, `node`, `dap.python`, `dap.js`, `dap.java`, and
`lsp.<server id>`.

Settings → Remote edits both per saved site, below the connection form: "Allow running commands on this host"
(withdrawing it is the only way to take the answer back) and a field per named tool, plus one box of
`server = command` lines for language servers (`ui/HostTools`; a tool id the page has no field for is kept as
it is). Editing a site's host, user or port carries its execution settings to the new id
(`ConnectionStore.renameExec`); removing the site keeps them, so a site added back is not asked about again
and keeps its tools (to withdraw the answer for a removed site, add it back and untick it). Every window compares the setting and its own
host's agreement and tools after each settings apply and each connection-store write
(`RemoteCoordinator.recheckExecution`) and, when they changed, runs the same refresh as the consent question:
Git, the outside-change watch and language servers look again, and `LspManager.forgetRemoteDetections`
drops what was found on the host.

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
  `git ls-files` inside a repository, else POSIX `find`), capped like a local walk. What `find` prints is
  filtered by the local walk's rules (`RemoteFiles.filtered`: no hidden files or folders, the `.gitignore` of
  the folder and of each folder below it, reading only the ones `find` listed; `node_modules` is not entered
  while ignoring). A host that runs no commands is walked over SFTP instead (`IndexCoordinator.sftpWalk`), a
  listing per folder, names only. There are no symbols for a remote project: finding them means reading every
  source file over the network, so Go to Symbol has nothing to offer there.
- **Run and Build:** a remote file's Run, a script / make / npm run configuration in a remote project, and
  Maven / Gradle / npm / Cargo / Go builds and tests run on the host (`RunService.startRemote`: user locale,
  long-lived, so Stop and a lost connection end the tree). Typed input goes as UTF-8 lines ending in `\n`.
  This machine's JDK setting and PATH are never sent; a host's JDK is its own `java`, or `jdk.home` from its
  tools (sent as `JAVA_HOME`). Build-wrapper detection on a host is POSIX (`./mvnw`, `./gradlew`) and reads
  the execute bits over SFTP. Test reports are polled every 2 s instead of 750 ms. Run is enabled for a remote
  file whatever the host allows; a refusal is reported when it is launched.
- **Java projects:** the main class (gutter ▶, Run Main Class, a saved configuration) runs on the host with
  the class path the host's jdtls resolves, or — without java-debug — Maven's (`MavenClasspathResolver`
  runs `mvn … dependency:build-classpath` there, writes the list into the module's `target/` with `:`
  separators, and reads it back over SFTP). The JDK is the host's (`jdk.home`, else what jdtls resolved, else
  its `java`); this machine's JDK setting, `JAVA_HOME` and `PATH` are never sent
  (`RunCoordinator.jdkHomeFor` / `javaEnvironment` / `hostJava`). A configuration's working directory is a
  folder on the host. Gradle projects use their `run` task, as locally.
- **Makefiles:** a makefile at a remote project's root shows the run-configuration toolbar like a local one;
  it is looked for once per root off the FX thread (`RunConfigurationCoordinator.projectHasMakefile`), and the
  toolbar is refreshed when the answer comes.
- **Outside changes:** a remote project's folder is watched on its host (`ui/RemoteWatcher`): one long-lived
  command, `inotifywait -m -r` when the host has it, else `find -newer` every 4 s (a folder whose entries
  changed is reported as itself, which is how a deletion shows). `.git` and `node_modules` are left out. Its
  batches join the Project panel's local watch pipeline (`ProjectPanel.onRemoteChanges`): the tree re-lists
  what changed, language servers get `didChangeWatchedFiles`, Git and build markers refresh, and the active
  tab, if it is a reported remote file, gets the "changed on disk" check it does not get on every focus. The
  watch starts and stops with the root and with what the host may run (`ProjectPanel.refreshRemoteWatch`).
  Only the polling fallback is covered by tests here (no `inotifywait` on the build machine).
- **Language servers:** a remote file is served by a language server on its host
  (`LanguageServerSession.start`: long-lived, the host's PATH and locale, no `processId`, since this machine's
  pid means nothing there). Documents are keyed by their `sftp://` URI inside `LspManager` and sent as the
  host's own `file:` URIs (`WireUris.remote`). The command is the host's `lsp.<id>` tool, else the global
  setting when it is a bare name, else the server's default; whether it is on the host is asked once per host
  (`LspManager.detectRemote`, `command -v`). The session root is the window's remote project for every file on
  its host, inside the project folder or not (a definition in the host's site-packages joins the project's
  server instead of starting one per library folder, each holding a channel); a file on another host gets its
  folder. No marker probing over the network. Project `.editora/settings.json` overrides are not read for a
  remote project. **astro is not offered remotely yet.**
- **jdtls on the host:** `-data` is `<cache>/jdtls-workspaces/<root hash>`, where the cache is
  `ExecTarget.cacheDir()` (`~/.cache/editora` from the login shell's `HOME`; the consent question names it).
  It is not claimed across windows as local workspaces are (a remote project has one window), and a failed
  handshake does not delete it. `java.configuration.runtimes` lists the host's JDKs, found by one `sh` command
  (`RemoteJavaRuntimes`: the host's `jdk.home` tool, `JAVA_HOME`, the `javac` on `PATH`, and the folders
  `JavaRuntimes` looks in). The java-debug bundles are not sent (they are paths on this machine; debugging on a
  host is not done yet). `-Djava.lsp.joinOnCompletion=true` goes on the command line when the host says the executable is
  jdtls's launcher (`jdtls.py` beside it, `JavaServerEnvironment.isRemoteJdtlsLauncher`), else in the
  server's environment.
- **Language servers and the connection:** a remote server that ends is given a second
  (`LspCoordinator.afterRemoteCrashSettles`) for the connection's own listener to catch up. If its host can
  no longer run commands, the connection went with it: its tabs go quiet, nothing is restarted and nothing
  counts towards the crash-loop cap. `RemoteCoordinator.mount` calls `remoteHostConnected`, which forgets that
  host's crash history and offers its (rebound) tabs to their servers again; consent or the setting changing
  (`remoteExecChanged`) re-decides every remote tab.
- **Workspace edits** (renames, code actions) stage, commit and roll back creates, renames and deletes on the
  host through SFTP like any filesystem. The crash journal stays on this machine and names remote files by
  their `sftp://` URI; a journal whose host is not connected is kept, not discarded, until it is
  (`WorkspaceEditJournal.pending`). The check that a file the server did not have open is unchanged since the
  request compares its host-stamped time through `ExecTarget.latestLocalTime`: the host's clock is read once per
  connection (`date +%s`), and a whole-second SFTP time is rounded up by the coarse margin first, so a skewed
  host clock cannot pass a later write off as an earlier one. No check of a remote edit reads the host from the
  FX thread: the destructive-edit question, the Local History capture, the rename preview and the stale-file
  check stat their files on the workspace worker first (`LspCoordinator.withDiskView`), and the apply-time
  re-checks, which must run in the FX turn that lands the edit, read a `WorkspaceFileSnapshot` the worker took
  after staging. A local edit still stats in place, which is cheap.
- **Debugging (Python, JavaScript):** a remote file is debugged on its host (`DapManager.startRemoteStdio` /
  `startRemoteSocket`), asked for its adapter when the session starts rather than detected up front.
  Python: the host's `dap.python` tool, else the Debugging setting when it is a bare name, else `python3`; a
  leading `env NAME=value …` and interpreter arguments are taken apart (`DapManager.HostPython`) into the
  adapter's environment, the launch's `env`, `python` and `pythonArgs`, because debugpy uses only the first
  word of a `python` array. `-c "import debugpy"` is tried first so a missing debugpy is reported by name.
  JavaScript: `dapDebugServer.js` is the host's `dap.js` tool (the file, or a folder to search) or found where
  Editora's installer, mason and VS Code's server put it; it is started with port 0 on `127.0.0.1`, the port
  it prints is tunnelled (`ExecTarget.forwardLocal`, closed with the session) and child sessions reach it
  through the same tunnel. `node` can be set per host (`node` tool). Every `Source.path` crosses `DapPaths`
  (host path out, path on the connection in; pseudo-sources such as `<node_internals>` come back as no file),
  and a session sends only breakpoints of files on its own machine (`DapPaths.owns`). Breakpoints of remote
  files are stored under their `sftp://` URI (`DebugCoordinator.key`), so a reconnect keeps them; a closed
  remote file's are sent while its host is connected.
- **Debugging Java:** java-debug runs inside the host's jdtls. Its jar is the host's `dap.java` tool, else this
  machine's copied into `<cache>/java-debug/` (`LspManager.remoteDebugBundles`; sent again only when the size
  differs, the name carries the version). The port `vscode.java.startDebugSession` returns is on the host and
  is tunnelled (`DapManager.connectJava`). jdtls's main class is matched to the file by host path, the
  working directory is named as the host names it, and the JDK is the one the host's jdtls resolves (a JDK
  setting on this machine is not used). With the program console on, `runInTerminal` starts the debuggee
  through `RunService` in the host folder the adapter names. Attach and Debug Test go through the host's
  jdtls, so `localhost` there is the host. A saved configuration is debugged on the host too, with its
  folder there and none of this machine's JDK environment. A loose file (no Maven or Gradle project) is
  compiled for the debugger on its host, with the host's `javac` (beside its `jdk.home`, else on its `PATH`),
  into `<cache>/debug-classes/<id>` (removed when the session ends), and launched from there
  (`DapManager.compileOnHost`).
- **Not yet:** cloning or `git init` onto a remote host (`GitService.REMOTE_NOT_YET`), auto-fetch, the Project
  tree's per-path repository lookup, `blame.ignoreRevsFile` handling (left to the host's git).

Checked against OpenSSH 10.0 and dropbear 2025.89, both with a dash `/bin/sh` (2026-10-15, unprivileged
servers on localhost), and, in the `Remote probe` workflow below, against macOS 15.7's own OpenSSH 9.9p2 with
a bash login shell and no `setsid`, and Ubuntu 24.04's dropbear 2022.83:
`RealSshdProbeFxTest` is an opt-in end-to-end probe — run it with `-Dremote.probe=user@host:port`, a key and
a scratch folder on the host (its Javadoc lists the options). It covers key login, the login-shell `PATH`,
argument quoting, Git, ripgrep, Run with stdin, group kill, the channel budget, a tunnelled port, the
outside-change watch, the host clock, pyright, jdtls with Java debugging, debugpy, js-debug, a frozen and a
dropped connection, and ed25519 keys; all pass on all four (dropbear 2022.83 since the start retry above). dropbear needed the group guard above: before it, Stop
left a program's children running and a dropped connection left the program itself. Its `authorized_keys`
folder (`-D`) and every folder above it must not be group- or world-writable, so not under `/tmp`. The guard
was also checked with BusyBox's `sh` and `setsid`.

The `Remote probe` workflow (`.github/workflows/remote-probe.yml`; run it from the Actions tab, or push a
`probe/**` branch) runs the probe on two runners, each against a server of its own started unprivileged on a
high port: a `macos-15` runner's own `/usr/sbin/sshd`, and dropbear from the distribution on `ubuntu-latest`
(Ubuntu's 2022.83, older than the 2025.89 checked locally; it reads the runner's `~/.ssh/authorized_keys`). It installs the host's tools from
the sources Editora's installer uses (the pinned jdtls milestone, java-debug from Open VSX, js-debug from
GitHub, debugpy with pip, pyright with npm), and gives the account a login profile like a real user's: the
tools' `bin` folders on `PATH`, a `JAVA_HOME`. It is not part of CI.

**ed25519** keys and host keys work through Bouncy Castle (`bcprov-jdk18on`, see
[dependencies](../dependencies.md)): MINA SSHD has no EdDSA of its own and turns `ssh-ed25519` on only when a
provider is present. Without it a login whose only key was ed25519 — `ssh-keygen`'s default — failed, as did
a server offering only an ed25519 host key. `RemoteFileSystemsEd25519FxTest` pins it.

Tests: `EmbeddedSftpFixture` is an in-process MINA server; `startWithShell` makes it run command lines with a
real `/bin/sh`. Tests that use it need the JavaFX toolkit (the connect result is posted to the FX thread), so
they are tagged `fx`.
