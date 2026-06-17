package com.editora.todo;

/**
 * A user-configurable highlight pattern (IntelliJ-style "TODO" patterns): a display {@code name}, a
 * {@code pattern} (Java regex), a highlight {@code color} (web hex), and {@code caseSensitive}/{@code enabled}
 * flags. A mutable Jackson POJO so it round-trips in {@code settings.toml} as part of {@code Settings}
 * (an array of tables); compiled to a {@link java.util.regex.Pattern} by {@link TodoPatterns}.
 */
public class TodoPattern {

    private String name = "";
    private String pattern = "";
    private String color = TodoPatterns.DEFAULT_COLOR;
    private boolean caseSensitive = false;
    private boolean enabled = true;

    public TodoPattern() {}

    public TodoPattern(String name, String pattern, String color, boolean caseSensitive, boolean enabled) {
        this.name = name;
        this.pattern = pattern;
        this.color = color;
        this.caseSensitive = caseSensitive;
        this.enabled = enabled;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    public void setCaseSensitive(boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
