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
    private final Region content;
    private final String commandId;

    public ToolWindow(
            String id, String title, Side side, Supplier<Node> iconSupplier, Region content, String commandId) {
        this.id = id;
        this.title.set(title);
        this.side = side;
        this.iconSupplier = iconSupplier;
        this.content = content;
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

    public Region getContent() {
        return content;
    }

    /** Optional id of the command that toggles this tool window — used to look up its keybinding. */
    public String getCommandId() {
        return commandId;
    }
}
