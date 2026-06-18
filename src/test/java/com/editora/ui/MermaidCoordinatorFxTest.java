package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.stage.Window;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for {@link MermaidCoordinator} (third extracted feature coordinator), exercised
 * against a hand-written fake {@link MermaidCoordinator.Host}. Pins the gating reads other features rely on
 * ({@code mmdcCommandOrNull} for PDF/print, {@code effectiveAutocomplete} for completion), the disabled
 * apply-support path (preview reconcile + autocomplete re-push), and the export no-ops.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MermaidCoordinatorFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final class FakeHost implements MermaidCoordinator.Host {
        final Settings settings = new Settings();
        final List<EditorBuffer> buffers = new ArrayList<>();
        EditorBuffer active;
        String lastStatus;
        int ensurePreviewCount;
        int applyAutocompleteCount;

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public boolean appThemeDark() {
            return false;
        }

        @Override
        public void forEachBuffer(Consumer<EditorBuffer> action) {
            buffers.forEach(action);
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public void setStatus(String message) {
            lastStatus = message;
        }

        @Override
        public void ensurePreviewControls(EditorBuffer buffer) {
            ensurePreviewCount++;
        }

        @Override
        public void restoreMarkdownMode(EditorBuffer buffer) {
            // no-op
        }

        @Override
        public void applyAutocomplete() {
            applyAutocompleteCount++;
        }

        @Override
        public String bufferBaseName(EditorBuffer buffer) {
            return "diagram.mmd";
        }

        @Override
        public Window window() {
            return null;
        }
    }

    @Test
    void isEnabledTracksTheSetting() {
        FakeHost host = new FakeHost();
        MermaidCoordinator c = new MermaidCoordinator(host);
        host.settings.setMermaidSupport(true);
        assertTrue(c.isEnabled());
        host.settings.setMermaidSupport(false);
        assertFalse(c.isEnabled());
    }

    @Test
    void mmdcCommandIsNullWhenDisabledAndPresentWhenEnabled() {
        FakeHost host = new FakeHost();
        MermaidCoordinator c = new MermaidCoordinator(host);

        host.settings.setMermaidSupport(false);
        assertNull(c.mmdcCommandOrNull(), "off → no mmdc command for PDF/print");

        host.settings.setMermaidSupport(true);
        List<String> cmd = c.mmdcCommandOrNull();
        assertTrue(cmd != null && !cmd.isEmpty(), "on → the configured/default mmdc command");
    }

    @Test
    void effectiveAutocompleteIsOffWithoutAllGates() {
        FakeHost host = new FakeHost();
        MermaidCoordinator c = new MermaidCoordinator(host);
        // All the settings on, but mmdc isn't detected (no applySupport/real CLI) → still off.
        host.settings.setAutocomplete(true);
        host.settings.setAutocompleteMermaid(true);
        host.settings.setMermaidSupport(true);
        assertFalse(c.effectiveAutocomplete(), "needs the mmdc CLI detected, which isn't in a unit test");

        host.settings.setMermaidSupport(false);
        assertFalse(c.effectiveAutocomplete());
    }

    @Test
    void applySupportDisabledReconcilesPreviewsAndReappliesAutocomplete() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setMermaidSupport(false); // disabled → the synchronous gating path
        MermaidCoordinator c = new MermaidCoordinator(host);
        host.buffers.add(FxTestSupport.callOnFx(() -> new EditorBuffer()));
        host.buffers.add(FxTestSupport.callOnFx(() -> new EditorBuffer()));

        FxTestSupport.runOnFx(c::applySupport);
        assertEquals(2, host.ensurePreviewCount, "every buffer's preview controls are reconciled");
        assertTrue(host.applyAutocompleteCount >= 1, "autocomplete is re-pushed (mermaid gate may have changed)");
    }

    @Test
    void exportNoOpsWhenDisabledOrNotADiagram() throws Exception {
        FakeHost host = new FakeHost();
        MermaidCoordinator c = new MermaidCoordinator(host);

        host.settings.setMermaidSupport(false);
        FxTestSupport.runOnFx(c::export);
        assertEquals(tr("statusbar.tip.mermaidDisabled"), host.lastStatus, "disabled → reports it, no dialog");

        host.settings.setMermaidSupport(true);
        host.active = FxTestSupport.callOnFx(() -> new EditorBuffer()); // a plain (non-diagram) buffer
        FxTestSupport.runOnFx(c::export);
        assertEquals(tr("status.mermaid.notDiagram"), host.lastStatus, "non-diagram → reports it, no file chooser");
    }

    private static String tr(String key) {
        return com.editora.i18n.Messages.tr(key);
    }
}
