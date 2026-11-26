---
title: Build tools
description: Maven, npm, Cargo, Go, Gradle, sbt, Mill, Scala CLI, the Clojure CLI, Leiningen and Babashka each get an IntelliJ-style tasks tool window that streams to a shared Output console, plus a test runner with live results.
category: Run & debug
order: 3
---

Each detected build tool gets its own **tasks tool window** (its stripe appears
when the tool's marker file is present, docked on the right by default): a
browsable tree of the tool's goals, scripts, or targets with a mini toolbar
(Run / Reload / Stop / Run custom…).
Double-click or Enter runs a task, and the output streams to the shared **Output**
window, which has **one tab per tool** (created on first run and
selected while it runs), so two builds running at once stay in separate tabs
instead of interleaving. A searchable actions popup is also available from the
palette (`<tool>.showActions`, e.g. *Maven: Show Actions*). `tool.<tool>` opens
the tasks window; `tool.buildOutput` opens the Output console. It's **on by
default**, and each tool is inert until its marker file is found.

The console holds more than build output. Alongside a tab per build tool it
carries a **CI** tab for a [failed GitHub Actions log](/docs/github), and a
**Git** and **GitHub** tab holding a transcript of the `git` / `gh` commands
Editora ran on your behalf, each with its output, exit code and duration.

While a build runs, a spinner turns beside its status and in its tab's title,
so a build in a tab you are not looking at still shows it is running; a **Git**
tab spins while a clone streams. In **Test Results** the spinner also covers
the compile step before the first test reports.

| Tool | Marker | Actions |
| --- | --- | --- |
| Maven | `pom.xml` | Lifecycle phases, profiles (three-state: off, `-P`, `-P!id`, with the condition that activates each), each plugin's bound goals and its full goal list, a *Reactor* toggle in a module, a *Projects* section when the build files sit below the root, and *Run custom…* |
| npm (the **Package Scripts** window) | `package.json` | One entry per `scripts` name, plus `install` / `ci` |
| Cargo | `Cargo.toml` | Standard subcommands, `[[bin]]` / `[[example]]` targets, and a `--release` toggle |
| Go | `go.mod` / `go.work` | Standard subcommands over the whole module |
| Gradle | `build.gradle[.kts]` | Common tasks, plus *Load all tasks…* |
| sbt | `build.sbt` | Common tasks (`compile`, `test`, `run`, `clean`, `package`, …), plus *Load all tasks…*: each subproject's `compile`, `test` and `run` |
| Mill | `build.mill` (or a Mill 0.x `build.sc`) | `__.compile`, `__.test`, `clean` and `resolve _`, plus *Load all tasks…*: each module's `compile` and `run`, and each test module |
| Scala CLI | `project.scala` | `compile`, `run`, `test`, `package`, `fmt` and `clean` over the project folder |
| Clojure CLI | `deps.edn` | The aliases that run something (`-M:test`, `-X:build`, `-T:build`), plus prepare (`-P`), `deps tree`, `deps list` and the classpath |
| Leiningen | `project.clj` | `run`, `test`, `check`, `compile`, `jar`, `uberjar`, `install`, `deps`, `clean` |
| Babashka | `bb.edn` | Each task in `:tasks` (`bb run <task>`), plus `bb tasks` |

Maven, Gradle and Mill prefer the project's own `./mvnw` / `./gradlew` /
`./mill` launcher, falling back to `mvn` / `gradle` / `mill` on your `PATH`. sbt
runs `sbt` and Scala CLI `scala-cli` from your `PATH`; sbt runs each task as one
batch command (`sbt compile`), which with sbt 2 starts sbt's server in the
background and keeps it for the next task. *Load all tasks…* runs the tool once
to list the build (`sbt projects`, `mill resolve __.compile`), which takes a
while the first time. A `package.json` project uses its own package manager
(npm, pnpm, yarn or bun); see
[npm and other package managers](#npm-and-other-package-managers).

A build runs code the repository supplies, with your privileges, beyond the
task you pick: a shipped `./mvnw`, `./gradlew` or `./mill`, the pom's plugins
and the files under `.mvn/`, every Gradle build script, sbt's `build.sbt` and
`project/` and Mill's `build.mill` (Scala the build compiles and runs),
Leiningen's `project.clj` and its plugins, a Babashka task's `:init` and
`:depends`, the JVM options a `deps.edn` alias adds (such as a `-javaagent`),
Cargo's `build.rs` scripts and procedural macros, the macros and compiler
plugins a Scala CLI compile runs, and npm's `pre` and `post` scripts, install
hooks and a yarn the repository ships through `.yarnrc.yml`'s `yarnPath`. So
the **first build of any kind** in a folder you have not trusted asks once,
whether it would run the repository's launcher or your own `mvn`, `sbt`,
`mill`, `lein`, `cargo` or `npm`. The **Workspace Trust** prompt lists what it
found: the launcher script, the pom's plugin coordinates (the first eight, then
"and *n* more"), the `.mvn/` files, the Gradle build scripts, the sbt and Mill
build files with a `mill-build/` meta-build, the launcher options files
(`.jvmopts`, `.sbtopts`, `.mill-jvm-opts`, `.lein-classpath`), the Cargo and
package manager settings (`.cargo/config.toml`, `rust-toolchain.toml`,
`.npmrc`, `.yarnrc.yml`, `.pnpmfile.cjs`), and a project
`.editora/settings.json` that replaces the build command, with the command it
sets. Trust is remembered
for the folder that ships the wrapper, else the open project, and for the
folders inside it; a module never silently covers a script one level up. The
tasks window's header shows **Trusted** or **Not trusted yet** with a
**Revoke** button. The same question fronts *Load all tasks…*, Test Results,
the build before Run and Debug, Run and Debug on a file of a Leiningen,
`deps.edn`, `bb.edn` or Scala CLI project or of an sbt or Mill build, an npm
[run configuration](/docs/run-debug#what-a-configuration-can-launch), and
Maven's classpath fallback. Declining cancels the run; nothing quieter runs
instead. Review or revoke trust under
[Trusted folders](/docs/workspace#trusted-folders). Only Go builds are not
asked about: `go build` never runs a module's code, and `go run` and `go test`
run it because that is the task you picked.

Choose the JDK used for Maven under **Settings → Build Tools → Default JDK**.
The selector finds installed JDKs in standard platform locations and through SDKMAN, asdf, mise, Jabba, and JetBrains-managed installs;
**System default** keeps using `JAVA_HOME` / `PATH`. The choice supplies both
`JAVA_HOME` and the matching `java` executable to Maven tasks, Maven classpath
resolution, Java Run and Debug, and before-launch commands, and the same JDK
runs sbt, Mill, Scala CLI and Scala's Run and Debug, the
[New Maven Project](#new-maven-project) wizard's Maven, and the
[Maven `pom.xml` server](/docs/xml#pomxml-and-the-maven-server).
A Java file without a build tool (a loose file, or a project folder with no
`pom.xml` or Gradle build) runs and debugs on it too, and it is the default JDK
of the [Java language server](/docs/java#language-server): the
JDK it builds those files for, shows JDK sources from, and runs Gradle imports
on. With **System default**, they use the `java` on your `PATH`. A changed
Default JDK reaches a running language server at once, with no restart.
A saved run configuration can override it for one project or launch. With
**System default** and no `JAVA_HOME`, the program a Maven Run launches uses the
`java` on your `PATH`, which may not be the version Maven compiled with.
A Java run configuration's before-launch command runs in the project root,
whatever the configuration's working directory, so a `mvn compile` or
`gradle classes` step finds the build file.

## The project's JDK, Maven, Gradle, sbt and GraalVM

A project can name its own JDK, Maven, Gradle, sbt and GraalVM, so everyone who
opens it builds with the same versions. Editora reads them, in this order for
each tool:

1. **Editora's project settings** (`toolchain` in `.editora/settings.json`),
   applied only in a [trusted folder](/docs/workspace#trusted-folders).
2. **The project's files**, the way [jkit](https://jkit-project.dev/) reads
   them: `jkit.toml` (or `.jkit.toml`), or else `.sdkmanrc`, `.tool-versions`
   (asdf, mise) and `.java-version` together. When jkit is installed, Editora
   asks jkit itself (`jkit status`), so the editor, your shells and CI agree;
   without jkit, Editora reads the `.sdkmanrc`.
3. Your own choice in Settings (the **Default JDK** above), else `PATH`.

The JDK found this way runs Java Run and Debug, Maven and Gradle builds and the
Java language server; the Maven, Gradle or sbt runs that project's builds when it
has no wrapper. The versions are matched among what jkit and SDKMAN installed
and every other JDK on the machine. When the project names one that is not
installed, the status line says so once; **Java Toolchain: Install the Project's
Tools** (`toolchain.installProjectTools`) installs it with `jkit sync`.

**Java Toolchain: Set Project JDK…** (and Maven, Gradle, sbt, GraalVM) picks an
install and asks where to keep the choice: `jkit.toml` (written by `jkit pin`,
read by jkit everywhere), `.sdkmanrc`, or Editora's project settings. A new
`jkit.toml` takes over from the folder's `.sdkmanrc`, `.tool-versions` and
`.java-version` for jkit, so the pins they made are copied into it first.
Choosing **My own (Settings)** removes the project's choice from all three.

### jkit

[jkit](https://jkit-project.dev/) installs and switches JDKs, Maven, Gradle and
sbt. Editora drives it through its `--json` output: every jkit command is in the
Command Palette under **Java Toolchain**, and in **Tools ▸ Java Toolchain**.

| Command | jkit |
| --- | --- |
| Install a JDK / GraalVM / Maven / Gradle / sbt with jkit… | `jkit search`, `jkit install` |
| Sync the Project with jkit | `jkit sync` (a dry run first; builds jkit cannot verify only if you say so) |
| Show jkit Status | `jkit status`: each tool's pin, what it picks, and jkit's hints |
| Upgrade jkit's Installs | `jkit upgrade` (newest build of each installed version) |
| Show jkit Installs… / Set jkit's Default… / Remove a jkit Install… | `jkit list`, `jkit use --global`, `jkit remove` |
| Remove Older jkit Builds… | `jkit prune` |
| jkit Settings… / Set jkit's Default JDK or GraalVM Vendor… / Toggle jkit Switching on cd | `jkit config` |
| Check jkit Setup (and Network) | `jkit doctor` |
| Add jkit to My Shell… | `jkit hook --install` |
| Update jkit | `jkit self-update` |

**Settings → Build Tools → jkit** sets the jkit program (blank finds `jkit` on
`PATH`), shows whether it was found, and links to **jkit Settings…** and
**Check Setup**. jkit's own settings (the default vendors, switching on `cd`)
live in jkit's `~/.jkit/config.toml`; a value set by an environment variable
wins there and is shown but cannot be changed. [Doctor](/docs/doctor)'s
Build Tools section has a jkit row with the first problem `jkit doctor` finds.
Editora needs jkit 0.4.0 or newer for its settings and **Update jkit**; an older
jkit says so.

In a [remote project](/docs/remote#allowing-a-host-to-run-commands), Maven,
Gradle, npm, Cargo and Go builds and test runs start on the host, over the same
SSH connection, with output and Stop as for a local build (the Output console
takes no input: a build is not interactive). The wrappers are looked for on the
host, and Java is the host's own (or the **JDK home** set for that host in
Settings → Remote), never the local Default JDK. A **Maven** command can be set
per host there too, beside the JDK home, for a host whose `PATH` finds the
wrong `mvn` or none.

A project whose tools [run in a container](/docs/remote#tools-in-a-container)
or, on Windows, [in a WSL distribution](/docs/remote#tools-in-wsl-windows) runs
its builds and test runs there the same way, with the wrappers, Java and
environment found inside. The wrapper run there is the POSIX `./mvnw` or
`./gradlew`, also when Editora itself runs on Windows.

Discovery parses the marker file directly, with no shell-out and no new
dependency, so it's instant and offline. Toggle each under **Settings → Build
Tools**.

Each tool's card there has a **Command override** field with **Browse…** and a
**Detected** line for the program a run uses when the project has no wrapper:
the override, or else the tool's own command. For example *mvn: found
(/usr/bin/mvn)* or *gradle: not found*. A misspelled override is still saved;
the line shows that it is not found. Below it, **Project** says whether the
active file's project uses the tool and, for Maven and Gradle, which program a
build of it really runs: *./mvnw (trusted)*, *./mvnw (not trusted yet)*, *mvn
on PATH*, or the override. [Doctor](/docs/doctor)'s Build Tools
section says the same. The command row is greyed while that tool is turned off.

The same page has **Build before Run and Debug** (on): in a Maven or Gradle
project, Run, **Debug: Debug Main Class…** and Debug on a run configuration first
compile with the build tool (`compile` or `compileJava`, or the test variant for
a class under `src/test`), as described under
[Running a project's main class](/docs/run-debug#running-a-projects-main-class).
A run configuration with its own before-launch step runs that step instead.
Turn it off and Run launches whatever `target/classes` holds, while Debug builds
with the Java language server.

Debug on a file (**Debug: Start / Continue** in a Java file, or the gutter's
Debug ▶) always builds with the Java language server, and stops on compile
errors. In a
Gradle project the language server writes its classes to `bin/` in the project
folder, so add `bin/` to your `.gitignore`. The Gradle daemons it starts for
its imports stop after 10 idle minutes.

## Clojure

A `deps.edn` gets a **Clojure CLI** tasks window. An alias with `:main-opts` is
listed as `-M:<alias>`, one with `:exec-fn` as `-X:<alias>`, and one with only
`:ns-default` as the tool `-T:<alias>`; an alias that only adds dependencies or
paths runs nothing on its own and is not listed. The CLI's own commands follow:
download the dependencies (`clojure -P`), print the dependency tree and list,
and print the classpath.

A `project.clj` gets a **Leiningen** window with the common `lein` tasks. A
`project.clj` is Clojure code, so its aliases and its plugins' tasks are not
read; run them with *Run custom…*.

A `bb.edn` gets a **Babashka** window with one entry per task in its `:tasks`,
in file order, run as `bb run <task>`. Private tasks (a name starting with `-`)
are left out, as `bb tasks` leaves them out.

The three tools run `clojure`, `lein` and `bb` from your `PATH`, or the command
override in their Settings cards. Their test tasks stream to the Output console;
the Test Results window does not read Clojure test runners' output. Running and
debugging a single Clojure file is covered in
[Clojure files](/docs/clojure#running).

## Maven

![The Maven tool window beside an open pom.xml: a toolbar, the build root marked Trusted, a search field, the Lifecycle phases from clean to deploy, and the project's profiles](/screenshots/docs/maven.png "The Maven tool window lists the lifecycle phases and the profiles the pom declares.")

### What the tree reads

The tree is built from the nearest `pom.xml` **merged with the `<parent>`
chain found beside it on disk**, followed through `<relativePath>` (Maven's
default `../pom.xml`) while the file is inside the open project and its
coordinates are the ones the child names. In the usual multi-module layout the
profiles, plugins and `<pluginManagement>` are declared once in the parent, so
a module's tree carries its parent's profiles and plugins, a
`pluginManagement` entry gets a goal group titled *(managed)*, and a plugin
version written as `${property}` is resolved through the merged properties, so
the goal list is read from the version the build runs. It is not an
effective pom: nothing is read from a repository, and a parent that is not on
disk under the project contributes nothing.

A bound execution with an id of its own runs as `prefix:goal@id`, so a plugin
bound twice runs the execution you picked, not both. A pom that does not parse
keeps the **Lifecycle** section, so `clean` or `compile` still run, and shows
the parse error under the tree with an **Open pom.xml** link. **Run** in the
mini toolbar is greyed until a task row is selected and while a build runs.
In [Simple UI mode](/docs/workspace#focus-modes) the build tools are off, and
a build command says so.

### Profiles

Each `<profile>` is a **three-state** toggle. The first click turns it on
(`-Pid`); the second turns it off even if Maven would activate it on its own
(`-P!id`); the third leaves it as the pom says. A profile's row notes why Maven
would activate it without `-P`: *active unless another profile is*
(`activeByDefault`), or its `<activation>` condition (`jdk [17,)`, `os linux`,
`property env.CI`, `file exists src/main/native`). A profile that your
`~/.m2/settings.xml` lists under `<activeProfiles>` is shown checked and
locked: it is on whatever the command line says. The tree and the
*Show Actions* popup share one set of checked profiles per build root, and
every task of that root carries them.

### Projects below the root

A project whose build files sit one or two folders below the root (a `java/`
subfolder, a repository holding several small builds) lists them in a
**Projects** section at the top of the tree. Picking one makes it the build
root until you switch to a file of another; the current one is marked
*(current)*. Detection never walks above the folder you opened, so a project
opened inside a module never builds the pom above it.

### Every goal a plugin offers

A literal reading of `pom.xml` can show only a goal written inside an explicit
`<execution>`. A plugin declared for **direct invocation**, such as
javafx-maven-plugin, spring-boot-maven-plugin or exec-maven-plugin, carries only
`<configuration>` and no executions, so that reading gives it no row, and
`javafx:run` would be reachable only by typing it into *Run custom…*.

Each plugin therefore gets a **collapsed section listing every goal it offers**,
read from the plugin's own descriptor, with each goal's description (its
Javadoc markup stripped) as its tooltip. Because the goals come from the
descriptor and not from a built-in table of well-known goals, the section
covers whatever plugins are in your pom. The group's tooltip names the version
it read; when the pom pins no version Editora can read, it says the goals are
those of the newest installed version and the build may run another.

The flat *Plugins* section and these per-plugin groups overlap by design and
answer different questions: the flat one is *what this build runs* (with the
phase and execution id in its tooltip), a group is *what this plugin can run*.

It reads your **local repository only, never the network**, so opening a project
never fetches anything and a plugin you have not downloaded contributes
nothing. Results are cached, since detection re-runs on every save and tab
switch. A goal belonging to a plugin declared only inside a profile is not
listed, even when that profile is checked.

### Building a module from its reactor root

In a multi-module project, the Maven tool window of a module starts with a
**Reactor** section holding one toggle, *Build from \<root\> (-pl \<module\> -am)*.
With it on, every task runs from the reactor root as
`mvn -f <root>/pom.xml -pl <module> -am <task>`, so `compile` or `test` in a
module that depends on a sibling builds the sibling too, instead of failing with
"Could not find artifact" until the sibling is installed. The toggle is offered only when the root pom
lists the module and the root is inside the open project; a top-level project
has no Reactor section.

### Run custom… and Rerun Last

**Run custom…** (`maven.runCustom`) takes a free-form goal string. The prompt
starts with the last string you ran, quotes group an argument (`"a b"` is
one), and it says which checked toggles (profiles, the reactor switch) it will
*not* add, so you type them when you want them. Each project keeps a
**history** of the strings it ran, shown as **Recent** rows at the top of the
*Show Actions* popup. **Rerun Last** (`maven.rerunLast`) repeats the last
invocation with the same root, task and toggles, and the status line names
the folder it runs in when that is not the active file's.

### The Build menu and keys

The **Run ▸ Build** submenu holds *Show Actions*, *Run Custom Goal(s)…*,
*Rerun Last*, *Stop* and *Refresh pom.xml*, then the Maven and Output tool
windows, so they can be reached from the menu as well as from the stripe and the
palette.
**Rerun Last** has no default key in any keymap. The IntelliJ keymap binds the
Output window to `Alt+Shift+B` (`⌥⇧B`), as IntelliJ IDEA does; the other
keymaps bind nothing by default, and any `maven.*` command can be bound
in [Keymaps](/docs/keymaps).

### The Output console

The Maven tab of the Output console colours `[ERROR]`, `[WARNING]`, `BUILD
SUCCESS` and `BUILD FAILURE` lines under every editor theme, and a compiler
location such as `App.java:[5,18]` is a link that opens the file at that line
and column, as stack-trace lines already were. Its header has four controls
beside Stop and Clear:

- **Find** (the magnifier, or the editor's Find chord while the console has
  focus) opens a find bar over the output. Every match is highlighted while the
  bar is open, the current one more strongly, and output that arrives meanwhile
  is highlighted and counted too.
- **Previous run** keeps the run before this one behind a toggle, so a failure
  can be compared with the build before it without re-running; Clear drops it.
  The header's buttons are commands too: *Output: Clear Output*
  (`buildOutput.clear`), *Output: Show Previous Run* (`buildOutput.previousRun`)
  and *Output: Stop Output's Job* (`buildOutput.stop`).
- **Save output…** writes the console to a file (`maven-output.txt` is
  suggested).
- **Stop** ends the build, and the console header, the status bar and Test
  Results then read **Stopped**, not an exit code or "Run failed".

Starting a task while another runs asks **Stop and run**: the new task starts
once the stopped build has exited. The console keeps the newest 200,000
characters and says at the top when it dropped older output.

### The Maven submenu

A **Maven** submenu appears wherever there's a pom in hand: on a `pom.xml` in the
editor's right-click menu, and on a folder or a `pom.xml` row in the Project
tree. It carries **Update Versions**, **Show Actions** and **Run custom…**, and
each acts on the pom it was opened on: right-click `moduleB/pom.xml` (or its
folder) and they read, list and run that module, not the one the active editor
tab is in. When that folder is not the active tab's, the status line names it.
*Rerun Last* and *Stop* are window-wide, so they are not in the submenu; the
palette and the tool window keep them. Inside the submenu the entries drop
their "Maven:" prefix, since the submenu already says it.

One builder serves all three surfaces, so they always offer the same actions.
It offers nothing when Maven is off, when there's no pom above the folder, or on
a file that isn't itself a `pom.xml`.

### New Maven Project

**Maven: New Maven Project…** (`maven.newProject`) creates a project from an
archetype and opens it in its own window. It is in the File menu, in the
Project tree's **New ▸** submenu on a folder, and in the palette, where
**Maven: New Maven Project in Folder…** (`maven.newProjectHere`) asks for the parent
folder first. It runs your own Maven (the command override, else `mvn` on your
`PATH`), never a wrapper found in the target folder, and says so before the
wizard opens when there is none. That Maven runs on the **Default JDK** chosen
under Build Tools, and while it generates the Output window opens on a **New
Project** tab and a background task shows in the status bar.

The archetype picker starts with a **curated list of eight** that generate and
build on a current JDK: Apache's `maven-archetype-quickstart`, `-webapp`,
`-simple`, `-site` and `-plugin` (1.5), OpenJFX's `javafx-archetype-simple` and
`javafx-archetype-fxml`, and Apache Camel's `camel-archetype-java`. Below them,
**Custom archetype…** takes a `groupId:artifactId:version`, and **Load full
catalog…** fetches Maven Central's catalog. That file is about 18 MB and lists
every published version of every archetype; Editora streams it into one row per
archetype at its newest version (about 3,800 rows) and caches the result under
`~/.editora/cache/archetype-catalog.json` for a day, after which the next load
asks Central whether the file changed before downloading it again. A fetch that
fails falls back to the cached rows, or to the curated list, with the error
shown. Every row reads `groupId:artifactId:version — description`, typing
filters on both, and where the catalog lists a curated archetype the curated
pin wins. A curated archetype runs at once; one you typed or loaded is
third-party code, so the wizard asks before Maven downloads and runs it. The
question names the archetype's repository; **Generate** allows it once,
**Trust and Generate** also skips the question for that archetype until
Editora is closed, and **Cancel** returns to the form with your fields kept.
**Maven: Set Archetype Catalog URL…** (`maven.setArchetypeCatalogUrl`) points
*Load full catalog…* elsewhere (`https` only).

The form then takes the name, location and coordinates. The package is
derived from the group and artifact ids in lower case, the way IntelliJ does it
(`my-app` becomes `my_app`). The status line under the form says which field
still blocks **Create**, and once the form is complete it says *Will create
<folder>*, with a relative or `~` location resolved to the folder it means.
The folder may exist if it is empty; a folder with files in it is refused. A
collapsed **Advanced** section takes the project `<url>`, the Java release
(for the quickstart archetype it is passed as the archetype's own
`javaCompilerVersion`) and the **Update dependencies and plugins to the latest
versions** option described below. Once the archetype is in your local
repository, an **Archetype properties** section lists the properties the
archetype declares (a JavaFX or JUnit version, say) with their defaults; a
value you leave empty keeps what the archetype writes.

The generated `pom.xml` opens in the new project's window, and the project
gets a run configuration for its main class whose
[before-launch step](/docs/run-debug#a-step-before-the-launch) is
`maven:compile` and whose working directory is blank (the project root), so
the first Run builds before it launches. A JavaFX archetype gets no run
configuration, because its main class cannot be launched as a plain classpath
program; the status line says to run it with `javafx:run`. A project generated
inside an open aggregator is added to that aggregator's `<modules>` and stays a
module of it, rather than opening as a second project.

### Update the versions in an existing project

**`maven.updateVersions`** checks the nearest `pom.xml`'s dependencies and
plugins against Maven Central and **shows what would change before writing
anything**. The lookups run in parallel under a background task; they stop at
the first connection failure (*Maven Central could not be reached*), and
running the command again while a check is in flight cancels the first. The
dialog lists a **checkbox per row**, current → latest, grouped under
**Dependencies**, **BOM imports**, **Plugins**, **Parent** and **Properties**;
a jump of the leading number (JUnit 5 → 6) carries a **major** tag, and a BOM
import has its own group because its bump re-versions everything it manages.
Untick the rows you do not want, or decline the whole dialog. Nothing is
written until you accept.

The update is applied **through the open buffer as a single edit**, so one
`C-z` takes the whole thing back and the buffer is left dirty for you to save.
Only a pom that isn't open is written to disk.

It has three limits:

- Maven Central's `<release>` marker is **not trusted**. It means "newest
  non-snapshot published", which for `maven-surefire-plugin` was a milestone for
  years; the full version list is filtered for stability instead.
- A version is **never walked backwards**, which an unguarded set-to-latest
  would do to a pom pinned on purpose. Versions are ordered by Maven's own
  rules (a port of its `ComparableVersion`), not as semver, so a number beats a
  qualifier and `guava 33.0.0-jre` is never "updated" to `r09`; a candidate whose
  leading number is below the current one is never offered either.
- A **property-driven version** (`${junit.version}`) is never rewritten in
  place, and neither is a range (`[2.0,3.0)`) or `LATEST` / `RELEASE`. When the
  property is declared in this pom and referenced **only from `<version>`
  elements**, the dialog offers the property itself under **Properties**, so
  the modern way of pinning is covered. A property also used elsewhere (a
  plugin configuration, a resource filter) is left alone, and so is a name the
  pom's own `<properties>` do not declare, such as `${project.version}`.

The dialog's footer counts what was not checked and why: *3 versions were not
checked: 2 property-driven, 1 inherited*. A dependency or plugin with no
`<version>` of its own is *inherited*: its version comes from a parent or a
BOM, and writing one would change how it is resolved. In a module, the dialog
offers the **parent's version** as a row and a **Check the Parent pom.xml**
button that runs the same check on the parent found through `<relativePath>`.
A pom that pins nothing of its own says so with the same two counts. Each
artifact is resolved independently, so one coordinate missing from Central
leaves that version alone rather than failing the rest.

The same option is offered up front when
[generating a new project](#new-maven-project): the wizard's **Update
dependencies and plugins to the latest versions** resolves both through this
same Maven Central path and stability filter, so a brand-new project never
starts on a milestone or beta, and the status reports both counts (*Versions
updated: 3 dependencies, 2 plugins*).

## npm and other package managers

The `package.json` scripts are in the **Package Scripts** tool window
(`tool.npm`). Each row shows a script's name; hover it to see the command it
runs. Run one with double-click or Enter. Scripts run as
`<manager> run <name>`, where the manager is the one the project uses:

1. the `packageManager` field of `package.json` (for example `"pnpm@9.12.0"`);
2. else the lockfile: `pnpm-lock.yaml` → pnpm, `yarn.lock` → yarn, a `bun`
   lockfile → bun;
3. else npm.

In a **workspace** (a monorepo), a member package has no lockfile of its own, so
Editora looks upward to the workspace root that has the `packageManager` field,
the lockfile or a `pnpm-workspace.yaml`, and stops at the project root. A member
of a pnpm workspace therefore runs pnpm, not npm, and is never offered
`npm install`. `install` from a member runs in the member's folder; pnpm, yarn
and npm 7 or newer find the workspace root themselves. Run configurations of
the npm type use the same manager.

Its commands are **Package Scripts: Show Actions** (`npm.showActions`),
**Package Scripts: Run Custom Task(s)…** (`npm.runCustom`), **Package Scripts:
Stop** (`npm.stop`), **Package Scripts: Rerun Last** (`npm.rerunLast`) and
**Package Scripts: Refresh package.json** (`npm.refresh`). **View: Toggle
Package Scripts** (`view.toggleNpmSupport`) turns the window, its actions popup
and its Output tab on or off.

The **Node.js** card in **Settings → Build Tools**, just
above the npm card, has the **Node.js** field: the Node.js for Run, npm scripts,
Debug, the language servers and HTTP scripts. Left blank, Editora uses `node`
on your `PATH`. A project's `.nvmrc` or `.node-version` wins over the field
when nvm has that version (see
[Which Node.js runs](/docs/javascript-typescript#running)). The card can be edited
while JavaScript debugging is turned off.

The npm card on the same page names the manager
the active project uses and why, for example *pnpm (pnpm-lock.yaml)*. Its
**Command override** applies only when it names that same manager, or is a
wrapper script of your own; an override of `npm` does not make a pnpm or yarn
project run npm.

The window always shows the project's own `package.json`: opening a file inside
`node_modules` does not switch it to that dependency's scripts.

![package.json open beside the Package Scripts tool window: Build root npm, a filter field, the Scripts section with start, build, test and lint, and a Common section with install and ci](/screenshots/docs/package-scripts.png "Package Scripts lists the scripts of the project's package.json, plus install and ci; double-click runs one.")

**When something is missing,** the Output console says how to fix it instead of
showing an error from Java:

- a missing npm says to install Node.js (npm comes with it);
- a missing pnpm or yarn says which file asked for it, and to run
  `corepack enable` (corepack comes with Node.js) or install it;
- a missing bun says to install it from bun.sh;
- a script that fails while the package has no `node_modules` suggests running
  `<manager> install` first;
- a `package.json` that can't be read says where the mistake is.

Runs stream to the **Package Scripts** tab of the Output window, which shows
the colours the script prints and the Node.js it runs under, such as
**Node.js 24.18.1**. The tab, and the Run console of an npm script
configuration, start with the command line that runs, and the status bar names
the manager that ran, for example *pnpm finished successfully*.

Scripts use the same Node.js as [Run](/docs/javascript-typescript#running): that
Node.js's folder goes first on the script's `PATH`, and the npm beside it runs.
So the `node` that a script's own commands call is the same Node.js too. Stop
first sends Ctrl+C (SIGINT), so the script and its child processes can clean
up.

## Test runner

Running a build tool's **test** task, from the tasks tree, the palette
(`test.run`), or a gutter icon, is intercepted and shown in a dedicated **Test
Results** tool window (`M-g e` in the Emacs keymap) instead of only as raw console text. The same
single process still runs, and its raw output still streams to the Output console.

The window shows a status header with pass / fail / skip counts, a progress bar,
and elapsed time, over a tree of suites and tests. Selecting a test shows its
failure message, stack trace, and captured output in the detail pane, with
**clickable stack frames**. Double-click or `Enter` on a test jumps to its
source. A right-click on a test row offers **Go to Test**, **Rerun This Test**
and **Debug This Test**; on a class row, **Go to Test Class**, **Rerun This
Class** and **Debug This Class**. The Debug entry appears where the Java
debugger is available. The same rows run from the keyboard on the selected test:
`test.goToSelected`, `test.rerunSelected` and `test.debugSelected`. The
filter buttons are `test.togglePassed`, `test.toggleFailed` and
`test.toggleSkipped`, `test.toggleFollow` keeps the newest result in view, and
`test.findInOutput` searches the selected test's output. A test's output, failure message and stack trace are
kept up to 200,000 characters; past that the start and the end are shown, with
a line between them saying how much was left out.

Results are parsed per tool: Maven and Gradle read the JUnit XML reports as they
are written (a Surefire or Failsafe `<reportsDirectory>` of the pom's own, or
of its parent's, is read too), Go uses `go test -json`, npm reads TAP when the
reporter emits it, and Cargo parses libtest output. For `node --test`, Test Results also reads
node:test's own default output, so `"test": "node --test"` fills the tree with no
reporter flag. In TAP output a `describe` block is a group, not a failed test,
and a failure shows its error and stack.

A test file that *Run File* or *Run Test at Caret* runs under its own runner
fills Test Results too, while the Run console shows the runner's usual report:

| Runner | How Test Results reads it |
| --- | --- |
| Jest | `--json --outputFile`, when the run ends |
| Vitest | its `json` reporter beside the default one, when the run ends |
| Deno | `deno test --junit-path`, when the run ends |
| RSpec | `--format json --out` beside the console format your `.rspec` names (else `progress`), when the run ends |
| Minitest, `bin/rails test` | the verbose console (`-v`), test by test |
| PHPUnit | `--log-junit`, when the run ends |
| ExUnit | `mix test --trace`, test by test |
| `dart test`, `flutter test` | `--file-reporter json:`, when the run ends |
| GoogleTest | `--gtest_output=xml:`, when the run ends |
| Catch2 | its XML reporter beside the console one (Catch2 2: the XML reporter only), when the run ends |
| doctest | its XML reporter, when the run ends (the console says little) |

A row opens its test where it failed, else where it is declared. **Rerun
Failed** and **Rerun This Test** narrow the file's run with the runner's own
filter: `-t` for Jest and Vitest, `--filter` for Deno and PHPUnit, `--example`
for RSpec, `-n` for Minitest, `--only test:` for ExUnit, `--name` for Dart,
`--gtest_filter` for GoogleTest, a test spec for Catch2 and `--test-case` for
doctest. A C or C++ test program is built again before them
([C & C++](/docs/c-cpp#tests)).
*Run File* on a Dart test file runs `dart test` (`flutter test` in a
Flutter package). sbt, Mill and Scala CLI tests are not
read yet: their output stays in the Output console. Where a structured format isn't available, the
window says so and points at the Output console.

For an unfiltered JVM run, Editora scans the project's test sources up front and
**pre-seeds the whole expected list greyed-out**, flipping each entry green or
red as results land, so a long run reads like a progress bar rather than classes
appearing already finished. Parameterized and dynamic tests are left out of the
seed, since their case ids are generated at runtime.

![The Test Results tool window after a Maven run: 9 of 9 tests passed with a full green bar and 2.0 s, a filter field, the tree of ShelfTest's methods each ticked with its time, and the run summary on the right: 9 passed, 0 failed, 0 skipped](/screenshots/docs/test-results.png "Test Results after a Maven test run: counts in the header, each test in the tree.")

A test that Surefire re-ran (`rerunFailingTestsCount`) shows its retries: a
test that passed on a retry is marked **flaky ×n**, one that failed every try
**retried ×n**, and the detail pane holds the trace of each try. A filter that
matches nothing, such as a hand-typed `-Dtest=` in *Run custom…*, reads
*No test matched the filter — nothing was run* rather than a failed build.

JUnit test classes and methods also get a **run icon in the gutter** (with a
tooltip naming what it runs), and a test file's right-click menu offers **Run
Tests** for the whole class. `test.runAtCaret` and `test.runClassAtCaret` do the
same from the keyboard (`test.debugClassAtCaret` debugs the class), and `test.rerun`, `test.rerunFailed`, and `test.stop`
control the run. These JUnit gutter icons need a detected Maven or Gradle
project. Kotlin and Groovy test files get them too: a JUnit test in Kotlin can
have a backtick name (``fun `adds a book`()``), a Spock specification's feature
methods (`def "adds a book"()`) are tests, and a Kotest spec (`StringSpec`,
`FunSpec`, `DescribeSpec` and the other spec styles) runs as a whole class (see
[Kotlin tests](/docs/kotlin#tests) and [Groovy tests](/docs/groovy#tests)).
Python test files get their own, one per test (see
[Python tests](#python-tests)). A JavaScript or TypeScript test file has one ▶ for
the whole file, which runs it under Jest, Vitest, Deno or `node --test` (see
[JavaScript and TypeScript tests](/docs/javascript-typescript#tests)); its
results can be rerun one test at a time or failed-only.

**Run Test at Caret** (`test.runAtCaret`) also runs one test in the other test
files Editora debugs at the caret, without the debugger: it runs the file as
*Run File* does, narrowed to the test the caret is in (the whole file when the
caret is in none). That is a Go test (`-test.run`), a Rust `#[test]` (its path,
exactly), a Dart `test` or `group` (`dart test --plain-name`), a `node:test`
test (`--test-name-pattern`, into Test Results), a Deno test, a Jest or Vitest
test, an RSpec example (`rspec file:line`), a Minitest test (`bin/rails test
file:line` in a Rails app, else `-n`), a PHPUnit test (`--filter`), an ExUnit
test (`mix test file:line`) and a C# test (`dotnet test --filter`). Its output
goes where *Run File*'s does: the Run console, and Test Results for every one
of these runners but Go, Rust and C#.

Tests in **nested classes** have their own icons: a method inside a JUnit
`@Nested` class, or inside a static nested test class, gets a ▶, and so does
the nested class itself. Run and Debug address the nested class as
`Outer$Inner`, so only that class's tests run. A class whose tests are all in
nested classes is marked the same way.

**Debug Test at Caret** (`test.debugAtCaret`) and the test-results context menu
launch the selected JUnit method or class through Maven or Gradle with its JVM
suspended, then attach Editora's Java debugger automatically. This needs the
same Java debug adapter as ordinary Java debugging. On Maven, Editora picks a
free loopback port before the launch, passes it in `-Dmaven.surefire.debug`,
and attaches as soon as the forked JVM listens on it, because Surefire 3 prints
the JDWP "Listening for transport" banner only after the test has run. While it
waits, the Test Results header reads *Debug: waiting for the test JVM to open
port N…*, and **Debug: Stop** (`debug.stop`) ends the Maven run before a session exists. After
60 s with no listener the status says so and points at the Output window. A
further fork of the same run (several forks, reruns) is attached after the
first has finished; forks are debugged one at a time. The test JVM prints one
"Debugger failed to attach: handshake failed" line in the Output console: that
is Editora's port check, not an error. Gradle keeps its own banner. In a JavaScript or TypeScript test file,
Debug Test at Caret debugs the test at the caret under its runner (see
[Debugging JavaScript and TypeScript](/docs/javascript-typescript#debugging)). In a Go test file it debugs the
test function at the caret under Delve, and in a Rust `#[test]` function that
test alone under CodeLLDB (see [Debugging Go](/docs/go#debugging) and
[Debugging Rust](/docs/rust#debugging)).

### Python tests

Python tests need no build tool. A test file (`test_*.py` or `*_test.py`) gets
a ▶ on every test pytest would collect: each `test` function, each class named
`Test…` or deriving from `unittest.TestCase`, and each of its `test` methods.
The first line gets one more ▶, **Run the tests in this file**. Run File
(`file.run`) on a test file runs its tests instead of the file. The right-click
menu offers **Run This Test** or **Run Tests**, and **Run Test at Caret**
(`test.runAtCaret`) and **Run Test Class at Caret** (`test.runClassAtCaret`)
work from the keyboard. **Run Tests** (`test.run`) runs every test of the
project when a Python file is in front, or when no build tool is found and the
project root has a `pyproject.toml`, `setup.py`, `setup.cfg`, `pytest.ini`,
`tox.ini` or `conftest.py`.

The tests run with the same Python as Run (see
[Python scripts](/docs/python#running)), with the project root and its
`src/` folder on `PYTHONPATH`:

- **With pytest** installed for that Python, Editora runs `python -m pytest`
  from the project root (the file's folder when there is no project) and reads
  its JUnit XML report into Test Results. Failure locations such as
  `tests/test_cart.py:14` are clickable.
- **Without pytest**, tests written with `unittest.TestCase` run with Python's
  own `python -m unittest`, from the test file's folder (a whole-project run
  from the project's `tests` folder, else its root), and the status line says
  so. Their results fill Test Results as they are printed.
- **Otherwise** the status line names the Python and how to install pytest
  there, for example `.venv/bin/python -m pip install pytest`.

The command's output streams to the **Run** console, which starts with the
command line; Stop there or in Test Results stops the tests. **Rerun Failed
Tests** (`test.rerunFailed`) runs only the tests that failed, and
**Rerun This Test**, **Go to Test** and **Rerun Tests** (`test.rerun`) work as
for JUnit; **Rerun Last** in the Run window repeats the test run too.
**Debug This Test** and Debug Test at Caret are described under
[Debugging](/docs/debugging). With the test runner turned off, Python
tests still run, in the Run console only.

Toggle the whole feature under **Settings → Build Tools**
(`view.toggleTestRunner`). It's off in [Simple UI mode](/docs/workspace).
