package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import com.editora.config.Project;
import com.editora.config.RunConfiguration;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opening a project whose saved Java run configuration has no Java file open must open the class that will
 * run — otherwise the launch reports "open a Java file from the project" while the configuration sits right
 * there in the toolbar (a Java launch routes its classpath resolution through an <em>open</em> Java file).
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenMainClassOnProjectOpenFxTest {

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

    /** A Maven-shaped project with one main class on disk. */
    private Path project(String name) throws Exception {
        Path root = Files.createTempDirectory("editora-openmain-" + name);
        Path src = root.resolve("src/main/java/com/example/demo");
        Files.createDirectories(src);
        Files.writeString(
                src.resolve("App.java"),
                "package com.example.demo;\n\npublic class App\n{\n"
                        + "    public static void main( String[] args )\n    {\n    }\n}\n");
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        return root;
    }

    /** Points the window at {@code root} as its project with {@code cfg} saved, then runs the startup hook. */
    private void openWith(Path root, RunConfiguration cfg) throws Exception {
        FxTestSupport.runOnFx(() -> {
            Object projects = FxTestSupport.field(fx.controller, "projects");
            Project p = (Project) FxTestSupport.call(
                    projects,
                    "createOrGet",
                    new Class[] {String.class, Path.class},
                    root.getFileName().toString(),
                    root);
            fx.controller.setWindowContext(fx.windowManager, p);
            Object config = FxTestSupport.field(fx.controller, "config");
            Object state = FxTestSupport.call(config, "getWorkspaceState", new Class[] {});
            FxTestSupport.call(state, "setRunConfigurations", new Class[] {List.class}, List.of(cfg));
            FxTestSupport.call(state, "setSelectedRunConfig", new Class[] {String.class}, cfg.name());
            FxTestSupport.invoke(fx.controller, "openMainClassForRunConfig");
        });
    }

    private boolean javaTabOpen(String fileName) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            TabPane tabs = FxTestSupport.field(fx.controller, "tabPane");
            for (Tab t : tabs.getTabs()) {
                Object content = t.getUserData();
                if (content instanceof EditorBuffer b
                        && b.getPath() != null
                        && b.getPath().getFileName().toString().equals(fileName)) {
                    return true;
                }
            }
            return false;
        });
    }

    private void closeAllTabs() throws Exception {
        FxTestSupport.runOnFx(() -> {
            TabPane tabs = FxTestSupport.field(fx.controller, "tabPane");
            tabs.getTabs().clear();
        });
    }

    @Test
    void opensTheConfiguredMainClassWhenNoJavaFileIsOpen() throws Exception {
        closeAllTabs();
        Path root = project("a");
        openWith(root, new RunConfiguration("demo", "com.example.demo.App", "", "", "", root.toString()));
        assertTrue(javaTabOpen("App.java"), "the class the configuration launches is opened");
    }

    @Test
    void doesNothingWhenTheMainClassIsAFileName() throws Exception {
        closeAllTabs();
        Path root = project("b");
        // The App.java-in-mainClass mistake is reported at launch with its own message; acting on it here
        // would re-hide it behind a file that opens but still cannot run.
        openWith(root, new RunConfiguration("demo", "App.java", "", "", "", root.toString()));
        assertFalse(javaTabOpen("App.java"));
    }

    @Test
    void doesNothingWhenTheConfigurationHasNoMainClass() throws Exception {
        closeAllTabs();
        Path root = project("c");
        openWith(root, new RunConfiguration("demo", "", "", "", "", root.toString()));
        assertFalse(javaTabOpen("App.java"));
    }

    @Test
    void doesNothingWhenTheClassIsNotOnDisk() throws Exception {
        closeAllTabs();
        Path root = project("d");
        openWith(root, new RunConfiguration("demo", "com.example.demo.Missing", "", "", "", root.toString()));
        assertFalse(javaTabOpen("Missing.java"));
    }
}
