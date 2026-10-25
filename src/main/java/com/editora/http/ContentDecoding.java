package com.editora.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import org.jspecify.annotations.Nullable;

/**
 * Decodes a response body by its {@code Content-Encoding}. The JDK client hands the body over exactly as it
 * arrived, so a server answering {@code Accept-Encoding: gzip} — a header every browser-copied cURL command
 * carries — produced a viewer full of binary noise, JSONPath chaining that found nothing, and a {@code >>} file
 * that held the compressed bytes. {@code gzip}, {@code x-gzip} and {@code deflate} (zlib-wrapped or raw) are
 * decoded, also when stacked; anything else ({@code br}, {@code zstd}) is passed through with a reason, so the
 * caller can say why the body looks the way it does. Pure, so it is unit-tested.
 *
 * <p>The size cap and the deadline apply to the <em>decoded</em> stream, so a small compressed body that
 * expands to gigabytes is still cut at the cap. At most {@link #MAX_LAYERS} codings are undone: a header listing
 * thousands of {@code gzip} codings would otherwise build that many nested decoders, and the first read would
 * recurse through all of them. Closing the decoded stream ends every decoder's native inflater.
 */
public final class ContentDecoding {

    /** The most codings undone; a longer list is passed through undecoded (no real server stacks more than two). */
    public static final int MAX_LAYERS = 4;

    private ContentDecoding() {}

    /** A read failure while decoding the body: the encoding is named in the message. */
    public static final class DecodeException extends IOException {
        private final String encoding;

        DecodeException(String encoding, IOException cause) {
            super(cause.getMessage(), cause);
            this.encoding = encoding;
        }

        public String encoding() {
            return encoding;
        }
    }

    /**
     * The decoded stream, and the encoding left undecoded (null when everything was decoded); {@code tooManyLayers}
     * when it was left undecoded because it lists more than {@link #MAX_LAYERS} codings.
     */
    public record Decoded(InputStream stream, @Nullable String undecoded, boolean tooManyLayers) {

        public Decoded(InputStream stream, @Nullable String undecoded) {
            this(stream, undecoded, false);
        }
    }

    /** Wraps {@code in} with decoders for {@code contentEncoding} (a header value, may be null). */
    public static Decoded decode(InputStream in, @Nullable String contentEncoding) {
        List<String> codings = new ArrayList<>();
        if (contentEncoding != null) {
            for (String c : contentEncoding.split(",")) {
                String t = c.strip().toLowerCase(Locale.ROOT);
                if (!t.isEmpty() && !t.equals("identity")) {
                    codings.add(t);
                }
            }
        }
        if (codings.size() > MAX_LAYERS) {
            return new Decoded(in, String.join(", ", codings), true);
        }
        for (String c : codings) {
            if (!supported(c)) {
                return new Decoded(in, String.join(", ", codings)); // undo none rather than half of them
            }
        }
        InputStream out = in;
        // Codings are listed in the order they were applied, so they are undone last to first.
        for (int i = codings.size() - 1; i >= 0; i--) {
            out = wrap(out, codings.get(i));
        }
        return new Decoded(out, null);
    }

    private static boolean supported(String coding) {
        return coding.equals("gzip") || coding.equals("x-gzip") || coding.equals("deflate");
    }

    private static InputStream wrap(InputStream in, String coding) {
        return guarded(lazy(in, coding), coding);
    }

    /** The decoder for {@code coding} over {@code p}; called on the first read, never before the body flows. */
    private static InputStream open(PushbackInputStream p, String coding) throws IOException {
        int b0 = p.read();
        if (b0 < 0) {
            return InputStream.nullInputStream(); // an empty body decodes to nothing, not to an error
        }
        if (coding.equals("deflate")) {
            // "deflate" is meant to be zlib-wrapped, but some servers send a raw deflate stream: a zlib header is
            // CMF/FLG with CM=8 and a big-endian value divisible by 31.
            int b1 = p.read();
            if (b1 >= 0) {
                p.unread(b1);
            }
            p.unread(b0);
            boolean zlib = b1 >= 0 && (b0 & 0x0F) == 8 && ((b0 << 8) | b1) % 31 == 0;
            Inflater inflater = new Inflater(!zlib);
            // An InflaterInputStream does not end an Inflater it was handed: end it here, or its native memory is
            // held until the Inflater is collected.
            return new InflaterInputStream(p, inflater, 16 * 1024) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        inflater.end();
                    }
                }
            };
        }
        p.unread(b0);
        return new GZIPInputStream(p, 16 * 1024);
    }

    /**
     * A stream that builds its decoder on first use: a decoder reads its header in its constructor, which must
     * not block before the caller's body deadline is armed.
     */
    private static InputStream lazy(InputStream in, String coding) {
        return new InputStream() {
            private @Nullable InputStream decoder;

            private InputStream decoder() throws IOException {
                if (decoder == null) {
                    decoder = open(new PushbackInputStream(in, 2), coding);
                }
                return decoder;
            }

            @Override
            public int read() throws IOException {
                return decoder().read();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return decoder().read(b, off, len);
            }

            @Override
            public void close() throws IOException {
                if (decoder != null) {
                    decoder.close(); // ends its inflater, and closes the stream below it
                } else {
                    in.close();
                }
            }
        };
    }

    /** Names the encoding in a decoding failure, so the error says "gzip" rather than "Not in GZIP format". */
    private static InputStream guarded(InputStream in, String coding) {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    return in.read();
                } catch (java.util.zip.ZipException | java.io.EOFException e) {
                    throw new DecodeException(coding, e);
                }
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                try {
                    return in.read(b, off, len);
                } catch (java.util.zip.ZipException | java.io.EOFException e) {
                    throw new DecodeException(coding, e);
                }
            }

            @Override
            public void close() throws IOException {
                in.close();
            }
        };
    }
}
