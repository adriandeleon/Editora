package com.editora.test;

import java.util.List;

import com.editora.build.BuildTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Debug-a-test argv (suspend flags) + recognizing the suspended JVM's JDWP banner. */
class TestDebugTest {

    @Test
    void debugTaskArgsAddTheSuspendFlagPerTool() {
        List<String> maven = TestDebug.debugTaskArgs(BuildTool.MAVEN, "com.x.FooTest", "bar", null, 41234);
        assertTrue(maven.contains("-Dtest=FooTest#bar"));
        // A loopback port Editora chose up front (review D1): surefire's default is a fixed 5005 (review A15),
        // and a system-picked port is only announced in a banner Surefire 3 prints after the test ran.
        assertEquals(
                "-Dmaven.surefire.debug=-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:41234",
                maven.get(maven.size() - 1));
        assertEquals(maven.get(maven.size() - 1), TestDebug.mavenDebugArg(41234));

        List<String> gradle = TestDebug.debugTaskArgs(BuildTool.GRADLE, "com.x.FooTest", "bar", null, 0);
        assertEquals(List.of("test", "--tests", "com.x.FooTest.bar", "--debug-jvm"), gradle);
        java.nio.file.Path init = java.nio.file.Path.of("/tmp/editora-debug-1.gradle");
        assertEquals(
                List.of("test", "--tests", "com.x.FooTest.bar", "--init-script", init.toString()),
                TestDebug.debugTaskArgs(BuildTool.GRADLE, "com.x.FooTest", "bar", init, 0),
                "an init script: each test JVM on a port the system picks, not --debug-jvm's 5005");

        // A null method debugs the whole class.
        assertTrue(TestDebug.debugTaskArgs(BuildTool.MAVEN, "com.x.FooTest", null, null, 5005)
                .contains("-Dtest=FooTest"));

        // Non-JVM tools have no supported suspend-and-wait flag.
        assertTrue(
                TestDebug.debugTaskArgs(BuildTool.GO, "pkg", "TestA", null, 0).isEmpty());
        assertTrue(TestDebug.debugTaskArgs(BuildTool.NPM, "x", "y", null, 0).isEmpty());
    }

    @Test
    void surefiresOwnBannerMarksTheStartOfTheTests() {
        assertTrue(TestDebug.isSurefireTestsBanner("[INFO]  T E S T S"));
        assertTrue(TestDebug.isSurefireTestsBanner(" T E S T S"));
        assertFalse(TestDebug.isSurefireTestsBanner("[INFO] Tests run: 1, Failures: 0"));
        assertFalse(TestDebug.isSurefireTestsBanner(null));
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
