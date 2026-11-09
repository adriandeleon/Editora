package com.editora.config;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * What the Project Map keeps of an exploration across restarts, per workspace (Project Map review 3 B6; workspace
 * schema v28): the folder it showed, its open folders and selected row, the Back/Forward places, the zoom and pan,
 * and the open code cards — with their carets and the answers asked from them (v31). Paths are relative to {@link #getRoot()} with {@code /} separators; the Map brings the
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
     * — relative to the root; where it is in the map area's pixels; its unzoomed size; its text zoom; whether
     * its Structure column was out; and whether it was pinned (never evicted; v29, absent means not).
     */
    public static class Card {
        private String path = "";
        private double x;
        private double y;
        private double width;
        private double height;
        private double textZoom = 1.0;
        private boolean structure;
        private boolean pinned;

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

        /** The card was pinned: the open-card limit never evicts it (Map explore, cards group). */
        public boolean isPinned() {
            return pinned;
        }

        public void setPinned(boolean pinned) {
            this.pinned = pinned;
        }

        private int caretLine = -1;
        private int caretColumn;
        private int topLine = -1;
        private List<Answer> answers = new ArrayList<>();

        /** The file's 0-based line the caret was on; -1 when none was recorded (v31, absent before). */
        public int getCaretLine() {
            return caretLine;
        }

        public void setCaretLine(int caretLine) {
            this.caretLine = caretLine;
        }

        /** The caret's 0-based column on {@link #getCaretLine()}. */
        public int getCaretColumn() {
            return caretColumn;
        }

        public void setCaretColumn(int caretColumn) {
            this.caretColumn = caretColumn;
        }

        /** The file's 0-based line at the top of the card's text; -1 when none was recorded (v31). */
        public int getTopLine() {
            return topLine;
        }

        public void setTopLine(int topLine) {
            this.topLine = topLine;
        }

        /**
         * The answers asked from this card (References, Callers, Go to Definition…), with their edges to the cards
         * they opened (v31; Map code browser gap 9).
         */
        public List<Answer> getAnswers() {
            return answers;
        }

        public void setAnswers(@Nullable List<Answer> answers) {
            this.answers = answers == null ? new ArrayList<>() : new ArrayList<>(answers);
        }

        /** A copy of this card, its answers shared (they are not changed once read). */
        public Card copy() {
            Card copy = new Card(path, x, y, width, height, textZoom, structure);
            copy.pinned = pinned;
            copy.caretLine = caretLine;
            copy.caretColumn = caretColumn;
            copy.topLine = topLine;
            copy.answers = new ArrayList<>(answers);
            return copy;
        }
    }

    /**
     * One answer stuck on a card: what was asked ({@code kind}, a {@code MapCardLinks.Kind} name), how it was found
     * ({@code via}: SERVER or NAME), the name asked about, its places grouped and ranked as the cards open (each with
     * whether it had an edge to an open card, and whether that card was the answer's own), and the places themselves
     * for its list. Paths are relative to the Map's root.
     */
    public static class Answer {
        private String kind = "";
        private String via = "";
        private @Nullable String name;
        private List<Group> groups = new ArrayList<>();
        private List<Place> places = new ArrayList<>();

        public Answer() {}

        public String getKind() {
            return kind;
        }

        public void setKind(@Nullable String kind) {
            this.kind = kind == null ? "" : kind;
        }

        public String getVia() {
            return via;
        }

        public void setVia(@Nullable String via) {
            this.via = via == null ? "" : via;
        }

        public @Nullable String getName() {
            return name;
        }

        public void setName(@Nullable String name) {
            this.name = name;
        }

        public List<Group> getGroups() {
            return groups;
        }

        public void setGroups(@Nullable List<Group> groups) {
            this.groups = groups == null ? new ArrayList<>() : new ArrayList<>(groups);
        }

        public List<Place> getPlaces() {
            return places;
        }

        public void setPlaces(@Nullable List<Place> places) {
            this.places = places == null ? new ArrayList<>() : new ArrayList<>(places);
        }
    }

    /**
     * A card an answer leads to: its card's path (a file, or a declaration below its file), its file, its label,
     * its first place's 0-based line, how many places it holds, the text marking the place, whether the answer had
     * an edge to it, and whether the card was opened by the answer (and so closes with its card).
     */
    public static class Group {
        private String path = "";
        private String file = "";
        private String label = "";
        private int line;
        private int count = 1;
        private @Nullable String mark;
        private boolean edge;
        private boolean owned;

        public Group() {}

        public String getPath() {
            return path;
        }

        public void setPath(@Nullable String path) {
            this.path = path == null ? "" : path;
        }

        public String getFile() {
            return file;
        }

        public void setFile(@Nullable String file) {
            this.file = file == null ? "" : file;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(@Nullable String label) {
            this.label = label == null ? "" : label;
        }

        public int getLine() {
            return line;
        }

        public void setLine(int line) {
            this.line = line;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public @Nullable String getMark() {
            return mark;
        }

        public void setMark(@Nullable String mark) {
            this.mark = mark;
        }

        public boolean isEdge() {
            return edge;
        }

        public void setEdge(boolean edge) {
            this.edge = edge;
        }

        public boolean isOwned() {
            return owned;
        }

        public void setOwned(boolean owned) {
            this.owned = owned;
        }
    }

    /** One place of an answer: its file, 0-based line and column, how many places it stands for, and its name. */
    public static class Place {
        private String file = "";
        private int line;
        private int column;
        private int count = 1;
        private @Nullable String name;

        public Place() {}

        public String getFile() {
            return file;
        }

        public void setFile(@Nullable String file) {
            this.file = file == null ? "" : file;
        }

        public int getLine() {
            return line;
        }

        public void setLine(int line) {
            this.line = line;
        }

        public int getColumn() {
            return column;
        }

        public void setColumn(int column) {
            this.column = column;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public @Nullable String getName() {
            return name;
        }

        public void setName(@Nullable String name) {
            this.name = name;
        }
    }
}
