package com.editora.ui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureDocTest {

    private static List<String> lines(String src) {
        return List.of(src.split("\n", -1));
    }

    @Test
    void javadocBlockAboveMethod() {
        List<String> l = lines("""
                /**
                 * Does the thing.
                 * @param x the input
                 */
                void run(int x) {}
                """);
        assertEquals("Does the thing.\n@param x the input", StructureDoc.commentAbove(l, 4));
    }

    @Test
    void skipsAnnotationBetweenCommentAndDecl() {
        List<String> l = lines("""
                // toggles the flag
                @Override
                public void set() {}
                """);
        assertEquals("toggles the flag", StructureDoc.commentAbove(l, 2));
    }

    @Test
    void lineCommentRunSlashSlash() {
        List<String> l = lines("""
                // first
                // second
                int field;
                """);
        assertEquals("first\nsecond", StructureDoc.commentAbove(l, 2));
    }

    @Test
    void hashCommentPython() {
        List<String> l = lines("""
                # returns the total
                def total():
                    pass
                """);
        assertEquals("returns the total", StructureDoc.commentAbove(l, 1));
    }

    @Test
    void rustDeriveDecoratorSkipped() {
        List<String> l = lines("""
                // a point
                #[derive(Clone)]
                struct Point;
                """);
        assertEquals("a point", StructureDoc.commentAbove(l, 2));
    }

    @Test
    void dashCommentSql() {
        List<String> l = lines("""
                -- the users table
                CREATE TABLE users;
                """);
        assertEquals("the users table", StructureDoc.commentAbove(l, 1));
    }

    @Test
    void plainBlockComment() {
        List<String> l = lines("""
                /* a helper */
                function help() {}
                """);
        assertEquals("a helper", StructureDoc.commentAbove(l, 1));
    }

    @Test
    void noCommentReturnsEmpty() {
        List<String> l = lines("""
                int x = 1;
                void run() {}
                """);
        assertEquals("", StructureDoc.commentAbove(l, 1));
    }

    @Test
    void shebangIsNotAComment() {
        List<String> l = lines("""
                #!/usr/bin/env python
                def main():
                    pass
                """);
        assertEquals("", StructureDoc.commentAbove(l, 1));
    }

    @Test
    void firstLineHasNoDoc() {
        List<String> l = lines("void run() {}\n");
        assertEquals("", StructureDoc.commentAbove(l, 0));
    }

    @Test
    void longCommentIsCapped() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("// line ").append(i).append('\n');
        }
        sb.append("void run() {}\n");
        String doc = StructureDoc.commentAbove(lines(sb.toString()), 40);
        assertTrue(doc.endsWith("…"), "capped comment should end with an ellipsis");
        assertTrue(doc.split("\n", -1).length <= StructureDoc.MAX_LINES + 1);
    }
}
