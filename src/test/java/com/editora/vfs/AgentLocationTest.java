package com.editora.vfs;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Where the SSH agent is, as {@code ssh} finds it from IdentityAgent and the environment. */
class AgentLocationTest {

    private static final Path HOME = Path.of("/home/ana");

    private static AgentLocation find(String identityAgent, Map<String, String> env, boolean windows) {
        return AgentLocation.find(identityAgent, env, windows, HOME, "ana");
    }

    @Test
    void withoutIdentityAgentItIsSshAuthSock() {
        assertEquals(
                new AgentLocation(AgentLocation.Kind.UNIX_SOCKET, "/run/agent.sock"),
                find("", Map.of("SSH_AUTH_SOCK", "/run/agent.sock"), false));
        assertNull(find("", Map.of(), false), "no agent to ask");
    }

    @Test
    void onWindowsWithoutSshAuthSockItIsTheOpenSshPipe() {
        assertEquals(
                new AgentLocation(AgentLocation.Kind.NAMED_PIPE, AgentLocation.WINDOWS_PIPE),
                find(null, Map.of(), true));
        assertEquals(
                AgentLocation.Kind.NAMED_PIPE,
                find("", Map.of("SSH_AUTH_SOCK", "//./pipe/other-agent"), true).kind());
        assertEquals(
                AgentLocation.Kind.UNIX_SOCKET,
                find("", Map.of("SSH_AUTH_SOCK", "C:\\Users\\ana\\agent.sock"), true)
                        .kind(),
                "Windows has Unix-domain sockets too");
    }

    @Test
    void identityAgentNoneTurnsTheAgentOff() {
        assertNull(find("none", Map.of("SSH_AUTH_SOCK", "/run/agent.sock"), false));
        assertNull(find("NONE", Map.of(), true));
    }

    @Test
    void identityAgentNamesAVariableOrAPath() {
        Map<String, String> env = Map.of("SSH_AUTH_SOCK", "/run/a.sock", "OP_SOCK", "/run/op.sock");
        assertEquals("/run/a.sock", find("SSH_AUTH_SOCK", env, false).where());
        assertEquals("/run/op.sock", find("$OP_SOCK", env, false).where());
        assertEquals("/run/op.sock", find("${OP_SOCK}", env, false).where());
        assertNull(find("$UNSET", env, false));
        assertEquals(
                "/home/ana/.1password/agent.sock",
                find("~/.1password/agent.sock", env, false).where());
        assertEquals(
                "/home/ana/x/ana.sock", find("\"%d/x/%u.sock\"", env, false).where());
    }
}
