package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javafx.collections.FXCollections;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.Group;
import javafx.scene.Node;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Project Map's rows for assistive technology (Map explore, position 14; it reverses review 2's position 7,
 * which left the canvas a single text summary). The map is painted on a Canvas, which a screen reader cannot
 * read, so the rows drawn in the viewport are exposed as virtual accessible children: one {@link Row} node per
 * row on screen, role {@link AccessibleRole#TREE_ITEM}, named with its kind and states, selected, expanded and
 * leaf as a tree item is, with its screen bounds. They sit in this {@link Group}, a child of the surface laid
 * under its controls, which answers as their {@link AccessibleRole#TREE_VIEW} (row count, row at index, the
 * selected row). Nothing here paints, lays out or takes the mouse.
 *
 * <p>Only the rows in the viewport get a node, so a 1,200-row map costs what its screen does. The surface hands
 * the rows over once per pulse after a paint when a screen reader is listening ({@code
 * Platform.isAccessibilityActive()}), and on demand when one asks for the children; a row keeps its node while it
 * stays on screen, so a reader's place survives a scroll.
 */
final class MapAccessibleRows extends Group {

    /** What a row node does when assistive technology acts on it; run on the FX thread. */
    interface Actions {
        /** Selects the row (the reader moved its focus there). */
        void select(Path path);

        /** The row's default action: what a click on it does (a folder opens or closes, a file opens). */
        void activate(Path path);

        /** Opens ({@code expand}) or closes the column the row opens; nothing when it already is. */
        void expand(Path path, boolean expand);

        /** The row's rectangle in screen coordinates, from its rectangle on the surface. */
        @Nullable
        Bounds toScreen(double x, double y, double width, double height);
    }

    /** One row on screen as the surface laid it out: where it is and what a reader hears. */
    record Spec(
            Path path,
            @Nullable Path parent,
            String name,
            int level,
            boolean selected,
            boolean expanded,
            boolean leaf,
            double x,
            double y,
            double width,
            double height) {}

    private final Actions actions;
    private final Map<Path, Row> byPath = new HashMap<>();
    private List<Row> rows = List.of();
    /** How many times the rows were handed over: a test counts the work per pulse. */
    long rebuildsForTest;

    MapAccessibleRows(Actions actions) {
        this.actions = actions;
        setManaged(false);
        setMouseTransparent(true);
        setFocusTraversable(false);
        setAccessibleRole(AccessibleRole.TREE_VIEW);
        setAccessibleText(tr("project.map.accessible.rows"));
    }

    /**
     * The rows on screen now, top to bottom per column. A row that was on screen before keeps its node, and is
     * told when it became selected or expanded; the others are dropped. Returns the selected row's node, or
     * null when the selection is off screen.
     */
    @Nullable
    Row update(List<Spec> specs) {
        rebuildsForTest++;
        List<Row> next = new ArrayList<>(specs.size());
        Map<Path, Row> kept = new HashMap<>();
        Row selected = null;
        for (Spec spec : specs) {
            Row row = byPath.get(spec.path());
            if (row == null) {
                row = new Row();
            }
            Spec before = row.spec;
            row.spec = spec;
            row.index = next.size();
            next.add(row);
            kept.put(spec.path(), row);
            if (spec.selected()) {
                selected = row;
            }
            if (before != null) {
                if (before.selected() != spec.selected()) {
                    row.notifyAccessibleAttributeChanged(AccessibleAttribute.SELECTED);
                }
                if (before.expanded() != spec.expanded()) {
                    row.notifyAccessibleAttributeChanged(AccessibleAttribute.EXPANDED);
                }
                if (!before.name().equals(spec.name())) {
                    row.notifyAccessibleAttributeChanged(AccessibleAttribute.TEXT);
                }
            }
        }
        byPath.clear();
        byPath.putAll(kept);
        if (!next.equals(rows)) {
            rows = List.copyOf(next);
            getChildren().setAll(rows); // a dropped node leaves the scene, which disposes its peer
        }
        return selected;
    }

    /** The node of the row at {@code path}, when it is on screen. */
    @Nullable
    Row row(@Nullable Path path) {
        return path == null ? null : byPath.get(path);
    }

    /** The row nodes, in order. */
    List<Row> rows() {
        return rows;
    }

    @Override
    public @Nullable Object queryAccessibleAttribute(AccessibleAttribute attribute, Object... parameters) {
        switch (attribute) {
            case ROW_COUNT:
                return rows.size();
            case ROW_AT_INDEX: {
                int index = parameters.length > 0 && parameters[0] instanceof Integer at ? at : -1;
                return index >= 0 && index < rows.size() ? rows.get(index) : null;
            }
            case SELECTED_ITEMS: {
                List<Node> selected = new ArrayList<>(1);
                for (Row row : rows) {
                    if (row.spec != null && row.spec.selected()) {
                        selected.add(row);
                    }
                }
                return FXCollections.observableArrayList(selected);
            }
            case FOCUS_ITEM: {
                for (Row row : rows) {
                    if (row.spec != null && row.spec.selected()) {
                        return row;
                    }
                }
                return null;
            }
            case MULTIPLE_SELECTION:
                return false;
            case VISIBLE:
                return true;
            default:
                return super.queryAccessibleAttribute(attribute, parameters);
        }
    }

    /** One row on screen; a tree item for assistive technology only. */
    final class Row extends Group {
        private @Nullable Spec spec;
        private int index;

        private Row() {
            setManaged(false);
            setMouseTransparent(true);
            setFocusTraversable(false);
            setAccessibleRole(AccessibleRole.TREE_ITEM);
        }

        Spec spec() {
            return Objects.requireNonNull(spec);
        }

        @Override
        public @Nullable Object queryAccessibleAttribute(AccessibleAttribute attribute, Object... parameters) {
            Spec known = spec;
            if (known == null) {
                return super.queryAccessibleAttribute(attribute, parameters);
            }
            switch (attribute) {
                case TEXT:
                    return known.name();
                case SELECTED:
                    return known.selected();
                case EXPANDED:
                    return known.expanded();
                case LEAF:
                    return known.leaf();
                case DISCLOSURE_LEVEL:
                    return known.level();
                case INDEX:
                    return index;
                case FOCUSED:
                    return known.selected();
                case VISIBLE:
                    return true;
                case TREE_ITEM_PARENT:
                    return row(known.parent());
                case TREE_ITEM_COUNT:
                    return children(known.path()).size();
                case TREE_ITEM_AT_INDEX: {
                    List<Row> children = children(known.path());
                    int at = parameters.length > 0 && parameters[0] instanceof Integer i ? i : -1;
                    return at >= 0 && at < children.size() ? children.get(at) : null;
                }
                case BOUNDS: {
                    Bounds screen = actions.toScreen(known.x(), known.y(), known.width(), known.height());
                    return screen != null ? screen : new BoundingBox(0, 0, 0, 0);
                }
                default:
                    return super.queryAccessibleAttribute(attribute, parameters);
            }
        }

        @Override
        public void executeAccessibleAction(AccessibleAction action, Object... parameters) {
            Spec known = spec;
            if (known == null) {
                return;
            }
            switch (action) {
                case REQUEST_FOCUS -> actions.select(known.path());
                case FIRE -> actions.activate(known.path());
                case EXPAND -> actions.expand(known.path(), true);
                case COLLAPSE -> actions.expand(known.path(), false);
                default -> super.executeAccessibleAction(action, parameters);
            }
        }

        /** The rows on screen whose parent is this row (its column's rows, as far as they are on screen). */
        private List<Row> children(Path path) {
            List<Row> children = new ArrayList<>();
            for (Row row : rows) {
                if (row.spec != null && path.equals(row.spec.parent())) {
                    children.add(row);
                }
            }
            return children;
        }

        @Override
        public String toString() {
            Spec known = spec;
            return "Row[" + (known == null ? "?" : known.name()) + "]";
        }
    }
}
