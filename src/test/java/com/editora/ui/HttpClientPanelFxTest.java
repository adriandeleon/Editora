package com.editora.ui;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;

import com.editora.http.HttpExchange;
import com.editora.http.HttpResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The response viewer formats off the FX thread under a generation guard, marks a cut body visibly, keeps the
 * raw body for Save, shows request warnings, and enables Cancel only while a request is running.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpClientPanelFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static HttpClientPanel panel() throws Exception {
        return FxTestSupport.callOnFx(() -> new HttpClientPanel(null, ex -> {}, ex -> {}, "monospace", 12));
    }

    private static HttpExchange exchange(String label, HttpResult r) {
        return new HttpExchange(label, "GET", "http://x.test/", List.of(), "", r);
    }

    private static HttpResult ok(String body, String contentType, List<String> warnings) {
        return new HttpResult(200, List.of(), body, contentType, 3, body.length(), null, warnings);
    }

    /** Shows {@code ex}, waits for its background render to land on the FX thread, and returns the body text. */
    private static String show(HttpClientPanel p, HttpExchange ex) throws Exception {
        CompletableFuture<Void> render = FxTestSupport.callOnFx(() -> {
            p.showExchanges(List.of(ex));
            return p.bodyRenderForTest();
        });
        render.get(30, TimeUnit.SECONDS);
        return FxTestSupport.callOnFx(p::shownBodyForTest);
    }

    @Test
    void aLongBodyIsCutWithAVisibleLineAndSaveKeepsAllOfIt() throws Exception {
        HttpClientPanel p = panel();
        String body = "line of response text\n".repeat(30_000); // 660 000 chars
        String shown = show(p, exchange("big", ok(body, "text/plain", List.of())));

        assertTrue(shown.startsWith("line of response text\n"));
        assertTrue(shown.length() < body.length(), "the view is limited");
        String marker = shown.substring(HttpClientPanel.MAX_BODY_CHARS);
        assertTrue(marker.contains("400,000"), "the cut is announced, with the shown length: " + marker);
        assertTrue(marker.contains("660,000"), "…and the full length: " + marker);

        String saved = FxTestSupport.callOnFx(p::getResponseText);
        assertTrue(saved.contains(body), "Save writes the whole body, not the truncated view");
    }

    @Test
    void theViewPrettyPrintsButSaveKeepsTheBodyAsReceived() throws Exception {
        HttpClientPanel p = panel();
        String raw = "{\"price\":10.50,\"n\":1e2,\"a\":1,\"a\":2}";
        String shown = show(p, exchange("json", ok(raw, "application/json", List.of())));
        assertTrue(shown.contains("\"price\" : 10.50"), shown);
        assertTrue(shown.contains("\"n\" : 1e2"), shown);
        assertEquals(raw, shown.replaceAll("\\s+", ""), "whitespace is the only change");
        String saved = FxTestSupport.callOnFx(p::getResponseText);
        assertTrue(saved.contains("\n" + raw + "\n"), "the report holds the raw body: " + saved);
        assertEquals(
                raw,
                FxTestSupport.callOnFx(() -> p.getSelectedExchange().result().body()));
    }

    @Test
    void onlyTheNewestSelectionIsRendered() throws Exception {
        HttpClientPanel p = panel();
        HttpExchange slow = exchange(
                "first", ok("{\"k\":" + "[1,2,3],".repeat(40_000) + "\"z\":0}", "application/json", List.of()));
        HttpExchange fast = exchange("second", ok("second body", "text/plain", List.of()));
        // Both are queued in the same FX pulse: the first render's result must be dropped, not painted late.
        List<CompletableFuture<Void>> renders = FxTestSupport.callOnFx(() -> {
            p.showExchanges(List.of(slow));
            CompletableFuture<Void> a = p.bodyRenderForTest();
            p.showExchanges(List.of(fast));
            return List.of(a, p.bodyRenderForTest());
        });
        for (CompletableFuture<Void> r : renders) {
            r.get(30, TimeUnit.SECONDS);
        }
        assertEquals("second body", FxTestSupport.callOnFx(p::shownBodyForTest));
    }

    @Test
    void warningsAndABinaryBodyAreShownNotSwallowed() throws Exception {
        HttpClientPanel p = panel();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 1};
        HttpResult r = HttpResult.ofBytes(
                200,
                List.of(),
                png,
                "image/png",
                5,
                List.of("response truncated at 50 MB — rest not downloaded"),
                true);
        String shown = show(p, exchange("img", r));
        assertTrue(shown.contains("binary"), shown);
        assertTrue(shown.contains("truncated"), "a size-capped response says so in the body pane: " + shown);
        String head = FxTestSupport.callOnFx(() -> ((TextArea) FxTestSupport.field(p, "headersArea")).getText());
        assertTrue(head.startsWith("⚠  response truncated at 50 MB"), head);
        assertTrue(FxTestSupport.callOnFx(() -> ((Button) FxTestSupport.field(p, "openTabButton")).isDisabled()));
    }

    @Test
    void theRunButtonTurnsIntoCancelOnlyWhileARequestRuns() throws Exception {
        HttpClientPanel p = panel();
        Button run = FxTestSupport.field(p, "runButton");
        int[] cancels = {0};
        int[] runs = {0};
        FxTestSupport.runOnFx(() -> {
            p.setOnCancel(() -> cancels[0]++);
            p.setOnRun(e -> runs[0]++);
        });
        assertEquals(tr("httppanel.run"), FxTestSupport.callOnFx(run::getText), "nothing to cancel yet");
        FxTestSupport.runOnFx(run::fire);
        assertEquals(1, runs[0], "Run runs the request again");

        FxTestSupport.runOnFx(() -> p.started("GET http://x.test/"));
        assertEquals(tr("httppanel.cancel"), FxTestSupport.callOnFx(run::getText));
        FxTestSupport.runOnFx(run::fire);
        assertEquals(1, cancels[0]);

        FxTestSupport.runOnFx(p::cancelled);
        assertEquals(tr("httppanel.run"), FxTestSupport.callOnFx(run::getText));

        FxTestSupport.runOnFx(() -> p.started("GET http://x.test/"));
        show(p, exchange("done", ok("ok", "text/plain", List.of())));
        assertEquals(
                tr("httppanel.run"), FxTestSupport.callOnFx(run::getText), "a finished run can no longer be cancelled");
    }

    @Test
    void theStatusLineSaysTheStatusTimeAndSizeAndTheHeadersBoxOnlyTheHeaders() throws Exception {
        HttpClientPanel p = panel();
        HttpResult r = new HttpResult(
                404, List.<String[]>of(new String[] {"content-type", "text/plain"}), "gone", "text/plain", 12, 4, null);
        show(p, exchange("missing", r));
        assertEquals("404 Not Found  ·  12 ms  ·  4 B", FxTestSupport.callOnFx(p::statusForTest));
        Label status = FxTestSupport.field(p, "status");
        assertTrue(FxTestSupport.callOnFx(() -> status.getStyleClass().contains(HttpStatusText.CLIENT_ERROR)));
        String head = FxTestSupport.callOnFx(() -> ((TextArea) FxTestSupport.field(p, "headersArea")).getText());
        assertEquals("HTTP 404 Not Found\ncontent-type: text/plain\n", head, "no summary line under the headers");
    }

    @Test
    void whileARequestRunsTheOldResponseIsClearedAndTheElapsedTimeShown() throws Exception {
        HttpClientPanel p = panel();
        show(p, exchange("old", ok("old body", "text/plain", List.of())));
        FxTestSupport.runOnFx(() -> p.started("GET http://x.test/slow"));
        assertEquals("", FxTestSupport.callOnFx(p::shownBodyForTest), "the previous body is not left under Running");
        String text = FxTestSupport.callOnFx(p::statusForTest);
        assertTrue(text.contains("GET http://x.test/slow") && text.contains(" s"), text);
        FxTestSupport.runOnFx(() -> p.progress(1, 5, "GET http://x.test/b"));
        assertTrue(FxTestSupport.callOnFx(p::statusForTest)
                .contains(tr("httppanel.runningOf", 2, 5, "GET http://x.test/b")));
        FxTestSupport.runOnFx(p::cancelled);
        assertEquals(
                "old body",
                FxTestSupport.callOnFx(() -> p.getSelectedExchange().result().body()),
                "cancelling shows the last response again");
    }

    @Test
    void theRequestAsSentIsShownWithItsHeadersAndBody() {
        HttpExchange ex = new HttpExchange(
                "POST http://x.test/a",
                "POST",
                "http://x.test/a?u=alice",
                List.<String[]>of(new String[] {"X-Token", "t-dev"}),
                "{\"a\":1}",
                ok("", "text/plain", List.of()));
        assertEquals("POST http://x.test/a?u=alice\nX-Token: t-dev\n\n{\"a\":1}", HttpClientPanel.requestText(ex));
    }

    @Test
    void aRunAllLandsOnItsFirstFailure() throws Exception {
        HttpClientPanel p = panel();
        HttpExchange good = exchange("good", ok("fine", "text/plain", List.of()));
        HttpExchange bad = exchange("bad", HttpResult.failure("Connection refused", List.of()));
        CompletableFuture<Void> render = FxTestSupport.callOnFx(() -> {
            p.showExchanges(List.of(good, bad, good));
            return p.bodyRenderForTest();
        });
        render.get(30, TimeUnit.SECONDS);
        assertEquals("bad", FxTestSupport.callOnFx(() -> p.getSelectedExchange().label()));
        assertEquals(tr("httppanel.requestFailed"), FxTestSupport.callOnFx(p::statusForTest));
        assertTrue(
                FxTestSupport.callOnFx(() -> ((Button) FxTestSupport.field(p, "saveButton")).isDisabled()),
                "a request that failed has nothing to save");
    }

    @Test
    void anImageResponseIsShownAsAPicture() throws Exception {
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(4, 3, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", png);
        HttpClientPanel p = panel();
        show(
                p,
                exchange(
                        "img",
                        HttpResult.ofBytes(200, List.of(), png.toByteArray(), "image/png", 2, List.of(), false)));
        assertTrue(FxTestSupport.callOnFx(p::showsImageForTest));
    }

    @Test
    void oneLineXmlIsIndentedInTheViewOnly() throws Exception {
        HttpClientPanel p = panel();
        String raw = "<a><b id=\"1\">x</b><c/></a>";
        String shown = show(p, exchange("xml", ok(raw, "application/xml", List.of())));
        assertEquals("<a>\n  <b id=\"1\">x</b>\n  <c/>\n</a>", shown);
        assertEquals(
                raw,
                FxTestSupport.callOnFx(() -> p.getSelectedExchange().result().body()));
    }

    @Test
    void anEntryWhoseBodyWasDroppedSaysSoAndKeepsItsStatus() throws Exception {
        HttpClientPanel p = panel();
        HttpHistoryEntry dropped = HttpHistoryEntry.of(exchange("big", ok("x".repeat(5000), "text/plain", List.of())))
                .withoutBody();
        CompletableFuture<Void> render = FxTestSupport.callOnFx(() -> {
            p.restore(List.of(dropped));
            return p.bodyRenderForTest();
        });
        render.get(30, TimeUnit.SECONDS);
        assertEquals(
                com.editora.i18n.Messages.tr("httppanel.bodyDropped", "4.9 KB"),
                FxTestSupport.callOnFx(p::shownBodyForTest));
        assertTrue(FxTestSupport.callOnFx(p::statusForTest).startsWith("200 OK"));
        assertTrue(
                FxTestSupport.callOnFx(() -> ((Button) FxTestSupport.field(p, "saveButton")).isDisabled()),
                "a dropped body cannot be saved");
    }

    @Test
    void escapeGoesBackToTheEditorAndTabLeavesTheResponseBody() throws Exception {
        HttpClientPanel p = panel();
        int[] escapes = {0};
        javafx.scene.Node body = FxTestSupport.field(p, "bodyArea");
        Button run = FxTestSupport.field(p, "runButton");
        javafx.stage.Stage stage = FxTestSupport.callOnFx(() -> {
            p.setOnEscape(() -> escapes[0]++);
            javafx.stage.Stage st = new javafx.stage.Stage();
            st.setScene(new javafx.scene.Scene(p, 800, 600));
            st.show();
            body.requestFocus();
            return st;
        });
        try {
            FxTestSupport.runOnFx(
                    () -> javafx.event.Event.fireEvent(body, key(javafx.scene.input.KeyCode.ESCAPE, false)));
            assertEquals(1, escapes[0]);
            FxTestSupport.runOnFx(() -> javafx.event.Event.fireEvent(body, key(javafx.scene.input.KeyCode.TAB, false)));
            assertSame(run, FxTestSupport.callOnFx(() -> stage.getScene().getFocusOwner()), "Tab moves on");
        } finally {
            FxTestSupport.runOnFx(stage::close);
        }
    }

    private static javafx.scene.input.KeyEvent key(javafx.scene.input.KeyCode code, boolean shift) {
        return new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false);
    }

    private static String tr(String key, Object... args) {
        return com.editora.i18n.Messages.tr(key, args);
    }
}
