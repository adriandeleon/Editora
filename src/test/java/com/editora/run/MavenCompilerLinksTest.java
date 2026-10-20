package com.editora.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Maven review B4: the compiler plugin's {@code path:[line,col]} locations in a build's console are links, with
 * the column. Surefire's stack frames already were; the one line a user most wants after a failed build was not.
 */
class MavenCompilerLinksTest {

    @Test
    void mavenCompilerErrorLocationLinksWithLineAndColumn() {
        String line = "[ERROR] /home/u/demo/src/main/java/com/example/App.java:[5,18] illegal start of expression";
        StackTraceLinks.Link link = StackTraceLinks.parse(line);
        assertNotNull(link);
        assertEquals("/home/u/demo/src/main/java/com/example/App.java", link.file());
        assertEquals(5, link.line());
        assertEquals(18, link.column());
        assertEquals(line, link.raw());
    }

    @Test
    void mavenCompilerWarningLocationLinks() {
        StackTraceLinks.Link link = StackTraceLinks.parse(
                "[WARNING] /home/u/demo/src/main/java/com/example/App.java:[12,5] [deprecation] foo() in Bar has been"
                        + " deprecated");
        assertNotNull(link);
        assertEquals(12, link.line());
        assertEquals(5, link.column());
    }

    @Test
    void otherJvmSourcesAndWindowsPathsLink() {
        StackTraceLinks.Link kotlin = StackTraceLinks.parse("[ERROR] /p/src/main/kotlin/App.kt:[3,1] unresolved");
        assertNotNull(kotlin);
        assertEquals("/p/src/main/kotlin/App.kt", kotlin.file());
        StackTraceLinks.Link scala = StackTraceLinks.parse("[ERROR] /p/src/main/scala/App.scala:[7,9] not found");
        assertNotNull(scala);
        assertEquals(7, scala.line());
        StackTraceLinks.Link groovy = StackTraceLinks.parse("[ERROR] /p/src/main/groovy/App.groovy:[2,3] oops");
        assertNotNull(groovy);
        assertEquals(3, groovy.column());
        StackTraceLinks.Link windows =
                StackTraceLinks.parse("[ERROR] C:\\work\\demo\\src\\main\\java\\App.java:[5,18] illegal start");
        assertNotNull(windows);
        assertEquals("C:\\work\\demo\\src\\main\\java\\App.java", windows.file());
        assertEquals(5, windows.line());
    }

    @Test
    void javacsOwnColonFormStillLinksAndPlainLinesDoNot() {
        StackTraceLinks.Link javac = StackTraceLinks.parse("/src/demo/Main.java:12: error: cannot find symbol");
        assertNotNull(javac);
        assertEquals(12, javac.line());
        assertEquals(0, javac.column());
        assertNull(StackTraceLinks.parse("[ERROR] COMPILATION ERROR :"));
        assertNull(StackTraceLinks.parse("[INFO] Compiling 2 source files with javac [debug target 25]"));
    }
}
