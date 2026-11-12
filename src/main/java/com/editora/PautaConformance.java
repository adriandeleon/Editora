package com.editora;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * How fully Editora conforms to its Pauta edition (pauta-project.dev; PAUTA-02): computed from the exceptions
 * in {@code pauta.json}, never stored. About and the Pauta window take it from here; the README is held to it
 * by {@code PautaConformanceTest}.
 *
 * <p>Partial when a MUST is pending, Core when a SHOULD is pending, Complete otherwise. A deviation, an
 * app-only or a not-applicable entry leaves the result alone, and so does a MAY: MAYs never count. The
 * {@code checked} list (PAUTA-18) is a record and never enters it.
 */
public final class PautaConformance {

    /** The bundled exceptions, next to {@code build-info.properties}. */
    static final String RESOURCE = "pauta.json";

    /** Complete, Core or Partial. Their names live in the catalogs ({@code pauta.conformance.<key>}). */
    public enum Conformance {
        COMPLETE,
        CORE,
        PARTIAL;

        /** The catalog key part and the README word's lower case: {@code complete}, {@code core}, {@code partial}. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The English word, as the README badge and the environment block write it. */
        public String english() {
            return name().charAt(0) + key().substring(1);
        }
    }

    /**
     * The four kinds of exception, in the order the Pauta window lists them (PWIN-05). {@code app-only} is a
     * template repository's alone (PAUTA-16); Editora records none, and its test fails one.
     */
    public enum Kind {
        PENDING("pending", "pending"),
        DEVIATION("deviation", "deviation"),
        APP_ONLY("app-only", "appOnly"),
        NOT_APPLICABLE("not-applicable", "notApplicable");

        private final String json;
        private final String key;

        Kind(String json, String key) {
            this.json = json;
            this.key = key;
        }

        /** How {@code pauta.json} spells it. */
        public String json() {
            return json;
        }

        /** The catalog key part: {@code pauta.kind.<key>}. */
        public String key() {
            return key;
        }

        static Kind parse(String value) {
            for (Kind kind : values()) if (kind.json.equals(value)) return kind;
            throw new IllegalArgumentException("Unknown exception kind: " + value);
        }
    }

    /**
     * One rule the app does not meet as written: the rule's ID, and its level and URL copied from the
     * edition's {@code rules.json}; the kind; one plain sentence for the user; for a deviation its decision
     * record and for a pending entry its issue.
     */
    public record Entry(
            String rule,
            String level,
            String url,
            Kind kind,
            String reason,
            @Nullable String link) {

        boolean isMust() {
            return "MUST".equals(level);
        }

        boolean isShould() {
            return "SHOULD".equals(level);
        }
    }

    /**
     * One rule a person checked by hand in the running app and found met (PAUTA-18): the rule, the edition it
     * was checked against, the date and, for a rule checked again with each release, the app's version. A
     * record only: it never changes the conformance.
     */
    public record Checked(
            String rule,
            String edition,
            String date,
            @Nullable String version) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private static @Nullable JsonNode bundled;

    private PautaConformance() {}

