package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.perf.BlockingSites;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-01 (Pauta 6.0.1) moved a {@code didChange}'s compare, diff and JSON to the session's writer thread. What must
 * not move with them: the order of the changes, the versions the server sees, or one character of the document it
 * holds. A fake server at the other end of a pipe applies every change it is sent, in order, and must end up with
 * exactly the text last synced, at versions that go up by one per change.
 */
@Tag("fx")
class LspSyncOverWireFxTest {

    private static final String URI = "file:///work/A.java";

    private final List<String> heardOn = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void stopListening() {
        BlockingSites.listenForTest(null);
    }

    private static ServerCapabilities caps(TextDocumentSyncKind kind) {
        var c = new ServerCapabilities();
        var sync = new TextDocumentSyncOptions();
        sync.setChange(kind);
        c.setTextDocumentSync(sync);
        return c;
    }

    private static LanguageServerSession wired(FakeLanguageServer fake, TextDocumentSyncKind kind) throws Exception {
        var spec = new LspServerRegistry.ServerSpec("java", List.of("jdtls"), List.of("pom.xml"));
        var s = new LanguageServerSession(spec, Path.of("/tmp"), d -> {}, (t, m) -> {}, null);
        s.attachWiredForTest(fake, caps(kind));
        return s;
    }

    @Test
    void incrementalChangesRebuildTheDocumentExactlyInOrder() throws Exception {
        syncsExactly(TextDocumentSyncKind.Incremental);
    }

    @Test
    void fullChangesRebuildTheDocumentExactlyInOrder() throws Exception {
        syncsExactly(TextDocumentSyncKind.Full);
    }

    private void syncsExactly(TextDocumentSyncKind kind) throws Exception {
        FakeLanguageServer fake = new FakeLanguageServer();
        LanguageServerSession s = wired(fake, kind);
        BlockingSites.listenForTest((site, thread) -> heardOn.add(site + "@" + thread.getName()));
        String text = "class A {\n    String s = \"ñ 日本 😀\";\n}\n";
        s.didOpen(URI, "java", text);
        Random random = new Random(678);
        AtomicInteger identical = new AtomicInteger();
        int expectedIdentical = 0;
        int sends = 0;
        for (int i = 0; i < 300; i++) {
            String next;
            if (i % 25 == 7) {
                next = new String(text); // equal text, another string: an edit and its undo
                expectedIdentical++;
            } else {
                next = edit(text, random);
            }
            Runnable onIdentical = identical::incrementAndGet;
            assertTrue(s.didChange(URI, next, onIdentical), "a different string is always sent");
            sends++;
            text = next;
        }
        int expected = sends;
        List<DidChangeTextDocumentParams> got = await(fake, expected);
        String rebuilt = fake.opened.get(0).getTextDocument().getText();
        int version = 1;
        for (DidChangeTextDocumentParams change : got) {
            assertEquals(version + 1, change.getTextDocument().getVersion(), "versions go up by one, in order");
            version = change.getTextDocument().getVersion();
            for (TextDocumentContentChangeEvent event : change.getContentChanges()) {
                rebuilt = apply(rebuilt, event);
            }
        }
        assertEquals(text, rebuilt, "the server holds exactly the text last synced");
        assertEquals(s.documentVersion(URI), version, "the session counts the versions the server saw");
        int want = expectedIdentical;
        waitFor(() -> identical.get() == want);
        assertEquals(expectedIdentical, identical.get(), "each equal text is told back once");
        assertTrue(
                heardOn.stream().anyMatch(h -> h.startsWith(LazyChangeEvents.SITE + "@lsp-writer")),
                "the diff ran on the writer: " + heardOn);
        assertFalse(
                heardOn.stream().anyMatch(h -> h.startsWith(LazyChangeEvents.SITE) && !h.contains("@lsp-writer")),
                "the diff ran nowhere else: " + heardOn);
        assertTrue(heardOn.stream().anyMatch(h -> h.startsWith(DeferredSyncConsumer.SITE + "@lsp-writer")));
        s.dispose();
    }

