package com.editora.doctor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoctorTextTest {

    @Test
    void recognizesPathsWorthEllipsizingFromTheLeft() {
        assertTrue(DoctorText.isPathLike("/usr/bin/git"));
        assertTrue(DoctorText.isPathLike("~/.editora/plugins/lsp/java/bin/jdtls"));
        assertTrue(DoctorText.isPathLike("./gradlew"));
        assertTrue(DoctorText.isPathLike("C:\\Program Files\\nodejs\\node.exe"));
        assertTrue(DoctorText.isPathLike("D:/tools/rg.exe"));

        assertFalse(DoctorText.isPathLike("git version 2.47.3"));
        assertFalse(DoctorText.isPathLike("npx -y @probelabs/maid"));
        assertFalse(DoctorText.isPathLike(""));
        assertFalse(DoctorText.isPathLike(null));
    }

    @Test
    void collapsesHomeOnlyOnAWholeSegment() {
        assertEquals(
                "~/.cargo/bin/rust-analyzer",
                DoctorText.collapseHome("/home/adl/.cargo/bin/rust-analyzer", "/home/adl"));
        assertEquals("~", DoctorText.collapseHome("/home/adl", "/home/adl"));
        // A trailing separator on the home property must not defeat the match.
        assertEquals("~/bin/gopls", DoctorText.collapseHome("/home/adl/bin/gopls", "/home/adl/"));
        // A sibling directory that merely shares the prefix is left alone.
        assertEquals("/home/adl2/bin/gopls", DoctorText.collapseHome("/home/adl2/bin/gopls", "/home/adl"));
        // Windows separators.
        assertEquals(
                "~\\AppData\\node.exe", DoctorText.collapseHome("C:\\Users\\adl\\AppData\\node.exe", "C:\\Users\\adl"));

        assertEquals("", DoctorText.collapseHome(null, "/home/adl"));
        assertEquals("/usr/bin/git", DoctorText.collapseHome("/usr/bin/git", ""));
        assertEquals("/usr/bin/git", DoctorText.collapseHome("/usr/bin/git", "/"));
    }

    @Test
    void dropsADetailThatOnlyRepeatsTheCommand() {
        String path = "~/.editora/plugins/lsp/xml/lemminx-linux";
        assertTrue(DoctorText.detailRepeatsCommand(path, path));
        assertTrue(DoctorText.detailRepeatsCommand(path, " " + path + " "));

        assertFalse(DoctorText.detailRepeatsCommand("jdtls", "~/.editora/plugins/lsp/java/bin/jdtls"));
        assertFalse(DoctorText.detailRepeatsCommand("git", "git version 2.47.3"));
        assertFalse(DoctorText.detailRepeatsCommand(path, ""));
        assertFalse(DoctorText.detailRepeatsCommand(null, path));
    }

    @Test
    void dropsABareCommandTheResolvedPathAlreadyEndsWith() {
        assertTrue(DoctorText.commandRepeatsDetail("jdtls", "~/.editora/plugins/lsp/java/bin/jdtls"));
        assertTrue(DoctorText.commandRepeatsDetail("rust-analyzer", "/home/adl/.cargo/bin/rust-analyzer"));
        assertTrue(DoctorText.commandRepeatsDetail("node.exe", "C:\\Program Files\\nodejs\\node.exe"));

        // A command carrying arguments still says something the path doesn't.
        assertFalse(DoctorText.commandRepeatsDetail("pyright-langserver --stdio", "~/bin/pyright-langserver"));
        // A version detail is not a path, so the command is the only launch info on the row.
        assertFalse(DoctorText.commandRepeatsDetail("git", "git version 2.47.3"));
        // Different binary — the mismatch is exactly what the user needs to see.
        assertFalse(DoctorText.commandRepeatsDetail("gopls", "~/go/bin/gopls-old"));
        assertFalse(DoctorText.commandRepeatsDetail("", "~/go/bin/gopls"));
        assertFalse(DoctorText.commandRepeatsDetail("gopls", ""));
    }
}
