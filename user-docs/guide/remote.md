---
title: Remote files (SFTP)
description: Browse, edit, search, and save files on a remote host over SSH/SFTP, and run Git, builds, language servers, and debuggers on that host, in a container, or in a WSL distribution.
category: Workspace
order: 3
beta: true
---

Editora can edit files on a remote host over **SSH/SFTP**. A remote folder
opens as a project in its own window, and from there editing, syntax
highlighting, search, bookmarks, notes, and preview all work over the wire. Save
writes straight back, with no dialog when the server copy is unchanged. If it
changed since you opened or last saved the file, Editora asks before overwriting
it.

On a host you allow to run commands, the tools Editora drives run **on that
host**, over the same SSH connection: Git and `gh`, ripgrep, builds, tests and
Run, language servers, debuggers, and the watch for outside changes. See
[What works remotely, and what doesn't](#what-works-remotely-and-what-doesnt).

A project on this machine can have its tools run elsewhere too, while you edit
its files here: in a Docker or Podman container the folder is mounted in (see
[Tools in a container](#tools-in-a-container)), or, on Windows, in the WSL
distribution the folder is inside (see
[Tools in WSL (Windows)](#tools-in-wsl-windows)).

Or the project stays on this machine and only its programs run on a host: a
[deployment](/docs/deployments) copies what changed to a folder there before
each run, then runs, builds and debugs the program in that copy.

## Connecting

| Action | Command |
| --- | --- |
| Connect to a host | `remote.connect` |
| Manage saved connections | `remote.manageConnections` |
| Open a remote file by URI | `remote.openFile` |
| Disconnect | `remote.disconnect` |

![The Connect to SFTP dialog over the editor, with Host, Port, User, Authentication, Key file, Secret and Path fields](/screenshots/docs/remote-connect.png "Connecting to a host over SFTP.")

`remote.connect` (*Remote: Connect to SFTP…*) opens a form for host, port,
user, the login method, and the folder to open (blank is the home folder). The
form has no password field: a password, a key's passphrase or a 2FA code is
asked for while the login runs, when the server or the key needs it (see
[Authentication](#authentication)). On success the remote folder opens as a
[project in its own window](#remote-folders-as-projects), with the Project tool
window showing its tree. The window you connected from is left as it was. With
projects turned off (Settings → Workspace), the folder is mounted as the
Project tree of the current window instead. Connections are remembered in
`connections.json`.

### Hosts from your SSH config

When `~/.ssh/config` names hosts, the form has a **From ssh config** button
listing them: every single host name of a `Host` line, in file order (patterns
such as `*.example.com` are left out). Pick one, or type a name the config
knows, and the form shows below the fields what the config says about it, as
`From ~/.ssh/config: HostName … · ProxyJump … · IdentityFile …`, and fills in
its **User** and **Port** until you type in those fields yourself.

The login then follows the config the way `ssh` does:

- **HostName**, **Port** and **User** decide where it goes and as whom.
- **ProxyJump** connects through the jump host first. The jump host's key is
  checked against `known_hosts` too, and asked about the same way if it is
  new.
- **IdentityFile** keys are offered by an Automatic login, before the default
  keys; **IdentitiesOnly yes** leaves the default keys out.
- **IdentityAgent** chooses the [SSH agent](#ssh-agent).
- **Include** lines are followed, globs too (`Include conf.d/*`, on Windows as
  well). A relative name is under `~/.ssh`, and a glob's files are read in
  name order.

`Match` sections are skipped, since Editora cannot evaluate their conditions,
and the rest of the file is read; options set only in a `Match` section do not
apply. `ProxyCommand` is not supported. A config that cannot be read at all is
ignored, and the connect goes on as if there were none. The file, and every
file it includes, is read again when it changes.

### When a connect fails

A connect that fails says why, in a dialog with advice and the underlying
message:

- **Could not reach** the host: no such name, nothing listening on the port,
  or no route from this network.
- The host **did not answer in time**.
- **The host key of** the host **has changed**: see
  [Host-key verification](#host-key-verification).
- The host **did not accept the login**: none of the ways tried were accepted.

Anything else reads **Could not connect to** the host, with the server's
message. Cancelling a login question, or declining a new host's key, only says
**Login to** the host **cancelled** in the status bar.

**Disconnect** closes every connection of the window, not only the mounted one,
and closes the SSH connection itself rather than only its file channel. When
remote tabs have unsaved changes it names them and asks first; their tabs stay
open but cannot be saved until you reconnect.

## Remote folders as projects

A remote folder is a project like a local one: it is listed with your other
projects in the project switcher (`project.switch`), and it has its own window,
its own saved tabs and layout, and its own [trust](/docs/workspace#trusted-folders).
A folder on a server and a local folder with the same path are trusted
separately.

Connecting to a remote folder from another window logs in once. The project's
window takes over
the connection you just logged in with, so a password or passphrase is asked
for once, and a server that wants a one-time 2FA code needs only one. Closing
the window you connected from leaves the project's window connected. Only when
the window you connected from still has tabs open on that host does it keep its
connection, and the project's window then logs in for itself.

Reopening a remote project, from the project switcher or when Editora restores
its windows at startup, opens its window and brings up the connect form filled
in for its host, since your password or passphrase is never stored. Until it
connects, the Project panel reads **Not connected to** the host, with a
**Reconnect** button. The tabs you had open come back when the connection does,
with their carets.

The same panel appears when the connection is lost and after **Disconnect**.
Editora asks the server whether it is still there every 30 seconds and gives up
after three unanswered asks, so a network that silently drops the connection is
noticed in about a minute and a half rather than whenever the system's TCP
timeout fires.

## Saved sites

Saved sites have three surfaces beyond the palette picker:

- A **Remote Sites** tool window (`tool.remote`; `M-g r` in the Emacs keymap) listing every saved
  site, with New, Connect, and Remove.
- A **Settings → Remote** page (`remote.settings`) to add, edit, and remove
  sites: label, host, port, user, auth method, and key path, and, under **This
  Host**, whether the host may [run commands](#allowing-a-host-to-run-commands)
  and which tool commands to use there.
- A **Remote Sites** quick-connect list on the [Welcome page](/docs/workspace).

Picking a site opens the connection form pre-filled. A password or key
passphrase is asked for during each login that needs it, and is never stored.

On the Settings page, each field is saved when you leave it or press Enter;
there is no Save button. A value that cannot work turns its field red, with the
reason in its tooltip, and the site keeps its last good values until you fix it:
**Port** takes a number from 1 to 65535 (blank means 22), **Host** takes only
the server's name or address (no spaces, no `user@`, no port and no `sftp://`),
and **User** takes no spaces, `@` or `:`. A **Key file** written as
`~/.ssh/...` is saved with your home folder filled in, and **Browse…** opens
in `~/.ssh`. A key file that cannot be read is marked with a warning but kept,
since the key may be on a drive that is not mounted yet. **Connect…** saves
what is typed and opens the connection form for the selected site. **Remove** asks
**Delete “*site*”?** first; Cancel is the default button.

## Authentication

The form's **Authentication** choice decides what the login may use:

- **Automatic (like ssh)**, the default, logs in the way `ssh` does: first the
  keys your [SSH agent](#ssh-agent) holds, then the `IdentityFile` keys
  `~/.ssh/config` names for the host, then the default keys in `~/.ssh` that
  exist (`id_ed25519`, `id_ecdsa`, `id_rsa`, unless the config says
  `IdentitiesOnly yes`), and then a password or the server's own questions, if
  the server offers those.
- **Private key file** offers only that key: no agent and no other key file. A
  server that asks for a 2FA code after the key still gets its questions.
- **Password** logs in with a password and the server's own questions, and
  offers no key at all.

Secrets are never stored, only the connection details (host, user, last path,
and which method to use). A password or passphrase you type is never written
to disk, and it and the keys are dropped from the SSH session as soon as the
login finishes.

Keys of every type `ssh-keygen` makes work, ed25519 (its default) included, and
so does a server that offers only an ed25519 host key.

### Questions during a login

What a login needs is asked for in a **Log In** dialog, at the moment the
server or the key needs it, as `ssh` asks at the terminal:

- **Password for** *user@host*, when the server wants a password.
- **Passphrase for the key** *file*, only when a key turns out to be encrypted
  and is about to be used.
- The server's own questions, such as a 2FA or one-time code or a Duo answer,
  in its own words, each in its own field. An answer is hidden as you type
  unless the server says it may be shown.

An answer that is not accepted is asked for again, and the dialog says **That
was not accepted. Try again.** Only the same question asked again counts as a
retry: a server that asks in rounds (a password, then a code) shows the code's
question as a new one. A password and each key's passphrase get three
tries. Cancelling a passphrase skips that key and the login goes on with the next way
in. Cancelling a password or a server's question ends the login, and only the
status bar says so. A question left unanswered for two minutes counts as
cancelled, and its dialog closes; the host-key question does the same. A server that asks for a new password because the old one expired
is not supported: the login fails.

The time you spend answering, the host-key question and a security key's touch
included, does not count toward the login's time limit, so a code read off a
phone does not time the connect out.

### SSH agent

An Automatic login uses the keys your SSH agent holds, before any key file:
`ssh-agent`, 1Password, Secretive, gpg-agent, or Windows' OpenSSH agent. So a
key on a security key (FIDO) or a smartcard, or one kept in a password
manager, logs in, and a key the agent has unlocked needs no passphrase.
Waiting for a security key's touch or a password manager's approval does not
time the login out.

The agent is found as `ssh` finds it:

1. The host's `IdentityAgent` in `~/.ssh/config`, when it names one: a socket
   path (it may start with `~` and use `%d` for your home folder and `%u` for
   your user name), `SSH_AUTH_SOCK`, or a variable such as `$MY_AGENT_SOCK`.
   `IdentityAgent none` turns the agent off for that host.
2. Otherwise `$SSH_AUTH_SOCK`.
3. Otherwise, on Windows, the OpenSSH agent's pipe
   (`\\.\pipe\openssh-ssh-agent`), which 1Password can serve too.

An agent that is not running is passed over quietly, and the login goes on with
the key files. When an Automatic login goes through a `ProxyJump` jump host,
the jump host is logged in with the agent too. Private key file and Password
logins never ask the agent, and the agent is never forwarded to the server.

## Host-key verification

Editora verifies the server's host key against `~/.ssh/known_hosts`, the
default file `ssh` uses, so a host you've already accepted at the terminal
connects with no prompt. That is the only file read: a `UserKnownHostsFile`
in your SSH config and the system-wide `ssh_known_hosts` are not, so a host
known only there asks once. A host you've never connected to shows its fingerprint and asks you
first (**Unknown Host Key**); **Connect** saves the key to `known_hosts`, so you
are not asked again, and declining ends the connect with only a note in the
status bar. Through a `ProxyJump`, the jump host's key is checked the same way.

A host whose key has **changed** is refused outright, without any button to
wave it through, since a changed key is how an impersonation attempt looks.
The error says so, and gives the command that removes the old entry
(`ssh-keygen -R host`, or `ssh-keygen -R [host]:port` for a port other than
22) for when the server was reinstalled or its key replaced on purpose.

## What works remotely, and what doesn't

The file features always work on a remote folder: editing, highlighting, find
and replace, Find in Files, bookmarks, notes, preview, and PDF or print. Recent
files keep remote files and reopen them once the connection is open.

Everything that runs a program needs the host's permission to run commands.
Once a host has it, those tools run **on the host**, as the account you signed
in with, each on its own channel of the same SSH connection. Nothing is
installed there.

### Allowing a host to run commands

The first time you open a folder on a host, Editora asks once: **Run commands
on** the host? The question names the host and the account, and says that the
Java language server keeps its index in `~/.cache/editora` there.

- **Allow** is remembered for that host (user, host and port), alongside the
  saved sites.
- **Not Now** is respected until you restart Editora; the file features keep
  working.

To withdraw a host's permission, open **Settings → Remote**
(`remote.settings`), pick the site, and untick **Allow running commands on this
host** under **This Host**. That is also where you can allow a host you said
Not Now to. Changes reach every open window at once: Git, language servers and
the outside-change watch start or stop accordingly.

Removing a site on Settings → Remote offers to forget the host's permission as
well (**Also forget that Editora may run commands on** the host, ticked), when
the host was allowed and no other saved site uses it; connecting again then asks
first. Removing a site from the Remote Sites tool window, or leaving that box
unticked, keeps its answer and tool commands, so adding it back neither asks
again nor loses them.

The switch for all hosts is **Run commands on the remote host** on
Settings → Remote, below the saved sites, also **Remote: Toggle Running
Commands on the Host**
(`remote.toggleExecution`). It is on by default; turned off, no host runs
anything, whatever it was allowed, and the **This Host** section of each site
is greyed out. Turning it back on with a remote folder open
asks about that host if it has not been allowed yet. The same switch covers
[containers](#tools-in-a-container) and [WSL distributions](#tools-in-wsl-windows):
while it is off, a project's tools run on this machine.

What the permission covers is the tools Editora already drives, listed below,
and the [Terminal](/docs/terminal#terminals-on-a-host-in-a-container-or-in-wsl),
which opens a shell on the host when you ask for one. Editora does not start an
AI agent on the host. It writes on the host only
where those tools write, plus `~/.cache/editora` (the Java language server's
index, the java-debug plugin, and classes compiled for debugging).

### What runs on the host

| Feature | On a host that may run commands |
| --- | --- |
| [Git](/docs/git) | Status, change bars, log, blame, staging (also single hunks), commit, branches, stashes, fetch / pull / push, clone and init, all by the host's `git` with the host's credentials. Automatic fetch works as for a local repository. Blame uses the repository's `.git-blame-ignore-revs`, and the Project tree's Git actions on a nested repository, submodule or worktree act on that repository. |
| [GitHub](/docs/github) | The host's own `gh`, signed in there. Links open in your local browser. |
| [Find in Files](/docs/navigation#find-in-files) | The host's ripgrep searches the folder in one command, when *Use ripgrep when available* is on and the host has `rg`. Otherwise every file is read over SFTP. |
| [Search Everywhere](/docs/navigation#search-everywhere) files | The host lists the project's files, skipping ignored ones as for a local project. There are no symbols for a remote project. |
| [Run, builds and tests](/docs/run-debug#running-on-a-remote-host) | Run on a file (a shell script under the interpreter its shebang names on the host, a Python script under the project's venv on the host), a main class, script / make / npm configurations, with output, input and Stop as locally; and Maven, Gradle, npm, Cargo and Go builds and test runs, with output and Stop (the Output console takes no input). A main class's Maven build runs on the host and streams into the Run console. |
| [Language servers](/docs/lsp) | Each server runs on the host: completion, hover, diagnostics, go to definition, rename and the rest. In a folder you have not trusted, servers that load the project's build wait, as locally ([details](/docs/lsp#servers-that-load-a-projects-build)). |
| [Debugging](/docs/run-debug#debugging) | Python (debugpy), JavaScript (vscode-js-debug) and Java (java-debug in the host's `jdtls`). |
| [Terminal](/docs/terminal#terminals-on-a-host-in-a-container-or-in-wsl) | A real terminal on the host, in the project's folder, with [shell integration](/docs/terminal#shell-integration). Open Terminal Here on a remote folder opens there. A dropped connection keeps the tab's text and offers Reconnect. |
| Outside changes | The project folder is watched on the host, so a `git pull` or a build there updates the Project tree, Git, language servers and an open file that changed. A host with `inotifywait` reports at once; others are checked every few seconds. |

The tools are the ones the host's own `PATH` finds. When it finds the wrong one,
or none, set the command for that host in **Settings → Remote → This Host**:
**Git**, **GitHub CLI**, **ripgrep**, **JDK home**, **Maven**, **Node.js**,
**Python for Run and tests**, **Python for debugging**, **JavaScript debugger
(vscode-js-debug)**, **java-debug plugin jar**, and **Language servers**, one
`server = command` line each (for example
`python = basedpyright-langserver --stdio`). Commands and paths are as the host
knows them; a change applies to the next command or server started there.
**Maven** is the `mvn` a Maven build on that host runs when the project has no
wrapper; Run's classpath resolution and the build before a launch use it too,
and write the classpath they resolve to a file in the host's `/tmp`.
**Python for Run and tests** is used when the project has no venv on the host
and no interpreter was picked for it; left blank, the host's `python3` usually
runs Python files (the full order is in
[Running on a remote host](/docs/run-debug#running-on-a-remote-host)).
Settings that name programs on this machine, such as your local Git command or
Default JDK, are never sent to a host.

Commands run with the account's login environment: the `PATH` and locale of its
login shell, and every variable its login profile (`~/.bash_profile`,
`~/.zprofile` or `~/.profile`) exports, such as `JAVA_HOME`, `MAVEN_OPTS`,
`GOPATH` or a version manager's. Variables that belong to the login session
itself (the prompt, history, `SSH_*`, the display) are left out.

### Still local only

These work only on files on this machine, whatever a host allows:

- [Local History](/docs/workspace#local-file-history)
- the [HTTP client](/docs/http-client) and the
  [HTML live preview](/docs/html#live-preview): a remote HTML file has no globe,
  and the HTML Preview commands say it is on a remote host
- [external tools](/docs/external-tools)
- *Reveal in File Manager* and *Save as Administrator*
- following a growing [log file](/docs/log-viewer)
- `.editorconfig` files, a project's committed `.editora/settings.json`, and a
  Markdown lint configuration file
- Go to Symbol in Project, which would have to read every source file over the
  network
- the Astro language server, and debugging languages other than Python,
  JavaScript and Java

### Hosts it works with

Remote commands work with **OpenSSH** servers, on Linux and on macOS (where
Remote Login is one), and with **dropbear**. The account's login shell must be
a POSIX shell (`sh`, `bash`, `dash`, `zsh`); Windows hosts and fish or csh login
shells are not supported. Stop ends a program together with everything it
started (on dropbear, when the host has `setsid`), and a lost connection ends it
on the host too.

## Saving

- **Save** writes back to the server, asking first when the server copy changed
  since you opened or last saved it.
- A file with **no write bit** on the server opens in
  [View mode](/docs/editing#read-only-and-view-mode). It is never replaced
  silently: Save asks first, and auto-save does not write it.
- **Save As** on a remote tab writes a **local copy**, and the tab becomes that
  local file. This also works after the connection is gone, so unsaved remote
  work can always be kept.
- An `.editorconfig` is not read for remote files, so a remote file in an
  encoding other than UTF-8 needs its
  [encoding chosen](/docs/workspace#file-encodings) with *Buffer: Reopen with
  Encoding…* or *Save with Encoding…*. The choice is remembered for that file,
  as for a local one.

Personal notes on a remote file are stored under the path you opened it by, not
the target of a symlink, so a file opened through a symlink and by its real
path keeps a separate set of notes for each.

## Behind the scenes

A remote path is a real `java.nio.file.Path` on an SFTP filesystem, so the same
open, save, list, and search code paths work unchanged. The transport is Apache
MINA SSHD. Saves use a server-side atomic rename when the host supports it. If a
safe replacement cannot be completed, Editora retains the previous remote copy
instead of risking a partial file.

Every window connects through one shared SSH client, which is what lets a
logged-in connection move to the project's window; each window still checks
host keys and asks its login questions itself. Editora reaches the SSH agent
over its Unix socket or, on Windows, its named pipe with no native library,
and offers the server no agent forwarding.

## Tools in a container

A project folder on this machine that is bind-mounted into a running **Docker**
or **Podman** container can have its tools run **inside the container**: Git
and `gh`, Run, builds and tests, language servers, and debuggers, each with the
container's own `PATH`, environment and user. The files stay where they are and
you edit them here as in any local project; only the commands move.

### Which container

- **The project's dev container.** When the project folder has a dev container
  configuration (`.devcontainer/devcontainer.json`, `.devcontainer.json`, or a
  `.devcontainer` folder) and the Dev Containers CLI or VS Code has started a
  container for it, Editora finds that container by the folder it was started
  for. A project without such a configuration is never looked up, so opening it
  runs no engine command.
- **A container you pick.** **Containers: Run This Project's Tools in a
  Container…** (`container.attach`) lists the running containers, and the one
  you pick is remembered for the project folder.

Either way, one of the container's bind mounts has to cover the project folder;
a named volume does not count. Pick one that does not, and Editora says the
container is not running or does not mount the folder. The command works in the window of a
project on this machine; anywhere else it asks you to open one first.

Editora looks for the container when the project opens, and again when its
window comes back to the front (at most every 30 seconds), so a dev container
started or stopped meanwhile is noticed. The status bar says when the project's
tools start running in a container, and when they run on this machine again.
Commands run as the dev container's `remoteUser` (or `containerUser`), else as
the image's own user.

### Allowing a container

The first time a project's tools could run in a container, Editora asks once
(**Run Tools in a Container**) whether to run them there. The question names the
container, the folder and the engine command, and says that Editora keeps
working files in `~/.cache/editora` there.

- **Allow** is remembered for that container, by name, with the hosts' answers
  in `connections.json`.
- **Not Now** keeps the project's tools on this machine, and is not asked again
  until you restart Editora.

Picking a container with *Run This Project's Tools in a Container…* is the
agreement itself, so it asks nothing.

**Containers: Run This Project's Tools Here** (`container.detach`) goes back: it
forgets the container picked for the project, withdraws the agreement, and the
tools run on this machine again. A dev container you leave this way is not asked
about again until you restart Editora.

Until a container is allowed, and while
[Run commands on the remote host](#allowing-a-host-to-run-commands) is off, the
project's tools run on this machine, as for any local folder.

### What runs in the container

| Feature | In a container |
| --- | --- |
| [Git](/docs/git) | Everything Git does, by the container's `git` with the container's configuration and credentials. Blame's ignore-revs settings work as on an SSH host. |
| [GitHub](/docs/github) | The container's own `gh`, signed in there. |
| [Run, builds and tests](/docs/run-debug#running-in-a-container-or-wsl) | Run on a file, a main class, run configurations, with output, input and Stop as locally; and Maven, Gradle, npm, Cargo and Go builds and test runs, with output and Stop (the Output console takes no input). Java is the container's own. |
| [Language servers](/docs/lsp#language-servers-in-a-container-or-wsl) | Each server runs in the container, the one its `PATH` finds. Java's jdtls keeps its index in the container. In a folder you have not trusted, servers that load the project's build wait, as locally ([details](/docs/lsp#servers-that-load-a-projects-build)). |
| [Debugging](/docs/run-debug#debugging-in-a-container-or-wsl) | Python (debugpy), JavaScript (vscode-js-debug) and Java (java-debug in the container's jdtls), as on an SSH host. |

The tools are the ones the container's `PATH` finds; the per-host tool commands
under **Settings → Remote → This Host** belong to saved SSH sites. Settings that
name programs on this machine, such as your local Git command or Default JDK,
are not used for a project whose tools run in a container.

Everything that reads the files works on them here, as in any local project:
editing, search (Find in Files uses this machine's ripgrep), Local History, and
the watch for outside changes, since a bind-mounted folder is this machine's
own.

### Ports and debuggers

A debugger that listens on a port inside the container, such as vscode-js-debug
or the Java debug port jdtls opens, is reached from here whatever the
container's network, rootless Podman and Docker Desktop included. For each
connection Editora runs a small relay in the container, which needs one of
`nc`, `bash` or `python3`. Most images have at least `bash`; in one with none of
the three, the port cannot be reached and the debug session reports why.

### Editora's files in the container

Editora keeps its working files in `~/.cache/editora` in the container's home:
the Java language server's index, the java-debug plugin it ships, the classes it
compiles to debug a Java file outside a project, and blame's ignore-revs
configuration. So the index survives a restart of Editora, and Debug works on a
loose Java file and with Editora's own java-debug plugin, as on an SSH host. The
folder lasts as long as the container does: a container created afresh starts
without it, and jdtls builds its index again.

### When Editora stops

On a normal quit, Editora stops the programs it started in the container. Ending
Editora's side of the connection does not end them, so if Editora is killed or
crashes, the next launch stops any language server, debugger, build or run it
left in the container, after checking that the process is still the one Editora
started.

### Docker or Podman

The engine is the **Container engine** command in **Settings → Remote**, under
*Run Commands on the Remote Host*: `docker` (also when it is blank) or `podman`,
which takes the same arguments. It may be a command line, such as
`flatpak-spawn --host docker`. Editora runs it on this machine with the `PATH`
it rebuilds from your login shell, so an engine your terminal finds is found
when Editora is started from the desktop too.

## Tools in WSL (Windows)

On Windows, a project inside a WSL distribution, opened by its Windows path
`\\wsl.localhost\Ubuntu\home\ana\app` (or the older `\\wsl$\Ubuntu\…`), can have
its tools run **inside that distribution**: Git and `gh`, Run, builds and tests,
language servers, and debuggers, with the distribution's own Linux tools and
its account's login environment, instead of Windows programs reading the files
across the share. What runs there is the same as for a
[container](#what-runs-in-the-container).

### Allowing a distribution

The first time such a project opens, Editora asks once (**Run Tools in WSL**)
whether to run its tools in the distribution. The question names the
distribution and the folder, and says that Editora keeps working files in
`~/.cache/editora` there.

- **Allow** is remembered for that distribution, with the hosts' answers.
- **Not Now** keeps the project's tools on Windows until you restart Editora.

**Containers: Run This Project's Tools Here** (`container.detach`) takes the
answer back, and the tools run on Windows again. As for containers, the switch
[Run commands on the remote host](#allowing-a-host-to-run-commands) must be on.

### Inside the distribution

A file in the distribution is handed to its tools by its Linux path
(`/home/ana/app/…`), and a file on a Windows drive by its `/mnt/c/…` path
(WSL's default mount of the drives). The
first command may wait while WSL starts the distribution. Editora's working
files, such as the Java language server's index and the java-debug plugin, are
in `~/.cache/editora` in the distribution's home. A debugger's port inside the
distribution is reached as in a container, through a relay that needs `nc`,
`bash` or `python3` there. If Editora is killed, the next launch stops what it
left running in the distribution, with the same check as for a container.

### Outside changes in WSL

Windows cannot watch a folder on the `\\wsl.localhost` share, so Editora watches
the project folder inside the distribution instead: a `git pull` or a build run
there reaches the Project tree, Git and the language servers. With
`inotifywait` in the distribution (the `inotify-tools` package) changes are
reported at once; without it the folder is checked every few seconds. While the project's tools run on Windows, changes made outside Editora
in such a folder are not seen.
