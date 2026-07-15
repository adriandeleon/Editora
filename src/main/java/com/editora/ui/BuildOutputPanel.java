package com.editora.ui;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import com.editora.build.OutputStyle;
import com.editora.run.StackTraceLinks;

/**
 * The shared <b>Build Output</b> tool window: a {@link TabPane} with one {@link BuildToolPanel} tab per build
 * tool that has run (Maven/npm/Cargo/Go/Gradle), so a polyglot repo gets a tab per active build instead of five
 * separate tool windows. Every {@code BuildCoordinator} streams here through an <em>owner</em>-routed API (the
 * owner is the coordinator): {@link #started} lazily creates the tool's tab (titled with the tool name) and
 * selects it, and {@link #appendOutput}/{@link #finished}/{@link #failed} route to that tool's own console, so
 * two concurrent builds stream side by side into their own tabs without interleaving. Tabs persist for the
 * session (bounded — at most one per tool), so a finished build's output stays readable.
 */
public final class BuildOutputPanel extends TabPane implements ToolWindowContent {

    private final Map<Object, BuildToolPanel> consoles = new IdentityHashMap<>();
    private final Map<Object, Tab> tabs = new IdentityHashMap<>();

    /** Applied to every console (kept so lazily-created tabs match): the editor code-area font. */
    private String fontFamily;

    private int fontSize;
    /** The clicked-stack-trace-link handler shared by every console. */
    private Consumer<StackTraceLinks.Link> onLink;

    public BuildOutputPanel() {
        getStyleClass().add("build-output-tabs");
        setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); // one bounded tab per tool; persist for the session
    }

    /** The clicked-file-path handler for every tab's console output (jump to it). */
    public void setOnLink(Consumer<StackTraceLinks.Link> onLink) {
        this.onLink = onLink;
        consoles.values().forEach(c -> c.setOnLink(onLink));
    }

    /** Matches every console's font to the editor's code-area font (family + effective size). */
    public void setOutputFont(String family, int size) {
        this.fontFamily = family;
        this.fontSize = size;
        consoles.values().forEach(c -> c.setOutputFont(family, size));
    }

    /** A build starts: get-or-create {@code owner}'s tab (titled {@code toolName}), select it, and begin. */
    public void started(Object owner, String toolName, String header, OutputStyle style, Runnable onStop) {
        BuildToolPanel console = consoleFor(owner, toolName);
        getSelectionModel().select(tabs.get(owner));
        console.started(header, style, onStop);
    }

    public void appendOutput(Object owner, String line, boolean stderr) {
        BuildToolPanel console = consoles.get(owner);
        if (console != null) {
            console.appendOutput(line, stderr);
        }
    }

    public void finished(Object owner, int code) {
        BuildToolPanel console = consoles.get(owner);
        if (console != null) {
            console.finished(code);
        }
    }

    public void failed(Object owner, String message) {
        BuildToolPanel console = consoles.get(owner);
        if (console != null) {
            console.failed(message);
        }
    }

    private BuildToolPanel consoleFor(Object owner, String toolName) {
        BuildToolPanel console = consoles.get(owner);
        if (console == null) {
            console = new BuildToolPanel();
            if (onLink != null) {
                console.setOnLink(onLink);
            }
            if (fontFamily != null) {
                console.setOutputFont(fontFamily, fontSize);
            }
            Tab tab = new Tab(toolName, console);
            consoles.put(owner, console);
            tabs.put(owner, tab);
            getTabs().add(tab);
        }
        return console;
    }

    @Override
    public void focusFirstItem() {
        Tab selected = getSelectionModel().getSelectedItem();
        if (selected != null && selected.getContent() instanceof ToolWindowContent content) {
            content.focusFirstItem();
        }
    }
}
