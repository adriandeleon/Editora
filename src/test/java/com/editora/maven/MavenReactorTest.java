package com.editora.maven;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenReactorTest {

    @Test
    void singleModuleIsItsOwnRoot() {
        Path project = Path.of("/repo/app");
        Predicate<Path> hasPom = Set.of(project)::contains; // only /repo/app has a pom
        assertEquals(project, MavenReactor.reactorRoot(project, hasPom));
    }

    @Test
    void walksUpContiguousPomChain() {
        Path reactor = Path.of("/repo");
        Path module = Path.of("/repo/services/api");
        Predicate<Path> hasPom = Set.of(reactor, Path.of("/repo/services"), module)::contains;
        assertEquals(reactor, MavenReactor.reactorRoot(module, hasPom));
    }

    @Test
    void stopsAtAGapInThePomChain() {
        // /repo has a pom but /repo/detached does NOT — so /repo/detached/mod's root is itself.
        Path module = Path.of("/repo/detached/mod");
        Predicate<Path> hasPom = Set.of(Path.of("/repo"), module)::contains; // gap at /repo/detached
        assertEquals(module, MavenReactor.reactorRoot(module, hasPom));
    }

    private static final String ROOT_POM = "<project><artifactId>root</artifactId><packaging>pom</packaging>"
            + "<modules><module>services/api</module><module>core</module></modules></project>";

    @Test
    void describeNamesTheRootAndSelectorOfAListedModule() {
        Path reactor = Path.of("/repo");
        Path module = Path.of("/repo/services/api");
        Predicate<Path> hasPom = Set.of(reactor, Path.of("/repo/services"), module)::contains;
        MavenReactor.Reactor r = MavenReactor.describe(module, null, hasPom, pom -> ROOT_POM);
        assertEquals(reactor, r.root());
        assertEquals(Path.of("/repo/pom.xml"), r.rootPom());
        assertEquals("services/api", r.moduleSelector());
        assertEquals("repo", r.rootName());
        assertEquals(
                List.of("-f", "/repo/pom.xml", "-pl", "services/api", "-am"),
                MavenReactor.reactorArgs(r.rootPom().toString(), r.moduleSelector()));
    }

    @Test
    void describeIsNullForATopLevelProjectAndForARootThatDoesNotListTheModule() {
        Path module = Path.of("/repo/samples/demo");
        Predicate<Path> hasPom = Set.of(Path.of("/repo"), Path.of("/repo/samples"), module)::contains;
        assertNull(MavenReactor.describe(Path.of("/alone"), null, Set.of(Path.of("/alone"))::contains, p -> ROOT_POM));
        // /repo's pom lists core and services/api, not samples/demo: Maven would say "not in the reactor".
        assertNull(MavenReactor.describe(module, null, hasPom, p -> ROOT_POM));
        assertNull(MavenReactor.describe(module, null, hasPom, p -> null), "an unreadable root pom offers nothing");
    }

    @Test
    void describeStopsAtTheOpenProject() {
        Path reactor = Path.of("/repo");
        Path module = Path.of("/repo/core");
        Predicate<Path> hasPom = Set.of(reactor, module)::contains;
        assertNull(MavenReactor.describe(module, module, hasPom, p -> ROOT_POM), "the project is the module itself");
        assertEquals(
                reactor,
                MavenReactor.describe(module, reactor, hasPom, p -> ROOT_POM).root());
    }

    @Test
    void listsModuleAcceptsTheSelectorItsFirstSegmentAndAPrefixAggregator() {
        assertTrue(MavenReactor.listsModule(List.of("core"), "core"));
        assertTrue(MavenReactor.listsModule(List.of("services"), "services/api"));
        assertTrue(MavenReactor.listsModule(List.of("./services/"), "services/api"));
        assertTrue(MavenReactor.listsModule(List.of("services\\api"), "services/api"));
        assertFalse(MavenReactor.listsModule(List.of("core"), "services/api"));
        assertFalse(MavenReactor.listsModule(List.of(), "core"));
    }

    @Test
    void moduleSelectorIsForwardSlashRelativePath() {
        assertEquals("services/api", MavenReactor.moduleSelector(Path.of("/repo"), Path.of("/repo/services/api")));
        assertEquals("mod", MavenReactor.moduleSelector(Path.of("/repo"), Path.of("/repo/mod")));
    }
}
