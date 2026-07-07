package com.editora.maven;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MavenExecutableTest {

    @Test
    void prefersUnixWrapperWhenPresentAndNotWindows() {
        assertEquals(List.of("./mvnw"), MavenExecutable.chooseArgv(true, true, false, "mvn.cmd"));
    }

    @Test
    void prefersWindowsWrapperWhenPresentAndWindows() {
        assertEquals(List.of("mvnw.cmd"), MavenExecutable.chooseArgv(true, true, true, "mvn.cmd"));
    }

    @Test
    void ignoresUnixWrapperOnWindowsWithoutWinWrapper() {
        assertEquals(List.of("mvn.cmd"), MavenExecutable.chooseArgv(true, false, true, "mvn.cmd"));
    }

    @Test
    void fallsBackToOverrideCommandWhenNoWrapper() {
        assertEquals(
                List.of("mvn.cmd", "--batch-mode"),
                MavenExecutable.chooseArgv(false, false, true, "mvn.cmd --batch-mode"));
    }

    @Test
    void fallsBackToBareMvnWhenNoWrapperNoOverride() {
        assertEquals(List.of("mvn"), MavenExecutable.chooseArgv(false, false, false, ""));
        assertEquals(List.of("mvn"), MavenExecutable.chooseArgv(false, false, false, null));
    }
}
