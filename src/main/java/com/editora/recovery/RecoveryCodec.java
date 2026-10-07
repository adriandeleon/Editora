package com.editora.recovery;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32C;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The on-disk form of a {@link RecoveryRecord}: a magic line, one line of JSON metadata, then the text.
 *
 * <pre>
 * EDITORA-RECOVERY 1\n
 * {"bufferId":…,"textEncoding":"utf-8","textBytes":123,"textCrc":456,…}\n
 * &lt;textBytes bytes of text&gt;
 * </pre>
 *
 * <p>The metadata states the length and CRC-32C of the text, so a record that was cut short, extended or
 * altered is recognised as such and never offered as if it were the user's document. The text is UTF-8 when
 * it encodes without loss; a document holding an unpaired surrogate (which UTF-8 cannot carry) is stored as
 * its raw UTF-16 code units instead, so what comes back is exactly what was in the buffer.
 */
public final class RecoveryCodec {

    static final String MAGIC = "EDITORA-RECOVERY 1\n";

    private static final String UTF8 = "utf-8";
    private static final String UTF16_UNITS = "utf-16-units";
    /** A metadata line longer than this is not one this class wrote. */
    private static final int MAX_HEADER_BYTES = 64 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    private RecoveryCodec() {}

    /** The bytes to store for {@code record}; its text must be present. */
    public static byte[] encode(RecoveryRecord record) {
        String text = record.text() == null ? "" : record.text();
        String encoding = UTF8;
        byte[] body;
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            body = new byte[encoded.remaining()];
            encoded.get(body);
        } catch (CharacterCodingException unpairedSurrogate) {
            encoding = UTF16_UNITS;
            ByteBuffer units = ByteBuffer.allocate(Math.multiplyExact(text.length(), 2));
            units.asCharBuffer().put(text);
            body = units.array();
        }
        ObjectNode header = JSON.createObjectNode();
        header.put("bufferId", record.bufferId());
        header.put("path", record.path());
        header.put("title", record.title());
        header.put("displayName", record.displayName());
        header.put("charset", record.charset());
        header.put("bom", record.bom());
        header.put("lineEnding", record.lineEnding());
        header.put("baseModifiedMillis", record.baseModifiedMillis());
        header.put("baseSize", record.baseSize());
        header.put("baseFingerprint", record.baseFingerprint());
        header.put("savedAtMillis", record.savedAtMillis());
        header.put("caret", record.caret());
        header.put("windowKey", record.windowKey());
        header.put("textEncoding", encoding);
        header.put("textBytes", body.length);
        header.put("textCrc", crc(body, 0, body.length));
        byte[] magic = MAGIC.getBytes(StandardCharsets.US_ASCII);
        byte[] meta = header.toString().getBytes(StandardCharsets.UTF_8); // Jackson escapes every line break
        byte[] out = new byte[Math.addExact(magic.length + meta.length + 1, body.length)];
        System.arraycopy(magic, 0, out, 0, magic.length);
        System.arraycopy(meta, 0, out, magic.length, meta.length);
        out[magic.length + meta.length] = '\n';
        System.arraycopy(body, 0, out, magic.length + meta.length + 1, body.length);
        return out;
    }

    /**
     * Reads a stored record, verifying it is whole.
     *
     * @param withText false to check the text but leave it out of the result (a listing)
     * @throws IOException when {@code bytes} is not a complete record written by {@link #encode}
     */
    public static RecoveryRecord decode(byte[] bytes, boolean withText) throws IOException {
        byte[] magic = MAGIC.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < magic.length || !java.util.Arrays.equals(bytes, 0, magic.length, magic, 0, magic.length)) {
            throw new IOException("not a recovery record");
        }
        int headerEnd = -1;
        int limit = Math.min(bytes.length, magic.length + MAX_HEADER_BYTES);
        for (int i = magic.length; i < limit; i++) {
            if (bytes[i] == '\n') {
                headerEnd = i;
                break;
            }
        }
        if (headerEnd < 0) {
            throw new IOException("recovery record has no complete header");
        }
        JsonNode header;
        try {
            header = JSON.readTree(bytes, magic.length, headerEnd - magic.length);
        } catch (IOException | RuntimeException e) {
            throw new IOException("recovery record header is unreadable", e);
        }
        if (header == null || !header.isObject()) {
            throw new IOException("recovery record header is not an object");
        }
        String bufferId = text(header, "bufferId");
        if (bufferId == null || !header.hasNonNull("textBytes") || !header.hasNonNull("textCrc")) {
            throw new IOException("recovery record header is incomplete");
        }
        int bodyStart = headerEnd + 1;
        long declared = header.get("textBytes").asLong(-1);
        if (declared != bytes.length - bodyStart) {
            throw new IOException("recovery record text is " + (bytes.length - bodyStart) + " bytes, not " + declared);
        }
        if (crc(bytes, bodyStart, bytes.length - bodyStart)
                != header.get("textCrc").asLong(-1)) {
            throw new IOException("recovery record text does not match its checksum");
        }
        String encoding = text(header, "textEncoding");
        String body = null;
        if (UTF16_UNITS.equals(encoding)) {
            if ((bytes.length - bodyStart) % 2 != 0) {
                throw new IOException("recovery record text has half a UTF-16 unit");
            }
            if (withText) {
                body = ByteBuffer.wrap(bytes, bodyStart, bytes.length - bodyStart)
                        .asCharBuffer()
                        .toString();
            }
        } else if (UTF8.equals(encoding)) {
            if (withText) {
                body = new String(bytes, bodyStart, bytes.length - bodyStart, StandardCharsets.UTF_8);
            }
        } else {
            throw new IOException("recovery record text encoding is unknown: " + encoding);
        }
        return new RecoveryRecord(
                bufferId,
                text(header, "path"),
                text(header, "title"),
                text(header, "displayName"),
                text(header, "charset"),
                header.path("bom").asBoolean(true),
                text(header, "lineEnding"),
                header.path("baseModifiedMillis").asLong(-1),
                header.path("baseSize").asLong(-1),
                text(header, "baseFingerprint"),
                header.path("savedAtMillis").asLong(0),
                header.path("caret").asInt(0),
                text(header, "windowKey"),
                body);
    }

    private static String text(JsonNode header, String field) {
        JsonNode node = header.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static long crc(byte[] bytes, int offset, int length) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, offset, length);
        return crc.getValue();
    }
}
