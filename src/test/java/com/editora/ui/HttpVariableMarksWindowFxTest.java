package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.scene.Node;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.markdown.MarkdownLint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code {{variable}}} squiggles of a {@code .http} file are actually drawn in a real window: the overlay they
 * share with Markdown lint is visible after the file is opened ({@code addBuffer}) and stays visible after a
 * settings apply, which pushes the Markdown-lint state to every buffer. The unit tests of the marks only looked at
 * the diagnostics list, which is why an overlay that was switched off again went unnoticed.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpVariableMarksWindowFxTest {

    private FxWindowFixture fx;
    private Settings settings;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        ConfigManager config = FxTestSupport.field(fx.controller, "config");
        settings = config.getSettings();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private EditorBuffer open(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                FxTestSupport.field(fx.controller, "fileWorkflows"), "openPath", new Class<?>[] {Path.class}, file));
        FxTestSupport.drainFx();
        return FxTestSupport.callOnFx(
                () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {}));
    }

    private static boolean overlayVisible(EditorBuffer b) {
        Object overlay = FxTestSupport.field(b, "mdLintOverlay");
        return overlay != null && ((Node) overlay).isVisible();
    }

    private static List<String> marks(EditorBuffer b) {
        return b.httpVariableDiagnostics().stream()
                .map(MarkdownLint.Diagnostic::code)
                .toList();
    }

    private static void await(String what, BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!FxTestSupport.callOnFx(done::getAsBoolean)) {
            assertTrue(System.nanoTime() < deadline, what);
            Thread.sleep(25);
        }
    }

    private void applySettings() throws Exception {
        FxTestSupport.runOnFxUnchecked(() -> FxTestSupport.invokeWith(
                FxTestSupport.field(fx.controller, "editorSettings"),
                "applyViewSettingsToAllBuffers",
                Settings.class,
                settings));
        FxTestSupport.drainFx();
    }

    @Test
    void theSquigglesAreDrawnAfterOpeningAndAfterASettingsApply() throws Exception {
        assertTrue(settings.isHttpClientSupport(), "the HTTP Client is on by default");
        Path dir = Files.createTempDirectory("editora-http-marks-");
        Path file = dir.resolve("api.http");
        Files.writeString(file, "GET http://127.0.0.1:9/a\nAuthorization: Bearer {{tokn}}\n");
        EditorBuffer b = open(file);

        await("the check marks {{tokn}} after the file opens", () -> marks(b).equals(List.of("{{tokn}}")));
        assertTrue(FxTestSupport.callOnFx(() -> overlayVisible(b)), "the squiggle overlay is shown after addBuffer");

        applySettings();
        await("the mark survives a settings apply", () -> marks(b).equals(List.of("{{tokn}}")));
        assertTrue(FxTestSupport.callOnFx(() -> overlayVisible(b)), "still shown after a settings apply");

        // An edit re-checks; the overlay stays on and follows the text.
        FxTestSupport.runOnFx(() -> b.getArea().appendText("X-Other: {{other}}\n"));
        await("the new reference is marked", () -> marks(b).equals(List.of("{{tokn}}", "{{other}}")));
        assertTrue(FxTestSupport.callOnFx(() -> overlayVisible(b)), "still shown after an edit");

        // Switching the HTTP Client off hides them; on again shows them again.
        FxTestSupport.runOnFxUnchecked(() -> settings.setHttpClientSupport(false));
        applySettings();
        await("off with the feature", () -> marks(b).isEmpty() && !overlayVisible(b));
        FxTestSupport.runOnFxUnchecked(() -> settings.setHttpClientSupport(true));
        applySettings();
        await("back with the feature", () -> marks(b).size() == 2 && overlayVisible(b));
    }

    @Test
    void aMarkdownBufferKeepsItsOwnLintOverlay() throws Exception {
        Path file = Files.createTempFile("editora-http-marks-", ".md");
        Files.writeString(file, "#Heading without a space\n\ntext\n");
        EditorBuffer md = open(file);
        await(
                "Markdown lint marks the heading",
                () -> !md.markdownLintDiagnostics().isEmpty());
        assertTrue(FxTestSupport.callOnFx(() -> overlayVisible(md)), "the Markdown buffer's overlay is shown");
        applySettings();
        await(
                "Markdown lint survives a settings apply",
                () -> !md.markdownLintDiagnostics().isEmpty());
        assertTrue(FxTestSupport.callOnFx(() -> overlayVisible(md)));
        assertEquals(List.of(), FxTestSupport.callOnFx(md::httpVariableDiagnostics), "no HTTP marks on Markdown");
    }
}
