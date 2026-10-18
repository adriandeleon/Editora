# 0013 — Remote development: tools run on the host over SSH exec channels

**Status:** Accepted. The terminal clause is superseded by [0014](0014-integrated-terminal.md), which adds a
PTY on interactive terminal channels only; the no-PTY rule for tool commands stands.

## Context

Editora could open a folder on another machine over SFTP, but nothing ran there: Git, builds, tests, search,
language servers and debuggers all assumed the files were on this machine. Users who keep projects on a
server wanted those tools to work as they do locally.

The common approaches are a helper program installed on the server that the editor talks to (VS Code Remote),
the system `ssh` client run once per command, or keeping a local mirror of the files and running tools here.

## Decision

- The tools Editora already drives run **on the host**, each on its own exec channel of the SSH session the
  SFTP connection already holds (Apache MINA SSHD). One login, one host-key check, one connection to lose.
  Nothing is installed on the host; Editora writes only to `~/.cache/editora` there (jdtls's index, the
  java-debug jar), and says so when it asks.
- Behind one seam: `process/ExecTarget`, chosen by `ExecTargets.forPath` from the file or folder a feature
  works on. Features keep their `Process` types; a remote process is a `RemoteProcess` without a local pid.
  Only SSH to POSIX hosts is implemented; WSL or containers would be other targets.
- Commands are one `/bin/sh -c` line with the login shell's `PATH` and locale, no PTY (it would corrupt
  LSP/DAP framing). Long-lived ones start a watchdog that kills their process group when the connection's
  server process dies — an SSH server signals nobody when a connection drops.
- Paths cross as strings: the SFTP path is the host's path. Language servers and debug adapters are told the
  host's `file:` names; Editora keeps its own `sftp://` keys (`WireUris`, `DapPaths`).
- Three gates: the global `remoteExecution` setting (on by default), a one-time agreement per host (kept with
  the saved connections, withdrawable in Settings → Remote), and an open connection. Tool commands are set per
  host; settings that name files on this machine are never sent to a host.
- Only the tools Editora already drives. No terminal, no agent CLIs, no "run a command" box.

## Consequences

- Every feature that shells out had to stop assuming a local path: ~70 gates moved from `Vfs.isLocal` to
  `RemoteExec.runsFor`, and path comparisons across file systems had to become `PathKeys.samePath` (a
  `Path.equals` between a local and a remote path throws).
- Each command is a round trip; latency-bound features (Git refresh, the outside-change watch) are debounced
  and cached per host, and a connection is budgeted against the server's `MaxSessions`.
- Not covered: Windows hosts, non-POSIX login shells (fish/csh), SSH agent forwarding, running a Java
  project's main class or debugging a loose Java file on a host.
- *Later (before 0.22.0):* the last two were added. A Java project's main class runs on the host with the
  class path its jdtls or Maven gives (`RunCoordinator`), and a Java file outside a project is compiled on the
  host for the debugger (`DapManager.compileOnHost`). See [docs/subsystems/remote.md](../subsystems/remote.md).
