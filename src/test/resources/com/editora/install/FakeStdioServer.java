import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A stand-in language server for {@code JvmLspSmokeTest}, run through the JDK's source launcher
 * ({@code java FakeStdioServer.java <mode>}). It speaks just enough JSON-RPC over stdio for the smoke: answers
 * {@code initialize} with a result, then — by mode — answers {@code textDocument/hover} with a result
 * ({@code ok}), with lemminx's -32603 {@code NoClassDefFoundError} ({@code error}), never ({@code hang}), or
 * exits with code 3 and a line on stderr before answering it ({@code exit}). Plain string handling, no JSON
 * library: the smoke's requests are small and their ids are known.
 */
public final class FakeStdioServer {

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "ok";
        InputStream in = System.in;
        OutputStream out = System.out;
        String body;
        while ((body = read(in)) != null) {
            if (body.contains("\"method\":\"initialize\"")) {
                send(out, "{\"jsonrpc\":\"2.0\",\"id\":" + id(body)
                        + ",\"result\":{\"capabilities\":{\"hoverProvider\":true}}}");
                // what a real server also does before the first request: a notification the client must skip
                send(out, "{\"jsonrpc\":\"2.0\",\"method\":\"window/logMessage\",\"params\":{\"type\":3,\"message\":\"hello\"}}");
            } else if (body.contains("\"method\":\"textDocument/hover\"")) {
                switch (mode) {
                    case "error" -> send(out, "{\"jsonrpc\":\"2.0\",\"id\":" + id(body)
                            + ",\"error\":{\"code\":-32603,\"message\":\"Internal error.\",\"data\":"
                            + "\"java.lang.NoClassDefFoundError: com/google/common/cache/CacheBuilder\\n\\tat org.eclipse.lemminx.uriresolver.CacheResourcesManager.<init>(CacheResourcesManager.java:144)\"}}");
                    case "hang" -> {
                        // never answers
                    }
                    case "exit" -> {
                        System.err.println("fake server: giving up");
                        System.err.flush();
                        System.exit(3);
                    }
                    default -> send(out, "{\"jsonrpc\":\"2.0\",\"id\":" + id(body) + ",\"result\":null}");
                }
            } else if (body.contains("\"method\":\"shutdown\"")) {
                send(out, "{\"jsonrpc\":\"2.0\",\"id\":" + id(body) + ",\"result\":null}");
            } else if (body.contains("\"method\":\"exit\"")) {
                System.exit(0);
            }
        }
    }

    private static String id(String body) {
        int at = body.indexOf("\"id\":") + 5;
        int end = at;
        while (end < body.length() && Character.isDigit(body.charAt(end))) {
            end++;
        }
        return body.substring(at, end);
    }

    private static void send(OutputStream out, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        out.write(("Content-Length: " + b.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    private static String read(InputStream in) throws IOException {
        int length = -1;
        while (true) {
            String line = line(in);
            if (line == null) {
                return null;
            }
            if (line.isEmpty()) {
                if (length >= 0) {
                    break;
                }
                continue;
            }
            if (line.toLowerCase().startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(15).trim());
            }
        }
        byte[] body = in.readNBytes(length);
        return body.length < length ? null : new String(body, StandardCharsets.UTF_8);
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') {
            if (c != '\r') {
                buf.write(c);
            }
        }
        return c < 0 && buf.size() == 0 ? null : buf.toString(StandardCharsets.US_ASCII);
    }
}
