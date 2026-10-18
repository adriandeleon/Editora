package com.editora.editops;

import com.editora.editops.Commenter.CommentStyle;
import com.editora.editops.Commenter.Edit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure comment toggle engine. */
class CommenterTest {

    /** Applies a toggle and returns the resulting full document text. */
    private static String apply(String text, int selStart, int selEnd, String lang) {
        Edit e = Commenter.toggle(text, selStart, selEnd, Commenter.styleFor(lang));
        if (e == null) {
            return text;
        }
        return text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
    }

    @Test
    void styleMapping() {
        assertEquals(new CommentStyle("//", "/*", "*/"), Commenter.styleFor("java"));
        assertEquals(new CommentStyle("#", null, null), Commenter.styleFor("python"));
        assertEquals(new CommentStyle(null, "<!--", "-->"), Commenter.styleFor("xml"));
        assertEquals(new CommentStyle(null, "/*", "*/"), Commenter.styleFor("css"));
        assertEquals(new CommentStyle("--", "/*", "*/"), Commenter.styleFor("sql"));
        assertEquals(new CommentStyle("//", null, null), Commenter.styleFor("markwhen"));
    }

    @Test
    void markwhenLineComment() {
        assertEquals("// 2023: Event", apply("2023: Event", 0, 0, "markwhen"));
        assertEquals("2023: Event", apply("// 2023: Event", 0, 0, "markwhen")); // round-trip
    }

    @Test
    void singleLineUsesLineComment() {
        assertEquals("// foo", apply("foo", 0, 0, "java")); // comment
        assertEquals("foo", apply("// foo", 0, 0, "java")); // uncomment (round-trip)
        assertEquals("    // x = 1", apply("    x = 1", 0, 0, "java")); // keeps indentation
    }

    @Test
    void pythonLineComment() {
        assertEquals("# hi", apply("hi", 0, 0, "python"));
        assertEquals("hi", apply("# hi", 0, 0, "python"));
    }

    @Test
    void multiLineUsesBlockWhenAvailable() {
        String text = "a\nb";
        assertEquals("/* a\nb */", apply(text, 0, text.length(), "java")); // block wraps the selection
    }

    @Test
    void multiLineFallsBackToLineCommentWhenNoBlock() {
        String text = "a\nb";
        assertEquals("# a\n# b", apply(text, 0, text.length(), "python"));
        // round-trip uncomment
        assertEquals("a\nb", apply("# a\n# b", 0, "# a\n# b".length(), "python"));
    }

    @Test
    void multiLineLineCommentTogglesAllOrNothing() {
        // a mix (one commented, one not) → commenting everything, not uncommenting
        String text = "# a\nb";
        assertEquals("# # a\n# b", apply(text, 0, text.length(), "python"));
    }

    @Test
    void blockToggleWrapAndUnwrap() {
        assertEquals("<!-- x -->", apply("x", 0, 1, "xml"));
        assertEquals("x", apply("<!-- x -->", 0, "<!-- x -->".length(), "xml"));
        // css single line (block-only)
        assertEquals("/* color: red; */", apply("color: red;", 0, 0, "css"));
    }

    @Test
    void plaintextHasNoComment() {
        assertNull(Commenter.toggle("hello", 0, 0, Commenter.styleFor("plaintext")));
    }

    @Test
    void blankLinesSkippedWhenCommenting() {
        String text = "a\n\nb";
        assertEquals("# a\n\n# b", apply(text, 0, text.length(), "python")); // blank line untouched
    }

    @Test
    void aNodeShebangIsNeverToggledEither() {
        // Node.js review A7: Ctrl+/ made `// #!/usr/bin/env node`, and select-all wrapped it in /* */.
        String js = "#!/usr/bin/env node\nconsole.log(1);\n";
        assertNull(Commenter.toggle(js, 3, 3, Commenter.styleFor("javascript")), "the shebang line alone");
        assertTrue(Commenter.onlyShebang(js, 3, 3, Commenter.styleFor("javascript")));
        assertEquals("#!/usr/bin/env node\n// console.log(1);\n", apply(js, 0, js.length(), "javascript"));
        // Rust's inner attribute is code, not a shebang.
        String rs = "#![allow(dead_code)]\nfn main() {}\n";
        assertEquals("// #![allow(dead_code)]\nfn main() {}\n", apply(rs, 0, 0, "rust"));
    }

