package org.adriandeleon.editora.exampleplugin;

import org.adriandeleon.editora.commands.CommandAction;
import org.adriandeleon.editora.plugins.EditoraContext;
import org.adriandeleon.editora.plugins.EditoraPlugin;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class TimestampPlugin implements EditoraPlugin {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String getId() {
        return "timestamp-plugin";
    }

    @Override
    public String getDisplayName() {
        return "Timestamp Plugin";
    }

    @Override
    public void onLoad(EditoraContext context) {
        Runnable insertTimestamp = () -> {
            String timestamp = LocalDateTime.now().format(FORMATTER);
            context.insertTextAtCaret("// Inserted by Timestamp Plugin at " + timestamp + System.lineSeparator());
            String activeDocument = context.activeDocumentPath()
                    .map(path -> path.getFileName().toString())
                    .orElse("untitled document");
            context.showStatusMessage("Timestamp inserted into " + activeDocument);
        };

        Runnable openReadme = () -> context.workspaceRoot()
                .map(root -> root.resolve("README.md"))
                .filter(java.nio.file.Files::exists)
                .ifPresentOrElse(path -> {
                    context.openFile(path);
                    context.showStatusMessage("Opened workspace README via plugin");
                }, () -> context.showStatusMessage("No README.md found in the current workspace root"));

        context.registerCommand(new CommandAction(
                "Insert Timestamp Comment",
                "Plugin demo: insert a timestamp comment at the active caret position",
                "Plugins",
                insertTimestamp
        ));
        context.registerPluginAction(new CommandAction(
                "Insert Timestamp Comment",
                "Plugin menu action from the example timestamp plugin",
                "Plugins",
                insertTimestamp
        ));
        context.registerCommand(new CommandAction(
                "Open Workspace README",
                "Plugin demo: open README.md from the active workspace root",
                "Plugins",
                openReadme
        ));
    }
}

