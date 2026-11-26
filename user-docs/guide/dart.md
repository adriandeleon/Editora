---
title: "Dart"
description: "Dart and Flutter: code intelligence from Dart's analysis server, Run File, tests, and debugging on the SDK's own debugger."
category: Languages
order: 4
---

Editora knows Dart: syntax colouring, comments, indenting and folding, the
Structure panel and Go to Symbol, snippets, a New File template and its file
icon. The rest comes from the Dart SDK, or the one inside Flutter: install
either and put `dart` (and `flutter`, for Flutter apps) on your `PATH`.

A file belongs to the **package** in the nearest folder above it with a
`pubspec.yaml`. A package whose `pubspec.yaml` depends on Flutter's SDK is a
**Flutter app**; one that only names `flutter_test` among its dev dependencies
is not.

## Language server

<a id="code-intelligence"></a>Dart's own analysis server (`dart language-server`) is Dart's
[language server](/docs/lsp): errors and warnings, completion, navigation,
rename and formatting. Its card is **Dart** on **Settings → LSP**;
a blank command runs the `dart` on your `PATH`.

An `analysis_options.yaml` can name analyzer plugins, which the server runs, so
in a folder you have not [trusted](/docs/workspace#trusted-folders) the server
waits while the folder has one
(see [Servers that load a project's build](/docs/lsp#servers-that-load-a-projects-build)).

## Running

A Dart file with a top-level `main` has a ▶ on that line. **Run File**
(`file.run`: `C-c r` in the Emacs keymap, Ctrl+Shift+B (⌘⇧B on macOS) in VS Code) runs it:

- a plain Dart program with `dart run`, in its package's folder (or the file's
  own folder outside a package);
- a file of a Flutter app with `flutter run -t`, in the app's folder, on this
  computer's desktop (`linux`, `macos` or `windows`), or on the device named
  in **Flutter device** on the Dart card of **Settings → Debugging**: a device
  id as `flutter devices` lists it, such as `chrome` or an emulator.

The arguments set by **File: Run with Arguments…** go to the program. Dart run
configurations run and debug the same way.

## Tests

A test file (`_test.dart`) runs under `dart test`, or `flutter test` in a
Flutter package, and its results go to [Test Results](/docs/build-tools#test-runner).
**Run Test at Caret** (`test.runAtCaret`) runs only the `test`, `testWidgets`
or `group` the caret is in (`--plain-name`); outside them, every test of the
file.

## Debugging

**Debug: Start / Continue** (`debug.start`: `C-c C-d d` in the Emacs keymap,
F5 in the VS Code, CUA and Sublime Text keymaps, Shift+F9 (⌃D on macOS) in the IntelliJ IDEA keymap) or **Debug File** debugs a
Dart file on the SDK's own debugger: `dart debug_adapter` for a Dart program,
`flutter debug_adapter` for a Flutter app's file, on the same device as Run
File.

- **Breakpoints**, with conditions, and logpoints.
- **Stepping**, the **call stack**, **Variables**, watches and hovers.
- **Exceptions**: the program stops on an uncaught exception, as in VS Code;
  in the Breakpoints list, **All Exceptions** stops on every exception thrown.
- Stops stay in your own code: the SDK's and other packages' libraries are
  stepped over.

**Debug Test at Caret** in a `_test.dart` file debugs the `test`, `testWidgets`
or `group` the caret is in, alone, on Dart's (or Flutter's) test runner;
outside them, every test of the file.

## Installing

The Dart SDK, or Flutter's, brings the language server and the debugger: put
`dart` (and `flutter`) on your `PATH`, or set their paths. The Dart card of
**Settings → Debugging**, **Dart and Flutter (dart debug_adapter)**, has the
**dart path** and **flutter path** fields (blank: the one on your `PATH`),
**Flutter device**, and **Enable Dart and Flutter debugging**. Neither it nor
the Dart card of Settings → LSP has an Install button.
[Doctor](/docs/doctor) has rows for `dart` and
`flutter`.
