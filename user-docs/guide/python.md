---
title: "Python"
description: "Pyright and the Python interpreter, sub-projects and formatting, running scripts, and debugging Python with debugpy."
category: Languages
order: 16
---

Python gets code intelligence from Pyright, runs a script from its `__main__` guard, and debugs with debugpy. What every language shares is on the [language servers](/docs/lsp), [running](/docs/run-debug) and [debugging](/docs/debugging) pages; this page is what is particular to Python.

## Language server

Python files use **Pyright** (`pyright-langserver --stdio`). If language
servers are off (Settings → LSP), the first Python file a window opens asks
**Turn on Python code intelligence?** with **Turn on** and **Not now**. The offer comes
once per window and follows the same switch as the install banners
(`view.toggleInstallPrompts`). It is not shown while **Enable Python (Pyright)**
is off in Settings → LSP.

### The Python interpreter

Pyright analyses your code with a Python interpreter, and it can only resolve
the packages installed in that interpreter. Editora picks one interpreter per
file and uses it for Run, Debug, the tests, Pyright and Format Document alike.
The first match wins:

1. the **Interpreter** field of a [run configuration](/docs/run-debug), for that
   configuration;
2. the interpreter picked for this project (see below);
3. the environment Editora was started from (`$VIRTUAL_ENV`, then
   `$CONDA_PREFIX`), so activating a virtual environment before starting
   Editora works;
