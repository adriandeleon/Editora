package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What Run Configurations → <b>Add</b> prefills from, and {@code run.saveConfig} saves.
 *
 * <p>The pure naming rules are covered by {@code RunConfigDefaultsTest}; what only the controller can answer
 * is which buffers count as a source of a main class at all.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunConfigSuggestionFxTest {

    private static final String WITH_MAIN = """
            package com.example;

            public class App {
                public static void main(String[] args) {
                    System.out.println("hi");
                }
            }
            """;

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private String suggestion() throws Exception {
        return FxTestSupport.callOnFx(
                () -> (String) FxTestSupport.call(fx.controller, "suggestedMainClass", new Class[] {}));
    }

    private void open(Path file, String content) throws Exception {
        Files.writeString(file, content);
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(content);
            b.setPath(file);
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
        });
    }

    @Test
    void suggestsTheMainClassOfTheActiveJavaFile(@TempDir Path dir) throws Exception {
        open(dir.resolve("App.java"), WITH_MAIN);
        assertEquals("com.example.App", suggestion());
    }

    /** A Java file with no {@code main} has nothing to suggest — Add falls back to a blank configuration. */
    @Test
    void suggestsNothingForAJavaFileWithNoMain(@TempDir Path dir) throws Exception {
        open(dir.resolve("Helper.java"), "package com.example;\n\npublic class Helper {}\n");
        assertNull(suggestion());
    }

    /** A Markdown file is not a source of a Java main class, however much it may contain the word. */
    @Test
    void suggestsNothingForANonJavaFile(@TempDir Path dir) throws Exception {
        open(dir.resolve("notes.md"), "# public static void main(String[] args)\n");
        assertNull(suggestion());
    }

    private com.editora.config.RunConfiguration blank() throws Exception {
        return FxTestSupport.callOnFx(() -> (com.editora.config.RunConfiguration)
                FxTestSupport.call(fx.controller, "suggestedBlankConfiguration", new Class[] {}));
    }

    private com.editora.config.RunConfiguration script() throws Exception {
        return FxTestSupport.callOnFx(() -> (com.editora.config.RunConfiguration)
                FxTestSupport.call(fx.controller, "suggestedScriptConfiguration", new Class[] {}));
    }

    /** Add's Type follows the active file even when the file is not the thing to launch: a makefile is make's. */
    @Test
    void aMakefileOrPackageJsonSuggestsItsOwnType(@TempDir Path dir) throws Exception {
        open(dir.resolve("Makefile"), "all:\n\techo hi\n");
        assertEquals("make", blank().type());
        open(dir.resolve("package.json"), "{\"scripts\": {\"build\": \"tsc\"}}\n");
        assertEquals("npm", blank().type());
        open(dir.resolve("tool.py"), "print('hi')\n");
        assertEquals("python", blank().type());
    }

    /** No configuration type launches a Markdown note, so there is nothing to suggest and Add keeps its Java default. */
    @Test
    void aFileNoTypeLaunchesSuggestsNoType(@TempDir Path dir) throws Exception {
        open(dir.resolve("notes.md"), "# Notes\n");
        assertNull(blank());
    }

    /** A C, C++, Go or Rust file in front is what Add and Save as Run Configuration propose: its own type, the file. */
    @Test
    void aCompiledSourceFileSuggestsAConfigurationForItself(@TempDir Path dir) throws Exception {
        open(dir.resolve("tally.c"), "int main(void) { return 0; }\n");
        assertEquals("c", script().type());
        assertEquals("tally.c", script().name());
        assertEquals(dir.resolve("tally.c").toString(), script().target(), "no project: the file as it is");
        open(dir.resolve("tally.cpp"), "int main() { return 0; }\n");
        assertEquals("cpp", script().type());
        open(dir.resolve("main.go"), "package main\n\nfunc main() {}\n");
        assertEquals("go", script().type());
        open(dir.resolve("main.rs"), "fn main() {}\n");
        assertEquals("rust", script().type());
        assertEquals("", script().workingDir(), "blank: where Debug File runs the program");
    }
}
