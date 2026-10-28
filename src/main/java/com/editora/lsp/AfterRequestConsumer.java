package com.editora.lsp;

import org.eclipse.lsp4j.jsonrpc.MessageConsumer;
import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage;

/**
 * The incoming end of a language-server session: LSP4J's own consumer, then {@code after} once a request named
 * {@code method} has been handled.
 *
 * <p>A request the client answers at once ({@code workspace/configuration} does) has its response handed to the
 * writer before LSP4J's consumer returns, so whatever {@code after} sends goes on the wire behind that answer. The
 * Bash server lints nothing it is sent before it has its configuration (Bash review B1): only from here is a
 * re-sync of the open documents certain to arrive after it.
 */
final class AfterRequestConsumer implements MessageConsumer {

    private final MessageConsumer delegate;
    private final String method;
    private final Runnable after;

    AfterRequestConsumer(MessageConsumer delegate, String method, Runnable after) {
        this.delegate = delegate;
        this.method = method;
        this.after = after;
    }

    @Override
    public void consume(Message message) {
        delegate.consume(message);
        if (message instanceof RequestMessage request && method.equals(request.getMethod())) {
            after.run();
        }
    }
}
