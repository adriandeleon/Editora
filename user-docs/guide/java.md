---
title: "Java"
description: "The Java language server and its refactorings, Java projects, and debugging Java: hot code replace, threads, and debugging through Maven or Gradle."
category: Languages
order: 1
---

Java gets code intelligence from Eclipse JDT LS (`jdtls`), runs a compact source file, a single-file program or any main class from the gutter, and debugs with the Java debug adapter. What every language shares is on the [language servers](/docs/lsp), [running](/docs/run-debug) and [debugging](/docs/debugging) pages; this page is what is particular to Java.

## Java refactorings that ask a question

Three refactorings in the Code Actions menu need more than the caret position,
so each one asks before it runs. They need the Java language server.

**Move** depends on what the caret is on:

| On | Moves it to |
| --- | --- |
| A class | another package, chosen from a list |
| A nested class | its own new file, or another class |
| A static member | another class |
| An instance method | one of its parameters or fields |

Moving a class lists the packages of its own source folders (a class in main
code: the main packages only), and ends with **New package…**: type a package that does not exist yet and its folder
is created under the current source folder. A Move Instance Method that jdtls
finds problems with shows them first and moves only if you continue.

**Extract Interface** shows the class's methods as a checkbox list, then asks
for the interface's name and its package.

**Change Signature** presents the method's signature as one line of text to
edit:

```java
public String greet(Helper helper, int n)
```

In that line you can rename the method, change its visibility or return type,
and reorder, rename, retype or remove parameters. To **add** a parameter, write
it as `type name = value`; the value is what existing callers pass for it:

```java
public String greet(Helper helper, int n, boolean loud = false)
```

