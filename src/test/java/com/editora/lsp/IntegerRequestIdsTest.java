package com.editora.lsp;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage;
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegerRequestIdsTest {

    private static RequestMessage request(String id) {
        RequestMessage m = new RequestMessage();
        m.setMethod("initialize");
        m.setId(id);
        return m;
    }

    @Test
    void lsp4jsNumberWrittenAsAStringGoesOutAsANumber() {
        List<Message> out = new ArrayList<>();
        IntegerRequestIds ids = new IntegerRequestIds(out::add);
        RequestMessage m = request("12");
        ids.consume(m);
        assertSame(m, out.getFirst());
        assertTrue(m.getRawId().isRight(), "a number: ElixirLS takes no other");
        assertEquals(12, m.getRawId().getRight().intValue());
        assertEquals("12", m.getId(), "LSP4J still matches the answer by its string");
    }

    @Test
    void anythingElseIsPassedOnAsItIs() {
        List<Message> out = new ArrayList<>();
        IntegerRequestIds ids = new IntegerRequestIds(out::add);
        RequestMessage named = request("abc-1");
        ids.consume(named);
        assertTrue(named.getRawId().isLeft(), "not LSP4J's counter");
        RequestMessage number = new RequestMessage();
        number.setId(7);
        ids.consume(number);
        assertEquals(7, number.getRawId().getRight().intValue());
        NotificationMessage note = new NotificationMessage();
        note.setMethod("initialized");
        ids.consume(note);
        assertEquals(3, out.size());
    }
}
