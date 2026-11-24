---
title: "C#"
description: "Debugging C# with netcoredbg, and C# tests at the caret."
category: Languages
order: 14
---

C# gets code intelligence from `csharp-ls` (see [language servers](/docs/lsp#servers-are-auto-detected-never-bundled)) and debugs with netcoredbg.

## Debugging

C# is debugged with [netcoredbg](https://github.com/Samsung/netcoredbg), the
open-source .NET debugger. **Debug: Start / Continue** (`debug.start`, F5) or
**Debug File** in the editor's right-click menu builds the program with
`dotnet build` in the Debug console, after Editora saves the project's edited
C# files, then debugs it:

- **In a project**, the nearest `.csproj` in the file's folder or above it (of
  several, the one named after the folder) is built. MSBuild runs the
  project's own build steps, so the first build asks you to
  [trust the folder](/docs/workspace#trusted-folders).
- **A lone `.cs` file** is built and debugged as a .NET 10 file-based app.
  The SDK's build of it reads the `Directory.Build.props` and
  `Directory.Build.targets` of the folders above it, which are code too, so it
  asks for the same trust first.

Declining the trust question builds and runs nothing. The program runs on `dotnet` in the project's folder, with the arguments set by
**File: Run with Arguments…**; its output reaches the Debug console. An
exception your code does not handle stops it, as in Visual Studio: the **C#:
user-unhandled exceptions** [exception breakpoint](/docs/debugging#exception-breakpoints) is on
by default, and **C#: all exceptions** stops on every throw.

netcoredbg doesn't log [logpoints](/docs/debugging#logpoints) itself, so Editora logs them:
the program pauses briefly at each hit while the message is written in the
Debug console.

**Debug Test at Caret** (`test.debugAtCaret`) in a test project (one that uses
`Microsoft.NET.Test.Sdk`, xUnit, NUnit or MSTest) runs the test the caret is
in, or every test of its class when the caret is in the class but in no test,
under `dotnet test` in the Debug console. The test host waits for a debugger,
netcoredbg attaches to it, and the test runs under the debugger, so its
breakpoints stop with the test's values. A test is a method marked `[Fact]`,
`[Theory]`, `[Test]`, `[TestCase]`, `[TestMethod]` or `[DataTestMethod]`, also
in a nested test class. The project is built as above, after the same trust
question. **Stop** ends the `dotnet test` run, and so does closing the
session's tab or the window, or an attach that fails; **Restart** runs the same
test again.

Attaching to a running .NET program isn't offered; a
[launch.json](/docs/debugging#launchjson-configurations) `coreclr` configuration can attach
by process id.

netcoredbg isn't bundled. Editora finds it as set on the **C# (netcoredbg)**
card of **Settings → Debugging**, else in its own install, else in the free C#
extension for VS Code-compatible editors (`muhammad-sammy.csharp`), else on the
PATH. Microsoft's own C# extension ships a debugger licensed only for
Microsoft's tools, so Editora doesn't use it. The card's **Install…** downloads
netcoredbg 3.2.0-1092 and checks its SHA-256 checksum; Samsung builds none for
Intel Macs. The .NET SDK isn't installed by Editora; it is found on the PATH
and in `~/.dotnet`, where Microsoft's install script puts it.
