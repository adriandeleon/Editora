package com.editora.ui;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.scene.control.Button;
import javafx.scene.control.Label;

import com.editora.http.HttpExchange;
import com.editora.http.HttpFixtures;
import com.editora.http.HttpResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        return HttpFixtures.exchange(label, "GET", "http://x.test/", List.of(), "", r);
    }

    private static HttpResult ok(String body, String contentType, List<String> warnings) {
        return HttpFixtures.text(200, List.of(), body, contentType, 3, body.length(), null, warnings);
    }

    /** Shows {@code ex}, waits for its background render to land on the FX thread, and returns the body text. */
    private static String show(HttpClientPanel p, HttpExchange ex) throws Exception {
        CompletableFuture<Void> render = FxTestSupport.callOnFx(() -> {
            p.showEntries(entries(ex));
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
        // The note sits above the body (X11), where it is seen, not after 400 000 characters.
        String marker = FxTestSupport.callOnFx(p::bodyNoteForTest);
        assertNotNull(marker, "the cut is announced above the body");
        assertTrue(marker.contains("400,000"), "the cut is announced, with the shown length: " + marker);
        assertTrue(marker.contains("660,000"), "…and the full length: " + marker);

        String saved = FxTestSupport.callOnFx(() -> responseText(p));
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
        String saved = FxTestSupport.callOnFx(() -> responseText(p));
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
            p.showEntries(entries(slow));
            CompletableFuture<Void> a = p.bodyRenderForTest();
            p.showEntries(entries(fast));
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
        HttpResult r = HttpFixtures.bytes(
                200,
                List.of(),
                png,
                "image/png",
                5,
                List.of("response truncated at 50 MB — rest not downloaded"),
                true);
        String shown = show(p, exchange("img", r));
        assertTrue(shown.contains("binary"), shown);
        String note = FxTestSupport.callOnFx(p::bodyNoteForTest);
        assertTrue(
                note != null && note.contains("truncated"), "a size-capped response says so above the body: " + note);
        String notes = FxTestSupport.callOnFx(p::notesForTest); // warnings sit apart from the headers (U4)
        assertTrue(notes.startsWith("⚠  response truncated at 50 MB"), notes);
        assertEquals("Notes 1", FxTestSupport.callOnFx(() -> p.tabTextForTest(HttpResponseTab.NOTES)));
        assertEquals(
                HttpTabStrip.CountStyle.WARN,
                FxTestSupport.callOnFx(() -> p.tabsForTest().countStyleForTest(HttpResponseTab.NOTES)),
                "a warning colours the Notes count");
        assertEquals(
                HttpResponseTab.BODY,
                FxTestSupport.callOnFx(p::selectedTabForTest),
                "a warning does not switch tabs (decision 4)");
        assertTrue(
                FxTestSupport.callOnFx(() -> p.controlsForTest().bodyArea().isWrapText()),
                "a note in place of the body wraps (U7)");
        assertTrue(
                FxTestSupport.callOnFx(() -> p.controlsForTest().openTabButton().isDisabled()));
    }

    @Test
    void theRunButtonTurnsIntoCancelOnlyWhileARequestRuns() throws Exception {
        HttpClientPanel p = panel();
        Button run = p.controlsForTest().runButton();
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
        HttpResult r = HttpFixtures.text(
                404, List.<String[]>of(new String[] {"content-type", "text/plain"}), "gone", "text/plain", 12, 4, null);
        show(p, exchange("missing", r));
        assertEquals("404 Not Found  ·  12 ms  ·  4 B", FxTestSupport.callOnFx(p::statusForTest));
        Label status = p.controlsForTest().statusLabel();
        assertTrue(FxTestSupport.callOnFx(() -> status.getStyleClass().contains(HttpStatusText.CLIENT_ERROR)));
        assertTrue(FxTestSupport.callOnFx(() -> status.getStyleClass().contains("state-pill")), "the status is a pill");
        assertEquals("404 Not Found", FxTestSupport.callOnFx(status::getText), "the pill holds the status alone");
        assertEquals(
                "12 ms  ·  4 B",
                FxTestSupport.callOnFx(() -> p.controlsForTest().metaLabel().getText()),
                "the timing and size sit beside it");
        String head = FxTestSupport.callOnFx(p::headersForTest);
        assertEquals("HTTP 404 Not Found\ncontent-type: text/plain\n", head, "no summary line under the headers");
        assertEquals(
                1,
                FxTestSupport.callOnFx(
                        () -> p.controlsForTest().headersTable().getItems().size()));
        assertEquals("Headers 1", FxTestSupport.callOnFx(() -> p.tabTextForTest(HttpResponseTab.HEADERS)));
    }

    @Test
    void whileARequestRunsTheOldResponseIsClearedAndTheElapsedTimeShown() throws Exception {
        HttpClientPanel p = panel();
        show(p, exchange("old", ok("old body", "text/plain", List.of())));
        HttpHistoryEntry old =
                FxTestSupport.callOnFx(() -> p.controlsForTest().historyPicker().getValue());
        FxTestSupport.runOnFx(() -> p.started("GET http://x.test/slow"));
        assertEquals("", FxTestSupport.callOnFx(p::shownBodyForTest), "the previous body is not left under Running");
        String text = FxTestSupport.callOnFx(p::statusForTest);
        assertTrue(text.contains("GET http://x.test/slow") && text.contains(" s"), text);
        assertTrue(text.startsWith(HttpPanelStatesFxTest.runningPrefix("GET http://x.test/slow")), text);
        // U7: the previous response is one labelled row, and the progress line runs.
        String row = FxTestSupport.callOnFx(p::previousRowForTest);
        assertNotNull(row, "the previous response's row shows");
        assertTrue(row.contains("200 OK") && row.contains("old"), row);
        assertTrue(FxTestSupport.callOnFx(() -> p.controlsForTest().progress().isVisible()));
        FxTestSupport.runOnFx(() -> p.progress(1, 5, "GET http://x.test/b"));
        assertTrue(FxTestSupport.callOnFx(p::statusForTest)
                .contains(tr("httppanel.runningOf", 2, 5, "GET http://x.test/b")));
        FxTestSupport.runOnFx(p::cancelled);
        // X10 / U7: the old body is not shown under "Cancelled after" (it read as this one's): its row comes back, and
        // History shows it selected rather than blank. Nothing is "selected" for the actions until it is shown.
        assertEquals(null, FxTestSupport.callOnFx(p::getSelectedExchange));
        String cancelled = FxTestSupport.callOnFx(p::statusForTest);
        assertTrue(
                cancelled.startsWith(
                        tr("httppanel.cancelledAfter", "").replace(" s", "").strip()),
                cancelled);
        assertTrue(cancelled.endsWith(" s"), cancelled);
        assertEquals("", FxTestSupport.callOnFx(p::shownBodyForTest));
        assertNotNull(FxTestSupport.callOnFx(p::previousRowForTest));
        assertSame(
                old,
                FxTestSupport.callOnFx(() -> p.controlsForTest().historyPicker().getValue()));
        assertFalse(FxTestSupport.callOnFx(() -> p.controlsForTest().progress().isVisible()));
        assertEquals(
                1,
                FxTestSupport.callOnFx(
                        () -> p.controlsForTest().historyPicker().getItems().size()));
    }

    @Test
    void theRequestAsSentIsShownWithItsHeadersAndBody() {
        HttpExchange ex = HttpFixtures.exchange(
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
            p.showEntries(entries(good, bad, good));
            return p.bodyRenderForTest();
        });
        render.get(30, TimeUnit.SECONDS);
        assertEquals("bad", FxTestSupport.callOnFx(() -> p.getSelectedExchange().label()));
        assertEquals(tr("httppanel.requestFailed"), FxTestSupport.callOnFx(p::statusForTest));
        assertTrue(
                FxTestSupport.callOnFx(() -> p.controlsForTest().saveButton().isDisabled()),
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
                        HttpFixtures.bytes(200, List.of(), png.toByteArray(), "image/png", 2, List.of(), false)));
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
                FxTestSupport.callOnFx(() -> p.controlsForTest().saveButton().isDisabled()),
                "a dropped body cannot be saved");
    }

    @Test
    void escapeGoesBackToTheEditorAndTabLeavesTheResponseBody() throws Exception {
        HttpClientPanel p = panel();
        int[] escapes = {0};
        javafx.scene.Node body = p.controlsForTest().bodyArea();
        Button run = p.controlsForTest().runButton();
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

    /** Finished exchanges as the coordinator hands them over: no name, no position. */
    private static List<HttpHistoryEntry> entries(com.editora.http.HttpExchange... exchanges) {
        return java.util.Arrays.stream(exchanges).map(HttpHistoryEntry::of).toList();
    }

    /** What Save writes with "status and headers": the selected response as a full text report. */
    private static String responseText(HttpClientPanel p) {
        com.editora.http.HttpExchange ex = p.getSelectedExchange();
        return ex == null ? "" : com.editora.http.HttpResponseFormat.render(ex.result());
    }
}
