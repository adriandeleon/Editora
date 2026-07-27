package com.editora.config;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The saved shape of a window's editor area: the tree of split groups, so a layout of two files side by side
 * (or an L of three) comes back on the next launch instead of collapsing into one strip (#762).
 *
 * <p>A node is either a <b>branch</b> — an {@link #getOrientation() orientation} plus two or more
 * {@link #getChildren() children} — or a <b>leaf</b>, which is one group of tabs. Leaves carry no file list:
 * membership lives on {@link WorkspaceState.OpenFile#getGroup()}, which stores a leaf's ordinal in
 * <b>depth-first order</b>. Keeping the files in one flat list and the shape here means the two can never
 * disagree about which files exist — a leaf that listed its own paths would be a second, divergent copy of
 * the same information, and restoring from two copies that disagree has no good answer.
 *
 * <p>Absent from an older session file, which is exactly right: no tree means one group, and every
 * {@code OpenFile} defaults to group 0.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EditorGroupLayout {

    /** {@code "HORIZONTAL"} or {@code "VERTICAL"} on a branch; {@code null} on a leaf. */
    private String orientation;

    /** Two or more children on a branch; empty on a leaf. */
    private List<EditorGroupLayout> children = new ArrayList<>();

    /** Leaf only: the index of the selected tab within this group. Clamped on restore. */
    private int selected;

    public EditorGroupLayout() {}

    /** A leaf group with the given selected-tab index. */
    public static EditorGroupLayout leaf(int selected) {
        EditorGroupLayout node = new EditorGroupLayout();
        node.selected = Math.max(0, selected);
        return node;
    }

    /** A branch splitting {@code children} along {@code orientation}. */
    public static EditorGroupLayout branch(String orientation, List<EditorGroupLayout> children) {
        EditorGroupLayout node = new EditorGroupLayout();
        node.orientation = orientation;
        node.setChildren(children);
        return node;
    }

    /** Whether this node is a group rather than a split. */
    @JsonIgnore
    public boolean isLeaf() {
        return children.isEmpty();
    }

    /** The number of leaves at or below this node — i.e. how many groups this subtree describes. */
    @JsonIgnore
    public int leafCount() {
        if (isLeaf()) {
            return 1;
        }
        int n = 0;
        for (EditorGroupLayout child : children) {
            n += child.leafCount();
        }
        return n;
    }

    public String getOrientation() {
        return orientation;
    }

    public void setOrientation(String orientation) {
        this.orientation = orientation;
    }

    public List<EditorGroupLayout> getChildren() {
        return children;
    }

    public void setChildren(List<EditorGroupLayout> children) {
        this.children = children == null ? new ArrayList<>() : new ArrayList<>(children);
    }

    public int getSelected() {
        return selected;
    }

    public void setSelected(int selected) {
        this.selected = Math.max(0, selected);
    }
}
