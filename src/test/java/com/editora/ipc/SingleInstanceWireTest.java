package com.editora.ipc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The single-instance request line: what is accepted from a second launch and what is refused — the wrong
 * program, the wrong token, arguments that are not what {@code buildRequest} writes, a line with no end.
 */
class SingleInstanceWireTest {

    private static final String TOKEN = "s3cret-token";

    @Test
    void aRequestRoundTripsItsArgumentsWhateverTheyContain() {
        List<String> args = List.of("/tmp/a file with spaces.txt", "--line=3", "naïve — ünïcödé");

        String line = SingleInstance.buildRequest(TOKEN, args);

        assertEquals(args, SingleInstance.parseRequest(line, TOKEN));
        assertEquals(args, SingleInstance.parseRequest("  " + line + "  \r", TOKEN), "surrounding blanks are ignored");
        assertEquals(List.of(), SingleInstance.parseRequest(SingleInstance.buildRequest(TOKEN, List.of()), TOKEN));
    }

    @Test
    void aNullArgumentIsLeftOutOfTheRequest() {
        String line = SingleInstance.buildRequest(TOKEN, Arrays.asList("first", null, "second"));

        assertEquals(List.of("first", "second"), SingleInstance.parseRequest(line, TOKEN));
    }

    @Test
    void aLineThatIsNotAnEditoraRequestForThisInstanceIsRefused() {
        String good = SingleInstance.buildRequest(TOKEN, List.of("file.txt"));

        assertNull(SingleInstance.parseRequest(null, TOKEN));
        assertNull(SingleInstance.parseRequest(good, null), "an instance with no token accepts nothing");
        assertNull(SingleInstance.parseRequest(good, "  "));
        assertNull(SingleInstance.parseRequest(SingleInstance.MAGIC, TOKEN), "no token on the line");
        assertNull(SingleInstance.parseRequest("GET / HTTP/1.1", TOKEN), "some other program on the port");
        assertNull(SingleInstance.parseRequest(good, "another-token"));
        assertNull(SingleInstance.parseRequest(good.replace(TOKEN, TOKEN + "x"), TOKEN));
        assertNull(
                SingleInstance.parseRequest(SingleInstance.MAGIC + " " + TOKEN + " not-hex", TOKEN),
                "arguments travel hex-encoded; anything else is not ours");
        assertNull(SingleInstance.parseRequest(SingleInstance.MAGIC + " " + TOKEN + " abc", TOKEN), "half a byte");
    }

    @Test
    void doubledSpacesBetweenArgumentsAreNotArguments() {
        String line = SingleInstance.buildRequest(TOKEN, List.of("one", "two")).replace(" ", "  ");

        // The token sits after two spaces too, so the first field after the magic is empty: not this token.
        assertNull(SingleInstance.parseRequest(line, TOKEN));

        String[] fields =
                SingleInstance.buildRequest(TOKEN, List.of("one", "two")).split(" ");
        String spaced = fields[0] + " " + fields[1] + "  " + fields[2] + "   " + fields[3];
        assertEquals(List.of("one", "two"), SingleInstance.parseRequest(spaced, TOKEN));
    }

    @Test
    void aRequestLineIsReadUpToItsLineEndWithoutTheCarriageReturn() throws IOException {
        assertEquals("EDITORA one", SingleInstance.readBounded(reader("EDITORA one\r\nignored")));
        assertEquals("no line end at all", SingleInstance.readBounded(reader("no line end at all")));
        assertNull(SingleInstance.readBounded(reader("")), "a connection that sent nothing");
        assertNull(SingleInstance.readBounded(reader("\r\n")), "an empty line is no request");
    }

    @Test
    void aLineWithNoEndIsGivenUpOnRatherThanBufferedForever() throws IOException {
        String endless = "x".repeat(70 * 1024);

        assertNull(SingleInstance.readBounded(reader(endless)));
    }

    @Test
    void withoutAConfigDirectoryThereIsNothingToHandOffThrough() {
        SingleInstance.Result result = SingleInstance.start(null, List.of("file.txt"), true, true);

        assertEquals(SingleInstance.Role.STANDALONE, result.role());
        assertNull(result.instance());
        assertFalse(result.forwarded());
    }

    private static BufferedReader reader(String text) {
        return new BufferedReader(new StringReader(text));
    }
}
