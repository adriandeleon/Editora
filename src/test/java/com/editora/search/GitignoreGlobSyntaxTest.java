package com.editora.search;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The glob syntax of a {@code .gitignore} line as {@link GitignoreFilter} reads it — {@code ?}, character
 * classes, {@code **}, escapes — and how a nested ignore file is scoped to its own directory.
 */
class GitignoreGlobSyntaxTest {

    private static boolean ignores(String rules, String path) {
        return GitignoreFilter.parse(rules).ignored(path, false);
    }

    @Test
    void aQuestionMarkMatchesOneCharacterThatIsNotASlash() {
        assertTrue(ignores("file?.txt", "file1.txt"));
        assertTrue(ignores("file?.txt", "sub/fileA.txt"));
        assertFalse(ignores("file?.txt", "file.txt"));
        assertFalse(ignores("file?.txt", "file12.txt"));
        assertFalse(ignores("a?b", "a/b"), "an anchorless pattern is matched against the name, and '?' is no slash");
    }

    @Test
    void aCharacterClassMatchesItsMembersAndCanBeNegated() {
        assertTrue(ignores("*.[oa]", "lib.o"));
        assertTrue(ignores("*.[oa]", "lib.a"));
        assertFalse(ignores("*.[oa]", "lib.c"));
        assertTrue(ignores("log[0-9].txt", "log7.txt"));
        assertFalse(ignores("log[0-9].txt", "logx.txt"));

        assertTrue(ignores("data[!0-9]", "datax"));
        assertFalse(ignores("data[!0-9]", "data5"));
        assertTrue(ignores("x[^a]y", "x^y"), "a caret is a member here, not a negation");
        assertTrue(ignores("x[^a]y", "xay"));
        assertFalse(ignores("x[^a]y", "xby"));
    }

    @Test
    void anUnterminatedClassAndAnEscapedCharacterAreLiterals() {
        assertTrue(ignores("odd[name", "odd[name"));
        assertFalse(ignores("odd[name", "oddn"));
        assertTrue(ignores("star\\*name", "star*name"));
        assertFalse(ignores("star\\*name", "starXname"));
        assertTrue(ignores("\\#notes", "#notes"), "an escaped leading '#' is not a comment");
        assertTrue(ignores("\\!important", "!important"), "an escaped leading '!' is not a negation");
        assertFalse(ignores("#notes", "#notes"), "an unescaped one is a comment");
    }

    @Test
    void aDoubleStarCrossesDirectoriesOnlyWhereSlashesSurroundIt() {
        assertTrue(ignores("a/**/z", "a/z"));
        assertTrue(ignores("a/**/z", "a/b/c/z"));
        assertFalse(ignores("a/**/z", "b/a/z"), "a slash anchors the pattern to the root");
        assertTrue(ignores("**/build", "build"));
        assertTrue(ignores("**/build", "x/y/build"));
        assertTrue(ignores("logs/**", "logs/a/b.txt"));
        assertFalse(ignores("logs/**", "logs"));
        assertTrue(ignores("/top.txt", "top.txt"));
        assertFalse(ignores("/top.txt", "sub/top.txt"), "a leading slash anchors to the root");
    }

    @Test
    void laterRulesWinDirectoryRulesNeedADirectoryAndBlankInputIgnoresNothing() {
        GitignoreFilter f = GitignoreFilter.parse("*.log\r\n!keep.log\r\n\r\n# comment\r\nbuild/\r\n/\r\n");
        assertTrue(f.ignored("debug.log", false));
        assertFalse(f.ignored("keep.log", false), "re-included by the later rule");
        assertTrue(f.ignored("build", true));
        assertFalse(f.ignored("build", false), "a trailing slash matches directories only");
        assertFalse(f.ignored("", false));
        assertFalse(f.ignored(null, false));
        assertFalse(f.nests());
        assertFalse(f.isEmpty());

        assertSame(GitignoreFilter.NONE, GitignoreFilter.parse(null));
        assertSame(GitignoreFilter.NONE, GitignoreFilter.parse("  \n"));
        assertSame(GitignoreFilter.NONE, GitignoreFilter.parse("# only a comment\n/\n"));
        assertSame(GitignoreFilter.NONE, GitignoreFilter.load(null));
        assertTrue(GitignoreFilter.NONE.isEmpty());
    }

    @Test
    void aNestedIgnoreFileRulesOnlyItsOwnDirectoryAndOutranksTheOnesAbove(@TempDir Path root) throws Exception {
        Files.createDirectory(root.resolve(".git")); // the root is the repository top: nothing above applies
        Files.writeString(root.resolve(".gitignore"), "*.tmp\n");
        Path sub = Files.createDirectories(root.resolve("docs"));
        Files.writeString(sub.resolve(".gitignore"), "!keep.tmp\ndrafts/\n");
        Path bare = Files.createDirectories(root.resolve("src"));

        GitignoreFilter top = GitignoreFilter.load(root);
        assertTrue(top.nests());
        assertTrue(top.ignored("a.tmp", false));
        assertTrue(top.ignored("docs/keep.tmp", false), "the root's rules alone do not know the nested exception");

        GitignoreFilter inDocs = top.nested(sub, "docs");
        assertFalse(inDocs.ignored("docs/keep.tmp", false), "the deeper file re-includes it");
        assertTrue(inDocs.ignored("docs/other.tmp", false), "what it says nothing about falls through to the root");
        assertTrue(inDocs.ignored("docs/drafts", true));
        assertFalse(inDocs.ignored("drafts", true), "outside its directory the nested file says nothing");
        assertFalse(inDocs.ignored("docs", true), "nor about its own directory");
        assertTrue(inDocs.ignored("keep.tmp", false), "the root's keep.tmp is not the nested one");

        assertSame(top, top.nested(bare, "src"), "a directory with no ignore file adds nothing");
        assertSame(top, top.nested(null, "docs"));
        assertSame(top, top.nested(sub, null));
        assertSame(top, top.nested(sub, ""));
        assertFalse(top.nested(sub, "docs/").ignored("docs/keep.tmp", false), "with or without the trailing slash");

        GitignoreFilter flat = GitignoreFilter.parse("*.tmp");
        assertSame(flat, flat.nested(sub, "docs"), "a filter parsed from text does not nest");
    }
}
