# 0016 — Deployments: a local project copied to a host and run there, chosen per run configuration

**Status:** Accepted.

## Context

[0013](0013-remote-execution-over-ssh-exec.md) made a folder *on* a host a project: its files are read over
SFTP and every tool runs there. The other common way to work is the opposite: the sources stay on this
machine (its jdtls, its Git, its speed) and only the program runs elsewhere — on a server with the database,
the GPU, the right OS or architecture.

IntelliJ has two features for this that overlap and confuse: **Deployment** (Tools → Deployment: SFTP/FTP
servers, mappings, excluded paths, upload on save) knows nothing about running, and **Run Targets** ("Run on:
SSH / Docker / WSL" on a run configuration) has its own remote path and its own rules. Users report slow
uploads (rsync on every run, the JDK re-sent), deletes that wiped files, and not knowing which feature applies.
VS Code has nothing built in: an SFTP extension (`uploadOnSave`, plain-text passwords in `sftp.json`) plus a
`preLaunchTask` running rsync and an attach configuration with path mappings.

## Decision

- **One mechanism, on the run configuration.** A project has *deployments* (host, user, login, an absolute
  folder there, exclude patterns, options); a run configuration's **Run on** names one or is blank (this
  machine). Deploy Now, Compare with Deployed and Open Terminal in Deployment act on the same deployments. There
  is no separate mapping or exclusion setting to keep in step.
- **Stored with the project's run configurations** (`WorkspaceState.deployments`, private to this machine) and
  carried by Export/Import of run configurations. A deployment names its host as a saved site does and holds no
  secret; logins ask as they run, as for remote folders.
- **The connection is the window's own MINA session, held without mounting.** No system `ssh`, no `rsync`, no
  agent on the host (as 0013). The host must be allowed to run commands — the same per-host agreement, asked
  when a deploy first needs it.
- **Transfer: a manifest plus one tar stream.** The host lists the folder (one `find`, pruning what it builds
  itself); a local manifest of the last deploy says what Editora put there. Only files whose contents changed
  are sent — as one gzip-compressed POSIX tar stream into the host's `tar -x` over an exec channel (SFTP file by
  file when the host has no `tar`). Files keep the local modified time, so the next listing tells the host's
  copy is still the one sent.
- **Removal is limited to Editora's own files.** A file Editora sent and the project no longer has is removed,
  when the host's copy is unchanged and the deployment allows it. Nothing else on the host is ever removed; a
  folder that is `/`, a system folder or the account's home is refused; a first deploy into a folder that already
  has files asks before replacing any; a file changed on the host since it was sent is asked about.
- **What runs: the project's sources, built on the host.** After the deploy the configuration runs as it would
  in a remote project: its before-launch step, interpreter, JDK and environment are the host's. A Java
  configuration builds with the host's Maven or Gradle (an init script prints Gradle's runtime classpath);
  Debug starts the JVM under JDWP on the host's loopback, tunnels that port here and attaches through the local
  project, whose sources are the same files.

Alternatives considered: build locally and upload classes and the dependency jars (IntelliJ's choice for a plain
Java target) — no build tool needed on the host, but every dependency jar travels, and JavaFX's native jars must
match the host's OS; system `rsync` — fast, but it needs `ssh` and `rsync` on this machine (absent on Windows)
and breaks 0013's single-session rule; a full mirror (`--delete`) — simplest to explain, and the cause of the
data loss users report.

## Consequences

- A deploy costs one listing round trip even when nothing changed (no 30-second rsync), and a branch switch that
  only touched times sends nothing (contents are hashed only when size or time moved).
- The host needs `sh`, `find` and `tar` (and `gzip` for compression); BusyBox, GNU and BSD userlands all work.
  Windows hosts are not supported, as in 0013.
- Hosts without Maven or Gradle cannot run a Java configuration; a shell configuration that runs a jar is the
  workaround.
- Debugging a deployed program is Java only (script configurations do not debug locally either).
