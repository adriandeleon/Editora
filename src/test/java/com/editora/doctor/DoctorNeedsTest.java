package com.editora.doctor;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoctorNeedsTest {

    @Test
    void aProjectRootsMarkerFilesCallForItsLanguagesServers() {
        assertEquals(
                Set.of("java", "maven-pom", "xml"), DoctorNeeds.serversForRootFiles(List.of("pom.xml", "README.md")));
        assertEquals(Set.of("go"), DoctorNeeds.serversForRootFiles(List.of("go.mod")));
        assertEquals(Set.of("rust", "toml"), DoctorNeeds.serversForRootFiles(List.of("Cargo.toml")));
        assertEquals(Set.of("java", "kotlin"), DoctorNeeds.serversForRootFiles(List.of("build.gradle.kts")));
        assertEquals(Set.of("typescript", "json"), DoctorNeeds.serversForRootFiles(List.of("package.json")));
        assertEquals(Set.of("csharp"), DoctorNeeds.serversForRootFiles(List.of("App.csproj")));
        assertEquals(Set.of("terraform"), DoctorNeeds.serversForRootFiles(List.of("main.tf")));
        assertEquals(Set.of("astro"), DoctorNeeds.serversForRootFiles(List.of("astro.config.mjs")));
        assertEquals(Set.of("dockerfile"), DoctorNeeds.serversForRootFiles(List.of("Dockerfile")));
        assertTrue(
                DoctorNeeds.serversForRootFiles(List.of("notes.md", "LICENSE")).isEmpty());
        assertTrue(DoctorNeeds.serversForRootFiles(null).isEmpty());
    }

    @Test
    void pythonProjectsAreRecognised() {
        assertTrue(DoctorNeeds.pythonProject(List.of("pyproject.toml")));
        assertTrue(DoctorNeeds.pythonProject(List.of("requirements.txt")));
        assertFalse(DoctorNeeds.pythonProject(List.of("pom.xml")));
    }

    @Test
    void aServerIsNeededWhenOpenCalledForOrConfigured() {
        Set<String> open = Set.of("java");
        Set<String> root = Set.of("go");
        assertTrue(DoctorNeeds.serverNeeded("java", open, root, false));
        assertTrue(DoctorNeeds.serverNeeded("go", open, root, false));
        assertTrue(DoctorNeeds.serverNeeded("lua", open, root, true));
        assertFalse(DoctorNeeds.serverNeeded("lua", open, root, false), "nothing asks for Lua: not a problem");
    }
}