    /**
     * {@code workspace/executeCommand} is encoded on the writer thread too: its arguments are frozen as it is sent, so
     * a caller that changes its own map straight after still sends what it had.
     */
    @Test
    void aCommandsArgumentsAreSentAsTheyWereWhenTheCallerChangesThemAfter() throws Exception {
        FakeLanguageServer fake = new FakeLanguageServer();
        LanguageServerSession s = wired(fake, TextDocumentSyncKind.Incremental);
        BlockingSites.listenForTest((site, thread) -> heardOn.add(site + "@" + thread.getName()));
        java.util.Map<String, Object> arg = new java.util.HashMap<>();
        arg.put("uri", "file:///a");
        List<Object> args = new ArrayList<>(List.of(arg, "two"));
        var answer = s.executeCommand("editora.test", args);
        arg.put("uri", "file:///changed"); // what a caller might do with its own map
        args.add("three");
        answer.get(10, java.util.concurrent.TimeUnit.SECONDS);
        var sent = fake.executedCommands.get(0).getArguments();
        assertEquals(2, sent.size(), "the list as it was sent: " + sent);
        assertEquals(
                "file:///a",
                ((com.google.gson.JsonObject) sent.get(0)).get("uri").getAsString());
        assertTrue(heardOn.stream().anyMatch(h -> h.startsWith(DeferredSyncConsumer.SITE + "@lsp-writer")));
        s.dispose();
    }

    @Test
    void frozenCopiesMapsListsAndJsonAndKeepsTheRest() {
        var json = new com.google.gson.JsonObject();
        json.addProperty("a", 1);
        java.util.Map<String, Object> map = new java.util.HashMap<>(java.util.Map.of("j", json, "s", "x"));
        Object frozen = LanguageServerSession.frozen(map);
        json.addProperty("a", 2);
        map.put("s", "y");
        var copy = (java.util.Map<?, ?>) frozen;
        assertEquals("x", copy.get("s"));
        assertEquals(1, ((com.google.gson.JsonObject) copy.get("j")).get("a").getAsInt());
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class, () -> ((java.util.Map<Object, Object>) copy).put("k", "v"));
    }

    /** A random edit of {@code text}: an insert, a delete or a replace, sometimes of a newline or a surrogate. */
    private static String edit(String text, Random random) {
        int at = random.nextInt(text.length() + 1);
        while (at > 0 && at < text.length() && Character.isLowSurrogate(text.charAt(at))) {
            at--;
        }
        String[] pieces = {"x", "\n", "日", "😀", "  ", "foo\nbar", ""};
        String insert = pieces[random.nextInt(pieces.length)];
        int remove = Math.min(text.length() - at, random.nextInt(4));
        int end = at + remove;
        while (end < text.length() && Character.isLowSurrogate(text.charAt(end))) {
            end++;
        }
        String next = text.substring(0, at) + insert + text.substring(end);
        return next.equals(text) ? next + "y" : next;
    }

    /** {@code event} applied to {@code text} as a server applies it: a range in UTF-16 lines and columns. */
    private static String apply(String text, TextDocumentContentChangeEvent event) {
        if (event.getRange() == null) {
            return event.getText();
        }
        int start = offset(text, event.getRange().getStart());
        int end = offset(text, event.getRange().getEnd());
        return text.substring(0, start) + event.getText() + text.substring(end);
    }

    private static int offset(String text, Position p) {
        int line = 0;
        int i = 0;
        while (line < p.getLine()) {
            i = text.indexOf('\n', i) + 1;
            line++;
        }
        return i + p.getCharacter();
    }

    private static List<DidChangeTextDocumentParams> await(FakeLanguageServer fake, int count) throws Exception {
        List<DidChangeTextDocumentParams> copy = new ArrayList<>();
        boolean got = com.editora.testkit.Await.holdsWithin(
                () -> {
                    try {
                        copy.clear();
                        copy.addAll(fake.changed);
                        return copy.size() >= count && !fake.opened.isEmpty();
                    } catch (ConcurrentModificationException racing) {
                        return false; // the fake is still adding: look again
                    }
                },
                java.time.Duration.ofSeconds(30));
        if (!got) {
            throw new AssertionError("the fake got " + fake.changed.size() + " of " + count + " changes");
        }
        return copy;
    }

    private static void waitFor(java.util.function.BooleanSupplier done) throws Exception {
        com.editora.testkit.Await.holdsWithin(done, java.time.Duration.ofSeconds(10)); // re-checked after each FX pulse
    }
}
