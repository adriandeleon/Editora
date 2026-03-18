package org.adriandeleon.editora.commands;

import java.util.List;

public record CommandAction(
        String name,
        String description,
        String category,
        String shortcutHint,
        List<String> keywords,
        Runnable action
) {
    public CommandAction {
        category = category == null || category.isBlank() ? "General" : category;
        shortcutHint = shortcutHint == null ? "" : shortcutHint;
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }

    public CommandAction(String name, String description, Runnable action) {
        this(name, description, "General", "", List.of(), action);
    }

    public CommandAction(String name, String description, String category, Runnable action) {
        this(name, description, category, "", List.of(), action);
    }

    public CommandAction(String name, String description, String category, String shortcutHint, Runnable action) {
        this(name, description, category, shortcutHint, List.of(), action);
    }

    public boolean matches(String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }

        String normalized = filter.toLowerCase();
        return name.toLowerCase().contains(normalized)
                || description.toLowerCase().contains(normalized)
                || category.toLowerCase().contains(normalized)
                || shortcutHint.toLowerCase().contains(normalized)
                || keywords.stream().anyMatch(keyword -> keyword.toLowerCase().contains(normalized));
    }

    @Override
    public String toString() {
        return name + " — " + description;
    }
}

