package com.editora.ui;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link HostTools}: one host's tool commands as the Settings → Remote page edits them. */
class HostToolsTest {

    @Test
    void languageServersAreLinesOfServerAndCommand() {
        Map<String, String> tools = Map.of("git", "/opt/git/bin/git", "lsp.python", "basedpyright-langserver --stdio");

        assertEquals("python = basedpyright-langserver --stdio", HostTools.serversText(tools));
        assertEquals(
                Map.of("lsp.python", "basedpyright-langserver --stdio", "lsp.go", "/home/u/go/bin/gopls"),
                HostTools.parseServers(
                        "python = basedpyright-langserver --stdio\n\n# a note\ngo=/home/u/go/bin/gopls\n"));
    }

    /** A host names its Maven beside its JDK (review D10); the page shows the field after the JDK's. */
    @Test
    void mavenIsANamedHostTool() {
        java.util.List<String> ids =
                HostTools.NAMED.stream().map(HostTools.Tool::id).toList();
        assertEquals(ids.indexOf("jdk.home") + 1, ids.indexOf("maven"), ids.toString());
        assertEquals(
                "settings.remote.tool.maven",
                HostTools.NAMED.get(ids.indexOf("maven")).labelKey());
    }

    @Test
    void halfWrittenLinesAreSkipped() {
        assertEquals(Map.of(), HostTools.parseServers("python\n= cmd\nrust =\ntwo words = cmd\n"));
    }

    @Test
    void theFormReplacesWhatItShowsAndKeepsWhatItDoesNot() {
        Map<String, String> previous = Map.of("git", "old-git", "lsp.java", "old-jdtls", "future.tool", "x");

        Map<String, String> merged = HostTools.merge(
                previous, Map.of("git", "", "rg", " /usr/local/bin/rg "), "python = pyright-langserver --stdio");

        assertEquals(
                Map.of("future.tool", "x", "rg", "/usr/local/bin/rg", "lsp.python", "pyright-langserver --stdio"),
                merged,
                "a cleared field and a removed line are gone; an unknown tool id survives");
    }
}
