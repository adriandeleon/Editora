// Sample sbt build for exercising Editora's sbt support. sbt can't be read without running it, so the actions
// popup shows the static Common tasks (compile/test/run/clean/package/…) plus "Load all tasks…", which runs
// `sbt projects` and lists each subproject's compile/test/run (here `root` and `core`). Standalone: the repo's
// own build never reads it. Needs sbt on PATH; the first run fetches sbt, Scala and munit.
ThisBuild / scalaVersion := "3.10.1"
ThisBuild / version := "1.0.0"

// A subproject with the code and its tests; the root aggregates it, so `sbt test` runs them.
lazy val core = project
  .in(file("core"))
  .settings(libraryDependencies += "org.scalameta" %% "munit" % "1.3.6" % Test)

// The root: the program, which calls core.
lazy val root = project
  .in(file("."))
  .aggregate(core)
  .dependsOn(core)
  .settings(
    name := "sbt-demo",
    hello := println("Hello from the sbt sample project.")
  )

// A custom task: sbt can't list it without running, so type `hello` into Run custom… to run it.
lazy val hello = taskKey[Unit]("Prints a greeting.")
