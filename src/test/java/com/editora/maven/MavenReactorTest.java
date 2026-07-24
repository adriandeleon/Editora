package com.editora.maven;

import java.nio.file.Path;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Test
    void moduleSelectorIsForwardSlashRelativePath() {
        assertEquals("services/api", MavenReactor.moduleSelector(Path.of("/repo"), Path.of("/repo/services/api")));
        assertEquals("mod", MavenReactor.moduleSelector(Path.of("/repo"), Path.of("/repo/mod")));
    }
}
