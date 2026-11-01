package com.editora.ui;

import java.util.List;

import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A git failure that arrives after its window has closed sets the status and opens no dialog: an orphan modal
 * alert outlived its window and was taken for the next dialog by every later test in the JVM (CI, PR 1401).
 */
class GitErrorClosedWindowFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aGitErrorAfterTheWindowClosedOpensNoDialog() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            Stage stage = FxTestSupport.field(w.fx.controller, "stage");
            FxTestSupport.runOnFx(stage::hide);

            // On a hidden window the old code entered showAndWait and this call never returned.
            FxTestSupport.runOnFx(() -> w.git.gitError("push failed", "remote: denied"));

            List<Window> dialogs = FxTestSupport.callOnFx(() -> Window.getWindows().stream()
                    .filter(win -> win.getScene() != null && win.getScene().getRoot() instanceof DialogPane)
                    .toList());
            assertEquals(List.of(), dialogs, "no orphan dialog");
        }
    }
}
