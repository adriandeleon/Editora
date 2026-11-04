package com.editora.lsp;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The "LSP:" segment names the server, not the wrapper that starts it (JS review B114). */
class ServerProgramNameTest {

    @Test
    void theServersProgramWinsOverAWrapper() {
        String ts = "typescript-language-server";
        assertEquals(ts, ServerProgramName.of(List.of("typescript-language-server", "--stdio"), ts));
        assertEquals(
                ts, ServerProgramName.of(List.of("env", "NODE_OPTIONS=--max-old-space-size=8192", ts, "--stdio"), ts));
        assertEquals(ts, ServerProgramName.of(List.of("npx", "-y", ts, "--stdio"), ts));
        assertEquals(
                ts,
                ServerProgramName.of(
                        List.of("node", "/x/proxy.mjs", "/n/bin/typescript-language-server", "--stdio"), ts));
        assertEquals(ts, ServerProgramName.of(List.of("C:\\\\n\\\\typescript-language-server.cmd", "--stdio"), ts));
        assertEquals(
                "vtsls",
                ServerProgramName.of(List.of("env", "A=1", "vtsls", "--stdio"), ts),
                "another server: its name");
        assertEquals("gopls", ServerProgramName.of(List.of("gopls"), "gopls"));
        assertEquals("env", ServerProgramName.of(List.of("env"), ""));
    }
}
