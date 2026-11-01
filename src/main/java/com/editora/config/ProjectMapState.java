package com.editora.config;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * What the Project Map keeps of an exploration across restarts, per workspace (Project Map review 3 B6; workspace
 * schema v28): the folder it showed, its open folders and selected row, the Back/Forward places, the zoom and pan,
 * and the open code cards. Paths are relative to {@link #getRoot()} with {@code /} separators; the Map brings the
 * state back only for that folder, the first time its window shows, and drops what no longer exists.
 */
public class ProjectMapState {

    private String root = "";
    private List<String> expanded = new ArrayList<>();
    private String selected = "";
    private List<String> history = new ArrayList<>();
    private int historyIndex = -1;
    /** The map's zoom; 0 when no viewport was recorded (the Map then fits itself as on a first open). */
    private double zoom;

    private double offsetX;
    private double offsetY;
    private List<Card> cards = new ArrayList<>();

    public ProjectMapState() {}

    /** The folder the Map showed: an absolute local path. */
    public String getRoot() {
        return root;
    }

    public void setRoot(@Nullable String root) {
        this.root = root == null ? "" : root;
    }

    /** The open folders, relative to the root. */
    public List<String> getExpanded() {
        return expanded;
    }

    public void setExpanded(@Nullable List<String> expanded) {
        this.expanded = expanded == null ? new ArrayList<>() : new ArrayList<>(expanded);
    }

    /** The selected row, relative to the root; "" is the root itself. */
    public String getSelected() {
        return selected;
    }

    public void setSelected(@Nullable String selected) {
        this.selected = selected == null ? "" : selected;
    }

    /** The Back/Forward places, oldest first, relative to the root. */
    public List<String> getHistory() {
        return history;
    }

    public void setHistory(@Nullable List<String> history) {
        this.history = history == null ? new ArrayList<>() : new ArrayList<>(history);
    }

    /** Which of {@link #getHistory()} is the current place; -1 for none. */
    public int getHistoryIndex() {
        return historyIndex;
    }

    public void setHistoryIndex(int historyIndex) {
        this.historyIndex = historyIndex;
    }

    public double getZoom() {
        return zoom;
    }

    public void setZoom(double zoom) {
        this.zoom = zoom;
    }

    public double getOffsetX() {
        return offsetX;
    }

    public void setOffsetX(double offsetX) {
        this.offsetX = offsetX;
    }

    public double getOffsetY() {
        return offsetY;
    }

    public void setOffsetY(double offsetY) {
        this.offsetY = offsetY;
    }

    /** The open code cards, the least recently used first. */
    public List<Card> getCards() {
        return cards;
    }

    public void setCards(@Nullable List<Card> cards) {
        this.cards = cards == null ? new ArrayList<>() : new ArrayList<>(cards);
    }

    /**
     * One open code card: what it shows — a file, or a declaration of one (the declaration's path below its file)
     * — relative to the root; where it is in the map area's pixels; its unzoomed size; its text zoom; and whether
     * its Structure column was out.
     */
    public static class Card {
        private String path = "";
        private double x;
        private double y;
        private double width;
        private double height;
        private double textZoom = 1.0;
        private boolean structure;

        public Card() {}

        public Card(String path, double x, double y, double width, double height, double textZoom, boolean structure) {
            this.path = path == null ? "" : path;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.textZoom = textZoom;
            this.structure = structure;
        }

        public String getPath() {
            return path;
        }

        public void setPath(@Nullable String path) {
            this.path = path == null ? "" : path;
        }

        public double getX() {
            return x;
        }

        public void setX(double x) {
            this.x = x;
        }

        public double getY() {
            return y;
        }

        public void setY(double y) {
            this.y = y;
        }

        public double getWidth() {
            return width;
        }

        public void setWidth(double width) {
            this.width = width;
        }

        public double getHeight() {
            return height;
        }

        public void setHeight(double height) {
            this.height = height;
        }

        public double getTextZoom() {
            return textZoom;
        }

        public void setTextZoom(double textZoom) {
            this.textZoom = textZoom;
        }

        public boolean isStructure() {
            return structure;
        }

        public void setStructure(boolean structure) {
            this.structure = structure;
        }
    }
}