    @Test
    void lineCommentsLineUpAtTheShallowestIndent() {
        String doc = "/**\n * Loads.\n */\nfunction f() {\n  return 1;\n}";
        String commented = apply(doc, 0, doc.length(), "javascript");
        assertEquals("// /**\n//  * Loads.\n//  */\n// function f() {\n//   return 1;\n// }", commented);
        assertEquals(doc, apply(commented, 0, commented.length(), "javascript"), "and uncommenting restores it");
        String py = "if x:\n    y()\n\n    z()";
        assertEquals("# if x:\n#     y()\n\n#     z()", apply(py, 0, py.length(), "python"));
    }

    @Test
    void aShebangIsNeverToggled() {
        // B3: Ctrl+/ on line 1 turned `#!/usr/bin/env bash` into `!/usr/bin/env bash`.
        String sh = "#!/usr/bin/env bash\n# usage: x\necho hi\n";
        assertNull(Commenter.toggle(sh, 3, 3, Commenter.styleFor("shell")), "the shebang line alone");
        assertNull(Commenter.toggle(sh, 0, 19, Commenter.styleFor("shell")));
        assertTrue(Commenter.onlyShebang(sh, 3, 3, Commenter.styleFor("shell")));
        // A selection over it toggles the lines after it only: the header comment is uncommented …
        assertEquals("#!/usr/bin/env bash\nusage: x\necho hi\n", apply(sh, 0, 30, "shell"));
        // … and select-all comments the code without touching the shebang.
        assertEquals("#!/usr/bin/env bash\n# # usage: x\n# echo hi\n", apply(sh, 0, sh.length(), "shell"));
        String py = "#!/usr/bin/env python3\nx = 1";
        assertEquals("#!/usr/bin/env python3\n# x = 1", apply(py, 0, py.length(), "python"));
        // Not a shebang: a `#!` anywhere else, or in a language whose comments are not `#`.
        assertEquals("echo\n!# x", apply("echo\n# !# x", 5, 5, "shell"));
        assertFalse(Commenter.onlyShebang("echo\n#!x", 6, 6, Commenter.styleFor("shell")));
    }

    @Test
    void aHereDocBodyIsNeverCommentedOnItsOwn() {
        // A `#` put into a here-doc's body is output, not a comment: the script would print it.
        String sh = "echo start\ncat <<EOF\nline one\n# kept\nEOF\necho end\n";
        int body = sh.indexOf("line one");
        assertNull(Commenter.toggle(sh, body, body, "shell"), "a body line");
        assertNull(
                Commenter.toggle(sh, sh.indexOf("# kept") + 1, sh.indexOf("# kept") + 1, "shell"), "nor uncommented");
        assertNull(Commenter.toggle(sh, sh.indexOf("EOF\necho"), sh.indexOf("EOF\necho"), "shell"), "the delimiter");
        assertNull(Commenter.toggle(sh, body, sh.length(), "shell"), "a selection starting inside one");
        assertTrue(Commenter.intoHereDoc(sh, body, body, "shell"));
        // The whole statement, its opening line included, is commented as a block — and uncommented again.
        Edit all = Commenter.toggle(sh, sh.indexOf("cat"), sh.indexOf("echo end"), "shell");
        String commented = sh.substring(0, all.from()) + all.replacement() + sh.substring(all.to());
        assertEquals("echo start\n# cat <<EOF\n# line one\n# # kept\n# EOF\necho end\n", commented);
        Edit back = Commenter.toggle(commented, commented.indexOf("# cat"), commented.indexOf("echo end"), "shell");
        assertEquals(sh, commented.substring(0, back.from()) + back.replacement() + commented.substring(back.to()));
        // Code around it, and other languages, are unaffected.
        assertEquals(
                "# echo start", apply(sh, 0, 0, "shell").lines().findFirst().orElseThrow());
        assertFalse(Commenter.intoHereDoc("x = 1\n<<EOF\ny\n", 8, 8, "python"));
    }
}
