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
 * What Settings → Run Configurations → <b>Add</b> prefills from, and {@code run.saveConfig} saves.
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
}
