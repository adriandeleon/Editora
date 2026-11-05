package com.editora.dap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A Java file without a build tool: where its classes come from and which java runs it (review 2, A101, B102). */
class LooseJavaLaunchTest {

    @Test
    void jdtlsOutputIsLaunchedOnlyRightAfterItsBuild() {
        assertEquals(LooseJavaLaunch.Classes.JDTLS_OUTPUT, LooseJavaLaunch.classesFor(true, true));
        assertEquals(
                LooseJavaLaunch.Classes.COMPILE_HERE,
                LooseJavaLaunch.classesFor(false, true),
                "a class file from an earlier build is never trusted");
        assertEquals(LooseJavaLaunch.Classes.COMPILE_HERE, LooseJavaLaunch.classesFor(true, false));
    }

    @Test
    void theSettingsJdkWinsThenPathsJava() {
        assertEquals("/jdk21/bin/java", LooseJavaLaunch.launchJava("/jdk21/bin/java", "/usr/bin/java"));
        assertEquals("/usr/bin/java", LooseJavaLaunch.launchJava("", "/usr/bin/java"));
        assertEquals("", LooseJavaLaunch.launchJava("", "java"), "PATH resolution found none");
        assertEquals("", LooseJavaLaunch.launchJava(null, null));
    }
}
