---
title: "PowerShell"
description: "PowerShell code intelligence and debugging with PowerShell Editor Services."
category: Languages
order: 15
---

PowerShell gets code intelligence and debugging from PowerShell Editor Services
(PSES), the engine of VS Code's PowerShell extension, which runs on `pwsh`
(PowerShell 7). Editora doesn't install either; see
[Installing PSES](#installing-pses).

## Code intelligence

PSES is PowerShell's [language server](/docs/lsp): diagnostics from
PSScriptAnalyzer (an alias in a script, an unused variable), completion,
hover, go to definition, references, rename, formatting, document symbols and
folding. A blank **PowerShell server command** under **Settings → LSP** starts
PSES from where the debugger finds it, on `pwsh`; set a command of your own to
start it another way.

## Running

A `.ps1` script has a ▶ on its first line. **Run File** (`file.run`, `C-c r`
in the Emacs keymap) runs it with `pwsh -NoLogo -NoProfile -File`, in its
folder, with the arguments set by **File: Run with Arguments…**. Without `pwsh`, Windows PowerShell's
`powershell` runs it. A module (`.psm1`) or data file (`.psd1`) has no ▶.

## Debugging

**Debug: Start / Continue** (`debug.start`: `C-c C-d d` in the Emacs keymap,
F5 in the VS Code, CUA and Sublime Text keymaps) on a `.ps1` debugs it on PSES,
in its folder, with its arguments:

- **Breakpoints**, with conditions, hit counts and log messages, and
  **function breakpoints** by a command's name.
- **Stepping**, the **call stack**, and **Variables** in PowerShell's scopes:
  Auto, Local, Script and Global. **Set Value** changes a variable.
- The **Debug console** evaluates an expression in the stopped frame.
- **The script's output** comes to the Debug console as it runs: its output,
  `Write-Host`, warnings (`WARNING:`), verbose and debug messages, and errors,
  formatted as PowerShell's own console formats them.

A **launch.json** configuration of type `PowerShell` with `request: launch`
runs its `script`, a file or a command, with its `args` and `cwd`, the same
way. Attaching to a running PowerShell isn't supported.

PSES has no console of its own in a debug session (its console needs a real
terminal), so the script runs wrapped: its streams are merged and written to
PSES's standard error, which the Debug console shows. A script that reads
input with `Read-Host` gets none.

## Installing PSES

Install [PowerShell](https://learn.microsoft.com/powershell/scripting/install/installing-powershell),
and either VS Code's PowerShell extension or PSES's release zip from
[github.com/PowerShell/PowerShellEditorServices](https://github.com/PowerShell/PowerShellEditorServices/releases).
Editora finds PSES's `Start-EditorServices.ps1`:

- at the path set under **Settings → Debugging → PowerShell**: the script, or
  the folder you unpacked the release into;
- else in Editora's own folder, `plugins/dap/powershell` under its settings
  folder (`~/.editora`): unpack the release there and nothing needs setting;
- else in VS Code's PowerShell extension (`ms-vscode.powershell`), the newest.

The **PowerShell** card of **Settings → Debugging** says whether it was found,
and **Doctor** has a row for it.
