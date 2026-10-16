package com.editora.ui;

import java.io.ByteArrayInputStream;
import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javafx.scene.image.Image;

import com.editora.editor.GrammarRegistry;
import com.editora.editor.TextMateHighlighter;
import com.editora.http.HttpResponseFormat;
import com.editora.http.HttpResult;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.richtext.model.StyleSpans;

/**
 * The worker half of showing a response body: the pretty-printed, length-limited text, its highlight spans or the
 * picture, computed off the FX thread ({@link #FORMAT}) for {@link HttpClientPanel} to apply. Pure apart from the
 * decoding and tokenizing.
 */
final class HttpBodyRendering {

    private HttpBodyRendering() {}

    /** Shared workers for response formatting + highlighting (a few, so panels do not wait on each other); idle out. */
    static final ExecutorService FORMAT = formatExecutor();

    private static ExecutorService formatExecutor() {
        // A few threads, not one: a multi-megabyte render in one window (or tab) must not hold up another's. Within one
        // panel a superseded render is skipped (bodyGeneration), so renders of the same panel never pile up. The
        // threads idle out, so nothing needs shutting down when a window closes.
        int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        ThreadPoolExecutor pool =
                new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
                    Thread t = new Thread(r, "http-response-format");
                    t.setDaemon(true);
                    return t;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /** A formatted body ready to apply on the FX thread: the view, the text shown, its spans (or null), a picture. */
    record Rendered(HttpResponseFormat.BodyView view, String text, StyleSpans<Collection<String>> spans, Image image) {}

    /** Worker-thread half: the pretty-printed, length-limited view, its highlight spans, or the picture. */
    static Rendered format(HttpResult r, int maxChars) {
        Image image = imageOf(r);
        if (image != null) {
            return new Rendered(HttpResponseFormat.view(r, 0), "", null, image);
        }
        HttpResponseFormat.BodyView view = HttpResponseFormat.view(r, maxChars);
        String ext = extFor(r.contentType());
        String text = "xml".equals(ext) ? HttpXmlIndent.indent(view.text()) : view.text();
        StyleSpans<Collection<String>> spans = null;
        IGrammar grammar = text.isEmpty() ? null : grammarFor(ext);
        if (grammar != null) {
            try {
                spans = TextMateHighlighter.compute(text, grammar);
            } catch (RuntimeException ignored) {
                // unknown/oversized — leave it unstyled
            }
        }
        return new Rendered(view, text, spans, null);
    }

    /** The decoded picture of an {@code image/*} response (not SVG, which is text), or null. */
    private static Image imageOf(HttpResult r) {
        String ct = r.contentType() == null ? "" : r.contentType().toLowerCase(Locale.ROOT);
        if (!ct.startsWith("image/") || ct.contains("svg") || r.rawBody() == null || r.rawBody().length == 0) {
            return null;
        }
        try {
            Image image = new Image(new ByteArrayInputStream(r.rawBody()));
            return image.isError() || image.getWidth() <= 0 ? null : image;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static IGrammar grammarFor(String ext) {
        if (ext == null) {
            return null;
        }
        try {
            return GrammarRegistry.shared().forFileName("response." + ext);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String extFor(String contentType) {
        if (contentType == null) {
            return null;
        }
        String ct = contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("json")) {
            return "json";
        }
        if (ct.contains("html")) {
            return "html";
        }
        if (ct.contains("xml")) {
            return "xml";
        }
        return null;
    }
}
