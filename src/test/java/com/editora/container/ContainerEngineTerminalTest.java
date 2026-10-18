package com.editora.container;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The engine command a container terminal runs. */
class ContainerEngineTerminalTest {

    @Test
    void aTerminalAsksForATtyInTheContainer() {
        ContainerEngine podman = ContainerEngine.of("podman --remote");
        assertEquals(
                List.of("podman", "--remote", "exec", "-i", "-t", "-u", "dev", "web", "/bin/sh", "-c", "x"),
                podman.terminalArgv("web", "dev", List.of("/bin/sh", "-c", "x")));
        assertEquals(
                List.of("docker", "exec", "-i", "-t", "web", "sh"),
                ContainerEngine.of("").terminalArgv("web", null, List.of("sh")));
    }

    @Test
    void closingATerminalHangsItsShellUp() {
        assertEquals(List.of("kill", "-s", "HUP", "--", "42"), ContainerExecTarget.hangUpArgv(42));
    }
}
