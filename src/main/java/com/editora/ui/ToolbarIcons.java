package com.editora.ui;

import javafx.scene.Node;

/**
 * Resolves a {@link com.editora.toolbar.ToolbarCatalog} {@code iconKey} to a fresh JavaFX icon node,
 * keeping the pure {@code toolbar} package free of JavaFX. Mirrors {@code FileIcons.forFileName}.
 */
final class ToolbarIcons {

    private ToolbarIcons() {}

    /** A fresh icon node for {@code iconKey}, falling back to the generic file-sheet glyph if unknown. */
    static Node node(String iconKey) {
        return switch (iconKey == null ? "" : iconKey) {
            case "newFile" -> Icons.newFile();
            case "fileSheet" -> Icons.fileSheet();
            case "open" -> Icons.open();
            case "closeTab" -> Icons.closeTab();
            case "save" -> Icons.save();
            case "saveAs" -> Icons.saveAs();
            case "recent" -> Icons.recent();
            case "trash" -> Icons.trash();
            case "undo" -> Icons.undo();
            case "redo" -> Icons.redo();
            case "cut" -> Icons.cut();
            case "copy" -> Icons.copy();
            case "paste" -> Icons.paste();
            case "find" -> Icons.find();
            case "findInFiles" -> Icons.findInFiles();
            case "splitVertical" -> Icons.splitVertical();
            case "splitHorizontal" -> Icons.splitHorizontal();
            case "palette" -> Icons.palette();
            case "simpleMode" -> Icons.simpleMode();
            case "settings" -> Icons.settings();
            case "git" -> Icons.git();
            case "gitLog" -> Icons.gitLog();
            case "run" -> Icons.run();
            case "problems" -> Icons.problems();
            case "todo" -> Icons.todo();
            case "bookmark" -> Icons.bookmark();
            case "structure" -> Icons.structure();
            case "zen" -> Icons.zen();
            default -> Icons.fileSheet();
        };
    }
}
