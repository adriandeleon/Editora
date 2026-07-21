package com.editora.test;

import java.util.List;

import com.editora.build.BuildTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Debug-a-test argv (suspend flags) + recognizing the suspended JVM's JDWP banner. */
class TestDebugTest {

    @Test
    void debugTaskArgsAddTheSuspendFlagPerTool() {
        List<String> maven = TestDebug.debugTaskArgs(BuildTool.MAVEN, "com.x.FooTest", "bar");
        assertTrue(maven.contains("-Dtest=FooTest#bar"));
        assertTrue(maven.contains("-Dmaven.surefire.debug"));

        List<String> gradle = TestDebug.debugTaskArgs(BuildTool.GRADLE, "com.x.FooTest", "bar");
        assertEquals(List.of("test", "--tests", "com.x.FooTest.bar", "--debug-jvm"), gradle);

        // A null method debugs the whole class.
        assertTrue(
                TestDebug.debugTaskArgs(BuildTool.MAVEN, "com.x.FooTest", null).contains("-Dtest=FooTest"));

        // Non-JVM tools have no supported suspend-and-wait flag.
        assertTrue(TestDebug.debugTaskArgs(BuildTool.GO, "pkg", "TestA").isEmpty());
        assertTrue(TestDebug.debugTaskArgs(BuildTool.NPM, "x", "y").isEmpty());
    }

    @Test
    void jdwpPortParsing() {
        assertEquals(5005, TestDebug.jdwpPort("Listening for transport dt_socket at address: 5005"));
        assertEquals(5005, TestDebug.jdwpPort("[INFO] Listening for transport dt_socket at address: 5005"));
        // Newer JVMs print a host-qualified address.
        assertEquals(5005, TestDebug.jdwpPort("Listening for transport dt_socket at address: *:5005"));
        assertEquals(61234, TestDebug.jdwpPort("Listening for transport dt_socket at address: localhost:61234"));
        // Non-matching lines.
        assertEquals(-1, TestDebug.jdwpPort("Tests run: 5, Failures: 0"));
        assertEquals(-1, TestDebug.jdwpPort(""));
        assertEquals(-1, TestDebug.jdwpPort(null));
    }
}
