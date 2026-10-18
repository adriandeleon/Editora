# 0014 — Integrated terminal: Termina's emulator, a PTY only for terminal channels

**Status:** Accepted. Local, SSH, container and WSL terminals are in; the follow-ups are listed in docs/subsystems/terminal.md

## Context

Editora opened a terminal only by launching the operating system's (`DesktopActions`, "Open Terminal Here"),
which is no use in a remote project: the external terminal opens on this machine, not on the host, the
container or the WSL distribution the project lives in. [0013](0013-remote-execution-over-ssh-exec.md) left a
terminal out on purpose ("No terminal, no agent CLIs, no 'run a command' box") and ruled out a PTY, because a
PTY on a tool's channel corrupts LSP/DAP framing.

Termina (github.com/adriandeleon/Termina, same author, MIT) is a JavaFX terminal with the split Editora needs:
JetBrains' JediTerm core emulates VT/xterm into a screen buffer and knows nothing about drawing, pty4j opens the
pseudo-terminal on every OS, and Termina's own code is the canvas renderer, key and mouse encoding, selection,
links and themes. Only JediTerm's `TtyConnector` knows where the bytes come from, so a remote shell is one more
connector, not another terminal.

Alternatives considered: writing an emulator (the xterm long tail shows up as programs misbehaving, not as
bugs that look like the terminal's); a shared `termina-core` library both apps consume (Termina is at 0.7 and
still changing, and Editora's needs will reshape the view first); JDK FFM instead of pty4j (removes JNA and
Kotlin but means maintaining forkpty and ConPTY bindings ourselves).

## Decision

- **Port Termina's terminal core into `com.editora.terminal`** (copy, with a provenance line naming the Termina
  commit), keeping it free of Editora imports apart from small interfaces, so extracting a shared library stays
  possible later. Fixes found in Editora are back-ported to Termina by hand.
- **Dependencies:** `jediterm-core` (vendored in `m2-repo/`: JetBrains publishes it only to its own repository),
  `pty4j`, JNA and the Kotlin standard library. Automatic modules get moditect descriptors that name their
  `requires`; `editora --check-terminal` proves the stack inside the linked image on every OS.
- **A PTY only on interactive terminal channels.** `ExecTarget` gains a terminal verb with its own spec;
  `ExecSpec`, which every tool command uses, never asks for a PTY. 0013's rule for tools is unchanged.
- **Targets:** local shells on pty4j; SSH shells on an exec channel of the window's existing MINA session with a
  `pty-req`; containers (`docker|podman exec -it`) and WSL (`wsl.exe`) inside a local pty4j PTY. The same
  `remoteExecution` setting and per-host agreement gate them: a terminal on a host is running commands there.
- **Channels:** an SSH terminal takes one of the long-lived slots of the connection's budget, with a refusal that
  names terminals; a second SSH session for terminals is a later decision, after real use.
- **A dropped connection ends a remote terminal the way plain `ssh` does:** the server hangs up the PTY session,
  the shell and its jobs get SIGHUP, and what the user put under `nohup` or tmux survives. Tools keep their
  watchdog.
- "Open in Terminal" opens the integrated terminal by default; the external launcher stays as a setting.

## Consequences

- About 8 MB more in every image (pty4j's natives for all platforms are most of it; a per-OS strip is possible).
- Native code in the jlink image: pty4j's libraries load only because `resources/com/pty4j/native/…` is not a
  valid package name, so JPMS leaves it readable. Nothing compiles that fact, so a workflow runs the image.
- On Linux pty4j sets the window size just after the child starts, so a program that asks at once can read
  0×0. An interactive shell gets the real size with the first resize; a program started directly in a tab
  needs the same allowance.
- The native-image (static FX) build does not cover the terminal; JNA there is separate work.
- 0013's "no terminal" line no longer holds; its PTY rule now applies to tool commands only.
