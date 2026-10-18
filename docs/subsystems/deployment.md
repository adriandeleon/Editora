# Deployments

A **deployment** runs a local project's programs on another machine: before a run, Editora copies what changed
in the project to a folder on an SSH host, then runs the configuration there, in the copy. It is the inverse of
[remote folders](remote.md), where the project itself lives on the host. Why it is built this way, and the
alternatives, are in [ADR 0015](../decisions/0015-deployments.md).

## Model

- `config/Deployment` — name, `host`/`port`/`user`/`auth`/`keyPath` (a saved site's shape, no secret),
  `remoteRoot` (absolute), `excludes` (lines), `useGitignore`, `deleteRemoved`, `uploadOnSave`. Kept in
  `WorkspaceState.deployments` (schema 22) beside the run configurations; `SharedRunConfigs` writes and reads
  them with Export/Import of configurations.
- `RunConfiguration.deployment` — "Run on": blank is this machine, else a deployment's name. Renaming a
  deployment in the Deployments window renames it in the configurations (`DeploymentsWindow.renamed`).

## The deploy (`deploy/`)

`Deployer.deploy` is blocking and never throws; `ui/DeploymentCoordinator` runs it on a virtual thread.

1. `DeployRoot.problem` refuses a folder that is relative, irregular, `/`, a system folder, the account's home
   or a folder above it (`TOO_WIDE`), before anything touches the host.
2. `LocalFiles.list` — `git ls-files -co --exclude-standard` when the project root is a Git repository and the
   deployment follows `.gitignore`, else a walk; then `DeployExcludes` (the built-ins — VCS and editor folders,
   `target/`, `build/`, `node_modules/`, virtual environments, compiled files — plus the deployment's lines;
   `!build/` takes a built-in out). Only regular files with names a listing can carry.
3. `HostScripts.listing` — one `sh` script on the host: whether the folder exists, whether `tar` and `gzip` are
   there, then `size seconds path` per file (GNU `find -printf`, else BSD `stat -f`, else `stat -c`), pruning
   plain folder names from the excludes.
4. `DeployPlan.of` — against `DeployManifest` (this machine's record of what the last deploy left there, under
   `<config>/deploy/<hash>.manifest`, keyed by project, host and folder): send what is missing or changed
   (hashing only when size or time moved), adopt identical files on a first deploy, flag files changed on the
   host since they were sent (**conflicts**), and remove files Editora sent that the project no longer has while
   the host's copy is unchanged. Nothing else is ever removed.
5. Decisions: conflicts → Overwrite / Keep the host's (they stop being Editora's files) / Cancel; a first deploy
   into a folder with files it would replace → confirm.
6. Send: `TarWriter` (ustar, pax `path` for long or non-ASCII names) gzip level 1 into `HostScripts.unpack`
   (`mkdir -p`, `tar -xof -`, in the host's locale so GNU tar can write UTF-8 names), files keeping the local
   modified second; per-file SFTP when the host has no `tar`. Then `HostScripts.remove` reads the files and
   emptied folders to remove from stdin. Then the new manifest is saved. A cancelled or failed deploy leaves the
   old manifest; the next listing adopts what did arrive.

## Connection and consent

`RemoteCoordinator.holdForDeployment` holds a connection in the window's `RemoteFileSystems` **without mounting
it** (the Project tree stays the local project) and asks the one-time "run commands on this host" question when
it was never answered. The run then reaches the host the usual way: paths under the deployment's folder are
SFTP paths on that connection, so `ExecTargets.forPath` gives the `SshExecTarget`. Settings → Remote's switch
for remote commands applies.

## Running on a deployment (`ui/RunCoordinator.runDeployedConfig`)

- Every edited file of the project is saved, then deployed (the Run console shows the progress; Stop cancels it
  after the file being sent).
- Fields that name a file inside the project by an absolute local path are made relative (`DeployPaths.hostField`);
  the configuration's own JDK is dropped (it names a folder here); then the launch resolves against the
  deployment's folder (`launchRoot`), so scripts, Python, make and npm, and the before-launch step, run there
  exactly as in a remote project, with the host's tools (`jdk.home`, `python`, …).
- Java builds on the host: Maven through `MavenClasspathResolver` (as for a remote project), Gradle through
  `build/GradleHostClasspath` (an init script with an `editoraClasspath` task, `sh ./gradlew` when the build
  has a wrapper). Projects with neither are refused with a message.
- Debug (Java): the JVM gets `-agentlib:jdwp=…,suspend=y,address=127.0.0.1:0`; its "Listening" line is
  tunnelled with `forwardLocal` and `DebugCoordinator.attachToPort` attaches through an open Java file of the
  local project (needed as the anchor). The tunnel closes when the program ends.
- The console's first line is the deploy's summary; its file links resolve in the local project
  (`consoleDir` is the local folder). Rerun deploys again.

## Commands and UI

- `deploy.now`, `deploy.compare` (the active file against its copy, in the diff viewer), `deploy.terminal`,
  `deploy.edit` — Run → Deployment. A command picks the selected configuration's deployment, the only one, or
  asks.
- `ui/DeploymentsWindow` — list + form, every field saved as it is left; "From saved site" fills the
  connection; the folder is checked as it is typed.
- Run Configurations → **Run on** (and **Deployments…**).
- Upload on save (`DeploymentCoordinator.fileSaved`, debounced): a saved project file deploys every deployment
  that asks for it; one that fails is not tried again on every save until a deploy succeeds.

## Tests

Pure: `DeployExcludesTest`, `DeployPlanTest`, `DeployManifestTest`, `DeployRootTest`, `DeployPathsTest`,
`TarWriterTest`, `GradleHostClasspathTest`, `DeploymentTest`. `HostScriptsTest` runs the scripts with this
machine's `sh`, `find` and `tar`. `DeploymentFxTest` deploys to the embedded SSH server (a real shell) and runs a
configuration there; `RealSshdProbeFxTest` has a deploy step for real OpenSSH, macOS sshd and dropbear.
