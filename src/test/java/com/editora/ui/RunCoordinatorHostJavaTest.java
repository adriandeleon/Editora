package com.editora.ui;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The {@code java} and environment a Java project's program gets on a remote host — never this machine's. */
class RunCoordinatorHostJavaTest {

    @Test
    void theHostsJdkWinsThenWhatJdtlsResolvedThereThenItsPath() {
        assertEquals("/opt/jdk-21/bin/java", RunCoordinator.hostJava("/opt/jdk-21/", "/usr/lib/jvm/17/bin/java"));
        assertEquals("/usr/lib/jvm/17/bin/java", RunCoordinator.hostJava("", "/usr/lib/jvm/17/bin/java"));
        assertEquals("java", RunCoordinator.hostJava(null, null));
    }

    @Test
    void onAHostOnlyItsJdkIsNamedInTheEnvironment() {
        assertEquals(Map.of("JAVA_HOME", "/opt/jdk-21"), RunCoordinator.remoteJdkEnvironment("/opt/jdk-21"));
        assertEquals(Map.of(), RunCoordinator.remoteJdkEnvironment(""), "no PATH of this machine, no JAVA_HOME");
    }
}
