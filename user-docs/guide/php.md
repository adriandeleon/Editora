---
title: "PHP"
description: "Running PHP scripts, web apps and PHPUnit tests, and debugging PHP with Xdebug."
category: Languages
order: 14
---

PHP gets code intelligence from `phpactor`, runs scripts, web apps and PHPUnit
tests from the gutter, and debugs with Xdebug.

## Language server

PHP's language server is `phpactor`. Editora finds it on the PATH or installs
it for you; see [language servers](/docs/lsp#servers-are-auto-detected-never-bundled).

## Running

<a id="running-php-files"></a>*Run File* (`file.run`: `C-c r` in the Emacs keymap, Ctrl+Shift+B (⌘⇧B on macOS) in VS Code) on a PHP file runs what it
is:

- a **script** runs as `php file` in its folder;
- a web app's **`index.php`** is served by PHP's built-in web server on its
  folder: `php -S 127.0.0.1:8000 -t public`. The first free port from 8000 is
  used, and the address appears in the Run console, ready to click;
- a **Laravel** app's `artisan` or `public/index.php` is served as
  `php artisan serve` serves it: the built-in server in `public/`, with
  Laravel's router. Run `composer install` first: the router is in `vendor/`;
- a **PHPUnit test file** (`…Test.php` with a `TestCase` class) runs its tests
  with the project's PHPUnit (`vendor/bin/phpunit`, else `phpunit` on your
  PATH), in the folder of its `phpunit.xml` or `composer.json`.

**Debug File** does the same with Xdebug (see
[Debugging PHP](#debugging)). Pressed in a class file of a web app, Debug
serves the app that file is part of.

## Tests

**Run Test at Caret** (`test.runAtCaret`) in a PHPUnit test file runs only the
test the caret is in (`--filter`): a method named `test…`, or marked `#[Test]`
or `@test`. Elsewhere in the file, all its tests run. The results fill
[Test Results](/docs/build-tools#test-runner).

## Debugging

PHP is debugged with [Xdebug](https://xdebug.org), through PHP Debug
(vscode-php-debug), the adapter VS Code uses. **Debug: Start / Continue**
(`debug.start`: `C-c C-d d` in the Emacs keymap, F5 in the VS Code, CUA and
Sublime Text keymaps, Shift+F9 (⌃D on macOS) in the IntelliJ IDEA keymap) or **Debug File** runs the file as [Run File](#running)
runs it, with Xdebug turned on for that run, and stops at your breakpoints:

- a **script** runs with the arguments set by **File: Run with Arguments…**;
- a **web app**: Debug on its `index.php`, Laravel's `artisan`, or any class file
  of the app starts PHP's built-in web server, as VS Code's *Launch Built-in
  web server* does. Each request you send it stops at your breakpoints and is
  a thread of the session. Stop ends the server;
- a **PHPUnit test file** runs its tests. **Debug Test at Caret** runs only the
  test the caret is in: a method named `test…`, or marked `#[Test]` or
  `@test`. Elsewhere in the file, all its tests run.

The program's output reaches the Debug console. PHP's [exception breakpoints](/docs/debugging#exception-breakpoints)
(exceptions, errors, warnings, notices, deprecations, or everything) are off by
default.

PHP has no Attach to Process: Xdebug connects to a debugger that listens for
it. **Debug: Listen for Xdebug** (`debug.listenXdebug`, also in **Run ▸ Launch and
Attach**) starts PHP Debug listening on Xdebug's port, 9003, without a
launch.json. PHP you start yourself then stops at your breakpoints: a request
to your own web server, or a script run with `XDEBUG_SESSION` set, when Xdebug
is set to connect (`xdebug.mode=debug`, with `xdebug.start_with_request=yes`
or a trigger). The session listens until you stop it. A
[launch.json](/docs/debugging#launchjson-configurations) `php` configuration without a
`program`, VS Code's *Listen for Xdebug*, does the same with its own port and
path mappings.

## Installing Xdebug and PHP Debug

PHP needs the Xdebug extension installed (on Debian and Ubuntu, the
`php-xdebug` package), and the adapter runs on Node.js 20.18 or later. Editora
finds PHP Debug as set on the **PHP (Xdebug)** card of **Settings → Debugging**
(its `phpDebug.js`, or the folder of the unpacked extension), else in its own
install, else in VS Code's PHP Debug extension (`xdebug.php-debug`). The card's
**Install…** downloads PHP Debug 1.40.2. Editora doesn't install PHP or Xdebug.
