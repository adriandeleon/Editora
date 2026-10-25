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
    void theJdwpAgentListensOnLoopbackOnly() {
        // JDWP has no authentication: "*:5005" (or a bare host-less address on JDK 8) is every interface.
        // And on a port the system picks — a fixed 5005 collided with any other debug run (review A15).
        assertTrue(BuildDebug.JDWP_AGENT.endsWith("address=127.0.0.1:0"), BuildDebug.JDWP_AGENT);
        assertFalse(BuildDebug.JDWP_AGENT.contains("*"));
    }

    /** The Boot plugin is read from the pom's build, not sniffed from its text (review D11). */
    @Test
    void springBootMavenPomIsAParseNotASniff() {
        assertTrue(BuildDebug.isSpringBootMavenPom("<project><build><plugins><plugin>"
                + "<artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build></project>"));
        assertFalse(BuildDebug.isSpringBootMavenPom("<project><!-- spring-boot-maven-plugin --></project>"));
        assertFalse(
                BuildDebug.isSpringBootMavenPom("<plugin><artifactId>spring-boot-maven-plugin</artifactId></plugin>"));
        assertFalse(BuildDebug.isSpringBootMavenPom("<project/>"));
        assertFalse(BuildDebug.isSpringBootMavenPom(null));
        assertTrue(BuildDebug.BANNER_TIMEOUT_SECONDS >= 30);
    }

    @Test
    void jdwpAgentAddressIsParseableByTestDebug() {
        // The banner Gradle/Maven print for our agent must be parsed back to the port by the shared parser.
        int port = TestDebug.jdwpPort("Listening for transport dt_socket at address: 5005");
        assertEquals(5005, port);
    }
}