4. a virtual environment in the project: a `.venv`, `venv`, `env` or `.env`
   folder with a `pyvenv.cfg`, looked for from the file's folder up to the
   project root. Pyright and the status-bar item look from the folder Pyright
   is rooted at, so a [Python sub-project](#python-sub-projects) finds its own;
5. **Settings → LSP → Python → Python interpreter (fallback for Run, Debug and
   code intelligence)**, which may be a path or a bare name such as python3.12
   that is looked up on `PATH`;
6. the system Python on `PATH` (`python3`, then `python`; on Windows `py -3`,
   then `python`, never the Microsoft Store stub).

A status-bar item on Python files shows the result and why, for example
`Python 3.13.5 (.venv)`, `Python 3.12.4 (PATH)` or `Python 3.13.5 (picked)`.
It is the interpreter Pyright analyses with. Hover it for the full command.
Click it, press Enter on it after reaching it with Tab, or run **Python: Select
Interpreter** (`python.selectInterpreter`), to choose another one: **Detect
automatically** (the order above), the one in use, the project's environments,
every Python on `PATH`, or **Browse…** for any file. An environment's row names
its folder, for example `Python 3.13.5 — .venv` or
`Python 3.13.5 — services/api/venv`, with a long path shortened in the middle, and typing
filters the rows by path as well as by name. An interpreter found twice on
`PATH` is listed once. **Browse…** checks that the file answers `--version` as
Python does and changes nothing when it does not. The pick keeps the whole
command, arguments included. It is kept per project; inside the project folder
it is stored as a relative path. In a window without a project it applies to
every loose Python file in that window.

The **Python** card on Settings → LSP holds the fallback interpreter, a
**Python: Select Interpreter** row whose **Choose…** button opens the same
picker for this window's project, and **Format on save (Ruff/Black)**. The card
can be edited with language servers and debugging off.

Pyright is told the interpreter, so the packages in a project's `.venv` resolve
with no `pyrightconfig.json`. A changed pick reaches the running server at once.
So does a virtual environment created while the project is open, as soon as its
`pyvenv.cfg` is written, and a package installed into it with `pip install`:
its imports resolve within seconds, with no restart.
A `pyrightconfig.json` or a `[tool.pyright]` section with `venvPath` and `venv`
still wins inside Pyright, and settings such as `pythonVersion` or
`typeCheckingMode` belong there too.
[Doctor](/docs/doctor) shows the same interpreter in its
**Python interpreter** row, whose **Settings…** opens the Python card.

### Python sub-projects

The Python server's root is the nearest folder with a `pyproject.toml`,
`setup.py`, `setup.cfg` or `pyrightconfig.json`, else the repository (`.git`),
else the nearest `requirements.txt` or `Pipfile`. A `docs/requirements.txt`
therefore does not split the documentation off from the package it documents.

In a project window the project root is the root, except for a Python
sub-project: a folder inside the project with its own `pyproject.toml`,
`setup.py`, `setup.cfg` or `pyrightconfig.json`, such as a `backend/` beside a
`frontend/`. It gets a Pyright of its own, with its own `.venv`, its own `src/`
and its own `[tool.pyright]` settings, and the status-bar item on its files
names that `.venv`. A member of a uv workspace (under a `pyproject.toml` with
`[tool.uv.workspace]`) shares the workspace's server and its one `.venv`.
Without a project, a uv workspace member still finds the workspace's `.venv`.
Remote projects keep one server at the project root.

### What Pyright adds

- **Unused names are faded** (an import or a variable nothing reads) and
  **calls to a deprecated function are struck through**. These marks are drawn
  in the editor but not listed in the Problems window or counted by the problem
  counter, where they would bury the real errors.
- **Organize Imports** (`lsp.organizeImports`) sorts and groups the imports
  with Pyright's own command. It leaves unused ones in place; they are the
  faded ones.
- **Find References** from a call lists that call with the others, and on a
  function's only call it stays there rather than jumping to the definition.
- **Go to Definition** into the standard library, site-packages (the project's
  own `.venv` included) or Pyright's bundled stubs opens that file in View
  mode, on the project's server, and without the library's own type errors.
  The status bar says **Read-Only**, and **Enable Editing** in the View mode
  bar makes it editable.
- An **untitled** buffer set to Python is analysed too. In a project window it
  sees the project's modules. Once you save it, its file takes over.
- A Python file keeps its server up to **50,000 lines**, even when the
  **Turn the language server off above** limit (50,000 lines by default) is set
  lower. Pyright can take several seconds to answer on a
  file that large; completion says **Waiting for Pyright…** meanwhile.

Pyright gives no inlay hints, semantic highlighting or code lenses. Turning one
of them on in a Python file says so in the status bar. basedpyright, set as the
Python server below, gives inlay hints and semantic highlighting.

To use another server, set the Python command in **Settings → LSP**, for example
`basedpyright-langserver --stdio`. If that command is not found, the banner
names it and offers **Settings…**, rather than installing Pyright.

### Python quick fixes

Pyright answers a request for code actions with nothing, so Editora adds its
own to **Code Actions** and the lightbulb on a Python problem:

- **Import Path from pathlib** (or **Import os**) on a name that is not
  defined. Pyright's own import suggestions come first; when it has none, a
  built-in list of common standard-library names and modules supplies the
  import. The new line goes after the file's imports, or into its existing
  one-line `from pathlib import …`. A module of your project that nothing in
  the file reaches yet is not offered.
- **Remove unused import sys** on a faded import, one name at a time.
- **Ignore reportAssignmentType on this line**, listed under **Suppress**, on
  an error, warning or information that names its Pyright rule. It adds
  `# pyright: ignore[reportAssignmentType]` at the end of the line, or adds the
  rule to the line's existing ignore comment. It is not offered on a line that
  ends inside a string or with a backslash.

### Formatting Python

Pyright does not format. **LSP: Format Document** (`lsp.formatDocument`) on a
Python file runs **Ruff** (`ruff format`) or, failing that, **Black**, giving
them the editor's text with the file's name so they read the project's
`pyproject.toml`. Each is looked for first in the interpreter's environment
(the project's `.venv`), then on `PATH`, in this order: the environment's Ruff,
the environment's Black, Ruff on `PATH`, Black on `PATH`. The result is one
undoable edit, and the status bar names the tool, for example
`Formatted with ruff`. It works with language servers off too.

If neither is installed, the status bar says so; install one into the project's
environment (`pip install ruff`). A Python server that formats by itself, such
as `ruff server`, keeps the job. Doctor's **Python formatter** row shows which
tool Format Document will use. A missing formatter is optional there, and a
warning only while Format on save is on.

The text is left as it was, and the status bar says why, when:

- the formatter prints nothing for a file that is not blank, which usually means
  the file matches its `force-exclude` setting;
- the result is larger than 10 MB;
- the buffer is narrowed (*Widen the buffer to format it*) or read-only;
- the file is on another machine (*Ruff and Black cannot format a file on
  another machine yet*). A Python server that formats by itself still formats
  remote files.

A formatter that writes CRLF line endings adds no blank lines, and the file is
saved with its own line endings. A format that finishes after you switch tabs
still lands in its own file.

**Format on save (Ruff/Black)** on the Python card of Settings → LSP formats a
Python file with Ruff or Black after you save it, then saves it again. It is
off by default, runs after an explicit save only (never after auto-save), and
uses Ruff or Black even when a Python server that formats is running. With no
formatter found, a save says so in the status bar.

## Running

<a id="running-scripts"></a>Run, Python [run configurations](/docs/run-debug#run-configurations), the
[test runner](/docs/build-tools#test-runner) and the debugger all use the same
Python. The status-bar item on a Python file shows which one and why, for
example `Python 3.13.5 (.venv)`; click it, or use **Python: Select
Interpreter** (`python.selectInterpreter`), to pick another for the project
(see [The Python interpreter](#the-python-interpreter)). The first of
these wins:

1. a run configuration's **Interpreter**;
2. the interpreter picked for the project;
3. an environment that was active when Editora started (`$VIRTUAL_ENV`, then
   `$CONDA_PREFIX`);
4. a `.venv`, `venv`, `env` or `.env` folder holding a `pyvenv.cfg`, looked for
   from the script's folder up to the project root;
5. the **Python interpreter** on the Python card of **Settings → LSP**;
6. `python3`, then `python`, on your `PATH`. On Windows: the `py` launcher
   (`py -3`), then `python`, never the Microsoft Store shortcut.

A **shebang** that asks for a particular Python is followed, as `./script`
would follow it. One that names plain `python` or `python3` asks for no
particular Python, so the project's runs the script, with the shebang's options:

| First line | Runs |
| --- | --- |
| `#!/usr/bin/env python3` | the project's Python |
| `#!/usr/bin/python3 -O` | the project's Python with `-O` |
| `#!/usr/bin/env python3.12` | `python3.12` |
| `#!/usr/bin/env -S uv run --script` | `uv run --script script` |

`pypy`, `pipx`, `poetry` and `pdm` shebangs are followed the same way. When the
interpreter a shebang asks for is not installed, the project's Python runs the
script, and the console and the status line say so. A configuration's
**Interpreter** wins over any shebang.

The script runs in **its own folder**. In a project, the project root, and its
`src/` folder when there is one, go in front of `PYTHONPATH` (anything you set
there stays after them), so `from mypackage import x` works in a file deep
inside the package. With no project open, `PYTHONPATH` is left as it is.
Output appears as the program prints it, not in one block at the end
(`PYTHONUNBUFFERED=1`), and in UTF-8 on every platform (`PYTHONIOENCODING`), so
`print("✓")` works on Windows too. A configuration's **Environment** can set
either variable differently.

A `.pyi` stub gets no ▶, and neither do `__init__.py` and `conftest.py` unless
they have a `__main__` guard. A guard inside a string or docstring does not
count. A test file (`test_*.py`, `*_test.py`) gets a ▶ for each test instead,
and Run on it runs its tests: see [Test runner](/docs/build-tools#test-runner).

## Web apps

Run File and Debug File on the file that starts a Django, Flask or FastAPI app
serve the app, under the Python that [Run](#running) uses:

| File | Runs |
| --- | --- |
| a `manage.py` that mentions Django | `manage.py runserver` |
| a module that makes `app = Flask(…)` | `python -m flask --app module:app run --reload` |
| a module that makes `app = FastAPI(…)` | `python -m uvicorn module:app --reload` |

The app's variable can have any name, and a module inside packages (folders
with an `__init__.py`) is named with its package, such as `shop.web:app`, and
served from the folder above the top package, so its imports work. An app made
only inside a factory function, such as `create_app()`, isn't recognised, and
neither is a file with an `if __name__ == "__main__":` guard, which starts its
own server: those run as scripts. The arguments set by **File: Run with
Arguments…** follow the command; on `manage.py` they replace `runserver`, so
`migrate` runs a migration. The framework's own default port is used, and no
browser opens. Stop ends the server and its reloader.

The debugger follows the reloader: the server's reloaded process is debugged
in a session of its own (see the subprocesses below), so breakpoints keep
stopping after each code change reloads it. On a remote host, in WSL or in a
container, the server is debugged without its reloader instead (`--noreload`,
`--no-reload`). Breakpoints in Django and Jinja templates are described under
[Debugging](#debugging).

**Run: Add Run Configuration** (`run.addConfig`) on such a file suggests a configuration named after
the framework, such as `app.py (Flask)`.

## Tests

Python tests run with pytest, or with `unittest` when pytest isn't installed,
with a ▶ on each test, Run Test at Caret, and their results in Test Results;
see [Python tests](/docs/build-tools#python-tests). Debugging a test is
described at the end of [Debugging](#debugging).

## Debugging

Debug a Python file with **Debug: Start / Continue** (`debug.start`:
`C-c C-d d` in the Emacs keymap, F5 in the VS Code, CUA and Sublime Text
keymaps, Shift+F9 (⌃D on macOS) in the IntelliJ IDEA keymap), or with
**Debug File** in the editor's right-click menu, next to **Run File**. The
program runs the way [Run](#running) runs it: under the same
interpreter (the project's virtual environment, the one picked for the project,
or the shebang's), in the script's own folder, with the project root and its
`src/` folder at the front of `PYTHONPATH`, and with the arguments set by
**File: Run with Arguments…** (`file.runWithArgs`). The Debug console's first
line shows the command that runs. When the program ends, the console shows its
exit code.

A script whose shebang hands it to a runner (`uv run --script`, `pipx run`,
`poetry run python`, `pdm run python`) is debugged under the project's Python:
the debugger needs an interpreter, not a runner.

The debugger itself (debugpy) runs under the same interpreter when that
interpreter can import debugpy, else under the **Python interpreter** set on
the Python card of Settings → LSP.

A process the program starts, with `multiprocessing` or as a Python
subprocess, is debugged too, in a session of its own beside the program's (see
[Several sessions](/docs/debugging#several-sessions)). Its tab is named as debugpy names it,
such as *Subprocess 1234*. Its breakpoints stop, and the Debug window shows it
when it stops. Stopping the program stops its subprocesses, and a subprocess's
tab goes once it ends, unless it is the one shown. Restart isn't available in a
subprocess's session: restart the program that started it. To run
subprocesses without the debugger, turn off **Debug Python subprocesses** on
the Python card of **Settings → Debugging**. A program debugged on a
[remote host](/docs/debugging#debugging-on-a-remote-host) runs its subprocesses without the
debugger.

A breakpoint on a line of a **Django or Jinja template** stops on that line.
The call stack's top frame is the template, and Variables shows the template's
context, such as a loop's item and `forloop`. Editora turns this on for a
project that uses Django (a `manage.py`, or Django in its requirements) or
Jinja (Jinja2, Flask or Quart in its requirements). **Template breakpoints**
on the Python card of **Settings → Debugging** sets it for every project:
**Automatic** (the default), **Django and Jinja**, or **Off**. A project in a
remote folder isn't detected, so pick Django and Jinja there.

A pandas DataFrame or Series, or a numpy array, opens as a table of its rows
with [View as DataFrame or View as Array](/docs/debugging#dataframes-and-numpy-arrays).

A pytest test is debugged from its test file:

- **Debug Test at Caret** (`test.debugAtCaret`) debugs the test at the caret
  (the whole file outside any test).
- The editor's right-click menu on a test has **Debug This Test** (or **Debug
  This Class**).
- In [Test Results](/docs/build-tools#test-runner), a Python test's
  right-click menu has **Debug This Test** (a class row: **Debug This Class**).

The test runs as `python -m pytest` with its node id, from the project root (the
file's folder when there is no project), under the debugger. When the
interpreter has no pytest, a `unittest.TestCase` test runs under
`python -m unittest` instead; for any other test the status bar names the
interpreter that lacks pytest. A Python
[run configuration](/docs/run-debug#run-configurations) runs but cannot be debugged; debug its
file instead.