A line that cannot be read as a method signature is rejected and nothing
changes. When Change Signature or Move changes other files, they are listed
first, ticked, the way a [rename](/docs/lsp#rename-preview) is, and a file you untick is
left alone.

## Java-only assists

**Pasted code imports itself.** Paste a snippet into a Java file and the server
is asked which imports the pasted text needs; they are added for you, and only
for names the pasted text uses. Only the
language server can do this, because it needs the type resolver. An answer that
lost a race with your typing is dropped rather than applied to text it wasn't
computed for. In the Emacs keymap, a yank (`C-y`) and each `M-y` get the imports
their text needs, each kill ring entry with the file it was killed from, and `M-y`
keeps working after those imports land.

**Pasting into a string escapes the text.** Pasted between the quotes of a Java
string, `C:\temp "x"` becomes `C:\\temp \"x\"`, and pasted lines become
concatenated strings, each ending in `\n`. Text copied from inside a string
literal pastes as it is, already escaped. In a text block only backslashes (and a
`"""`) are escaped, and in a char literal its backslash and quote. One Undo takes
the paste back.

**Smart semicolon placement.** Typing `;` part-way through an expression puts it
at the end of the statement: `compute(1, 2|)` becomes `compute(1, 2);|`. The
semicolon is never held back waiting for the server, since that would make a
keystroke wait on a round trip. It lands where you typed it and moves afterwards
if the server disagrees, as a single undo step. If you keep typing (the very
common `;` then Enter), the document has moved past the answer, so the
correction is skipped rather than applied late.

Both need the Java language server running. Pasted-code imports are **on by
default**; smart semicolon placement is **off by default**. Switch either in
Settings → Code Completion, or with `view.togglePasteImports` and
`view.toggleSmartSemicolon`.

**Re-indent as you type** does the same on `;`, `}` and Enter. It is indentation
only, never a reformat of the line under you, and the local auto-indent still
acts first, so nothing feels slower and the server only corrects the cases the
heuristic gets wrong. Off by default: turn it on in Settings → Code Completion or
with `view.toggleOnTypeFormatting`.

**A Javadoc for the declaration below.** Typing `/**` above a method or class
closes the comment and offers the server's **Javadoc comment** template, with
`@param`, `@return` and `@throws` for that declaration. Pressing Enter instead
splits the comment as usual.

**Organize imports and format on save.** **Organize imports on save (Java)** and
**Format on save (Java)** in Settings → Code Completion → Language server
features tidy a Java file after you save it, and save it again when that changed
it, as IntelliJ's Actions on Save do. Both are off by default. Autosave never
runs them, since organizing imports while you type would remove one you have
just added, and an ambiguous import is left out rather than asked about. While
Organize Imports is asking you which class to import, a save runs none of them,
so your answer still applies.

**Clean-ups.** **Code → Clean Up File** applies the jdtls clean-ups you tick
under **Java clean-ups → Choose…** on the same Settings page: add `@Override`
and `@Deprecated`, remove redundant modifiers and `super()` calls, qualify
members, add `final`, use pattern-matching `instanceof`, switch expressions,
lambdas, try-with-resources and text blocks, and more (19 in all). The first
four are on by default; the others rewrite code, so they are yours to pick.
**Clean up on save (Java)** runs them after an explicit save, before Organize
Imports and Format.

## The Java server and your project

- **Untrusted folders.** In a folder you have not trusted, jdtls starts
  without the project's Maven or Gradle build, and the file says so with
  **Trust Folder**; see
  [Servers that load a project's build](/docs/lsp#servers-that-load-a-projects-build).
- **Which jdtls.** Install fetches jdtls **1.63.0**. [Doctor](/docs/troubleshooting#doctor)
  names the installed version and warns when it is older, with an
  [Update…](/docs/troubleshooting#update-for-servers-older-than-editoras)
  button that installs 1.63.0 and restarts the server on it.
- **Which JDK.** jdtls itself needs a recent Java (25 for jdtls 1.63, read from
  the installed jdtls), and is given one Editora finds even when `JAVA_HOME` is
  older; when there is none, it says so, and Doctor's jdtls row names the Java
  it runs on and its version. Of two JDKs of one version, a project gets the one
  with its sources, so JDK parameter names and docs show; when no JDK of that
  version has them, Doctor says which package adds them, reading the JDK the build
  compiles with (the compiler plugin's `<release>`, a parent pom, a Gradle
  toolchain or version catalog). The
  default JDK for your projects, which Gradle also runs on, is Settings → Build
  Tools → Default JDK, else the JDK of the `java` on your `PATH`, else
  `JAVA_HOME`, else the newest JDK installed. It is the JDK a Java file without a
  build tool runs on, so jdtls builds a loose file or a plain folder for the
  Java that runs it, and a JDK class opened while debugging shows that JDK's
  source. A changed Default JDK reaches a running jdtls at once; only the Java
  jdtls itself runs on waits for **Restart Language Servers**. The Gradle daemons
  jdtls starts for its imports stop after 10 idle minutes.
- **Library sources.** A Maven library class you open (with Go to Definition,
  say) gets its sources when a repository has them: the tab shows the real code,
  with its parameter names and Javadoc. A class that has none is shown
  decompiled, and its tab says so with two buttons: **Download Sources** looks in
  your local repository and on Maven Central (whose search it can ask by the
  jar's checksum), and **Choose Sources…** attaches a `-sources.jar` or
  zip from disk, for a `lib/` jar or an artifact only installed locally. The
  palette has both as **LSP: Download Library Sources**
  (`lsp.downloadLibrarySources`) and **LSP: Choose Library Sources…**
  (`lsp.attachLibrarySources`). A Gradle library's sources come from its build,
  and a JDK class without sources is a JDK with no `src.zip` (see Which JDK
  above). Once a library has its sources, every open tab of it shows them at
  once. To fetch every dependency's sources up front, turn on Settings → LSP →
  **Download library sources when a Java project is imported** (off by default;
  Maven only), or run **View: Toggle Downloading Library Sources at Import**
  (`view.toggleJavaDownloadSources`). It applies at the next import; **LSP: Clean Java
  Workspace and Restart** applies it now.
- **A failed import says why.** When jdtls cannot import the project, the status
  bar names the reason, each of its open files says **Not checked**, and Problems
  lists the build file's own errors. That covers a Gradle build that does not run
  (a settings file that `include`s a folder that does not exist, say), a pom that
  does not parse, and a pom Maven cannot build a model of: a dependency without a
  `<version>`, a parent it cannot resolve. A failure that belongs to one module or
  folder is named with it, as in **jdtls (app): the project did not import**, and
  the rest of the project stays checked. A module with
  `<packaging>pom</packaging>` that holds Java sources says Maven compiles none of
  them. Fix the build file and save it, or run **Reload Project Configuration**:
  the project imports again, after a pom's model error with a cleaned workspace,
  and a fix that arrives by `git pull` or another editor does the same. A project
  that imports with build problems (a Maven dependency or a Gradle one that cannot
  be found) is loaded and checked, and Problems lists them. A file the build
  leaves out on purpose (a nested sample project, `buildSrc`, an excluded source)
  does not make a healthy project look broken.
- **Your project folder stays clean.** jdtls keeps Eclipse's `.project`,
  `.classpath` and `.settings/` in its own workspace, not in your project.
- **Build files sync on save.** A change to `pom.xml` or `build.gradle` reaches
  the classpath when you save it, also a module's in a folder you never expanded,
  and so does one made outside Editora (a `git pull`, a branch switch).
- **One server per build.** Files of the modules of one Maven reactor or Gradle
  build share a server even without a project open. A plain folder of `.java`
  files with packages, and no build file, gets the right source folder as soon as
  a file opens. Java files keep their server up to 50,000 lines.
- **The project's own code style.** IntelliJ's import settings in the project's
  `.editorconfig` (`ij_java_imports_layout`,
  `ij_java_class_count_to_use_import_on_demand`,
  `ij_java_names_count_to_use_import_on_demand`) set the import order and when
  imports become `*`. The Eclipse formatter profile your build formats with
  (Spotless's `<eclipse><file>` or `eclipse().configFile(…)`, else
  `eclipse-formatter.xml` at the project root) is what Format Document uses.
  Editing the profile, or removing it, applies without restarting the server,
  also for a profile kept in Eclipse's `.settings` or a `config` folder, and a
  profile removed while Editora was closed no longer formats the old way. A
  project that already has an Eclipse `.settings` folder gets jdtls's compiler
  settings written into it (a committed `org.eclipse.jdt.core.prefs` changes);
  a project without one gets none.
- **Lombok.** For a project whose build declares Lombok, jdtls runs with it as
  its agent, using the newest Lombok jar already in your local Maven or Gradle
  cache, so the members Lombok generates are seen. When none has been downloaded
  yet, Editora offers to download Lombok from Maven Central and restarts the
  server with it.
- **Null analysis.** In a project with JSpecify, `javax.annotation`, Spring or
  Eclipse null annotations, jdtls checks `@Nullable` and `@NonNull` use.
- **A fresh start.** **LSP: Clean Java Workspace and Restart**
  (`lsp.cleanJavaWorkspace`) deletes the project's jdtls index and the
  classpath it worked out, then imports the project again: the cure for a stale
  or corrupt index.

## Debugging

Compact `.java` sources and extensionless Java `--source 25+` shebang files can
be debugged with breakpoints, stepping, and local variables. Editora maps
breakpoints and stack frames to the original file and removes its temporary
compiled files after the session. Java debugging needs the Java language server
and debug adapter described below. In a folder you have not
[trusted](/docs/workspace#trusted-folders), the Java language server runs
without the project's Maven or Gradle build, so trust the folder before
debugging a project's code.

JUnit tests are debugged from their gutter icon, with *Debug Test at Caret*, or
with Debug in the test file (see [Starting a Java session](#starting-a-java-session)),
including tests inside `@Nested` classes; see the
[test runner](/docs/build-tools#test-runner).

### Starting a Java session

**Debug** on a Java file debugs that file's own `main`. When the file has no
`main` and its folder has a single main class, Debug runs that class, and the
Debug console says which program runs. The gutter's Debug ▶ and Debug on a file
use the saved [run configuration](/docs/run-debug#run-configurations) that names the same
class, so its arguments, VM options, environment, working directory and JDK
apply, as they do for Run ▶. Before a Java launch, Editora saves every unsaved
Java file of the project.

In a JUnit test file of a Maven or Gradle project, Debug debugs the test at the
caret, or the whole class when the caret is outside a test, the way Debug Test
does. The caret on a test's annotations, such as `@Test`, counts as in that
test. A file with a `main` of its own is launched as a program instead.

In a Maven or Gradle project, Debug builds first, so the program runs the code
you see. Debug on a file builds the project with the Java language server: if
that build finds compile errors, nothing is launched, the Debug console lists
the errors as links to their lines (with paths relative to the project), the
status bar gives their number, and the Debug window reads "Not started: the
build failed". If the project could not be imported, the console says so
instead; when its errors name a Java version, choose another Default JDK in
**Settings → Build Tools**. If the build cannot run, the program starts anyway,
and the console warns you when a class file is missing or older than its
source.

*Debug Main Class* and Debug on a saved configuration compile with the build
tool when **Build before Run and Debug** is on (the default): Maven `compile`,
or `test-compile` for a class under `src/test`; Gradle `compileJava` or
`compileTestJava`. The build runs in the Debug console, through the same Maven
or Gradle the tasks tree runs, and a failed build aborts the launch. With the
setting off, they build with the Java language server, as Debug on a file does.
In a Gradle project the Java language server builds after Gradle as well, since
the program runs from the class files it writes, so the program runs the code
you edited. The Gradle daemon that this build starts stops after 10 minutes
without work. Stop during either build cancels the launch. A run configuration
with a [before-launch step](/docs/run-debug#a-step-before-the-launch) uses that step as its
build instead. A Java configuration's step runs in the project root, whatever the
configuration's working directory.

A class with a `package` line in a folder that has no `pom.xml` or Gradle build
can be debugged too, and such a class has **Debug File** in the editor's
right-click menu. In a project window for such a folder, *Debug Main Class* and
Debug on a saved Java configuration work, and the program runs in the project
root.

A loose `.java` file, or a project folder without Maven or Gradle, is built by
the Java language server before every Debug, so the program always runs the
code you saved. When that build reports errors or cannot run (a folder with
several packages can be more than the language server works out without a
build tool), Editora compiles the file with `javac` from the source root its
`package` line names. `javac` compiles only the code the program uses, so only
errors there stop the launch, listed in the Debug console as in a Maven
project; a broken file the program never uses, or a JUnit test without JUnit,
does not. Code that does not compile never runs. A configuration's
before-launch step runs first, and the build after it. The program runs on the
Default JDK, else on the `java` on your `PATH` (see
[Which runtime a program runs under](/docs/run-debug#which-runtime-a-program-runs-under)).
When the Default JDK's folder no longer exists, the Debug console says so and
the `java` on your `PATH` runs the program. A compact source file is always
compiled by `javac`.

While the language server builds before a launch, the Debug window reads
*Building…* from the moment you press Debug, and Stop cancels the launch.

**Start / Continue** (and the Debug window's green ▶), **Debug File** in the
editor's right-click menu and Debug Test make one check before they launch.
Start / Continue on a paused session continues it. For another file, a session
that is paused, running or still starting (building, compiling, waiting for a
test to start, or waiting for you to choose a main class) is replaced only
after you agree, for example to *Main.java is paused in the debugger. Stop it
and debug App.java instead?* For the same file, the status bar says that it is
already being debugged and whether it is running, paused or still starting. Esc
on the main-class chooser cancels the launch.

**Debug: Attach to Running Program…** (`debug.attach`) on a Java file attaches
to a JVM listening for a debugger. The first time in a project, it asks for the
`host:port`, offering `localhost:5005`. After that it lists the addresses this
project attached to, at most eight and the most recent first, then **Another
address…**, which asks for one, offering the most recent, and last
**A program on this machine…**, which opens
[Attach to Process](/docs/debugging#attach-to-process). The list is kept
with the project, so it is there after a restart. When nothing listens at the
address, the Debug console shows the `-agentlib:jdwp` option that starts a JVM
listening on it.

**Debug: Reattach** (`debug.reattach`, `C-c C-d S-a`) attaches again to the
address this project attached to last, without asking. Before the first attach,
the status bar says to use Attach to Running Program. Reattach is in
**Run ▸ Launch and Attach**.

When Java debugging can't start, the status bar names the missing
piece: LSP is off, the Java language server is turned off, or jdtls or
java-debug was not found. The Java card of **Settings → Debugging** has an
**Install…** button for what is missing.

### Stepping through Java code

Step Into skips JDK classes (`java.*`, `javax.*`, `jdk.*`, `sun.*`, `com.sun.*`
and a few more) and synthetic methods, and a frame's variables include its
class's static fields. You can turn either off on the Java card of
**Settings → Debugging**: **Step Into skips JDK classes and synthetic methods**
and **Show static fields in Variables**. To have Step Into go over your own or
a library's classes too, add them to [Don't step into](/docs/debugging#dont-step-into).

When you step into JDK or library code anyway, or click such a frame in the
call stack, its source opens in a read-only tab. A stop at a breakpoint, a
Pause or an exception still selects the nearest frame of your own code.

**Debug: Smart Step Into…** (`C-c C-d s`, also in Run ▸ Stepping) lists the
calls on the current line in the order they run, for example `twice(1)`,
`twice(2)` and `add(twice(1), twice(2))`. Pick one to step into it. On a line
with a single call it steps straight in. The list closes when the program runs
on.

**Debug: Restart Frame** (`C-c C-d f`, the call stack's right-click menu, or
Run ▸ Stepping) runs the method of the selected frame again from its first line
and pauses there. It is greyed out on the bottom frame.

If the connection to the debugger is lost, Editora ends the program at once and
says so in the Debug console and the status bar.

### Threads in a Java program

**Pause** stops the whole Java program: every thread is suspended, not only the
one on screen. **Continue** after a Pause resumes every thread. A step between
the two runs only the thread you step, and the next Continue still resumes them
all.

By default, a breakpoint, an exception or a step stops only the thread that
hit it, and the program's other threads keep running. To stop them all, turn on
**Suspend all threads when one stops** on the Java card of **Settings →
Debugging** (off by default). Continue then resumes every thread. The setting
takes effect at the next debug session.

To pause or resume one thread and leave the others as they are, use the
[Threads view](/docs/debugging#threads-view).

### Editing during a session

The running program keeps the code it was started with. When you edit a file
during a session, Editora matches breakpoints and the stopped line to the code
the program runs, so they stay on the right lines. At a stop in an edited file,
the status bar says that the program still runs the code it started with. A
breakpoint on a line you change in place, re-indent or wrap in a block (such as
an `if`) keeps working and stopping there; when you split a line in two, the
breakpoint stays on its first half. A breakpoint on a line you added during
the session is kept, and its hover says that it takes effect in the next
session. To run the new code, restart the session, or let
[hot code replace](#hot-code-replace) load a change to a method body.

### Hot code replace

During a Java session, saving a `.java` file loads the change into the running
program without restarting it. Editora builds the project with the Java
language server and sends the changed classes to the program. The Debug
console and the status bar say which classes were replaced, or why nothing
was:

- the project has compile errors: fix them and save again;
- the program refused the change, with its reason. A change to a method's body
  can be loaded; a change such as adding or removing a method or a field needs
  a restart, and the console tells you so;
- for a file without Maven or Gradle, the program runs an older Java than the
  code was built for ("the program runs Java N, but the code was built for
  Java M"): choose the program's JDK as the Default JDK in **Settings → Build
  Tools**, or restart the session;
- the Java language server could not build the project;
- the session runs classes compiled with `javac`, because the Java language
  server cannot build the folder: restart the session to run your edits;
- the program already runs this code.

A change to a method that is running when you save is applied too. That method
goes on with its old code until it returns, and its frame in the call stack
reads "old code (replaced while it ran)". To run it again with the new code,
select its frame and use **Debug: Restart Frame**. The first method of a
thread, such as `main`, has no caller to run it again, so it keeps its old code
until the program restarts, and the console says so.

Choose when this happens with **Apply code changes to a running Java program**
on the Java card of **Settings → Debugging**:

| Choice | What a save does |
| --- | --- |
| **On save** (default) | loads the change when you save a file yourself; autosave does not |
| **Only with Debug: Apply Code Changes** | shows a hint in the status bar; run **Debug: Apply Code Changes** (`C-c C-d h`, Run ▸ Debugger) to load the saved changes |
| **Never** | nothing; Debug: Apply Code Changes says that it is off |

Debug: Apply Code Changes loads only what is saved. When the active Java tab
has unsaved edits, the console says so.

Hot code replace also runs in an attached session and in Debug Test and Debug
via Build Tool sessions. There the program runs the classes your build tool
compiled, so a change can be refused that a session Editora started would
accept; the console then tells you to restart.

## Debug via the build tool

Some applications are meant to be started by the build tool rather than by a bare
`java` command. **Debug: Debug via Build Tool** (`debug.viaBuild`) launches the
app under a suspended JVM through Gradle (`run` or `bootRun`) or, for a Spring
Boot Maven project, `spring-boot:run` with a JDWP agent. Editora
watches the build output for the JDWP banner and attaches the debugger when the
app is listening. It complements *Debug Main Class*, which uses the language
server; a plain Maven `main` has no uniform build-tool debug mechanism, so use
*Debug Main Class* there. Two cases are called out in the status bar: a
`spring-boot-maven-plugin` whose configuration sets its own `<jvmArguments>`
replaces the debug agent Editora passes, so the launch warns up front, and a
build still running after 45 seconds with no banner says the app may have
started without the agent and points at *Debug Main Class* or the plugin's
configuration.

For [Debug Test](/docs/build-tools#test-runner) and Debug via Build Tool, in
Maven and in Gradle, every JVM the build starts listens on a free port, so a
busy port 5005 does not get in the way. Gradle and `spring-boot:run` print the
JDWP banner, which Editora reads for the port. A Maven test JVM is different:
Surefire 3 prints that banner only after the test has run, so Editora picks
the port itself, passes it in `-Dmaven.surefire.debug` and attaches as soon as
the fork listens; the one "Debugger failed to attach: handshake failed" line
the JVM prints is Editora's port check, not an error. For Gradle, Editora
passes an init script that adds the debug agent to
the `test`, `run` or `bootRun` task. A Gradle build that runs on another
machine uses `--debug-jvm` and its fixed port 5005 instead. When a run starts
several JVMs, for example forked test JVMs, Editora attaches to each in turn,
as the session before it ends. **Stop** on
such a session also ends the test or build run that started the JVM, with any
JVMs still waiting.
