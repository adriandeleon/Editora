# Remote folders, remote projects and remote execution

Editora can open a folder on another machine over SFTP, treat it as a project, and (work in progress, off by
default) run the tools it drives on that machine. This page is the map; the SFTP file layer itself is
described in the `vfs/` entry of the [architecture catalog](../reference/architecture-catalog.md).

The opposite arrangement — the project on this machine, its programs run on a host in a copy of it — is a
[deployment](deployment.md); it uses the same connection, consent and execution layers without mounting the
host's folder.

## Files

A remote file is an ordinary `java.nio.file.Path` on a non-default filesystem (Apache MINA SSHD's
`SftpFileSystem`), owned by the window's `vfs/RemoteFileSystems`. `Vfs.isLocal(path)` is the predicate every
local-only feature gates on; `Vfs.toStorableString` / `parseStorable` round-trip a remote path as an
`sftp://user@host:port/path` URI. Each window has its own engine; all of them connect through one SSH client
(`vfs/SshClients`), started with the first engine and stopped with the last. The client's host-key check asks
the engine that owns the session (the `SshClients.ENGINE` attribute: the connect's context, or the session's
own once another engine adopts it), so each window keeps its own `known_hosts` and its own prompt.

`Remote: Disconnect` closes the SSH session, not only the SFTP channel: the session also carries commands and
forwarded ports.

## Logging in

The connect form takes no password: what a login needs is asked for while it runs, as `ssh` asks at the
terminal. `vfs/LoginPrompt` is the window's question (in the app, `RemoteCoordinator.answerLogin`, a blocking
FX dialog on the pattern of the host-key question, 120 s to answer); `vfs/LoginQuestions` puts it for the
engine that owns the session (`SshClients.engineOf`), so a window's login asks in that window.

