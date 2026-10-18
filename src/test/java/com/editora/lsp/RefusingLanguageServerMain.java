package com.editora.lsp;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A language server that answers {@code initialize} with an error and exits, as typescript-language-server does
 * beside TypeScript 7 ("Could not find a valid TypeScript installation"). Run as a child JVM by tests.
 */
public final class RefusingLanguageServerMain {

    static final String REASON = "Could not find a valid TypeScript installation.";

    private RefusingLanguageServerMain() {}

    public static void main(String[] args) throws Exception {
        InputStream in = System.in;
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        while (!header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) {
                return;
            }
            header.write(b);
        }
        Matcher m = Pattern.compile("Content-Length: (\\d+)").matcher(header.toString(StandardCharsets.US_ASCII));
        int length = m.find() ? Integer.parseInt(m.group(1)) : 0;
        String body = new String(in.readNBytes(length), StandardCharsets.UTF_8);
        Matcher id = Pattern.compile("\"id\"\\s*:\\s*(\"[^\"]*\"|\\d+)").matcher(body);
        String reply = "{\"jsonrpc\":\"2.0\",\"id\":" + (id.find() ? id.group(1) : "null")
                + ",\"error\":{\"code\":-32603,\"message\":\"" + REASON + "\"}}";
        byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
        OutputStream out = System.out;
        out.write(("Content-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }
}
