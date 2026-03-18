package org.adriandeleon.editora.plugins;

import org.adriandeleon.editora.commands.CommandAction;

import java.nio.file.Path;

public interface EditoraContext {
    void registerCommand(CommandAction commandAction);

    void registerPluginAction(CommandAction commandAction);

    void openFile(Path path);

    void insertTextAtCaret(String text);

    void replaceSelection(String text);

    void replaceActiveDocumentText(String text);

    java.util.Optional<String> activeDocumentText();

    java.util.Optional<Path> activeDocumentPath();

    java.util.List<Path> openDocumentPaths();

    java.util.Optional<Path> workspaceRoot();

    void refreshWorkspace();

    void showStatusMessage(String message);

    Path pluginsDirectory();
}

