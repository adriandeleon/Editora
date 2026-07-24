package com.editora.build;

import com.editora.test.TestDebug;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildDebugTest {

    @Test
    void gradleDebugArgsAppendsDebugJvm() {
        assertEquals(java.util.List.of("bootRun", "--debug-jvm"), BuildDebug.gradleDebugArgs("bootRun"));
        assertEquals(java.util.List.of("run", "--debug-jvm"), BuildDebug.gradleDebugArgs("run"));
    }

    @Test
    void mavenSpringBootArgsCarryJdwp() {
        var args = BuildDebug.mavenSpringBootDebugArgs();
        assertEquals("spring-boot:run", args.get(0));
        assertTrue(args.get(1).startsWith("-Dspring-boot.run.jvmArguments="));
        assertTrue(args.get(1).contains("transport=dt_socket"));
        assertTrue(args.get(1).contains("suspend=y"));
    }

    @Test
    void springBootMavenPomSniff() {
        assertTrue(
                BuildDebug.isSpringBootMavenPom("<plugin><artifactId>spring-boot-maven-plugin</artifactId></plugin>"));
        assertFalse(BuildDebug.isSpringBootMavenPom("<project/>"));
        assertFalse(BuildDebug.isSpringBootMavenPom(null));
    }

    @Test
    void jdwpAgentAddressIsParseableByTestDebug() {
        // The banner Gradle/Maven print for our agent must be parsed back to the port by the shared parser.
        int port = TestDebug.jdwpPort("Listening for transport dt_socket at address: 5005");
        assertEquals(5005, port);
    }
}
