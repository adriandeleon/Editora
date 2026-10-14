package com.editora.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A project's root may be a folder on a remote host: it is stored with its host and is a path only while connected. */
class RemoteProjectRootTest {

    @Test
    void aLocalRootIsStoredAndReadAsItAlwaysWas(@TempDir Path dir) {
        ProjectManager pm = new ProjectManager(dir.resolve("config"));
        Path root = dir.resolve("app");

        Project project = pm.createOrGet("app", root);

        assertEquals(root.toAbsolutePath().normalize().toString(), project.root());
        assertEquals(root.toAbsolutePath().normalize(), project.rootPath());
        assertFalse(project.remote());
        assertSame(project, pm.find(root));
        assertTrue(project.contains(root.resolve("src/A.java")));
        assertFalse(project.contains(dir.resolve("app2/A.java")), "a sibling that merely starts with the same text");
    }

    @Test
    void aRemoteRootIsStoredWithItsHost(@TempDir Path dir) throws IOException {
        ProjectManager pm = new ProjectManager(dir.resolve("config"));
        try (FakeRemoteHost host = new FakeRemoteHost(dir, "ana@build:22")) {
            Path root = host.path("/srv/app");

            Project project = pm.createOrGet("app", root);

            assertEquals("sftp://ana@build:22/srv/app", project.root());
            assertTrue(project.remote());
            assertEquals(root, project.rootPath());
            assertSame(project, pm.find(root), "the same folder is the same project");
            assertTrue(project.contains(host.path("/srv/app/src/A.java")));
            assertFalse(
                    project.contains(Path.of("/srv/app/src/A.java")), "the local file of the same name is not in it");
        }
    }

    @Test
    void theSameFolderOnTwoHostsOrHereIsThreeProjects(@TempDir Path dir) throws IOException {
        ProjectManager pm = new ProjectManager(dir.resolve("config"));
        try (FakeRemoteHost one = new FakeRemoteHost(dir, "ana@one:22");
                FakeRemoteHost two = new FakeRemoteHost(dir, "ana@two:22")) {
            Project onOne = pm.createOrGet("app", one.path("/srv/app"));
            Project onTwo = pm.createOrGet("app", two.path("/srv/app"));
            Project here = pm.createOrGet("app", Path.of("/srv/app"));

            assertNotEquals(onOne.id(), onTwo.id());
            assertNotEquals(onOne.id(), here.id());
            assertEquals(3, pm.list().size());
            assertFalse(here.contains(one.path("/srv/app/A.java")));
        }
    }

    @Test
    void aRemoteRootHasNoPathWhileItsHostIsNotConnected(@TempDir Path dir) throws IOException {
        Project project;
        try (FakeRemoteHost host = new FakeRemoteHost(dir, "ana@build:22")) {
            project = new ProjectManager(dir.resolve("config")).createOrGet("app", host.path("/srv/app"));
        }

        assertNull(project.rootPath(), "never a made-up local path");
        assertTrue(project.remote());
        assertFalse(project.contains(Path.of("/srv/app/A.java")));
        assertNull(new Project("x", "x", "").rootPath(), "and a project with no root has none either");
    }

    @Test
    void noCodeTurnsAStoredRootIntoAPathByHand() throws IOException {
        // Path.of(project.root()) is right for a local root and silently wrong for a remote one: on a POSIX
        // system "sftp://host/srv/app" parses as a relative local path. Project.rootPath() is the one way.
        Pattern byHand = Pattern.compile("Path\\s*\\.\\s*of\\s*\\(\\s*[\\w.()]*\\broot\\(\\)\\s*\\)");
        Path main = Path.of("src/main/java/com/editora");
        assertTrue(Files.isDirectory(main), "Run from the Maven project root");
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file)
                        .replaceAll("(?s)/\\*.*?\\*/", " ")
                        .replaceAll("(?m)//.*$", " ");
                assertFalse(byHand.matcher(source).find(), file + " builds a path from a project's stored root");
            }
        }
    }
}
