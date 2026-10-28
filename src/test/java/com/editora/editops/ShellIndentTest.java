package com.editora.editops;

import com.editora.editops.Indenter.Style;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shell indentation that needs more than the caret's line: case items (B1), here-docs (B2), and closers
 * matched to their opener. Through {@link Indenter}'s public entry points, as the editor calls them.
 */
class ShellIndentTest {

    /** The indent Enter gives at the end of {@code text} (4-space unit). */
    private static String enter(String text) {
        String insert = Indenter.enterEdit(text, text.length(), "shell", "    ").insert();
        return insert.substring(1).replace(' ', '·');
    }

    /** The indent the last line gets once {@code typed} completes what is on it. */
    private static String closer(String text, String currentIndent, char typed) {
        return Indenter.closerAlignIndent(Style.SHELL, text, text.length(), 4, currentIndent, typed, "    ")
                .replace(' ', '·');
    }

    @Test
    void aCasePatternIndentsItsBody() {
        assertEquals("····", enter("case $x in"));
        assertEquals("········", enter("case $x in\n    a)"));
        assertEquals("········", enter("case $x in\n    a|b)"));
        assertEquals("········", enter("case $x in\n    --dry-run) echo dry"), "body begun on the pattern line");
        assertEquals("····", enter("case $x in\n    *) echo default ;;"), "a one-line item is finished");
    }

    @Test
    void doubleSemicolonSitsAtBodyLevelAndTheNextPatternGoesBack() {
        String item = "case $x in\n    a)\n        echo a\n        ;";
        assertEquals("········", closer(item, "        ", ';'), ";; stays with its body (shfmt, Google style)");
        assertEquals("········", closer("case $x in\n    a) echo a\n    ;", "    ", ';'), "one unit past the pattern");
        assertEquals("····", enter("case $x in\n    a)\n        echo a\n        ;;"));
        assertEquals("····", enter("case $x in\n    a)\n        echo a\n        ;&"));
    }

    @Test
    void esacAlignsWithItsCaseWhateverTheItemsLookLike() {
        // shfmt -ci layout: ;; at body level used to send esac to the pattern level.
        String ci = "case $x in\n    a)\n        echo a\n        ;;\n        esac";
        assertEquals("", closer(ci, "        ", ' '));
        String shfmt = "f() {\n    case $x in\n    a)\n        echo a\n        ;;\n        esac";
        assertEquals("····", closer(shfmt, "        ", '\n'));
    }

    @Test
    void closersMatchTheirOpenerNotTheNearestShallowerLine() {
        String sh = "if a; then\n    x=$(\n        cmd\n    )\n    fi";
        assertEquals("", closer(sh, "    ", '\n'));
        String loop = "while read -r l; do\n    case $l in\n        *) : ;;\n    esac\n    done";
        assertEquals("", closer(loop, "    ", ' '));
    }

    @Test
    void aHereDocDelimiterTypedAtTheBodysIndentIsUnindented() {
        String plain = "f() {\n    cat <<EOF\n    hello\n    EOF";
        assertTrue(Indenter.completesCloserKeyword(Style.SHELL, "    EOF\n"));
        assertEquals("", closer(plain, "    ", '\n'), "bash only ends <<EOF at an unindented EOF");
        String dashSpaces = "f() {\n    cat <<-EOF\n    hello\n    EOF";
        assertEquals("", closer(dashSpaces, "    ", '\n'), "<<- strips tabs, not spaces");
        String dashTabs = "f() {\n\tcat <<-EOF\n\t\thello\n\tEOF";
        assertEquals("\t", Indenter.closerAlignIndent(Style.SHELL, dashTabs, dashTabs.length(), 4, "\t", '\n', "\t"));
        // A word that is not the delimiter is body text and keeps its indent.
        String other = "f() {\n    cat <<EOF\n    EOFX";
        assertEquals("····", closer(other, "    ", '\n'));
    }

    @Test
    void codeResumesAtTheCommandsLevelAfterTheDelimiter() {
        assertEquals("····", enter("f() {\n    cat <<EOF\n    hello\nEOF"));
        assertEquals("····", enter("f() {\n    cat <<'EOF' > out\nEOF"));
    }

