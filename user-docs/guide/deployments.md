---
title: Deployments
description: Keep a project on this machine and run it on an SSH host. Before each run, Editora copies what changed to a folder there, then runs, builds and debugs the program in that copy.
category: Run & debug
order: 4
beta: true
---

A **deployment** lets you work on a project on this machine and run its
programs on another one: a server with the database, a GPU box, or a machine
with the right operating system or architecture. The sources stay here, along
with your language servers, Git and Local History. Before a run, Editora copies
what changed in the project to a folder on an **SSH host**, then runs the
configuration **there, in that copy**.

It is the reverse of a [remote folder](/docs/remote), where the project itself
lives on the host and you edit it over SFTP. Use a remote folder when the files
belong on the host. Use a deployment when they belong here and only the program
needs to run there.

A deployment uses the same SSH connection, logins, host keys and
[permission to run commands](/docs/remote#allowing-a-host-to-run-commands) as a
remote folder. Nothing is installed on the host, and this machine needs no
`ssh` or `rsync`.

## Setting one up

A project's deployments are listed in the **Deployments** window: **Run →
Deployment → Edit Deployments…** (`deploy.edit`), or **Deployments…** next to
**Run on** in the Run Configurations window. **Add** creates one and **Remove**
deletes one. Each field is saved when you leave it; there is no Save button.

| Field | What it holds |
| --- | --- |
| **Name** | How run configurations and commands refer to it. Renaming it renames it in every configuration that uses it. |
| **Host**, **Port** | The SSH server. **From saved site** fills the host, port, user, authentication and key file from one of your [saved sites](/docs/remote#saved-sites), and the folder from the site's last path when the folder is still blank. |
| **User** | The account on the host. |
| **Authentication**, **Key file** | Your default `~/.ssh` keys, a specific key file, or a password, as for a [remote folder](/docs/remote#authentication). A password or passphrase is asked for when the connection opens and is never stored. |
| **Folder on host** | An absolute folder that holds the copy, such as `/home/you/apps/project`. It is created on the first deploy if it is missing. A folder that cannot be used is flagged as you type ([see below](#what-a-deploy-never-does)). |
| **Leave out** | Patterns for files not to send, one per line ([see below](#what-is-sent)). |
| **Options** | **Send only what Git tracks or would track (.gitignore)**, **Remove files deleted from the project**, and **Upload on save**. The first two are on for a new deployment. |

![The Deployments window with one deployment, staging: Host staging.example.com, port 22, User ada, Automatic authentication, Folder on host /srv/apps/shelf, an empty Leave out list and the Options checkboxes, with Deploy Now and Close buttons](/screenshots/docs/deployments.png "A deployment to a staging host, filled in.")

Deployments belong to a project on this machine. In a remote project, or in the
No Project window, the deployment commands say to open a local project's
window.

## Running on a deployment

In the **Run Configurations** window (`run.editConfigs`), **Run on** chooses
where a configuration runs: **This machine** (the default), or one of the
project's deployments. Pickers that list configurations, such as **Run: Run
Configuration…**, show it after what the configuration launches:
*com.example.App · on app-server*.

When you **Run** a configuration that runs on a deployment, Editora:

1. Saves every edited file of the project.
2. Deploys: it copies what changed to the deployment's folder. The Run console
   shows the progress, and its first line is the deploy's summary, such as
   *Deployed to app-server: 3 sent, 1 removed, 412 up to date*. Stop cancels the
   deploy after the file being sent.
3. Runs the configuration's [step before the launch](/docs/run-debug#a-step-before-the-launch),
   then the program, on the host, in the copy.

Another Run while any of these steps is under way is refused, and leaves the
deploy and the launch as they are: Stop still cancels them.

The program runs as it would in a [remote project](/docs/run-debug#running-on-a-remote-host):
with the host's interpreters, JDK, tools and login environment, and with output,
input and Stop over the same connection. A script, a working directory or
another path that names a file inside the project is turned into the same place
in the copy, and a relative one is relative to the deployment's folder. The
configuration's **JDK override** names a folder on this machine, so it is not
used there; set **JDK home** for the host in
[Settings → Remote → This Host](/docs/remote#what-runs-on-the-host) instead.

File links in the Run console, such as a stack trace's, open the local files,
since they are the same as the copy. **Rerun** deploys again before it runs.

### Java on a deployment

A Java configuration is **built on the host**, from the copied sources, with
the host's own build tool:

- **Maven**: the host's `mvn` compiles the project and gives the class path
  (as in a remote project, the `PATH` one rather than a `./mvnw` wrapper).
- **Gradle**: the project's `./gradlew` when it has a wrapper, else the host's
  `gradle`.

A Java project with neither cannot run on a deployment, and Run says so. To run
a jar built some other way, use a shell script configuration that runs it.

### Debugging on a deployment

**Debug** on a Java configuration deploys, builds on the host and starts the
program there under the debugger, listening only on the host's own loopback.
Editora tunnels that port through the SSH connection and attaches to it, so
breakpoints, stepping, variables and the call stack work on the local files, as
in any [Java debug session](/docs/run-debug#debugging-java). The tunnel closes
when the program ends.

Debug needs a Java file of the project open in a tab, which the debugger works
through. Debugging on a deployment is for Java only.

## What is sent

A deploy sends only the files whose **contents** changed since the last deploy
to that folder. Editora remembers, on this machine, what each deploy put there,
and asks the host for the folder's file sizes and times in one command. So a
deploy where nothing changed costs one round trip, and a branch switch that only
touched file times sends nothing. The files go as one compressed stream into
the host's `tar` (file by file over SFTP when the host has no `tar`), and keep
their local modified times.

Which files count as the project's:

- With **Send only what Git tracks or would track (.gitignore)** on, and the
  project in a Git repository, the files Git tracks plus the new ones it does
  not ignore. Otherwise every file in the project folder.
- Some folders and files are always left out: `.git/`, `.hg/`, `.svn/`,
  `.idea/`, `.DS_Store`, `Thumbs.db`, `target/`, `build/`, `out/`, `.gradle/`,
  `node_modules/`, `.venv/`, `venv/`, `__pycache__/`, `*.pyc`, `*.class`,
  `*.hprof`, `*~` and `*.swp`. The host builds its own `target/` or
  `node_modules/`; your local ones are not sent.
- **Leave out** adds patterns of your own, written like `.gitignore` lines:
  `*.log`, `data/`, `/docs/*.pdf`. A line `!pattern` sends one of the always-left-out
  ones after all: `!build/` sends the `build/` folder.

Secrets are files like any other: a `.env`, an `http-client.private.env.json`
or a key file in the project is sent unless **Leave out** names it, or Git
ignores it while **Send only what Git tracks or would track** is on.

With **Remove files deleted from the project** on, a file a deploy put on the
host that the project no longer has is removed from the host, together with any
folder that leaves empty.

**Upload on save** deploys each time you save a file of the project, shortly
after the save, without running anything. A deployment whose upload fails is not
tried again on every save; the next deploy that succeeds turns it back on.

## What a deploy never does

A deploy is careful with files it did not put there:

- **It removes only Editora's own files.** A file is removed only when a deploy
  sent it, the project no longer has it, and the host's copy is still the one
  sent. Build output, logs, uploads and anything else the host made are never
  removed.
- **It asks before replacing a file changed on the host.** When a file a deploy
  sent was changed on the host since, **Changed on the Host** lists it and offers
  **Overwrite** with the project's file, **Keep the Host's**, or Cancel.
  A file you keep is remembered as the host's: later deploys leave it alone, and
  never remove it, while the project's file stays as it was. Edit it in the
  project and the next deploy asks again. If the host loses the file, it is sent
  again.
- **A first deploy into a folder that already has files asks first.** When the
  folder holds files the project would replace, **Folder Not Empty** lists them
  and **Replace** goes ahead. Files that are already the same are kept as they
  are, without sending them again.
- **It refuses a folder that is too wide.** The folder must be absolute, and
  cannot be `/`, a system folder such as `/usr`, `/etc`, `/var` or `/tmp`, the
  account's home folder, or a folder above it. Pick a folder of its own below it,
  such as `~/apps/project`.

## Commands

All of them are in **Run → Deployment**. Each acts on the deployment of the
configuration selected in the toolbar, or the project's only deployment, and
otherwise asks which one.

| Action | Command |
| --- | --- |
| Deploy Now: bring the copy up to date without running anything | `deploy.now` |
| Compare with Deployed: the active file beside its copy on the host, in the [diff viewer](/docs/diff-merge) | `deploy.compare` |
| Open Terminal in Deployment: a [Terminal](/docs/terminal) tab on the host, in the deployment's folder | `deploy.terminal` |
| Edit Deployments… | `deploy.edit` |

The Deployments window has its own **Deploy Now** button for the deployment
selected there.

## Sharing them

Deployments are kept with the project's run configurations, on this machine.
**Export Configurations to Project** (`run.exportConfigs`) writes them to
`.editora/run-configurations.json` together with the configurations, and
**Import** (`run.importConfigs`) brings them back, as described in
[Sharing them with your team](/docs/run-debug#sharing-them-with-your-team). A
deployment holds no password or passphrase, only the host, user, authentication
method, key file path and folder. The key file path is this computer's, so
check [what the file holds](/docs/run-debug#what-the-file-holds) before you
commit it.

## What the host needs

- An **OpenSSH** or **dropbear** server, on Linux or macOS, with a POSIX login
  shell, as for [remote commands](/docs/remote#hosts-it-works-with). Windows
  hosts are not supported.
- **`sh`**, **`find`** and **`tar`**, plus `gzip` to compress the stream.
  GNU, BSD and BusyBox versions all work. Without `tar`, files are sent one by
  one over SFTP.
- Permission to run commands. The first deploy to a host you have not allowed
  asks **Run commands on** the host, once, as a remote folder does. **Run
  commands on the remote host** on Settings → Remote must be on; while it is
  off, nothing can be deployed.
- For Java, a JDK and Maven or Gradle (a Gradle wrapper in the project is
  enough).
