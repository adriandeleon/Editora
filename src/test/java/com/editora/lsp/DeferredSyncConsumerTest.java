package com.editora.lsp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.json.StreamMessageConsumer;
import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage;
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage;
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DeferredSyncConsumer} moves the JSON encoding of document-sync notifications to the writer thread.
 * What must not move with it: a single byte of what the server receives, or the order it receives it in.
 */
class DeferredSyncConsumerTest {

    private static final String URI = "file:///work/A.java";

    private static MessageJsonHandler jsonHandler() {
        return new MessageJsonHandler(ServiceEndpoints.getSupportedMethods(LanguageServer.class));
    }

    private static NotificationMessage notification(String method, Object params) {
        NotificationMessage message = new NotificationMessage();
        message.setMethod(method);
        message.setParams(params);
        return message;
    }

    private static RequestMessage request(int id, String method, Object params) {
        RequestMessage message = new RequestMessage();
        message.setId(id);
        message.setMethod(method);
        message.setParams(params);
        return message;
    }

    /** A session's traffic: open, then edits interleaved with requests, a save with text, and a close. */
    private static List<Message> traffic() {
        String text = "class A {\n    String s = \"quotes \\\" and unicode ñ 日本 😀\";\n}\n".repeat(50);
        List<Message> out = new ArrayList<>();
        out.add(notification(
                "textDocument/didOpen", new DidOpenTextDocumentParams(new TextDocumentItem(URI, "java", 1, text))));
        for (int version = 2; version < 12; version++) {
            var incremental = new TextDocumentContentChangeEvent(
                    new Range(new Position(0, 0), new Position(0, 1)), "c" + version);
            var full = new TextDocumentContentChangeEvent(text + version);
            out.add(notification(
                    "textDocument/didChange",
                    new DidChangeTextDocumentParams(
                            new VersionedTextDocumentIdentifier(URI, version),
                            List.of(version % 2 == 0 ? incremental : full))));
            out.add(request(
                    version, "textDocument/documentSymbol", new DocumentSymbolParams(new TextDocumentIdentifier(URI))));
        }
        out.add(notification(
                "textDocument/didSave", new DidSaveTextDocumentParams(new TextDocumentIdentifier(URI), text)));
        out.add(notification(
                "textDocument/didClose",
                new org.eclipse.lsp4j.DidCloseTextDocumentParams(new TextDocumentIdentifier(URI))));
        return out;
    }

    @Test
    void theServerReceivesExactlyTheBytesLsp4jWouldHaveWrittenInTheSameOrder() throws Exception {
        MessageJsonHandler json = jsonHandler();
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        StreamMessageConsumer plain = new StreamMessageConsumer(expected, json);
        for (Message message : traffic()) {
            plain.consume(message);
        }

        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        AsyncPipeWriter writer = new AsyncPipeWriter(wire, "test-writer", () -> {});
        try {
            var deferring = new DeferredSyncConsumer(new StreamMessageConsumer(writer, json), writer, () -> json);
            for (Message message : traffic()) {
                deferring.consume(message);
            }
            assertTrue(writer.awaitDrained(10, TimeUnit.SECONDS));
        } finally {
            writer.close();
        }

        assertArrayEquals(expected.toByteArray(), wire.toByteArray());
        assertTrue(wire.toString(StandardCharsets.UTF_8).contains("textDocument/didSave"), "precondition");
    }

    /** Records the thread that turns it into JSON. */
    @JsonAdapter(Probe.Adapter.class)
    static final class Probe {
        static final List<String> ENCODED_ON = new CopyOnWriteArrayList<>();

        static final class Adapter extends TypeAdapter<Probe> {
            @Override
            public void write(JsonWriter out, Probe value) throws java.io.IOException {
                ENCODED_ON.add(Thread.currentThread().getName());
                out.beginObject().endObject();
            }

            @Override
            public Probe read(JsonReader in) throws java.io.IOException {
                in.skipValue();
                return new Probe();
            }
        }
    }

    @Test
    void documentSyncIsEncodedOnTheWriterThreadAndEverythingElseWhereItWasSent() throws Exception {
        Probe.ENCODED_ON.clear();
        MessageJsonHandler json = jsonHandler();
        AsyncPipeWriter writer = new AsyncPipeWriter(new ByteArrayOutputStream(), "sync-writer", () -> {});
        String caller = Thread.currentThread().getName();
        try {
            var deferring = new DeferredSyncConsumer(new StreamMessageConsumer(writer, json), writer, () -> json);
            deferring.consume(notification("textDocument/didOpen", new Probe()));
            deferring.consume(notification("textDocument/didChange", new Probe()));
            deferring.consume(notification("textDocument/didSave", new Probe()));
            deferring.consume(notification("textDocument/didClose", new Probe()));
            deferring.consume(request(1, "textDocument/hover", new Probe()));
            assertTrue(writer.awaitDrained(10, TimeUnit.SECONDS));
        } finally {
            writer.close();
        }

        List<String> writerThread =
                Probe.ENCODED_ON.stream().filter("sync-writer"::equals).toList();
        List<String> callingThread =
                Probe.ENCODED_ON.stream().filter(caller::equals).toList();
        assertEquals(3, writerThread.size(), "didOpen, didChange and didSave: " + Probe.ENCODED_ON);
        assertEquals(2, callingThread.size(), "didClose and the request: " + Probe.ENCODED_ON);
    }

    @Test
    void theBacklogEstimateFollowsTheDocumentText() {
        String text = "x".repeat(10_000);
        long open = DeferredSyncConsumer.estimatedBytes(
                new DidOpenTextDocumentParams(new TextDocumentItem(URI, "java", 1, text)));
        long change = DeferredSyncConsumer.estimatedBytes(new DidChangeTextDocumentParams(
                new VersionedTextDocumentIdentifier(URI, 2),
                List.of(new TextDocumentContentChangeEvent(text), new TextDocumentContentChangeEvent(text))));
        long save = DeferredSyncConsumer.estimatedBytes(
                new DidSaveTextDocumentParams(new TextDocumentIdentifier(URI), null));

        assertTrue(open >= 10_000 && open < 11_000, "open: " + open);
        assertTrue(change >= 20_000 && change < 21_000, "change: " + change);
        assertTrue(save > 0 && save < 1_000, "a save without text is only an envelope: " + save);
    }

    /** A writer that is gone reports the way LSP4J's own consumer does, so the endpoint logs it quietly. */
    @Test
    void aClosedWriterSurfacesAsAClosedStream() {
        MessageJsonHandler json = jsonHandler();
        AsyncPipeWriter writer = new AsyncPipeWriter(new ByteArrayOutputStream(), "test-writer", () -> {});
        writer.close();
        var deferring = new DeferredSyncConsumer(new StreamMessageConsumer(writer, json), writer, () -> json);

        var failure = org.junit.jupiter.api.Assertions.assertThrows(
                org.eclipse.lsp4j.jsonrpc.JsonRpcException.class,
                () -> deferring.consume(notification(
                        "textDocument/didOpen",
                        new DidOpenTextDocumentParams(new TextDocumentItem(URI, "java", 1, "x")))));

        assertTrue(org.eclipse.lsp4j.jsonrpc.JsonRpcException.indicatesStreamClosed(failure));
    }
}
