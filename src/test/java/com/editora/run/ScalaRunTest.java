package com.editora.run;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScalaRunTest {

    private static final Path P = Path.of("/work/app").toAbsolutePath();

    private static ScalaRun.Owner owner(Path file, Path top, Path... files) {
        Set<Path> present = Set.of(files);
        return ScalaRun.ownerOf(file, top, present::contains);
    }

    @Test
    void entryPointsAreReadInEveryStyleWithTheirPackage() {
        String text = """
                package demo
                package cli

                @main def hello(name: String): Unit =
                  println(name)

                object Legacy extends App {
                  println("legacy")
                }

                object Tool:
                  def helper(): Int = 1
                  def main(args: Array[String]): Unit = ()

                class NotAProgram
                """;
        assertEquals(
                List.of(
                        new ScalaRun.Main(3, "demo.cli.hello"),
                        new ScalaRun.Main(6, "demo.cli.Legacy"),
                        new ScalaRun.Main(10, "demo.cli.Tool")),
                ScalaRun.mains(text));
        assertEquals("demo.cli.hello", ScalaRun.mainClass(text));
        assertEquals("run", ScalaRun.mainClass("@main def `run`() = ()"));
        assertNull(ScalaRun.mainClass("object Util:\n  def twice(n: Int) = n * 2\n"));
    }

    @Test
    void runLineIsTheFirstMainElseTheTopOfAScriptElseNone() {
        assertEquals(2, ScalaRun.runLine("Main.scala", "package a\n\n@main def go() = ()\n"));
        assertEquals(-1, ScalaRun.runLine("Util.scala", "object Util\n"), "a library file is not a program");
        assertEquals(0, ScalaRun.runLine("hello.sc", "println(1)\n"), "a script runs from the top");
        assertEquals(-1, ScalaRun.runLine("build.sbt", "lazy val root = project\n"));
        assertEquals(-1, ScalaRun.runLine("build.mill", "object foo extends ScalaModule\n"));
        assertEquals(-1, ScalaRun.runLine("build.sc", "import mill._\n"), "a Mill 0.x build script");
    }

    @Test
    void sbtsRootIsTheHighestBuildSbtAndOtherBuildsAreTheNearest() {
        Path file = P.resolve("core/src/main/scala/demo/Util.scala");
        assertEquals(
                new ScalaRun.Owner(ScalaRun.Build.SBT, P),
                owner(file, null, P.resolve("build.sbt"), P.resolve("core/build.sbt")),
                "a subproject's own build.sbt does not make it the root");
        assertEquals(new ScalaRun.Owner(ScalaRun.Build.MILL, P), owner(file, P, P.resolve("build.mill")));
        assertEquals(new ScalaRun.Owner(ScalaRun.Build.MILL, P), owner(file, P, P.resolve("build.sc")));
        assertEquals(
                new ScalaRun.Owner(ScalaRun.Build.SCALA_CLI, P.resolve("core")),
                owner(file, P, P.resolve("core/project.scala"), P.resolve("build.mill")),
                "the nearest build wins");
        assertEquals(new ScalaRun.Owner(ScalaRun.Build.OTHER, P), owner(file, P, P.resolve("build.gradle.kts")));
        assertEquals(new ScalaRun.Owner(ScalaRun.Build.NONE, null), owner(file, P));
        assertEquals(
                new ScalaRun.Owner(ScalaRun.Build.NONE, null),
                owner(file, P.resolve("core"), P.resolve("build.sbt")),
                "nothing above the project is looked at");
    }

    @Test
    void scalaCliRunsTheFileOrItsProjectFolderWithTheMainClass() {
        ScalaRun.Owner project = new ScalaRun.Owner(ScalaRun.Build.SCALA_CLI, P);
        assertEquals(
                List.of("--main-class", "demo.hello", "/work/app"),
                ScalaRun.cliInputs("/work/app/Main.scala", project, "/work/app", "demo.hello"));
        assertEquals(List.of("/work/app"), ScalaRun.cliInputs("/work/app/Main.scala", project, "/work/app", null));
        assertEquals(
                List.of("/tmp/hello.sc"),
                ScalaRun.cliInputs("/tmp/hello.sc", new ScalaRun.Owner(ScalaRun.Build.NONE, null), null, null));
    }

    @Test
    void sbtRunsTheMainClassAsOneBatchCommand() {
        assertEquals(List.of("runMain demo.hello"), ScalaRun.sbtRunTask("demo.hello", List.of()));
        assertEquals(
                List.of("runMain demo.hello a \"b c\" \"say \\\"hi\\\"\""),
                ScalaRun.sbtRunTask("demo.hello", List.of("a", "b c", "say \"hi\"")));
        assertEquals(List.of("run x"), ScalaRun.sbtRunTask(null, List.of("x")));
    }

    @Test
    void millRunsTheModulesMainClass() {
        assertEquals(
                List.of("core.runMain", "demo.hello", "a"), ScalaRun.millRunTask("core", "demo.hello", List.of("a")));
        assertEquals(List.of("runMain", "demo.hello"), ScalaRun.millRunTask("", "demo.hello", List.of()));
        assertEquals(List.of("core.run"), ScalaRun.millRunTask("core", null, List.of()));
        assertEquals("core", ScalaRun.millModule(P, P.resolve("core/src/demo/Main.scala")));
        assertEquals("core.test", ScalaRun.millModule(P, P.resolve("core/test/src/demo/MainTest.scala")));
        assertEquals("", ScalaRun.millModule(P, P.resolve("src/Main.scala")));
        assertEquals("", ScalaRun.millModule(P, P.resolve("scripts/Main.scala")), "outside a module's src");
    }
}
