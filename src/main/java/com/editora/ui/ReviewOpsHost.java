package com.editora.ui;

import java.nio.file.Path;
import java.util.function.Consumer;

import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/** What {@link ReviewCoordinator} needs from a window, resolved from {@link MainController} when called. */
final class ReviewOpsHost implements ReviewCoordinator.Ops {

    private final MainController mc;

    ReviewOpsHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public void inRepositoryOf(Path file, Consumer<Path> withRoot) {
        mc.git.withRepositoryOf(file, () -> {
            Path root = mc.git.repoRoot(); // the file's repository while the action runs
            if (root != null) {
                withRoot.accept(root);
            }
        });
    }

    @Override
    public void openAndNavigate(Path file, int line) {
        mc.openAndNavigate(file, line);
    }

    @Override
    public @Nullable EditorBuffer bufferForPath(Path file) {
        return MainController.bufferOf(mc.tabForPath(file));
    }

    @Override
    public void installEmacsKeys(javafx.scene.control.TextInputControl control) {
        com.editora.command.TextInputKeymap.install(control, mc.keymap);
    }
}
