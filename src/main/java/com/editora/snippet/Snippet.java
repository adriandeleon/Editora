package com.editora.snippet;

/**
 * A snippet definition: a {@code prefix} (the trigger word typed before Tab), the {@code body}
 * template (TextMate/VS Code syntax, lines joined with {@code \n}), a human {@code name} and
 * {@code description}, and the {@code language} it belongs to ({@code "global"} = all files).
 */
public record Snippet(String name, String prefix, String body, String description, String language) {}
