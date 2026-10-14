package com.editora.command;

/** A named, runnable action. Commands are the unit dispatched by keybindings and the palette. */
public interface Command {

    String id();

    String title();

    void run();

    /**
     * A one-line description, resolved (lazily, so it follows the active UI language) from the message
     * catalog key {@code "command." + id + ".desc"}. Empty when no such key exists (e.g. a plugin or
     * dynamically-registered command), so callers can hide the line gracefully.
     */
    default String description() {
        String key = "command." + id() + ".desc";
        String value = com.editora.i18n.Messages.tr(key);
        return value.equals(key) ? "" : value; // tr returns the key itself when missing
    }

    /**
     * Words the palette also finds this command by, besides its title — matched only when the title itself
     * does not match, and ranked below every title match. By default the id's namespace when the title does
     * not carry it: {@code markdown.tableAddRow} is titled "Table: Add Row", and is found by "markdown table"
     * too. Empty when there is nothing to add. Never shown; not localized (ids are not).
     */
    default String keywords() {
        String id = id();
        int dot = id.indexOf('.');
        if (dot <= 0) {
            return "";
        }
        String title = title();
        return title.regionMatches(true, 0, id, 0, dot) ? "" : id.substring(0, dot);
    }

    /**
     * A command whose title is resolved (lazily, so it follows the active UI language) from the message
     * catalog key {@code "command." + id}. Preferred over the explicit-title overload for localization.
     */
    static Command of(String id, Runnable action) {
        return new Command() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String title() {
                return com.editora.i18n.Messages.tr("command." + id);
            }

            @Override
            public void run() {
                action.run();
            }
        };
    }

    static Command of(String id, String title, Runnable action) {
        return new Command() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String title() {
                return title;
            }

            @Override
            public void run() {
                action.run();
            }
        };
    }
}
