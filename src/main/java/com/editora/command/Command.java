package com.editora.command;

/** A named, runnable action. Commands are the unit dispatched by keybindings and the palette. */
public interface Command {

    String id();

    String title();

    void run();

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
