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

    @Test
    void kotlinGroovyAndScalaFramesResolveLikeJava() {
        assertEquals("com/foo/Main.kt", pathOf("\tat com.foo.MainKt.main(Main.kt:4)"));
        assertEquals("com/foo/Build.kts", pathOf("\tat com.foo.Build.run(Build.kts:4)"));
        assertEquals("demo/Shelf.groovy", pathOf("\tat demo.Shelf.add(Shelf.groovy:12)"));
        assertEquals("demo/Main.scala", pathOf("\tat demo.Main$.main(Main.scala:5)"));
        assertEquals("demo/Shelf.scala", pathOf("\tat demo.Shelf.add$$anonfun$1(Shelf.scala:30)"));
    }

    /** A Clojure function's class is its munged namespace, `$`, and the function: the namespace gives the path. */
    @Test
    void mungedClojureFramesResolveToTheNamespacesFile() {
        assertEquals("my_app/core.clj", pathOf("\tat my_app.core$fn__123.invoke(core.clj:12)"));
        assertEquals("my_app/core.clj", pathOf("\tat my_app.core$_main.invokeStatic(core.clj:5)"));
        assertEquals("my_app/util/strings.cljc", pathOf("\tat my_app.util.strings$trim.invoke(strings.cljc:3)"));
    }

    /** Clojure's own error report names the namespace as written, with dashes, and a space before the file. */
    @Test
    void clojuresOwnErrorLocationResolvesToo() {
        assertEquals(
                "my_app/core.clj", pathOf("Execution error (ArithmeticException) at my-app.core/divide (core.clj:4)."));
        assertNull(pathOf("Syntax error compiling at user/eval1 (REPL:1:1)."), "the REPL is not a file");
        assertNull(pathOf("at core/divide (core.clj:4)"), "a one-segment namespace has no directory");
    }
}
