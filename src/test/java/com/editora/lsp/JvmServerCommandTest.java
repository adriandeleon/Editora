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
}
