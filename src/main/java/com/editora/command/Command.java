package com.editora.command;

/** A named, runnable action. Commands are the unit dispatched by keybindings and the palette. */
public interface Command {

    String id();

    String title();

    void run();

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
