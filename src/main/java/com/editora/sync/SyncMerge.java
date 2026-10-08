package com.editora.sync;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import com.editora.config.AbbrevStore;
import com.editora.config.SharedConfig;
import com.editora.config.StoreMerge;
import com.editora.config.migration.ConfigSchema;
import com.editora.snippet.SnippetFileMerge;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

/**
 * Three-way merge of one synced file: what this machine last synced ({@code base}), what it holds now
 * ({@code mine}) and what the repository holds ({@code theirs}). Pure.
 *
 * <p>The merge unit is the entry — one snippet, one abbreviation, one template, one dictionary word — so two
 * machines that each added or removed different entries of the same file both keep their work. An entry
 * changed differently on both sides is a conflict and this machine's version wins; the other version stays in
 * the repository's history. When only one side changed the file, that side's text is taken whole, which
 * keeps a snippet file's comments.
 *
 * <p>A file that cannot be read on either side, or that a newer Editora wrote in a format this build does not
 * know, is {@linkplain Skip skipped}: neither copy is touched.
 */
public final class SyncMerge {

    private SyncMerge() {}

    /** Why a file was left alone on both sides. */
    public enum Skip {
        NONE,
        /** This machine's copy is not valid; pushing it would break the other machines. */
        LOCAL_UNREADABLE,
        /** The repository's copy is not valid; it is never applied here. */
        REMOTE_UNREADABLE,
        /** The repository's copy was written by a newer Editora. */
        REMOTE_NEWER
    }