    /** The bundled {@code pauta.json}, read once. */
    private static synchronized JsonNode file() {
        JsonNode root = bundled;
        if (root == null) {
            try (InputStream in = PautaConformance.class.getResourceAsStream(RESOURCE)) {
                if (in == null) throw new IllegalStateException(RESOURCE + " is not bundled");
                root = JSON.readTree(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            bundled = root;
        }
        return root;
    }

    private static volatile @Nullable List<Entry> exceptions;

    /**
     * The app's own exceptions, from the bundled {@code pauta.json}: read and parsed once. The config prefetch
     * thread asks first, at start-up, so About and the Pauta window parse nothing on the FX thread (PERF-01).
     */
    public static List<Entry> exceptions() {
        List<Entry> parsed = exceptions;
        if (parsed == null) {
            parsed = parse(file());
            exceptions = parsed;
        }
        return parsed;
    }

    /** The by-hand checks the app records (PAUTA-18); empty when it records none. */
    public static List<Checked> checked() {
        return parseChecked(file());
    }

    /** The app's own conformance. */
    public static Conformance conformance() {
        return of(exceptions());
    }

    /** Reads {@code pauta.json}'s {@code exceptions} array. */
    static List<Entry> parse(JsonNode root) {
        List<Entry> entries = new ArrayList<>();
        for (JsonNode node : root.path("exceptions")) {
            JsonNode link = node.get("link");
            entries.add(new Entry(
                    node.path("rule").asText(),
                    node.path("level").asText(),
                    node.path("url").asText(),
                    Kind.parse(node.path("kind").asText()),
                    node.path("reason").asText(),
                    link == null || link.isNull() ? null : link.asText()));
        }
        return List.copyOf(entries);
    }

    /** Reads {@code pauta.json}'s {@code checked} array; a file without one records nothing. */
    static List<Checked> parseChecked(JsonNode root) {
        List<Checked> checked = new ArrayList<>();
        for (JsonNode node : root.path("checked")) {
            JsonNode version = node.get("version");
            checked.add(new Checked(
                    node.path("rule").asText(),
                    node.path("edition").asText(),
                    node.path("date").asText(),
                    version == null || version.isNull() ? null : version.asText()));
        }
        return List.copyOf(checked);
    }

    /** Partial when any MUST is pending, Core when any SHOULD is pending, Complete otherwise. */
    public static Conformance of(List<Entry> exceptions) {
        boolean should = false;
        for (Entry entry : exceptions) {
            if (entry.kind() != Kind.PENDING) continue;
            if (entry.isMust()) return Conformance.PARTIAL;
            if (entry.isShould()) should = true;
        }
        return should ? Conformance.CORE : Conformance.COMPLETE;
    }

    /** How many pending exceptions have this level: the number Core's and Partial's sentences give. */
    public static long pending(List<Entry> exceptions, String level) {
        return exceptions.stream()
                .filter(e -> e.kind() == Kind.PENDING && e.level().equals(level))
                .count();
    }

    /** How many exceptions of each kind, in list order; a kind with none is left out. */
    public static Map<Kind, Integer> counts(List<Entry> exceptions) {
        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        for (Entry entry : exceptions) counts.merge(entry.kind(), 1, Integer::sum);
        return counts;
    }

    /** Pending first, then deviations, then app-only, then not applicable; within a kind MUSTs before SHOULDs, then by ID (PWIN-05). */
    public static List<Entry> sorted(List<Entry> exceptions) {
        return exceptions.stream()
                .sorted(Comparator.comparing(Entry::kind)
                        .thenComparingInt(PautaConformance::levelRank)
                        .thenComparing(Entry::rule))
                .toList();
    }

    private static int levelRank(Entry entry) {
        return switch (entry.level()) {
            case "MUST" -> 0;
            case "SHOULD" -> 1;
            default -> 2;
        };
    }

    /**
     * The environment block's line (ABOUT-09): {@code Pauta conformance: Core (2 exceptions)}. Plain
     * English on purpose, like the rest of the block: it goes into bug reports.
     */
    public static String environmentLine(List<Entry> exceptions) {
        int n = exceptions.size();
        String count = n == 0 ? "no exceptions" : n == 1 ? "1 exception" : n + " exceptions";
        return "Pauta conformance: " + of(exceptions).english() + " (" + count + ")";
    }

    /** A pending entry's issue number, {@code #212}, or {@code null} when the link is not an issue. */
    public static @Nullable String issueNumber(String link) {
        int at = link.indexOf("/issues/");
        return at < 0 ? null : "#" + link.substring(at + "/issues/".length());
    }

    /** A deviation's decision record number, {@code 0007}, or {@code null} when the link is not one. */
    public static @Nullable String decisionNumber(String link) {
        int at = link.indexOf("/docs/decisions/");
        if (at < 0) return null;
        String file = link.substring(at + "/docs/decisions/".length());
        int dash = file.indexOf('-');
        return dash > 0 ? file.substring(0, dash) : null;
    }
}
