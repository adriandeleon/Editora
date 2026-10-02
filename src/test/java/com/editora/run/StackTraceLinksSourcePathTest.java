package com.editora.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Frame → path derivation. A Java frame prints a bare file name, which on its own resolves only to an open
 * tab or {@code <root>/Bar.java}; the qualified class beside it is what says where the file really is.
 */
class StackTraceLinksSourcePathTest {

    private static String pathOf(String line) {
        return StackTraceLinks.javaSourcePath(StackTraceLinks.parse(line));
    }

    @Test
    void thePackageComesFromTheQualifiedClass() {
        assertEquals("com/foo/Bar.java", pathOf("\tat com.foo.Bar.baz(Bar.java:12)"));
        assertEquals("org/acme/deep/pkg/Thing.java", pathOf("at org.acme.deep.pkg.Thing.run(Thing.java:7)"));
    }

    @Test
    void constructorsLambdasAndNestedClassesResolveToTheOuterFile() {
        assertEquals("com/foo/Bar.java", pathOf("\tat com.foo.Bar.<init>(Bar.java:5)"));
        assertEquals("com/foo/Bar.java", pathOf("\tat com.foo.Bar.lambda$run$0(Bar.java:31)"));
        assertEquals("com/foo/Bar.java", pathOf("\tat com.foo.Bar$Inner.work(Bar.java:44)"));
        assertEquals("com/foo/Bar.java", pathOf("\tat com.foo.Bar$Inner$1.call(Bar.java:48)"));
    }

    /** The file name is taken from the parentheses: a secondary top-level class lives in another class's file. */
    @Test
    void theFileNameIsTheOneTheFramePrints() {
        assertEquals("com/foo/Shapes.java", pathOf("\tat com.foo.Circle.area(Shapes.java:9)"));
    }

    @Test
    void classLoaderAndModulePrefixesAreSkipped() {
        assertEquals("com/foo/BarTest.java", pathOf("\tat app//com.foo.BarTest.works(BarTest.java:21)"));
        assertEquals("java/util/ArrayList.java", pathOf("\tat java.base/java.util.ArrayList.get(ArrayList.java:427)"));
        assertEquals(
                "com/foo/Bar.java", pathOf("\tat my.module@1.0/com.foo.Bar.baz(Bar.java:3)"), "module@version/ too");
    }

    @Test
    void theDefaultPackageAndNonJavaFramesHaveNoDerivedPath() {
        assertNull(pathOf("\tat Main.main(Main.java:3)"), "no package: the bare name is all there is");
        assertNull(pathOf("  File \"/srv/app/x.py\", line 12, in <module>"));
        assertNull(pathOf("    at run (/srv/app/x.js:12:5)"));
        assertNull(StackTraceLinks.javaSourcePath(null));
        assertNull(StackTraceLinks.javaSourcePath(new StackTraceLinks.Link("Bar.java", 12)), "no raw line kept");
    }

    /** A failure message can quote a file before the frame; the frame's own file is the one that counts. */
    @Test
    void aLineMayCarryTextAroundTheFrame() {
        assertEquals(
                "com/foo/Bar.java",
                pathOf("[ERROR]   BarTest.works:21 expected <1> but was <2>   at com.foo.Bar.baz(Bar.java:12)"));
    }
}
