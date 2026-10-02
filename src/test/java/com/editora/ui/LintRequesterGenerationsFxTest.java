package com.editora.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.editora.editor.MarkdownLintService;
import com.editora.mermaid.MermaidService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lint services' stale-result guard is scoped to the requester. With one generation counter for every
 * buffer, re-linting N buffers in one FX tick (toggling a rule with several Markdown tabs open) bumped the
 * counter N times before any result came back, so only the last buffer's result passed the guard and the
 * others kept their stale squiggles.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LintRequesterGenerationsFxTest {

    private static final String MARKDOWN = "# Title\n\ntext  \n";

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /**
     * Submits a marker request after the ones under test and waits for its result. The services run one
     * request at a time, in order, and post results in order — so once the marker's has been delivered,
     * every earlier request has been delivered or dropped.
     */
    private static void awaitDrained(java.util.function.Consumer<Runnable> submitMarker) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        FxTestSupport.runOnFx(() -> submitMarker.accept(done::countDown));
        assertTrue(done.await(30, TimeUnit.SECONDS), "the lint service never answered");
        FxTestSupport.drainFx();
    }

    @Test
    void markdownLintDeliversEveryRequestersResultFromOneTick() throws Exception {
        MarkdownLintService service = new MarkdownLintService();
        try {
            Object first = new Object();
            Object second = new Object();
            Object third = new Object();
            List<String> delivered = Collections.synchronizedList(new ArrayList<>());
            FxTestSupport.runOnFx(() -> {
                service.validate(first, MARKDOWN, Set.of(), d -> delivered.add("first"));
                service.validate(second, MARKDOWN, Set.of(), d -> delivered.add("second"));
                service.validate(third, MARKDOWN, Set.of(), d -> delivered.add("third"));
            });
            awaitDrained(done -> service.validate(new Object(), MARKDOWN, Set.of(), d -> done.run()));
            assertEquals(List.of("first", "second", "third"), delivered);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void markdownLintStillDropsARequestersOwnStaleRequest() throws Exception {
        MarkdownLintService service = new MarkdownLintService();
        try {
            Object buffer = new Object();
            Object other = new Object();
            List<String> delivered = Collections.synchronizedList(new ArrayList<>());
            FxTestSupport.runOnFx(() -> {
                service.validate(buffer, MARKDOWN, Set.of(), d -> delivered.add("stale"));
                service.validate(other, MARKDOWN, Set.of(), d -> delivered.add("other"));
                service.validate(buffer, MARKDOWN, Set.of(), d -> delivered.add("latest"));
            });
            awaitDrained(done -> service.validate(new Object(), MARKDOWN, Set.of(), d -> done.run()));
            assertEquals(List.of("other", "latest"), delivered, "typing ahead of the linter keeps only the newest");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void theRequesterlessOverloadsKeepOneSharedGuard() throws Exception {
        MarkdownLintService service = new MarkdownLintService();
        try {
            List<String> delivered = Collections.synchronizedList(new ArrayList<>());
            FxTestSupport.runOnFx(() -> {
                service.validate(MARKDOWN, d -> delivered.add("stale"));
                service.validate(MARKDOWN, Set.of(), d -> delivered.add("latest"));
            });
            awaitDrained(done -> service.validate(new Object(), MARKDOWN, Set.of(), d -> done.run()));
            assertEquals(List.of("latest"), delivered);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void mermaidValidationDeliversEveryRequestersResultFromOneTick() throws Exception {
        MermaidService service = new MermaidService();
        // A maid that is not there: every validation fails fast and reports no diagnostics, which is all
        // this needs — the guard, not the linter, is under test.
        service.setPaths("", "/nonexistent/editora-test/maid");
        try {
            Object first = new Object();
            Object second = new Object();
            List<String> delivered = Collections.synchronizedList(new ArrayList<>());
            FxTestSupport.runOnFx(() -> {
                service.validate(first, "graph TD; a-->b", d -> delivered.add("first-stale"));
                service.validate(second, "graph TD; a-->b", d -> delivered.add("second"));
                service.validate(first, "graph TD; a-->c", d -> delivered.add("first-latest"));
            });
            awaitDrained(done -> service.validate(new Object(), "graph TD; x-->y", d -> done.run()));
            assertEquals(List.of("second", "first-latest"), delivered);
        } finally {
            service.shutdown();
        }
    }
}
