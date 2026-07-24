package com.editora.build;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringBootTest {

    @Test
    void detectsPluginsDslId() {
        String g = "plugins {\n  id 'org.springframework.boot' version '3.2.0'\n  id 'java'\n}\n";
        assertTrue(SpringBoot.isSpringBootGradle(g));
        assertEquals("bootRun", SpringBoot.gradleRunTask(g));
    }

    @Test
    void detectsLegacyPluginArtifact() {
        assertTrue(
                SpringBoot.isSpringBootGradle("classpath 'org.springframework.boot:spring-boot-gradle-plugin:2.7.0'"));
    }

    @Test
    void plainApplicationProjectRunsRun() {
        String g = "plugins {\n  id 'application'\n}\napplication {\n  mainClass = 'com.app.Main'\n}\n";
        assertFalse(SpringBoot.isSpringBootGradle(g));
        assertEquals("run", SpringBoot.gradleRunTask(g));
    }

    @Test
    void nullSafe() {
        assertFalse(SpringBoot.isSpringBootGradle(null));
        assertEquals("run", SpringBoot.gradleRunTask(null));
    }
}