    /**
     * The outcome for one file.
     *
     * @param text the merged file in its repository form, or null when the file no longer exists; meaningless
     *     when {@code skip} is not {@link Skip#NONE}
     * @param received entries this machine takes from the repository (added, changed or removed there)
     * @param sent entries the repository takes from this machine
     * @param conflicts entries changed differently on both sides; this machine's version was kept
     */
    public record FileResult(
            String path,
            String text,
            List<String> received,
            List<String> sent,
            List<String> conflicts,
            Skip skip,
            int mineEntries,
            int theirEntries,
            int resultEntries) {

        public boolean skipped() {
            return skip != Skip.NONE;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectWriter PRETTY =
            MAPPER.writer(new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n")));

    /** A file's text in repository form with its entries; {@code tree} only for the abbreviation store. */
    private record Parsed(String text, Map<String, String> entries, JsonNode tree) {
        static final Parsed ABSENT = new Parsed(null, Map.of(), null);
    }

    private static final class NewerFormatException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    /**
     * The text of a file as the repository stores it: line feeds only, no byte-order mark, and for the two
     * stores Editora serializes itself a canonical rendering (sorted words; pretty-printed JSON), so the same
     * data is the same bytes on every operating system. Null when the file is absent, blank where a blank
     * file means "nothing", or unreadable.
     */
    public static String normalize(SyncCategory category, String raw) {
        try {
            return parse(category, raw).text();
        } catch (IOException e) {
            return null;
        }
    }

    public static FileResult merge(SyncCategory category, String path, String base, String mine, String theirs) {
        Parsed m;
        Parsed t;
        try {
            m = parse(category, mine);
        } catch (IOException e) {
            return skipped(path, Skip.LOCAL_UNREADABLE);
        }
        try {
            t = parse(category, theirs);
        } catch (NewerFormatException e) {
            return skipped(path, Skip.REMOTE_NEWER);
        } catch (IOException e) {
            return skipped(path, Skip.REMOTE_UNREADABLE);
        }
        Parsed b;
        try {
            b = parse(category, base);
        } catch (IOException e) {
            b = Parsed.ABSENT; // nothing to compare with: both sides look new, nothing is removed
        }

        List<String> received = new ArrayList<>();
        List<String> sent = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        // Entries whose merged value is not this machine's, with the value to take (null = remove).
        Map<String, String> fromTheirs = new LinkedHashMap<>();
        boolean keepsMine = false;
        Set<String> names = new LinkedHashSet<>(m.entries().keySet());
        names.addAll(t.entries().keySet());
        names.addAll(b.entries().keySet());
        int resultEntries = 0;
        for (String name : names) {
            String bv = b.entries().get(name);
            String mv = m.entries().get(name);
            String tv = t.entries().get(name);
            String label = label(category, path, name);
            String result;
            if (Objects.equals(mv, tv)) {
                result = mv;
            } else if (Objects.equals(mv, bv)) {
                result = tv;
                received.add(label);
                fromTheirs.put(name, tv);
            } else {
                result = mv;
                keepsMine = true;
                sent.add(label);
                if (!Objects.equals(tv, bv)) {
                    conflicts.add(label);
                }
            }
            if (result != null) {
                resultEntries++;
            }
        }

        String text;
        if (Objects.equals(m.text(), b.text())) {
            text = t.text(); // this machine did not touch the file
        } else if (Objects.equals(t.text(), b.text()) || Objects.equals(t.text(), m.text())) {
            text = m.text(); // only this machine changed it, or both made the same change
        } else if (fromTheirs.isEmpty()) {
            text = m.text();
        } else if (!keepsMine) {
            text = t.text();
        } else {
            try {
                text = assemble(category, b, m, t, fromTheirs);
            } catch (IOException e) {
                return skipped(path, Skip.LOCAL_UNREADABLE);
            }
        }
        return new FileResult(
                path,
                text,
                List.copyOf(received),
                List.copyOf(sent),
                List.copyOf(conflicts),
                Skip.NONE,
                m.entries().size(),
                t.entries().size(),
                text == null ? 0 : resultEntries);
    }

    private static FileResult skipped(String path, Skip skip) {
        return new FileResult(path, null, List.of(), List.of(), List.of(), skip, 0, 0, 0);
    }

    /** This machine's file with the repository's own changes written into it. */
    private static String assemble(
            SyncCategory category, Parsed base, Parsed mine, Parsed theirs, Map<String, String> fromTheirs)
            throws IOException {
        switch (category) {
            case DICTIONARY -> {
                Set<String> words = new TreeSet<>(mine.entries().keySet());
                fromTheirs.forEach((word, value) -> {
                    if (value == null) {
                        words.remove(word);
                    } else {
                        words.add(word);
                    }
                });
                return dictionaryText(words);
            }
            case ABBREVIATIONS -> {
                JsonNode merged = StoreMerge.merge(
                        base.tree(), mine.tree(), theirs.tree(), StoreMerge.keysFor(ConfigSchema.ABBREVIATIONS));
                return merged == null ? null : pretty(merged);
            }
            case SNIPPETS -> {
                return SnippetFileMerge.apply(mine.text(), fromTheirs);
            }
            default -> {
                return mine.text(); // a template is one entry: a file changed on both sides is a conflict
            }
        }
    }

    private static Parsed parse(SyncCategory category, String raw) throws IOException {
        if (raw == null) {
            return Parsed.ABSENT;
        }
        String text = stripBom(raw).replace("\r\n", "\n").replace('\r', '\n');
        switch (category) {
            case DICTIONARY -> {
                Set<String> words = new TreeSet<>();
                for (String line : text.split("\n")) {
                    String word = SharedConfig.dictionaryForm(line);
                    if (!word.isEmpty()) {
                        words.add(word);
                    }
                }
                Map<String, String> entries = new LinkedHashMap<>();
                words.forEach(word -> entries.put(word, ""));
                return new Parsed(dictionaryText(words), entries, null);
            }
            case ABBREVIATIONS -> {
                if (text.isBlank()) {
                    return Parsed.ABSENT; // an empty store file is a placeholder, not "no abbreviations"
                }
                JsonNode tree = MAPPER.readTree(text);
                if (tree == null || !tree.isObject()) {
                    throw new IOException("not an object");
                }
                if (tree.path("schemaVersion").asInt(0) > AbbrevStore.SCHEMA_VERSION) {
                    throw new NewerFormatException();
                }
                Map<String, String> entries = new LinkedHashMap<>();
                for (JsonNode entry : tree.path("abbreviations")) {
                    JsonNode key = entry.get("abbreviation");
                    if (key != null && key.isTextual()) {
                        entries.put(key.asText(), MAPPER.writeValueAsString(entry));
                    }
                }
                return new Parsed(pretty(tree), entries, tree);
            }
            case SNIPPETS -> {
                return new Parsed(text, SnippetFileMerge.entries(text), null);
            }
            case TEMPLATES -> {
                if (text.isBlank()) {
                    return Parsed.ABSENT;
                }
                JsonNode tree = MAPPER.readTree(text);
                if (tree == null || !tree.isObject()) {
                    throw new IOException("not an object");
                }
                return new Parsed(text, Map.of("", MAPPER.writeValueAsString(tree)), null);
            }
            default -> throw new IOException("unknown category");
        }
    }

    private static String dictionaryText(Set<String> sortedWords) {
        StringBuilder sb = new StringBuilder();
        for (String word : sortedWords) {
            sb.append(word).append('\n');
        }
        return sb.toString();
    }

    private static String pretty(JsonNode tree) throws IOException {
        return PRETTY.writeValueAsString(tree) + "\n";
    }

    private static String stripBom(String s) {
        return !s.isEmpty() && s.charAt(0) == '﻿' ? s.substring(1) : s;
    }

    /** How an entry is named in a report: the word, the abbreviation, {@code java: sysout}, the template id. */
    private static String label(SyncCategory category, String path, String name) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        String stem = file.contains(".") ? file.substring(0, file.lastIndexOf('.')) : file;
        return switch (category) {
            case SNIPPETS -> stem + ": " + name;
            case TEMPLATES -> stem;
            default -> name;
        };
    }
}