    @Test
    void aHereDocBodyIsDataNothingInItIndentsOrDedents() {
        assertEquals("", enter("cat <<EOF\nif x; then"), "then inside a here-doc opens nothing");
        assertEquals("····", enter("f() {\n    cat <<EOF\n    {"));
        String fi = "if a; then\n    cat <<EOF\n    fi";
        assertTrue(Indenter.completesCloserKeyword(Style.SHELL, "    fi\n"));
        assertEquals("····", closer(fi, "    ", '\n'), "a fi in the data is not the if's closer");
    }

    @Test
    void aMultiLineStringIsNotCode() {
        assertEquals("··", enter("msg=\"first\n  if x; then"));
        String s = "if a; then\n    msg='\n    fi";
        assertEquals("····", closer(s, "    ", '\n'));
    }

    @Test
    void anAssignmentNamedLikeACloserIsNotOne() {
        assertEquals("····", closer("while true; do\n    done=", "    ", '='));
        assertFalse(ShellIndent.closerWord("done=false") != null);
    }

    @Test
    void smartTabUsesTheSameRules() {
        assertEquals("        ", tabOnBlankLine("case $x in\n    a)\n\nesac\n"), "a pattern's body line");
        assertEquals("    ", tabOnBlankLine("case $x in\n    a)\n        b\n        ;;\n\nesac\n"), "the next pattern");
        assertEquals("", tabOnBlankLine("cat <<EOF\nif x; then\n\nEOF\n"), "a here-doc body opens nothing");
    }

    /** What smart Tab puts on the (first) blank line of {@code text}. */
    private static String tabOnBlankLine(String text) {
        int blank = text.indexOf("\n\n") + 1;
        Indenter.TabEdit edit = Indenter.smartTab(text, blank, blank, "shell", 4, false, true, 4);
        return edit.replacement();
    }

    /** Tab / Shift-Tab over {@code [from, to)} of {@code text}, as the editor runs it: through its window. */
    private static String block(String text, int from, int to, boolean shift, String unit) {
        Indenter.TabEdit e = IndentWindow.tabEdit(IndentWindow.of(text), from, to, "shell", 4, shift, unit);
        return text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
    }

    private static final String HEREDOC = "#!/bin/bash\nmain() {\ncat <<EOF\nhello $1\n  world\nEOF\necho done\n}\n";

    /** Indenting a whole script kept it a script: the shebang and the here-doc stay put (Bash review C2). */
    @Test
    void blockIndentLeavesTheShebangAndHereDocsAlone() {
        assertEquals(
                "#!/bin/bash\n    main() {\n    cat <<EOF\nhello $1\n  world\nEOF\n    echo done\n    }\n",
                block(HEREDOC, 0, HEREDOC.length(), false, "    "));
        String indented = "#!/bin/bash\n    main() {\n    cat <<EOF\nhello $1\n  world\nEOF\n    echo done\n    }\n";
        assertEquals(HEREDOC, block(indented, 0, indented.length(), true, "    "), "and outdenting gives it back");
        assertEquals(
                "#!/bin/bash\nmain() {\ncat <<EOF\nhello $1\n  world\nEOF\n    echo done\n}\n",
                block(HEREDOC, HEREDOC.indexOf("hello"), HEREDOC.indexOf("}"), false, "    "),
                "a selection that starts inside the here-doc knows it is there");
        String notFirst = "x=1\n#!/not/a/shebang\n";
        assertEquals("    x=1\n    #!/not/a/shebang\n", block(notFirst, 0, notFirst.length(), false, "    "));
    }

    @Test
    void aDashHereDocMovesByTabsOnly() {
        String text = "f() {\n\tcat <<-EOF\n\thello\n\tEOF\n}\n";
        assertEquals(
                "\tf() {\n\t\tcat <<-EOF\n\t\thello\n\t\tEOF\n\t}\n",
                block(text, 0, text.length(), false, "\t"),
                "bash strips the tabs, so they may move");
        assertEquals(
                "    f() {\n    \tcat <<-EOF\n\thello\n\tEOF\n    }\n",
                block(text, 0, text.length(), false, "    "),
                "spaces would hide the delimiter");
        assertEquals("f() {\ncat <<-EOF\nhello\nEOF\n}\n", block(text, 0, text.length(), true, "\t"));
    }

    @Test
    void aMultiLineStringIsNotReindented() {
        String text = "msg=\"one\ntwo\"\necho \"$msg\"\n";
        assertEquals("    msg=\"one\ntwo\"\n    echo \"$msg\"\n", block(text, 0, text.length(), false, "    "));
    }
}
