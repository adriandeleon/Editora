package com.editora.lsp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.function.Supplier;

import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.jsonrpc.JsonRpcException;
import org.eclipse.lsp4j.jsonrpc.MessageConsumer;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.json.StreamMessageConsumer;
import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage;
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage;
import org.jspecify.annotations.Nullable;

/**
 * The outgoing end of a language-server session: LSP4J's stream writer, except that the messages the session
 * sends are encoded on the writer thread instead of the calling one (PERF-01, Pauta 6.0.1).
 *
 * <p>LSP4J turns a message into JSON on the thread that sends it, and most of what a session sends is sent from
 * the FX thread: document sync (the whole document for {@code didOpen}, a full-sync {@code didChange} and a
 * {@code didSave} with text) and the requests a caret or a key asks for (hover, completion, definition…).
 * {@link AsyncPipeWriter} already moved the pipe write off that thread; this moves the encoding with it. A
 * {@code didChange}'s content changes are {@link LazyChangeEvents}, worked out (compared and diffed) as they are
 * encoded, so that work moves too.
 *
 * <p>Every request and notification is encoded there. A message's parameters must therefore not change between
 * the send and its turn on the writer: the session builds most of them fresh for the one send; the settings of
 * {@code workspace/didChangeConfiguration} and the arguments of {@code workspace/executeCommand} are frozen
 * copies ({@link LanguageServerSession#frozen}); and a resolve sends an item the server answered, which Editora
 * changes only while mapping that answer ({@code CompletionMapper.itemsOf}, {@code MavenPomServer.hideEnvironmentValues}),
 * before any resolve of it can be sent. Responses to the server's requests are sent from LSP4J's own threads and
 * are encoded there, as before.
 *
 * <p>Wire order is unchanged. Every message passes through one lock here, and under it either lands in
 * the writer's queue as bytes or takes its place in the same queue as an entry to be encoded later.
 */
final class DeferredSyncConsumer implements MessageConsumer {

    /** The encoding of a message on the writer thread, named for {@link com.editora.perf.BlockingSites}. */
    public static final String SITE = "DeferredSyncConsumer.encode";

    /** Stands in for the JSON envelope when estimating a deferred message's size. */
    private static final int ENVELOPE_BYTES = 512;

    private final MessageConsumer direct;
    private final AsyncPipeWriter writer;
    private final Supplier<MessageJsonHandler> jsonHandler;

    /**
     * @param direct LSP4J's own consumer, writing to {@code writer}
     * @param jsonHandler the handler {@code direct} encodes with, so both paths produce the same bytes
     */
    DeferredSyncConsumer(MessageConsumer direct, AsyncPipeWriter writer, Supplier<MessageJsonHandler> jsonHandler) {
        this.direct = direct;
        this.writer = writer;
        this.jsonHandler = jsonHandler;
    }

    @Override
    public synchronized void consume(Message message) {
        if (deferred(message)) {
            Object params =
                    message instanceof RequestMessage r ? r.getParams() : ((NotificationMessage) message).getParams();
            try {
                writer.writeDeferred(estimatedBytes(params), () -> encode(message));
            } catch (IOException e) {
                throw new JsonRpcException(e); // what LSP4J's own consumer reports for a closed stream
            }
        } else {
            direct.consume(message);
        }
    }

    /** Whether {@code message} is encoded on the writer thread. */
    static boolean deferred(Message message) {
        return message instanceof NotificationMessage || message instanceof RequestMessage;
    }

    /** The framed message exactly as LSP4J writes it: its own consumer, pointed at a buffer. */
    private byte[] encode(Message message) {
        com.editora.perf.BlockingSites.enter(SITE);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new StreamMessageConsumer(bytes, jsonHandler.get()).consume(message);
        return bytes.toByteArray();
    }

    /** Pure: roughly how many bytes {@code params} encodes to — its document text plus an envelope. */
    static long estimatedBytes(@Nullable Object params) {
        long text = 0;
        if (params instanceof DidOpenTextDocumentParams open && open.getTextDocument() != null) {
            text = length(open.getTextDocument().getText());
        } else if (params instanceof DidChangeTextDocumentParams change
                && change.getContentChanges() instanceof LazyChangeEvents lazy) {
            text = lazy.estimatedChars(); // read on the writer thread, as it is encoded
        } else if (params instanceof DidChangeTextDocumentParams change && change.getContentChanges() != null) {
            for (var event : change.getContentChanges()) {
                text += event == null ? 0 : length(event.getText());
            }
        } else if (params instanceof DidSaveTextDocumentParams save) {
            text = length(save.getText());
        }
        return text + ENVELOPE_BYTES;
    }

    private static long length(String text) {
        return text == null ? 0 : text.length();
    }
}
