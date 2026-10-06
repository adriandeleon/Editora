package com.editora.lsp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;
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

/**
 * The outgoing end of a language-server session: LSP4J's stream writer, except that the three notifications
 * that carry document text are encoded on the writer thread instead of the calling one.
 *
 * <p>LSP4J turns a message into JSON on the thread that sends it, and document sync is sent from the FX
 * thread. {@code didOpen}, a full-sync {@code didChange} (every change for a full-sync server, the periodic
 * resync for an incremental one) and a {@code didSave} that includes the text each escape and UTF-8 encode
 * the whole document — up to the megabytes the LSP size gate lets through — inside the edit-settled pulse.
 * {@link AsyncPipeWriter} already moved the pipe write off that thread; this moves the encoding with it.
 *
 * <p>Only these three are deferred, because only their parameters are known to be untouched after the
 * call: the session builds them from strings and fresh lists. Any other message may reference an object
 * its caller still owns (a completion item being resolved, the active signature help), so it is encoded
 * where it always was, before {@code consume} returns.
 *
 * <p>Wire order is unchanged. Every message passes through one lock here, and under it either lands in
 * the writer's queue as bytes or takes its place in the same queue as an entry to be encoded later.
 */
final class DeferredSyncConsumer implements MessageConsumer {

    static final Set<String> DEFERRED_METHODS =
            Set.of("textDocument/didOpen", "textDocument/didChange", "textDocument/didSave");

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
        if (message instanceof NotificationMessage notification
                && DEFERRED_METHODS.contains(notification.getMethod())) {
            try {
                writer.writeDeferred(estimatedBytes(notification.getParams()), () -> encode(message));
            } catch (IOException e) {
                throw new JsonRpcException(e); // what LSP4J's own consumer reports for a closed stream
            }
        } else {
            direct.consume(message);
        }
    }

    /** The framed message exactly as LSP4J writes it: its own consumer, pointed at a buffer. */
    private byte[] encode(Message message) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new StreamMessageConsumer(bytes, jsonHandler.get()).consume(message);
        return bytes.toByteArray();
    }

    /** Pure: roughly how many bytes {@code params} encodes to — its document text plus an envelope. */
    static long estimatedBytes(Object params) {
        long text = 0;
        if (params instanceof DidOpenTextDocumentParams open && open.getTextDocument() != null) {
            text = length(open.getTextDocument().getText());
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
