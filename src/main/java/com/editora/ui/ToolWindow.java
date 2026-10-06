package com.editora.ui;

import java.util.function.Supplier;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.Node;
import javafx.scene.layout.Region;

/** Metadata + content holder for one IntelliJ-style tool window. */
public final class ToolWindow {

    public enum Side {
        LEFT,
        RIGHT,
        BOTTOM
    }

    private final String id;
    /** Mutable so a tool window can retitle itself at runtime (e.g. Project ⇄ Current Folder); the
     *  header label binds to it. */
    private final StringProperty title = new SimpleStringProperty();

    private final Side side;
    private final Supplier<Node> iconSupplier;
    /** Builds the content on first use; {@code null} once it has (or when the content was handed in built). */
    private Supplier<? extends Region> contentSupplier;

    private Region content;
    private final String commandId;

    public ToolWindow(
            String id, String title, Side side, Supplier<Node> iconSupplier, Region content, String commandId) {
        this(id, title, side, iconSupplier, (Supplier<Region>) null, commandId);
        this.content = content;
    }

    /**
     * A tool window whose content is built the first time it is needed — normally its first open. For a
     * panel that nothing has to reach while it is closed, so a window that never opens it never builds it.
     */
    public ToolWindow(
            String id,
            String title,
            Side side,
            Supplier<Node> iconSupplier,
            Supplier<? extends Region> content,
            String commandId) {
        this.id = id;
        this.title.set(title);
        this.side = side;
        this.iconSupplier = iconSupplier;
        this.contentSupplier = content;
        this.commandId = commandId;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title.get();
    }

    /** Retitles this tool window at runtime; the header label is bound to this property. */
    public void setTitle(String title) {
        this.title.set(title);
    }

    public StringProperty titleProperty() {
        return title;
    }

    public Side getSide() {
        return side;
    }

    /** Returns a fresh icon node — a JavaFX Node can only have one parent, so each consumer needs its own. */
    public Node createIcon() {
        return iconSupplier.get();
    }

    /** The content node, building it now if this is its first use. */
    public Region getContent() {
        if (contentSupplier != null) {
            Supplier<? extends Region> build = contentSupplier;
            contentSupplier = null;
            content = build.get();
        }
        return content;
    }

    /** The content node if it exists yet, else {@code null} — for a lookup that must not be what builds it. */
    public Region contentIfBuilt() {
        return contentSupplier == null ? content : null;
    }

    /** Optional id of the command that toggles this tool window — used to look up its keybinding. */
    public String getCommandId() {
        return commandId;
    }
}
