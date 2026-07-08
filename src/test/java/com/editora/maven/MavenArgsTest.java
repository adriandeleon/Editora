package com.editora.maven;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MavenArgsTest {

    @Test
    void buildsPlainGoalsWithNoProfileFlag() {
        assertEquals(List.of("clean", "install"), MavenArgs.build(List.of("clean", "install"), List.of()));
    }

    @Test
    void appendsSingleDashPFlagForOneProfile() {
        assertEquals(List.of("compile", "-Pdist"), MavenArgs.build(List.of("compile"), List.of("dist")));
    }

    @Test
    void joinsMultipleProfilesCommaSeparated() {
        assertEquals(List.of("verify", "-Pdist,fatjar"), MavenArgs.build(List.of("verify"), List.of("dist", "fatjar")));
    }

    @Test
    void preservesGoalOrder() {
        assertEquals(List.of("spotless:check"), MavenArgs.build(List.of("spotless:check"), null));
    }
}
