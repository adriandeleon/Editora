package com.editora.ui;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.scene.control.Button;
import javafx.scene.control.TextArea;

import com.editora.http.HttpExchange;
import com.editora.http.HttpResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void cancelIsEnabledOnlyWhileARequestRuns() throws Exception {
        HttpClientPanel p = panel();
        Button cancel = FxTestSupport.field(p, "cancelButton");
        int[] cancels = {0};
        FxTestSupport.runOnFx(() -> p.setOnCancel(() -> cancels[0]++));
        assertTrue(FxTestSupport.callOnFx(cancel::isDisabled), "nothing to cancel yet");

        FxTestSupport.runOnFx(() -> p.started("GET http://x.test/"));
        assertFalse(FxTestSupport.callOnFx(cancel::isDisabled));
        FxTestSupport.runOnFx(cancel::fire);
        assertEquals(1, cancels[0]);

        FxTestSupport.runOnFx(p::cancelled);
        assertTrue(FxTestSupport.callOnFx(cancel::isDisabled));

        FxTestSupport.runOnFx(() -> p.started("GET http://x.test/"));
        show(p, exchange("done", ok("ok", "text/plain", List.of())));
        assertTrue(FxTestSupport.callOnFx(cancel::isDisabled), "a finished run can no longer be cancelled");
    }
}
