# Architecture decision records

Short records of the non-obvious choices in Editora — the ones where the "why" isn't visible in
the code and a future contributor (or your future self) would otherwise re-litigate them.

Format is lightweight: **Status / Context / Decision / Consequences**. A record is a snapshot of
the reasoning at the time; if a decision is later reversed, add a new record that supersedes it
rather than rewriting history.

| # | Decision | Status |
| --- | --- | --- |
| [0001](0001-richtextfx-fork.md) | Fork RichTextFX for multiple cursors, vendor it in `m2-repo/` | Accepted |
| [0002](0002-native-cli-git.md) | Native-CLI git (shell out), not JGit | Accepted |
| [0003](0003-toml-settings-json-session.md) | TOML for settings, JSON for session + stores | Accepted |
| [0004](0004-textmate-grammars.md) | TextMate grammars via tm4e, not a hand-written highlighter | Accepted |
| [0005](0005-in-scene-overlays.md) | In-scene overlays (`OverlayHost`), not `javafx.stage.Popup` | Accepted |
| [0006](0006-headless-awt-guard.md) | Force `java.awt.headless=true` before anything else | Accepted |
| [0007](0007-multi-window-shared-config.md) | One window per project + a `SharedConfig`/`ConfigManager` split | Accepted |
| [0008](0008-feature-coordinators.md) | Decompose `MainController` into feature coordinators | Accepted (ongoing) |
| [0009](0009-plugins-no-modulelayer.md) | Plugins via a child `URLClassLoader`, no `ModuleLayer` | Accepted |
| [0010](0010-builtin-headless-test-platform.md) | JavaFX 26 built-in Headless test platform, drop self-built Monocle | Accepted |

## Writing a new one

Copy the format of an existing record, take the next number, and add a row above. Keep it to the
reasoning — the *what* belongs in the code and the other [docs](../README.md); the ADR captures
the *why* and the alternatives considered.
