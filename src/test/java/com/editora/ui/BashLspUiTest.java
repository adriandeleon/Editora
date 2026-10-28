package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.lsp.LspManager;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Bash server's answers that mean something else (Bash review C1, B11, B3), as pure decisions. */
class BashLspUiTest {

    @org.junit.jupiter.api.BeforeAll
    static void catalog() {
        com.editora.i18n.Messages.init("en"); // real text, so a wrong argument shows
    }

    @Test
    void shfmtsParseErrorBecomesTheFormatMessageWithItsLine() {
        String logged = "02:41:38.518 ERROR ⛔️ Error while formatting: Error: Shfmt: exited with status 1: "
                + "/tmp/p/broken.sh:5:1: `for` statement must end with `done`";
        assertEquals(
                tr("status.lsp.bashCannotFormatAt", 5, "`for` statement must end with `done`"),
                BashLspUi.shfmtFailure(logged));
        String posix = "Error while formatting: Error: Shfmt: exited with status 1: /tmp/p/posix.sh:2:5: arrays are a "
                + "bash/mksh/zsh feature; tried parsing as posix (parsed as posix via -ln=auto)";
        assertEquals(
                tr("status.lsp.bashCannotFormatPosix", 2, "arrays are a bash/mksh/zsh feature; tried parsing as posix"),
                BashLspUi.shfmtFailure(posix),
                "the shebang or the dialect setting is the way out");
        assertEquals(
                tr("status.lsp.bashCannotFormat", "something else broke"),
                BashLspUi.shfmtFailure("Error while formatting: Error: something else broke"));
        assertNull(BashLspUi.shfmtFailure("Error while linting: no shellcheck"));
        assertNull(BashLspUi.shfmtFailure(null));
    }

    @Test
    void anEmptyFormatSaysWhyForTheBashServerOnly() {
        assertEquals(tr("status.lsp.formatNoChange"), BashLspUi.formatNoChange("java", () -> false, "x"));
        assertEquals(tr("status.lsp.bashNoShfmt"), BashLspUi.formatNoChange("bash", () -> false, null));
        assertEquals(tr("status.lsp.formatNoChange"), BashLspUi.formatNoChange("bash", () -> true, null));
        assertEquals(
                tr("status.lsp.bashCannotFormatAt", 3, "reached EOF without closing quote `\"`"),
                BashLspUi.formatNoChange(
                        "bash",
                        () -> true,
                        "Error while formatting: Error: Shfmt: exited with status 1: /a b/x.sh:3:8: reached EOF "
                                + "without closing quote `\"`"));
    }

    @Test
    void aNewNameMustBeAShellName() {
        assertTrue(BashLspUi.validName("count_2", false));
        assertFalse(BashLspUi.validName("1bad", false));
        assertFalse(BashLspUi.validName("bad name", true));
        assertFalse(BashLspUi.validName("git-sync", false), "a variable takes no dash");
        assertTrue(BashLspUi.validName("git-sync", true), "a function does");
        assertTrue(BashLspUi.validName("ns::run.all", true));
        assertFalse(BashLspUi.validName("$x", true));
    }

    @Test
    void aFunctionIsToldByItsDeclaration() {
        assertTrue(BashLspUi.declaresFunction("greet() {", "greet"));
        assertTrue(BashLspUi.declaresFunction("  function greet {", "greet"));
        assertTrue(BashLspUi.declaresFunction("function greet() {", "greet"));
        assertTrue(BashLspUi.declaresFunction("git-sync () { git pull; }", "git-sync"));
        assertFalse(BashLspUi.declaresFunction("greet=hello", "greet"));
        assertFalse(BashLspUi.declaresFunction("local greet", "greet"));
        assertFalse(BashLspUi.declaresFunction("greeting() {", "greet"));
    }

    @Test
    void onlyATargetInAFileIsADeclaration() {
        assertNull(BashLspUi.declaration(List.of()));
        assertNull(BashLspUi.declaration(null));
        var t = new LspManager.Target(Path.of("/p/lib.sh"), 3, 0);
        assertEquals(t, BashLspUi.declaration(List.of(new LspManager.Target(null, 0, 0, "jdt://x"), t)));
    }
}
