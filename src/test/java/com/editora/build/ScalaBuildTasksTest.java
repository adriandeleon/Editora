package com.editora.build;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScalaBuildTasksTest {

    /** {@code sbt -batch -no-colors projects} from sbt 2.0.11 on a two-project build (paths shortened). */
    private static final String SBT_PROJECTS = """
            [info] welcome to sbt 2.0.11 (Eclipse Adoptium Java 25.0.4)
            [info] loading project definition from /work/app/project
            [info] set current project to root (in build file:/work/app/)
            [info] In file:/work/app/
            [info] \t   core
            [info] \t * root
            """;

    @Test
    void sbtProjectsAreTheListingAfterInFile() {
        assertEquals(List.of("core", "root"), ScalaBuildTasks.sbtProjects(SBT_PROJECTS));
        assertEquals(
                List.of("api"),
                ScalaBuildTasks.sbtProjects(
                        "\u001B[0m[\u001B[0m\u001B[0minfo\u001B[0m] In file:/w/\n[info] \t * api\n"),
                "colour escapes are ignored");
        assertEquals(List.of(), ScalaBuildTasks.sbtProjects("[info] root\n"), "nothing before the listing");
        assertEquals(List.of(), ScalaBuildTasks.sbtProjects(null));
    }

    /** {@code ./mill resolve __.compile} from Mill 1.3.0 on a build with two modules and a test module. */
    @Test
    void millModulesAreThoseWithACompileTask() {
        assertEquals(
                List.of("app", "app.test", "core"),
                ScalaBuildTasks.millModules("app.compile\napp.test.compile\ncore.compile\nclean\n1] resolve\n"));
    }

    @Test
    void eachProjectOrModuleGetsItsCompileTestAndRun() {
        assertEquals(List.of("core/compile", "core/test", "core/run"), ScalaBuildTasks.sbtTasks(List.of("core")));
        assertEquals(
                List.of("app.compile", "app.run", "app.test"), ScalaBuildTasks.millTasks(List.of("app", "app.test")));
    }

    @Test
    void theProvidersOfferTheCommonTasksThenTheLoadedOnes() {
        ScalaActionsProvider sbt = ScalaActionsProvider.sbt();
        sbt.addLoadedTasks(List.of("core/compile", "compile", "core/compile"));
        List<BuildAction.Section> sections = sbt.sections(Set.of());
        assertEquals(2, sections.size());
        assertEquals(
                new BuildAction.Task("compile", List.of("compile")),
                sections.get(0).rows().get(0));
        assertEquals(
                List.of(new BuildAction.Task("core/compile", List.of("core/compile"))),
                sections.get(1).rows());

        BuildAction.Row millResolve =
                ScalaActionsProvider.mill().sections(Set.of()).get(0).rows().get(3);
        assertEquals(new BuildAction.Task("resolve _", List.of("resolve", "_")), millResolve);
        BuildAction.Row cliCompile =
                ScalaActionsProvider.scalaCli().sections(Set.of()).get(0).rows().get(0);
        assertEquals(new BuildAction.Task("compile", List.of("compile", ".")), cliCompile);
    }
}