- **Keyboard-interactive** (a server's own questions — where 2FA codes come from): the shared client's
  `UserInteraction` is `vfs/LoginInteraction`. The server's name, instruction and prompts are shown as sent,
  each answer hidden unless the server allows echo.
- **Password**: a given password (`connect`'s `secret`, used by tests) is the first try; then
  `resolveAuthPasswordAttempt` asks, up to three in all.
- **Key passphrase**: asked only when a key turns out to be encrypted (`RemoteFileSystems.Passphrases`, the
  key provider's `FilePasswordProvider`), three tries; cancelling skips that key and the login goes on.
- What each method may use: `PASSWORD` no keys (MINA's default keys were tried first before); `KEY` that file;
  `DEFAULT_KEYS` (shown as "Automatic") the SSH agent's keys, then what MINA puts together for the session —
  `~/.ssh/config`'s `IdentityFile` keys, then the default keys, unless `IdentitiesOnly yes`. Each may still answer the server's
  questions, so a key + 2FA server works. After the login the session's key provider is emptied, so no
  passphrase outlives it.

**`~/.ssh/config`.** `vfs/SshEnvironment` is where the engine's SSH setup lives (`~/.ssh`'s `config`,
`known_hosts` and default keys, and the environment); the app's is `SshEnvironment.standard()`, a test's a
folder of its own (the package-private test constructors use the folder of their `known_hosts`, so no test reads
the config or keys of whoever runs it). The shared client's host-config resolver, default-key provider and
`FilePasswordProvider` (`vfs/Passphrases`) all delegate to the owning engine, the resolver through the connect's
context. The resolver is `vfs/SshConfigFile`: MINA's own entry parser and matching (HostName, Port, User,
IdentityFile, CertificateFile, IdentitiesOnly, ProxyJump) over the config with `Include`s read in place
(relative to `~/.ssh`, `~`, globs sorted, 16 deep) and `Match` sections left out — MINA's resolver refuses the
whole file over a `Match` ("Currently not able to process Match sections"), for every host, so such a config
had stopped every connect. It re-reads when the config or an included file changes; the jump session carries the same context, so its host-key question and login
are the same window's. The connection id stays what the form says (`user@alias:port`), so saved sites, exec
consent and `sftp://` URIs don't depend on the config. The connect form offers the config's single-name `Host`
entries (`RemoteFileSystems.configuredHosts`, the editor's `sshconfig.SshConfig` parser), and for the host
typed shows HostName, ProxyJump and IdentityFile and fills User and Port from `RemoteFileSystems.configured` —
MINA's resolver, the connect's own reading; User from the editor parser of the same text, since MINA fills in
this machine's user when the config has none — until you type in those fields. Not supported: `ProxyCommand`,
and what a `Match` section says.
- Time spent answering is left out of the login deadline (`PromptClock`, the #486 rule for host-key prompts,
  now for every question); MINA's own `AUTH_TIMEOUT` is raised to 15 minutes so it never ends a login first.
- Cancelling a password or server question sets `LoginQuestions.CANCELLED` (and the engine's per-connect flag,
  since a closed session's attributes are cleared): nothing more is asked and the connect fails as
  `CANCELLED`, which the status bar reports without a dialog.

**SSH agent.** The shared client's `SshAgentFactory` is `vfs/AgentFactory`; for an Automatic login only (the
session's `RemoteFileSystems.AUTH` attribute) it opens `vfs/AgentConnection`, MINA's `AbstractAgentProxy`
(the whole agent protocol) over a transport of our own: a Unix-domain socket (`SocketChannel` +
`UnixDomainSocketAddress`) or, on Windows, a named pipe opened as a file (`RandomAccessFile`, `CreateFile`
underneath) — `java.base` only. MINA's `agent.unix` needs Tomcat's APR natives and is not used. MINA offers the
agent's keys before any key file. Where the agent is, `vfs/AgentLocation`: the host's `IdentityAgent`
(`none`, `SSH_AUTH_SOCK`, `$VAR`, a path with `~`, `%d`, `%u`), else `$SSH_AUTH_SOCK` from the engine's
`SshEnvironment`, else on Windows `\\.\pipe\openssh-ssh-agent` (the OpenSSH service's, also 1Password's). An
agent that is not there is passed over (a FINE log). A signature is timed as answering time, so a security
key's touch or a password manager's approval does not run out the login deadline. No agent forwarding: the
factory registers no forwarding channel. A GUI launch inherits `SSH_AUTH_SOCK` from launchd (macOS) or the
desktop session (Linux); an agent started only in a shell profile is not seen — name it with `IdentityAgent`.
Tests: `SshAgentLoginFxTest` (an agent of the test's own: MINA's `AbstractAgentClient` over `AgentImpl` on a
socket), `AgentLocationTest`, and the opt-in `RealSshAgentProbeFxTest` against a real agent, which
`remote-probe.yml` runs with `ssh-agent` on macOS and Linux and with the OpenSSH agent service on Windows (the
only test of the named pipe).

**Why a connect failed** is `vfs/ConnectFailure`: unreachable, timed out, host key declined, host key changed
(`KnownHostsServerKeyVerifier`'s modified-key acceptor records it), login refused, cancelled, other.
`RemoteCoordinator.reportConnectFailure` shows a "Remote" dialog with advice for the kind (for a changed key,
the `ssh-keygen -R` line naming the entry) and the underlying message; a cancel or a declined key only sets
the status.

## Projects

A remote folder is a `config/Project` whose `root` is its `sftp://` URI.

- `Project.rootPath()` is the only way to turn a stored root into a path. It is `null` while the host is not
  connected. `Path.of(project.root())` is forbidden (a scan in `RemoteProjectRootTest` enforces it): for a
  remote root it silently yields a relative local path.
- `Project.contains(file)` refuses a file on another filesystem; paths of two providers cannot be compared.
- `TrustStore` keys a remote folder by its URI and only matches folders on the same filesystem, so a remote
  `/srv/app` and the local `/srv/app` are trusted separately.

**One window per project, and that window holds the connection.** When the connect form succeeds in window
A for a folder that is not A's project, `RemoteCoordinator.connected` creates the project and hands A's
logged-in session over: `RemoteFileSystems.handOff` gives it up without closing it, and
`WindowManager.openRemoteProject` opens the project's window, whose engine `adopt`s it
(`adoptForWindowProject`). One login serves both windows, which matters when it took a one-time 2FA code.
The session's close listener asks `SshClients.engineOf` which engine to tell, so a drop after the handover
reaches the project's window, and closing A leaves the project's window connected. When A's tabs still use
that host (or A shows it), the session stays in A and the project's window logs in for itself
(`connectForWindowProject`) with a copy of the secret, wiped after it. With projects turned off, the folder is
mounted in the current window as before.

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

Where a command runs is the target's question, not the filesystem's (`ExecTargets.runsHere`,
`ExecTarget.isLocal`). A folder of this machine can have its tools run elsewhere — bind-mounted into a
container, inside a WSL distribution — through a *mapped* resolver (`ExecTargets.registerMapped`), asked
about every local path without I/O. While the setting is off, or that target has not been allowed, such a
folder's tools run here, as before: its files are local. So code that picks a tool, its environment or its
arguments asks the target, and every path handed to a tool goes through `ExecTarget.toTargetPath` (an SFTP
path's text happens to be the host's path; a mapped folder's is not). File-access decisions — whether reading
a path is a network round trip — stay on the filesystem (`Vfs.isLocal`). Git follows this already
(`GitService.isLocal` / `gitPath`, `GitHubService`, `RemoteExec.runsFor`), and so do language servers: a
mapped folder's server starts on its target, gets no process id, and is told about the folder and its
documents by the target's names for them (`WireUris.mapped`, translating `file:` keys through
`toTargetPath` / `toPath`; a file outside the folder, or of another scheme, passes as it is). A test-only
`MappedTestTarget` records any path handed to a tool by this machine's spelling (`GitMappedTargetFxTest`,
`LspMappedTargetProcessTest`, `RunMappedTargetFxTest`). Run, Build and Maven follow it too: a mapped folder's
program, build and classpath resolution start on its target (`RunService`, `BuildService`,
`MavenClasspathResolver`), with that side's JDK and environment and none of this machine's; a file is handed
to its interpreter, and a Maven wrapper above the module by its absolute path, by the target's name for it;
the wrapper is the POSIX one whatever this machine is (`BuildTool`); and an absolute working directory typed in
a run configuration is read as the target names it (`RunConfigWorkingDirectory`). Search still reads a mapped
folder with this machine's ripgrep: its files are local. Debugging follows as well: a mapped folder's file is debugged
on its target through the same launch path as a remote host's (`DapManager`, `DebugCoordinator`), and
`DapPaths.on` owns only the files whose tools run on the adapter's target, so a breakpoint in another local
file is not sent there (`DapMappedTargetFxTest`).

**Containers** (`container/`). `ContainerExecTarget` runs a bind-mounted folder's tools in a running container
with `docker exec` or `podman exec` (`ContainerEngine`, one configurable command that both accept):
`exec -i [-u user] <container> /bin/sh -c <guard> editora <script> <argv>…` — the same script as on an SSH
host (`PosixShell.argv`), so the command prints its pid, sets its folder and the container's login environment
(`RemoteEnv`, probed once through the container's login shell), and becomes the program. `exec` starts it as
the leader of its own process group, so Stop signals the group with a second `exec` (`PosixShell.killArgv`).
Paths cross through the bind mounts read from `inspect` (`ContainerMounts`, deepest mount first; `ContainerInfo`
also reads a dev container's `remoteUser` from its `devcontainer.metadata` label). Unlike an SSH session,
killing the local `exec` client leaves the program running in the container: Editora stops what it started
(`ProcessRegistry`, through `process/LauncherProcess`, which reads the pid line and signals with a second
command).

A folder is bound to its container by `ContainerBindings` (one per app, the mapped resolver `App` registers):
the container the user picked for the folder (`Settings.containerBindings`, folder path → container), else
the running dev container started for it (its `devcontainer.local_folder` label), provided one of its mounts
covers the folder. `ui/ContainerCoordinator` asks the engine off the FX thread when the window's project
changes and when the window comes back to the front (at most every 30 s), then puts the one-time question
per container, as for an SSH host (the answer is kept with the hosts' answers, by `container:<name>`); picking
a container with Containers: Run This Project's Tools in a Container… is the agreement itself, and Containers:
Run This Project's Tools Here forgets the pick and withdraws it. Until then, or with remote execution off, the
folder's tools run here. When where a folder's tools run changes, a language server started on the other
side is stopped and its tabs reopen on the right one (`LspManager.shutdownSessionsOnAnotherTarget`: a
session remembers the target it started on). The engine's command is `Settings.containerEngine` (Settings →
Remote; blank is `docker`). A port only the container has — a debugger's — is reached through a loopback port
here (`ContainerExecTarget.forwardLocal`, `process/RelayPortForward`): each connection starts one `exec` running a
relay the container has, found once (`process/PortRelays`: `nc`, else `bash`'s `/dev/tcp` with `dd` copying
each way, else `python3`), joined to the port
inside. It works for every engine and network, including rootless Podman and Docker Desktop, whose containers
this machine cannot reach by address; the pumps flush every read, since a process's stdin is buffered. The
`bash` relay copies with `dd`, not `cat`: uutils' `cat` (Ubuntu 26.04's coreutils, so `eclipse-temurin:25`'s)
holds what it reads from a socket until the socket closes, so a reply on a connection that stays open — every
debug adapter's — never arrived. `ContainerProbeFxTest.aJavaProgramIsDebuggedInTheContainerByItsJdtls` runs jdtls
in the container (mounted read-only, `-Dcontainer.probe.jdtls`) with the java-debug plugin copied into its cache
(`-Dcontainer.probe.javaDebug`), and debugs a Maven project's main class and a loose file to a breakpoint. Editora's
working folder in a container is `~/.cache/editora` in the container's home (`ContainerExecTarget.cache`), which
this machine cannot see: its files are written by commands in the container (`process/ShellTargetCache`:
`mkdir -p`, a file streamed through `cat` into a `.part` and moved in, `rm -rf`, the folder and name always
passed as arguments), so a container's jdtls index, the java-debug jars, a loose file's debug classes and blame's
stand-in git config live there as on an SSH host. They last as long as the container; the consent question
names the folder. A program Editora tracks in a container outlives a crashed Editora (killing the client here
does not end it there): its ledger row says how to reach it, and the next launch stops it in the container,
only while its pid still has the recorded start time (`ContainerExecTarget.reapOrphan`; see
[LSP and DAP](lsp-and-dap.md), process lifecycle). The `Remote probe` workflow's
container job runs `ContainerProbeFxTest` against Docker on `ubuntu-latest`. `ContainerProbeFxTest` is the opt-in check against a real engine
(`-Dcontainer.probe=podman -Dcontainer.probe.image=<an image already present>`).

**WSL** (`wsl/`, Windows only). A folder inside a WSL distribution, opened from Windows as
`\\wsl.localhost\<distro>\…` (or `\\wsl$\<distro>\…`), has its tools run inside that distribution:
`WslExecTarget` runs `wsl.exe -d <distro> --exec /bin/sh -c <guard> editora <script> <argv>…`, the same script
as a container's. That argv does not reach `wsl.exe` as given: Windows joins it into one command line, Java
does not escape an embedded `"`, and `wsl.exe` splits it again, so the whole command goes as one shell command
line encoded for `printf %b` (letters and digits as they are, other bytes as `\0ooo`), which a fixed
`set -f; IFS=; eval $(printf %b $1)` decodes inside (`WslExecTarget.windowsSafe`, `WslArgvTest` runs it
through dash and bash). So the pid line, Stop (a second `wsl.exe` running `kill`, `LauncherProcess`), the login
environment (`RemoteEnv`) and port relays (`PortRelays`, `RelayPortForward`) are shared. Paths are mapped by
`WslPaths`: `\\wsl.localhost\<distro>\x` ↔ `/x`, and a Windows drive `C:\x` ↔ `/mnt/c/x` (a path elsewhere
passes as it is). `WslTargets` is the mapped resolver `App` registers on Windows only; it recognises the share
prefix without I/O and keeps one target per distribution. `ContainerCoordinator` asks once per distribution
(`wsl:<distro>`, kept with the hosts' answers) when such a project opens; Containers: Run This Project's Tools
Here withdraws the answer. The cache folder and the home are the distribution's (`~/.cache/editora`).
The `Remote probe` workflow's `wsl` job runs `WslProbeTest` on `windows-latest` against a WSL 1 Ubuntu
(`-Dwsl.probe=<distro>`; hosted runners cannot run WSL 2). Outside changes: Windows cannot watch the share
at all (registering a watch on `\\wsl.localhost` fails with "Incorrect function", which the panel used to
swallow, so a WSL project saw no outside change), so a WSL target says `localWatchSeesChanges()` false and a
WSL project whose tools run in the distribution is watched there, as an SSH host's folder is
(`ProjectPanel.watchedOnTarget`, `RemoteWatcher`); with its tools here again it goes back to this machine's
watcher. `WslWatchProbeTest` shows a change made inside and a file written from Windows through the share both
reported, with `inotifywait` and with the poll.

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
on this machine. The ids: `git`, `gh`, `rg`, `jdk.home`, `node`, `python`, `dap.python`, `dap.js`, `dap.java`, and
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

- **The integrated terminal:** a login shell on a PTY channel of the window's session (`startTerminal`; see
  [terminal.md](terminal.md#terminals-on-an-ssh-host)). It is the one channel with a PTY, takes a long-lived
  slot, and on a dropped connection the server's hang-up ends it, with no watchdog. The tab offers Reconnect.
  In a container or WSL folder the terminal is the target's own launcher (`exec -it`, `wsl.exe`) on a PTY here.
  It hangs its shell up through the target on close, because a container keeps what an `exec` client left
  ([terminal.md](terminal.md#terminals-in-a-container-or-a-wsl-distribution)). Shell integration writes its bash
  and zsh scripts to the target's cache once per version, and closing a busy remote shell asks first, from `ps`
  on the target ([terminal.md](terminal.md#shell-integration)). A run configuration with Run in a Terminal tab,
  and a debugged program with `debugProgramInTerminal`, run in a terminal on the target too
  (`TerminalSpec.program`, through the account's login shell; [terminal.md](terminal.md#ide-features)).
- **Git:** status, the gutter diff, tab colouring, log, blame and blob lookups, and every command that changes
  a repository (stage, commit, checkout, stash, merge, rebase, fetch / pull / push, hunk staging). `GitService`
  keeps git's availability and version per host (`gitAvailable(Path)`), uses the host's `git` (or its
  configured tool), and never probes a host from the JavaFX thread. User commands get a host-neutral
  environment (`REMOTE_USER_ENV`: nothing from this machine's locale). Credentials for fetch and push are the
  host's own; nothing prompts (`GIT_TERMINAL_PROMPT=0`).
- **Repositories inside a remote project** (a nested repository, a submodule, another worktree): the Project
  tree's and the tab menu's Git actions on their files go to that repository, as locally (`GitPathScope`).
  Locally the nearest `.git` is found with a few `stat` calls as the menu opens; on a host each would be an
  SFTP round trip on the FX thread, so the host is asked once, off it, where the project's `.git` entries are
  (`GitPathScope.learnRemote`: one `find`, eight levels deep, skipping `node_modules` and the like) when the
  project is shown, after a clone or init, and on a tree refresh at most once a minute. Until it has answered,
  a remote path is in the active repository when under its root, as before.
- **Blame's ignore-revs file** (`BlameIgnoreRevs`) works on a host as locally: a `.git-blame-ignore-revs` at
  the root is used when nothing is configured, and a `blame.ignoreRevsFile` the host's global config names
  but this repository lacks no longer breaks blame. The configuration is the host's (its `~`, read with
  `git config --path`), the files are looked at over SFTP on the blame's thread, and the stand-in global
  config (the user's without that key) is written on the host, in its `~/.cache/editora`, named by its
  contents so it is written once (`GitService.hostGlobalConfigCopy`), for `GIT_CONFIG_GLOBAL`.
- **Fetch automatically** (`GitAutoFetch`, `GitService.autoFetch`) fetches a remote repository on its host,
  by the host's git (`commandFor`), with the same batch-mode environment as a local one (`GitSafety.autoFetchEnv`)
  and under the same trust rule. A `GIT_SSH_COMMAND` or `GIT_SSH` the host's login profile exports is the
  user's, as an exported one is locally, so Editora's batch-mode ssh is not set over it
  (`GitService.hostExportsSsh`, one quick command on the host per fetch).
- **Clone and init on the host:** in a window whose project is remote, Git: Clone clones on that host, by its
  git, from the host's network with its SSH keys and credential helpers. The form proposes the folder above the
  project; the folder is typed as the host names it (`PathKeys.resolveUserInputOn`: an absolute path is the
  host's, `~` the account's home there, `ExecTarget.homeDir`), checked and its missing parents created over SFTP
  off the FX thread (`CloneDestination`), and its Browse button (a local folder dialog) is hidden. The README
  is looked for off the FX thread too. Git: Init in such a window resolves the typed folder the same way.
  `GitService.clone` / `init` hand git the host's own path (`gitPath`). An HTTPS URL that needs a password
  fails rather than prompts, as fetch does.
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
  tools (sent as `JAVA_HOME`). A Python file or configuration runs under the project's venv on the host
  (`.venv`/`venv`/… with `pyvenv.cfg`, found over SFTP off the FX thread), else the host's `python` tool, else
  `python3` there (`PythonRunPlan` with `Host.elsewhere`). Build-wrapper detection on a host is POSIX (`./mvnw`, `./gradlew`) and reads
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
  The same watch serves a local folder whose target says this machine's watcher is not told of its changes
  (`ExecTarget.localWatchSeesChanges`: a WSL distribution's), with the target's names mapped back
  (`ExecTarget.toPath`). The poll's `find` takes `-H`, so a root that is a symlink is walked. Only the
  polling fallback is covered by tests here (no `inotifywait` on the build machine); `inotifywait` is covered
  by the WSL probe.
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
  `ExecTarget.cache()` (`~/.cache/editora` from the login shell's `HOME`; the consent question names it). Every
  file Editora puts there goes through `process/TargetCache`: an SSH host's and a WSL distribution's through
  their file systems (`PathTargetCache`, the folder `ExecTarget.cacheDir()`), a container's by commands
  (`ShellTargetCache`).
  It is not claimed across windows as local workspaces are (a remote project has one window), and a failed
  handshake does not delete it. `java.configuration.runtimes` lists the host's JDKs, found by one `sh` command
  (`RemoteJavaRuntimes`: the host's `jdk.home` tool, `JAVA_HOME`, the `javac` on `PATH`, and the folders
  `JavaRuntimes` looks in). The java-debug bundles are the host's `dap.java` tool, else this machine's jars copied
  into `<cache>/java-debug/` (`LspManager.remoteDebugBundles`; sent again only when the size differs, the name
  carrying the version). `-Djava.lsp.joinOnCompletion=true` goes on the command line when the host says the executable is
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
  Editora's installer and mason put it (VS Code's js-debug has no `dapDebugServer.js`); the launch reads its
  output with `outputCapture: "std"` (Editora cannot start the program on the host); it is started with port 0 on `127.0.0.1`, the port
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
  through `RunService` in the host folder the adapter names, or in a Terminal tab on the host with
  `debugProgramInTerminal`. Attach and Debug Test go through the host's
  jdtls, so `localhost` there is the host. A saved configuration is debugged on the host too, with its
  folder there and none of this machine's JDK environment. A loose file (no Maven or Gradle project) is
  compiled for the debugger on its host, with the host's `javac` (beside its `jdk.home`, else on its `PATH`),
  into `<cache>/debug-classes/<id>` (removed when the session ends), and launched from there
  (`DapManager.compileOnHost`).

Checked against OpenSSH 10.0 and dropbear 2025.89, both with a dash `/bin/sh` (2026-10-15, unprivileged
servers on localhost), and, in the `Remote probe` workflow below, against macOS 15.7's own OpenSSH 9.9p2 with
a bash login shell and no `setsid`, and Ubuntu 24.04's dropbear 2022.83:
`RealSshdProbeFxTest` is an opt-in end-to-end probe — run it with `-Dremote.probe=user@host:port`, a key and
a scratch folder on the host (its Javadoc lists the options). It covers key login, the login-shell `PATH`,
argument quoting, Git, a clone onto the host and an automatic fetch there, ripgrep, Run with stdin, group kill, the channel budget, a tunnelled port, the
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
