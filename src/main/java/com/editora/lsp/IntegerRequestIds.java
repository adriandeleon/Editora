package com.editora.lsp;

import org.eclipse.lsp4j.jsonrpc.MessageConsumer;
import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage;

/**
 * Writes the client's requests with numeric ids, as VS Code does. LSP4J numbers its requests but writes the number as a
 * string ({@code "id": "1"}), which JSON-RPC allows and most servers accept — but ElixirLS refuses it ({@code "expected
 * an integer"}) and crashes on the first request, so it restarted again and again. A request whose id is a number in
 * a string goes out as that number; LSP4J still matches the answer, since it reads every id as a string. Anything else
 * is passed on as it is.
 */
final class IntegerRequestIds implements MessageConsumer {

    private final MessageConsumer next;

    IntegerRequestIds(MessageConsumer next) {
        this.next = next;
    }

    @Override
    public void consume(Message message) {
        if (message instanceof RequestMessage request) {
            numeric(request);
        }
        next.consume(message);
    }

    /** {@code request}'s id made a number when it is a number written as a string. Pure but for the message. */
    static void numeric(RequestMessage request) {
        var raw = request.getRawId();
        if (raw == null || !raw.isLeft()) {
            return; // already a number, or none
        }
        String id = raw.getLeft();
        if (id == null || id.isEmpty() || id.length() > 9 || !id.chars().allMatch(Character::isDigit)) {
            return; // not LSP4J's counter: kept as it is
        }
        request.setId(Integer.parseInt(id));
    }
}
