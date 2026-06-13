package com.example.editora;

import com.editora.plugin.ActiveEditor;
import com.editora.plugin.Plugin;
import com.editora.plugin.PluginContext;
import com.editora.plugin.ToolWindowSide;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * A demo plugin that exercises the whole {@link PluginContext} surface: a palette command + keybinding,
 * a command that edits the active buffer, a dockable tool window, an editor right-click item, and a
 * clickable status-bar segment. Pair it with the declarative {@code keymap}/{@code commands}/{@code snippets/}
 * in {@code plugin.json} + {@code snippets/} for the full picture.
 *
 * <p>Compile against Editora's exported API ({@code com.editora.plugin}) + JavaFX — see {@code build.sh}.
 * The plugin jar is loaded by a child {@code URLClassLoader} whose parent is the app loader, so it works
 * the same in dev and inside the packaged jlink image.
 */
public class HelloPlugin implements Plugin {

    @Override
    public void start(PluginContext ctx) {
        ctx.log("example-plugin starting in this window");

        // 1) A palette command + a keybinding bound from code.
        ctx.registerCommand("sayHello", "Example: Say Hello",
                () -> ctx.setStatus("Hello from the example plugin!"));
        ctx.bindKey("C-c C-h", "sayHello");

        // 2) A command that edits the active buffer (also bound via plugin.json's keymap).
        ctx.registerCommand("insertStamp", "Example: Insert Marker", () -> {
            ActiveEditor ed = ctx.activeEditor();
            ed.insertAtCaret("<!-- inserted by example-plugin -->");
        });

        // 3) A dockable tool window (its content node is built fresh per window).
        Label body = new Label("This panel is contributed by the example plugin.\n"
                + "Plugin dir: " + ctx.pluginDir());
        body.setWrapText(true);
        StackPane pane = new StackPane(body);
        pane.setPadding(new Insets(12));
        Region content = pane;
        ctx.registerToolWindow("hello", "Example", ToolWindowSide.BOTTOM, content, null);

        // 4) An editor right-click item operating on the selection.
        ctx.addEditorMenuItem("Uppercase selection (example)", ed -> {
            String sel = ed.selectedText();
            if (sel != null && !sel.isEmpty()) {
                ed.replaceSelection(sel.toUpperCase());
            }
        });

        // 5) A clickable status-bar segment (runs the namespaced command on click).
        ctx.addStatusBarSegment("👋 Example", "sayHello");
    }

    @Override
    public void stop() {
        // No resources to release in this demo.
    }
}
