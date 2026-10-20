package com.editora.ui;

import java.util.function.Supplier;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.Node;
import javafx.scene.layout.Region;

import org.jspecify.annotations.Nullable;

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

    /** Something this tool window shows is under way (a program running); its stripe button badges a spinner. */
    private final BooleanProperty busy = new SimpleBooleanProperty(this, "busy");

    private final Side side;
    private final Supplier<Node> iconSupplier;
    /** Builds the content on first use; {@code null} once it has (or when the content was handed in built). */
    private @Nullable Supplier<? extends Region> contentSupplier;

    private @Nullable Region content;
    private final String commandId;

    /** The share of the window's height this bottom window takes on its first open (0: its side's default). */
    private double firstOpenShare;

    /**
     * A bottom window that is unusable at the default strip height asks for more on its first open: the Debug
     * window showed one variable row in a 1000×650 window (review C4). The user's own size wins afterwards.
     */
    public void setFirstOpenShare(double share) {
        this.firstOpenShare = Math.max(0, Math.min(0.8, share));
    }

    public double firstOpenShare() {
        return firstOpenShare;
    }

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
            @Nullable Supplier<? extends Region> content,
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

    /** True while this window's work runs — bound by its owner (the Run window to its program's run state). */
    public BooleanProperty busyProperty() {
        return busy;
    }

    public Side getSide() {
        return side;
    }

    /** Returns a fresh icon node — a JavaFX Node can only have one parent, so each consumer needs its own. */
    public Node createIcon() {
        return iconSupplier.get();
    }

    /** The content node, building it now if this is its first use. */
    public @Nullable Region getContent() {
        if (contentSupplier != null) {
            Supplier<? extends Region> build = contentSupplier;
            contentSupplier = null;
            content = build.get();
        }
        return content;
    }

    /** The content node if it exists yet, else {@code null} — for a lookup that must not be what builds it. */
    public @Nullable Region contentIfBuilt() {
        return contentSupplier == null ? content : null;
    }

    /** Optional id of the command that toggles this tool window — used to look up its keybinding. */
    public String getCommandId() {
        return commandId;
    }
}
