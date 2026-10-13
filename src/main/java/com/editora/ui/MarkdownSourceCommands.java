package com.editora.ui;

import com.editora.command.Command;

/**
 * The Markdown commands that work on a document's structure rather than on the text at the caret: going to
 * a heading anywhere in the project, formatting, heading numbers. One call from the window's command
 * registrar, like {@link MarkdownSettingCommands}.
 */
final class MarkdownSourceCommands {

    private MarkdownSourceCommands() {}

    static void register(WindowCommandRegistrar.Host host) {
        host.registry()
                .register(Command.of(
                        "project.gotoMarkdownHeading",
                        () -> host.indexCoordinator().headingPicker.show()));
    }
}
