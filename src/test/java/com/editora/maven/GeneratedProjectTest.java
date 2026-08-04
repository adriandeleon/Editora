package com.editora.maven;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GeneratedProjectTest {

    private static void write(Path root, String relative, String body) throws Exception {
        Path f = root.resolve(relative);
        Files.createDirectories(f.getParent());
        Files.writeString(f, body);
    }

    /**
     * Conventionally formatted, because that is what an archetype emits — and because
     * {@code MainMethodScanner} only reports a {@code main} at brace depth 1, i.e. with the class body brace
     * on its own line. A one-liner {@code class App { ... main ... }} is deliberately NOT detected; see
     * {@link #aClassBodyOnOneLineIsNotDetected()}.
     */
    private static String appWithMain(String pkg, String cls) {
        return "package " + pkg + ";\n"
                + "\n"
                + "public class " + cls + "\n"
                + "{\n"
                + "    public static void main( String[] args )\n"
                + "    {\n"
                + "        System.out.println( \"Hello World!\" );\n"
                + "    }\n"
                + "}\n";
    }

    @Test
    void findsTheQuickstartMainClass(@TempDir Path root) throws Exception {
        write(root, "src/main/java/com/example/demo/App.java", appWithMain("com.example.demo", "App"));
        assertEquals("com.example.demo.App", GeneratedProject.findMainClass(root));
    }

    @Test
    void returnsNullWhenTheArchetypeHasNoMainClass(@TempDir Path root) throws Exception {
        // A webapp/plugin archetype: real sources, nothing to launch. No configuration must be seeded.
        write(root, "src/main/java/com/example/Servlet.java", "package com.example;\npublic class Servlet {}\n");
        assertNull(GeneratedProject.findMainClass(root));
    }

    @Test
    void returnsNullWhenThereAreNoSources(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src").resolve("test").resolve("java"));
        assertNull(GeneratedProject.findMainClass(root));
    }

    @Test
    void ignoresTestSources(@TempDir Path root) throws Exception {
        // A runnable main under src/test/java is not what "Run" should mean.
        write(root, "src/test/java/com/example/Harness.java", appWithMain("com.example", "Harness"));
        assertNull(GeneratedProject.findMainClass(root));
    }

    @Test
    void isDeterministicWithSeveralMainClasses(@TempDir Path root) throws Exception {
        // Shallowest path first, then alphabetical — the same project must always seed the same config.
        write(root, "src/main/java/com/example/deep/nested/Zeta.java", appWithMain("com.example.deep.nested", "Zeta"));
        write(root, "src/main/java/com/example/Beta.java", appWithMain("com.example", "Beta"));
        write(root, "src/main/java/com/example/Alpha.java", appWithMain("com.example", "Alpha"));
        assertEquals("com.example.Alpha", GeneratedProject.findMainClass(root));
        assertEquals("com.example.Alpha", GeneratedProject.findMainClass(root), "stable across calls");
    }

    @Test
    void survivesAnUnreadableOrOversizeFile(@TempDir Path root) throws Exception {
        StringBuilder huge = new StringBuilder("package com.example;\npublic class Huge {\n");
        huge.append("// padding\n".repeat(40_000)); // past MAX_FILE_BYTES
        huge.append("public static void main(String[] a) {}\n}\n");
        write(root, "src/main/java/com/example/Huge.java", huge.toString());
        write(root, "src/main/java/com/example/Small.java", appWithMain("com.example", "Small"));
        assertEquals("com.example.Small", GeneratedProject.findMainClass(root), "the oversize file is skipped");
    }

    /**
     * A known, accepted boundary rather than a bug: the shared {@code MainMethodScanner} only sees a
     * {@code main} at brace depth 1, so a class whose body opens on the same line is not reported. The
     * gutter Run marker has the same limit. The consequence here is benign — no configuration is seeded,
     * which is the same graceful outcome as an archetype with no main class at all.
     */
    @Test
    void aClassBodyOnOneLineIsNotDetected(@TempDir Path root) throws Exception {
        write(
                root,
                "src/main/java/com/example/OneLine.java",
                "package com.example;\npublic class OneLine { public static void main(String[] a) {} }\n");
        assertNull(GeneratedProject.findMainClass(root));
    }

    @Test
    void nullProjectDirIsNullNotAnException() {
        assertNull(GeneratedProject.findMainClass(null));
    }
}
