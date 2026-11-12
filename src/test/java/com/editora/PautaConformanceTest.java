package com.editora;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.PautaConformance.Checked;
import com.editora.PautaConformance.Conformance;
import com.editora.PautaConformance.Entry;
import com.editora.PautaConformance.Kind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Editora's Pauta conformance (pauta-project.dev, the Conformance page): the helper computes Complete, Core or
 * Partial (PAUTA-02); {@code pauta.json} agrees with the edition's {@code rules.json}, copied to
 * {@code src/test/resources/pauta-rules.json} (PAUTA-06, PAUTA-07, PAUTA-09, PAUTA-10), its {@code checked}
 * list included (PAUTA-18); and {@code pom.xml}, the README's badge and line and {@code AGENTS.md} name one
 * edition and the conformance the helper returns (ABOUT-08, PAUTA-15, PAUTA-17).
 */
class PautaConformanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Entry entry(String rule, String level, Kind kind) {
        String link = kind == Kind.NOT_APPLICABLE || kind == Kind.APP_ONLY ? null : "https://github.com/x/y/issues/1";
        return new Entry(rule, level, "https://pauta-project.dev/6.0.1/x/#" + rule.toLowerCase(), kind, "R.", link);
    }

    // ---- the helper (PAUTA-02) ----

    @Test
    void noExceptionsIsComplete() {
        assertEquals(Conformance.COMPLETE, PautaConformance.of(List.of()));
    }

    @Test
    void aPendingMustIsPartialAndAPendingShouldIsCore() {
        assertEquals(Conformance.PARTIAL, PautaConformance.of(List.of(entry("A-01", "MUST", Kind.PENDING))));
        assertEquals(Conformance.CORE, PautaConformance.of(List.of(entry("A-02", "SHOULD", Kind.PENDING))));
        assertEquals(
                Conformance.PARTIAL,
                PautaConformance.of(
                        List.of(entry("A-02", "SHOULD", Kind.PENDING), entry("A-01", "MUST", Kind.PENDING))));
    }

    @Test
    void deviationsAppOnlyAndNotApplicableEntriesAndMaysLeaveTheConformanceAlone() {
        List<Entry> none = List.of(
                entry("A-01", "MUST", Kind.NOT_APPLICABLE),
                entry("A-02", "SHOULD", Kind.NOT_APPLICABLE),
                entry("A-03", "SHOULD", Kind.DEVIATION),
                entry("A-04", "MAY", Kind.PENDING),
                entry("A-07", "MUST", Kind.APP_ONLY),
                entry("A-08", "SHOULD", Kind.APP_ONLY));
        assertEquals(Conformance.COMPLETE, PautaConformance.of(none));
        List<Entry> core = new java.util.ArrayList<>(none);
        core.add(entry("A-05", "SHOULD", Kind.PENDING));
        assertEquals(Conformance.CORE, PautaConformance.of(core));
    }

    @Test
    void theNamesAreTheWordsTheReadmeAndTheEnvironmentWrite() {
        assertEquals("Complete", Conformance.COMPLETE.english());
        assertEquals("Core", Conformance.CORE.english());
        assertEquals("partial", Conformance.PARTIAL.key());
        assertEquals("notApplicable", Kind.NOT_APPLICABLE.key());
        assertEquals("not-applicable", Kind.NOT_APPLICABLE.json());
        assertEquals("appOnly", Kind.APP_ONLY.key());
        assertEquals("app-only", Kind.APP_ONLY.json());
        assertThrows(IllegalArgumentException.class, () -> Kind.parse("waived"));
    }

    @Test
    void pendingFirstThenDeviationsThenAppOnlyThenNotApplicableMustsBeforeShouldsThenById() {
        List<Entry> sorted = PautaConformance.sorted(List.of(
                entry("Z-01", "MUST", Kind.NOT_APPLICABLE),
                entry("Y-07", "SHOULD", Kind.APP_ONLY),
                entry("Y-08", "MUST", Kind.APP_ONLY),
                entry("B-02", "SHOULD", Kind.DEVIATION),
                entry("C-03", "SHOULD", Kind.PENDING),
                entry("D-04", "MUST", Kind.PENDING),
                entry("A-05", "MUST", Kind.PENDING),
                entry("A-06", "MAY", Kind.PENDING)));
        assertEquals(
                List.of("A-05", "D-04", "C-03", "A-06", "B-02", "Y-08", "Y-07", "Z-01"),
                sorted.stream().map(Entry::rule).toList());
    }

    @Test
    void countsAndTheEnvironmentLine() {
        List<Entry> list = List.of(
                entry("A-01", "MUST", Kind.PENDING),
                entry("A-02", "SHOULD", Kind.PENDING),
                entry("A-03", "SHOULD", Kind.DEVIATION));
        assertEquals(Map.of(Kind.PENDING, 2, Kind.DEVIATION, 1), PautaConformance.counts(list));
        assertEquals(1, PautaConformance.pending(list, "MUST"));
        assertEquals(1, PautaConformance.pending(list, "SHOULD"));
        assertEquals("Pauta conformance: Partial (3 exceptions)", PautaConformance.environmentLine(list));
        assertEquals(
                "Pauta conformance: Core (1 exception)",
                PautaConformance.environmentLine(List.of(entry("A-02", "SHOULD", Kind.PENDING))));
        assertEquals("Pauta conformance: Complete (no exceptions)", PautaConformance.environmentLine(List.of()));
    }

    @Test
    void issueAndDecisionNumbers() {
        assertEquals("#212", PautaConformance.issueNumber("https://github.com/a/b/issues/212"));
        assertNull(PautaConformance.issueNumber("https://github.com/a/b/blob/master/docs/decisions/0007-x.md"));
        assertEquals(
                "0007", PautaConformance.decisionNumber("https://github.com/a/b/blob/master/docs/decisions/0007-x.md"));
        assertNull(PautaConformance.decisionNumber("https://github.com/a/b/issues/212"));
        assertNull(PautaConformance.decisionNumber("https://github.com/a/b/blob/master/docs/decisions/README.md"));
    }

    @Test
    void parsesEveryField() throws Exception {
        JsonNode root = JSON.readTree("""
                {"exceptions": [
                  {"rule": "A-01", "level": "MUST", "url": "u", "kind": "pending", "reason": "R.", "link": "l"},
                  {"rule": "A-02", "level": "SHOULD", "url": "v", "kind": "not-applicable", "reason": "S."}
                ], "checked": [
                  {"rule": "SET-02", "edition": "6.0.1", "date": "2026-11-11"},
                  {"rule": "ACC-07", "edition": "6.0.1", "date": "2026-11-11", "version": "0.28.0"}
                ]}""");
        List<Entry> parsed = PautaConformance.parse(root);
        assertEquals(new Entry("A-01", "MUST", "u", Kind.PENDING, "R.", "l"), parsed.get(0));
        assertNull(parsed.get(1).link());
        assertEquals(Kind.NOT_APPLICABLE, parsed.get(1).kind());
        assertEquals(
                List.of(
                        new Checked("SET-02", "6.0.1", "2026-11-11", null),
                        new Checked("ACC-07", "6.0.1", "2026-11-11", "0.28.0")),
                PautaConformance.parseChecked(root));
        assertTrue(PautaConformance.parse(JSON.readTree("{}")).isEmpty());
        assertTrue(PautaConformance.parseChecked(JSON.readTree("{}")).isEmpty());
    }

    @Test
    void editionsCompareByNumberNotByText() {
        assertTrue(compare("4.10.0", "4.9.0") > 0);
        assertTrue(compare("5.0.0", "6.0.0") < 0);
        assertEquals(0, compare("6.0.1", "6.0.1"));
    }

    // ---- pauta.json against the edition (PAUTA-09, PAUTA-10) ----

    private static String read(String file) throws Exception {
        // A Windows checkout may end lines in \r\n.
        return Files.readString(Path.of(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String pomEdition() throws Exception {
        Matcher m =
                Pattern.compile("<pauta\\.version>([^<]+)</pauta\\.version>").matcher(read("pom.xml"));
        assertTrue(m.find(), "pom.xml defines pauta.version");
        return m.group(1).strip();
    }

    private static JsonNode edition() throws Exception {
        return JSON.readTree(Path.of("src/test/resources/pauta-rules.json").toFile());
    }

    private static JsonNode file() throws Exception {
        return JSON.readTree(
                Path.of("src/main/resources/com/editora/pauta.json").toFile());
    }

    @Test
    void theRulesCopyIsTheEditionThePomNames() throws Exception {
        assertEquals(pomEdition(), edition().path("edition").asText(), "replace pauta-rules.json with this edition's");
        assertTrue(edition().path("rules").size() > 100);
    }

    @Test
    void appInfoReadsTheEditionFromThePom() throws Exception {
        assertEquals(pomEdition(), AppInfo.PAUTA_EDITION);
        assertEquals("https://pauta-project.dev/" + pomEdition() + "/", AppInfo.pautaUrl());
        assertFalse(AppInfo.pautaUrl().contains("latest"));
        assertEquals(AppInfo.docsUrl() + "/pauta/", AppInfo.pautaDocsUrl());
    }

    @Test
    void theBundledFileIsTheOneTheAppReads() throws Exception {
        assertEquals(PautaConformance.parse(file()), PautaConformance.exceptions());
        assertEquals(PautaConformance.parseChecked(file()), PautaConformance.checked());
    }

    @Test
    void everyEntryNamesARuleOfTheEditionWithItsLevelAndAddress() throws Exception {
        Map<String, JsonNode> rules = new HashMap<>();
        for (JsonNode rule : edition().path("rules")) {
            rules.put(rule.path("id").asText(), rule);
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode node : file().path("exceptions")) {
            String id = node.path("rule").asText();
            for (String field : List.of("rule", "level", "url", "kind", "reason")) {
                assertFalse(node.path(field).asText().isBlank(), id + " has no " + field);
            }
            JsonNode rule = rules.get(id);
            assertNotNull(rule, id + " is not a rule of Pauta " + pomEdition());
            assertTrue(seen.add(id), id + " appears twice");
            assertEquals(rule.path("level").asText(), node.path("level").asText(), id + "'s level");
            assertEquals(rule.path("url").asText(), node.path("url").asText(), id + "'s url");
            assertNotEquals("MAY", node.path("level").asText(), id + ": a MAY needs no exception");
        }
    }

    @Test
    void eachKindHasWhatItNeeds() throws Exception {
        String repo = Pattern.quote(AppInfo.GITHUB_REPO);
        // An issue of this repository, never number 0: a placeholder must not pass.
        Pattern issue = Pattern.compile("https://github\\.com/" + repo + "/issues/[1-9]\\d*");
        Pattern decision = Pattern.compile("https://github\\.com/" + repo + "/blob/master/(docs/decisions/[^/]+\\.md)");
        for (Entry e : PautaConformance.parse(file())) {
            String link = e.link();
            switch (e.kind()) {
                case NOT_APPLICABLE -> assertNull(link, e.rule() + ": a not-applicable entry has no link");
                // PAUTA-16: only a template repository records app-only entries, and Editora was not made
                // from one, so every build fails one, the release build included.
                case APP_ONLY -> throw new AssertionError(e.rule() + ": app-only is a template repository's alone");
                case PENDING -> {
                    assertNotNull(link, e.rule() + ": a pending entry links its issue (PAUTA-07)");
                    assertTrue(issue.matcher(link).matches(), e.rule() + ": not an issue in this repository: " + link);
                }
                case DEVIATION -> {
                    assertEquals("SHOULD", e.level(), e.rule() + ": only a SHOULD can be a deviation (PAUTA-06)");
                    assertNotNull(link, e.rule() + ": a deviation links its decision record");
                    Matcher m = decision.matcher(link);
                    assertTrue(m.matches(), e.rule() + ": not a decision record: " + link);
                    Path record = Path.of(m.group(1));
                    assertTrue(Files.exists(record), record + " is missing");
                    assertTrue(read(record.toString()).contains(e.rule()), record + " does not name " + e.rule());
                }
            }
            // PAUTA-08: one plain sentence for the person using the app, without rule IDs.
            assertTrue(e.reason().endsWith("."), e.rule() + ": the reason is a sentence");
            assertFalse(e.reason().matches(".*\\b[A-Z]{2,6}-\\d{2,3}\\b.*"), e.rule() + ": the reason names a rule");
        }
    }

    // ---- the checked list (PAUTA-18) ----

    @Test
    void eachCheckedEntryIsWellFormedAndCurrent() throws Exception {
        Map<String, JsonNode> rules = new HashMap<>();
        for (JsonNode rule : edition().path("rules")) {
            rules.put(rule.path("id").asText(), rule);
        }
        Set<String> excepted = new HashSet<>();
        for (Entry e : PautaConformance.parse(file())) {
            excepted.add(e.rule());
        }
        String pom = pomEdition();
        Set<String> seen = new HashSet<>();
        for (JsonNode node : file().path("checked")) {
            String id = node.path("rule").asText();
            for (String field : List.of("rule", "edition", "date")) {
                assertFalse(node.path(field).asText().isBlank(), "checked " + id + " has no " + field);
            }
            assertTrue(node.path("date").asText().matches("\\d{4}-\\d{2}-\\d{2}"), "checked " + id + "'s date");
            JsonNode rule = rules.get(id);
            assertNotNull(rule, "checked " + id + " is not a rule of Pauta " + pom);
            assertEquals(
                    "by hand",
                    rule.path("check").path("kind").asText(),
                    "checked " + id + ": only a rule checked by hand is recorded");
            assertTrue(seen.add(id), "checked " + id + " appears twice");
            assertFalse(excepted.contains(id), id + " is both checked and an exception");
            String edition = node.path("edition").asText();
            assertTrue(compare(edition, pom) <= 0, "checked " + id + ": " + edition + " is newer than " + pom);
            String changed = rule.path("changed").asText(pom);
            assertTrue(
                    compare(edition, changed) >= 0,
                    "checked " + id + " is stale: checked against " + edition + ", the rule changed in " + changed
                            + "; remove it and check the rule again");
        }
    }

    /** Compares two editions number by number: 4.10.0 is newer than 4.9.0. */
    private static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }

    // ---- the README (PAUTA-15) and AGENTS.md (PAUTA-17) ----

    @Test
    void theReadmeNamesTheEditionAndTheConformanceAndLinksTheDocsPage() throws Exception {
        String edition = pomEdition();
        Conformance conformance = PautaConformance.conformance();
        String readme = read("README.md");
        // The unversioned page: it always shows the latest release.
        String docs = AppInfo.HOMEPAGE + "/docs/pauta/";

        Matcher badge = Pattern.compile(
                        "\\[!\\[Pauta]\\(https://img\\.shields\\.io/badge/Pauta-([0-9.]+)%20%C2%B7%20(\\w+)-(\\w+)\\)]\\(([^)]+)\\)")
                .matcher(readme);
        assertTrue(badge.find(), "README has the Pauta badge");
        assertEquals(edition, badge.group(1), "the badge's edition");
        assertEquals(conformance.english(), badge.group(2), "the badge's conformance");
        // Green for Complete, blue for Core, amber for Partial (Pauta's README badges).
        String color = switch (conformance) {
            case COMPLETE -> "2ea043";
            case CORE -> "5b8def";
            case PARTIAL -> "d29922";
        };
        assertEquals(color, badge.group(3), "the badge's color");
        assertEquals(docs, badge.group(4), "the badge links the conformance page");

        String opening = conformance == Conformance.PARTIAL ? "Working toward" : "Built to";
        String other = conformance == Conformance.PARTIAL ? "Built to [Pauta" : "Working toward [Pauta";
        String line = opening + " [Pauta (the adl desktop apps guidelines)](https://pauta-project.dev/), edition ["
                + edition + "](https://pauta-project.dev/" + edition + "/), [Pauta conformance: "
                + conformance.english() + "](" + docs + ").";
        assertTrue(readme.contains(line), "README's line: " + line);
        assertFalse(readme.contains(other), "README still says " + other);
        Matcher named = Pattern.compile("Pauta[- ](\\d+\\.\\d+\\.\\d+)").matcher(readme);
        while (named.find()) {
            assertEquals(edition, named.group(1), "README names another edition");
        }
    }

    /** PAUTA-17: the coding agent reads the edition's own rules at its numbered address, never latest/. */
    @Test
    void agentsHasAPautaSectionThatLinksTheEditionsRules() throws Exception {
        String edition = pomEdition();
        String agents = read("AGENTS.md");
        int start = agents.indexOf("\n## Pauta\n");
        assertTrue(start >= 0, "AGENTS.md has a Pauta section");
        int end = agents.indexOf("\n## ", start + 1);
        String section = agents.substring(start, end < 0 ? agents.length() : end);
        assertTrue(section.contains("Pauta " + edition), "the section names Pauta " + edition);
        String base = "https://pauta-project.dev/" + edition + "/";
        assertTrue(section.contains(base + "llms.txt"), "AGENTS.md links " + base + "llms.txt");
        assertTrue(section.contains(base + "llms-full.txt"), "AGENTS.md links " + base + "llms-full.txt");
        assertFalse(agents.contains("pauta-project.dev/latest/"), "AGENTS.md links latest/");
        Matcher other =
                Pattern.compile("pauta-project\\.dev/(\\d+\\.\\d+\\.\\d+)/").matcher(agents);
        while (other.find()) {
            assertEquals(edition, other.group(1), "AGENTS.md links another edition");
        }
        // "Built to" is for an app that meets every MUST; at Partial the section says "working toward".
        boolean partial = PautaConformance.conformance() == Conformance.PARTIAL;
        assertEquals(partial, section.contains("is working toward Pauta " + edition), "working toward, at Partial");
        assertEquals(!partial, section.contains("is built to Pauta " + edition), "built to, at Core or Complete");
    }
}
