package com.editora.maven;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MavenPluginPrefixTest {

    @Test
    void derivesFromMavenDashNameDashPlugin() {
        assertEquals("compiler", MavenPluginPrefix.derive("org.apache.maven.plugins", "maven-compiler-plugin"));
        assertEquals("surefire", MavenPluginPrefix.derive("org.apache.maven.plugins", "maven-surefire-plugin"));
        assertEquals("antrun", MavenPluginPrefix.derive("org.apache.maven.plugins", "maven-antrun-plugin"));
    }

    @Test
    void derivesFromNameDashMavenDashPlugin() {
        assertEquals("spotless", MavenPluginPrefix.derive("com.diffplug.spotless", "spotless-maven-plugin"));
        assertEquals("jacoco", MavenPluginPrefix.derive("org.jacoco", "jacoco-maven-plugin"));
        assertEquals("moditect", MavenPluginPrefix.derive("org.moditect", "moditect-maven-plugin"));
        assertEquals("exec", MavenPluginPrefix.derive("org.codehaus.mojo", "exec-maven-plugin"));
        assertEquals("javafx", MavenPluginPrefix.derive("org.openjfx", "javafx-maven-plugin"));
        assertEquals("jpackage", MavenPluginPrefix.derive("org.panteleyev", "jpackage-maven-plugin"));
    }

    @Test
    void fallsBackToArtifactIdWhenNoConventionMatches() {
        assertEquals("custom-tool", MavenPluginPrefix.derive("com.example", "custom-tool"));
    }
}
