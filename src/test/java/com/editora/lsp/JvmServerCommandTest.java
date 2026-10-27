package com.editora.lsp;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JvmServerCommandTest {

    private static final String JDK = Path.of("/opt/jdks/jdk 21").toString();

    @Test
    void aBareJavaRunsOnTheConfiguredJdk() {
        String command = JvmServerCommand.withJdk("java -jar /x/gls.jar", JDK);
        List<String> argv = com.editora.run.ProgramArgs.commandLine(command);
        assertEquals(com.editora.run.JdkToolchain.javaExecutable(JDK), argv.get(0));
        assertEquals(List.of("-jar", "/x/gls.jar"), argv.subList(1, argv.size()));
    }

    @Test
    void withoutAJdkOrWithItsOwnExecutableTheCommandIsKept() {
        assertEquals("java -jar /x/gls.jar", JvmServerCommand.withJdk("java -jar /x/gls.jar", ""));
        assertEquals("java -jar /x/gls.jar", JvmServerCommand.withJdk("java -jar /x/gls.jar", null));
        assertEquals("/usr/bin/java -jar g.jar", JvmServerCommand.withJdk("/usr/bin/java -jar g.jar", JDK));
        assertEquals("gls", JvmServerCommand.withJdk("gls", JDK));
        assertEquals("javac x", JvmServerCommand.withJdk("javac x", JDK));
        assertEquals("", JvmServerCommand.withJdk(null, JDK));
    }

    /** A JVM server keeps its files under Editora's user.home, not the passwd home (XML/pom review 2, D14). */
    @Test
    void aJavaLauncherGetsEditorasUserHome() {
        assertEquals(
                List.of("java", "-Duser.home=/fake", "-cp", "/x/*", "Main"),
                JvmServerCommand.withUserHome(List.of("java", "-cp", "/x/*", "Main"), "/fake"));
        assertEquals(
                List.of("/opt/jdk/bin/java", "-Duser.home=/fake", "-cp", "x", "M"),
                JvmServerCommand.withUserHome(List.of("/opt/jdk/bin/java", "-cp", "x", "M"), "/fake"));
        assertEquals(
                List.of("C:\\jdk\\bin\\java.exe", "-Duser.home=/fake", "M"),
                JvmServerCommand.withUserHome(List.of("C:\\jdk\\bin\\java.exe", "M"), "/fake"));
    }

    @Test
    void anythingElseOrAnExplicitUserHomeIsKept() {
        List<String> own = List.of("java", "-Duser.home=/probe", "-cp", "x", "M");
        assertEquals(own, JvmServerCommand.withUserHome(own, "/fake"), "the command's own choice wins");
        assertEquals(List.of("lemminx"), JvmServerCommand.withUserHome(List.of("lemminx"), "/fake"));
        assertEquals(List.of("javac", "x"), JvmServerCommand.withUserHome(List.of("javac", "x"), "/fake"));
        assertEquals(List.of("java", "M"), JvmServerCommand.withUserHome(List.of("java", "M"), ""));
        assertEquals(List.of(), JvmServerCommand.withUserHome(List.of(), "/fake"));
    }
}
