package com.editora.ui;

import java.util.List;
import java.util.Set;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;

import com.editora.doctor.DoctorCheck;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row layout: a long probed path must never squeeze the tool name into an ellipsis. Only a real layout pass
 * can catch this — an {@code HBox} shrinks every label by an equal share, so the name went to "…" while the
 * path beside it stayed long, and nothing about the model was wrong.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DoctorPaneLayoutFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final DoctorPane.Actions NO_ACTIONS = new DoctorPane.Actions() {
        @Override
        public void refresh() {}

        @Override
        public void install(DoctorCheck check) {}

        @Override
        public void openSettings(String settingsKey) {}
    };

    /**
     * Rows that genuinely overflow the column: a long configured command <i>and</i> a different long resolved
     * path, so neither can be deduped away and something has to give. The name must not be what gives.
     */
    private static List<DoctorCheck> overflowingRows() {
        return List.of(
                DoctorCheck.checking("lsp.xml", "lsp", "XML", "/opt/editora/plugins/lsp/xml/lemminx-linux --stdio")
                        .ok("/opt/editora-runtime/plugins/lsp/xml/lemminx-linux-x86-64-release"),
                DoctorCheck.checking("lsp.bash", "lsp", "Shell", "/opt/node/versions/v24.17.0/bin/bash-language-server")
                        .ok("/opt/node/versions/v24.17.0/lib/node_modules/bash-language-server/bin/main.js"),
                DoctorCheck.checking(
                                "lsp.typescript",
                                "lsp",
                                "TypeScript / JavaScript",
                                "/opt/node/versions/v24.17.0/bin/typescript-language-server --stdio")
                        .ok("/opt/node/versions/v24.17.0/lib/node_modules/typescript-language-server/lib/cli.mjs"));
    }

    private static Set<Node> laidOutNames(List<DoctorCheck> rows, double sceneWidth) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            DoctorPane pane = new DoctorPane(NO_ACTIONS);
            pane.setChecks(rows);
            Stage stage = new Stage();
            stage.setScene(new Scene(pane, sceneWidth, 900));
            stage.show();
            pane.applyCss();
            pane.layout();
            Set<Node> names = pane.lookupAll(".doctor-name");
            stage.hide();
            return names;
        });
    }

    @Test
    void theToolNameIsNeverEllipsizedByALongProbedPath() throws Exception {
        Set<Node> names = laidOutNames(overflowingRows(), 1600);
        assertEquals(3, names.size(), "one name label per row");
        for (Node n : names) {
            Label name = (Label) n;
            assertTrue(
                    name.getWidth() + 0.5 >= name.prefWidth(-1),
                    "name '" + name.getText() + "' was squeezed below its text width: " + name.getWidth() + " < "
                            + name.prefWidth(-1));
        }
    }

    @Test
    void theNameSurvivesEvenOnANarrowWindow() throws Exception {
        // Narrower than the column's minimum, i.e. maximum layout pressure — the path columns must absorb it.
        Set<Node> names = laidOutNames(overflowingRows(), 700);
        for (Node n : names) {
            Label name = (Label) n;
            assertFalse(name.getText().isEmpty());
            assertTrue(
                    name.getWidth() + 0.5 >= name.prefWidth(-1),
                    "name '" + name.getText() + "' was squeezed on a narrow window");
        }
    }

    @Test
    void aRedundantCommandIsDroppedAndLongTextKeepsAHoverTooltip() throws Exception {
        String underHome = java.nio.file.Path.of(System.getProperty("user.home"), ".editora/plugins/lsp/java/bin/jdtls")
                .toString();
        List<DoctorCheck> rows =
                List.of(DoctorCheck.checking("lsp.java", "lsp", "Java", "jdtls").ok(underHome));
        Object[] found = FxTestSupport.callOnFx(() -> {
            DoctorPane pane = new DoctorPane(NO_ACTIONS);
            pane.setChecks(rows);
            Stage stage = new Stage();
            stage.setScene(new Scene(pane, 1600, 900));
            stage.show();
            pane.applyCss();
            pane.layout();
            Object[] out = {pane.lookupAll(".doctor-command"), pane.lookupAll(".doctor-detail")};
            stage.hide();
            return out;
        });
        @SuppressWarnings("unchecked")
        Set<Node> commands = (Set<Node>) found[0];
        @SuppressWarnings("unchecked")
        Set<Node> details = (Set<Node>) found[1];

        assertTrue(commands.isEmpty(), "'jdtls' beside '~/…/bin/jdtls' adds nothing and should be dropped");
        assertEquals(1, details.size());
        Label detail = (Label) details.iterator().next();
        assertTrue(detail.getText().startsWith("~/"), "the home prefix should collapse: " + detail.getText());
        assertNotNull(detail.getTooltip(), "a long path needs the full text on hover");
    }
}
