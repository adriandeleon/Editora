package com.editora.maven;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Parses Maven's {@code archetype-catalog.xml} into {@link MavenArchetype}s — one per
 * {@code groupId:artifactId}, at its newest version.
 *
 * <p>Streamed with StAX rather than read into a DOM: Maven Central's catalog is 18 MB and growing (74,830
 * {@code <archetype>} rows for 3,758 distinct archetypes in October 2026), and the previous whole-document
 * parse sat behind a 16 MiB byte cap the real file crossed and a 20,000-entry cap that, applied in document
 * order, silently kept only the {@code ai.*}…{@code com.vaadin} groups. Now there is no entry cap: the rows
 * are folded into a map keyed by {@code groupId:artifactId} as they stream past, keeping the newest version
 * by {@link MavenVersion} (the catalog lists every published version of each archetype), so the result is a
 * few thousand records whatever the document size.
 *
 * <p>XXE-hardened like {@link PomParser#parseDocument}: no DTD, no external entities. It matters more here —
 * a pom is a file the user opened, this document is fetched from the network.
 *
 * <p>Tolerant by design — a row missing a version or artifactId is skipped rather than failing the whole
 * fetch, because one bad row should not cost the user the other few thousand.
 */
public final class ArchetypeCatalogParser {

    /** Byte cap for a streamed catalog: ~14× today's Central file, which grows about 1 MB a year. */
    public static final long MAX_CATALOG_BYTES = 256L * 1024 * 1024;

    private ArchetypeCatalogParser() {}

    /** @return the archetypes, newest version per key, in order of first appearance; never null. */
    public static List<MavenArchetype> parse(String xml) throws PomParseException {
        if (xml == null || xml.isBlank()) {
            return List.of();
        }
        try {
            return parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), Long.MAX_VALUE, null);
        } catch (IOException e) {
            throw new PomParseException("Malformed archetype catalog: " + e.getMessage(), e);
        }
    }

    /**
     * Streams {@code in} (not closed here) into the archetype list.
     *
     * @param maxBytes abort with an {@link IOException} once more than this many bytes have been read
     * @param deadline abort with an {@link IOException} once this long has passed (null: no deadline) — the
     *     HTTP request timeout ends when the headers arrive, so a server trickling the body would otherwise
     *     hold the fetch for as long as it likes
     * @throws PomParseException on malformed XML, a DOCTYPE, or an entity reference
     */
    public static List<MavenArchetype> parse(InputStream in, long maxBytes, Duration deadline)
            throws PomParseException, IOException {
        Map<String, MavenArchetype> byKey = new LinkedHashMap<>();
        BoundedInputStream bounded = new BoundedInputStream(in, maxBytes, deadline);
        XMLStreamReader reader = null;
        try {
            reader = factory().createXMLStreamReader(bounded);
            String groupId = "";
            String artifactId = "";
            String version = "";
            String description = "";
            String repository = "";
            int depth = 0;
            int archetypeDepth = -1;
            StringBuilder text = new StringBuilder();
            while (reader.hasNext()) {
                int event = reader.next();
                switch (event) {
                    case XMLStreamConstants.DTD ->
                        throw new PomParseException("Malformed archetype catalog: a DOCTYPE is not allowed", null);
                    case XMLStreamConstants.ENTITY_REFERENCE ->
                        throw new PomParseException(
                                "Malformed archetype catalog: entity references are not allowed", null);
                    case XMLStreamConstants.START_ELEMENT -> {
                        depth++;
                        text.setLength(0);
                        if (archetypeDepth < 0 && "archetype".equals(reader.getLocalName())) {
                            archetypeDepth = depth;
                            groupId = artifactId = version = description = repository = "";
                        }
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        if (archetypeDepth >= 0 && depth == archetypeDepth + 1) {
                            text.append(reader.getTextCharacters(), reader.getTextStart(), reader.getTextLength());
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        if (archetypeDepth >= 0 && depth == archetypeDepth + 1) {
                            String value = text.toString().strip();
                            switch (reader.getLocalName()) {
                                case "groupId" -> groupId = value;
                                case "artifactId" -> artifactId = value;
                                case "version" -> version = value;
                                case "description" -> description = value;
                                case "repository" -> repository = value;
                                default -> {
                                    // other children (none in the schema) are ignored
                                }
                            }
                        } else if (depth == archetypeDepth) {
                            archetypeDepth = -1;
                            if (!groupId.isEmpty() && !artifactId.isEmpty() && !version.isEmpty()) {
                                MavenArchetype row = new MavenArchetype(
                                        groupId, artifactId, version, description, repository, false);
                                byKey.merge(row.key(), row, ArchetypeCatalogParser::newer);
                            }
                        }
                        text.setLength(0);
                        depth--;
                    }
                    default -> {
                        // comments, whitespace, processing instructions: nothing to do
                    }
                }
            }
        } catch (XMLStreamException e) {
            // A bounded stream's abort surfaces wrapped in the parser's exception: keep it an IOException so
            // the caller can tell "too large / too slow" from "malformed".
            if (bounded.aborted != null) {
                throw bounded.aborted;
            }
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new PomParseException("Malformed archetype catalog: " + e.getMessage(), e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // nothing left to read
                }
            }
        }
        return List.copyOf(byKey.values());
    }

    /** The newer of two rows for one key; on a tie the first one seen stays. */
    private static MavenArchetype newer(MavenArchetype have, MavenArchetype candidate) {
        return MavenVersion.isNewer(candidate.version(), have.version()) ? candidate : have;
    }

    private static XMLInputFactory factory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        // XXE hardening: never resolve a DOCTYPE or an external/parameter entity.
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, Boolean.FALSE);
        f.setProperty(XMLInputFactory.IS_COALESCING, Boolean.FALSE);
        return f;
    }

    /** Counts bytes and watches the clock; the parser pulls through it, so a cap applies while streaming. */
    private static final class BoundedInputStream extends FilterInputStream {
        private final long max;
        private final long end;
        private final Duration deadline;
        private long total;
        IOException aborted;

        BoundedInputStream(InputStream in, long max, Duration deadline) {
            super(in);
            this.max = max;
            this.deadline = deadline;
            this.end = deadline == null ? Long.MAX_VALUE : System.nanoTime() + deadline.toNanos();
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws IOException {
            total += n;
            if (total > max) {
                aborted = new IOException("response exceeds " + max + " bytes");
                throw aborted;
            }
            if (System.nanoTime() - end > 0) {
                aborted = new IOException("response took longer than " + deadline.toSeconds() + " s");
                throw aborted;
            }
        }

        @Override
        public void close() {
            // the caller owns the stream
        }
    }
}
