package com.editora.ui;

import java.util.function.Supplier;

import javafx.scene.Node;
import javafx.scene.layout.Region;

/** Metadata + content holder for one IntelliJ-style tool window. */
public final class ToolWindow {

    public enum Side {
        LEFT, RIGHT, BOTTOM
    }

    private final String id;
    private final String title;
    private final Side side;
    private final Supplier<Node> iconSupplier;
    private final Region content;

    public ToolWindow(String id, String title, Side side, Supplier<Node> iconSupplier, Region content) {
        this.id = id;
        this.title = title;
        this.side = side;
        this.iconSupplier = iconSupplier;
        this.content = content;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
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
}
