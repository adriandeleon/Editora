package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Colour rules of Pauta 6.2.0 that a change anywhere can break, checked on the repository's own text (pure: no
 * toolkit):
 *
 * <ul>
 *   <li>ICON-07: a warning, an error or a success is painted with its {@code -state-*} token, declared once on
 *       {@code .root}; no component rule names the role behind it ({@code -color-danger-fg} and the rest);
 *   <li>ICON-08: no component rule of the app's stylesheets holds a fixed colour (a hex, an {@code rgb()},
 *       {@code rgba()}, {@code hsb()} or a named colour); one stands only as a token's value on {@code .root};
 *   <li>ICON-18: a rule that uses the amber, red or green token marks one of their meanings, which its selector
 *       says;
 *   <li>ICON-14: no FX test computes a contrast ratio; that arithmetic is pure ({@link StyleContrastTest}).
 * </ul>
 *
 * The editor themes ({@code editor-themes/*.css}) and the vendored control themes are theme files, palettes of
 * their own, and are not walked here.
 */
@Tag("guard")
class StyleColourGuardTest {

    /** The app's own stylesheets, the ones whose component rules colour by token. */
    private static final List<String> SHEETS = List.of("app.css", "syntax.css");

    /**
     * Scoped blocks that set {@code -color-*} tokens, where ICON-08 lets a fixed colour stand: the Markdown preview's
     * own paper, which the user picks with the preview's sun/moon switch, independent of the app theme (a GitHub
     * light or dark page, as a document is rendered, not the window's ground).
     */
    private static final Set<String> TOKEN_BLOCKS =
            Set.of(".root", ".markdown-preview-pane.md-light", ".markdown-preview-pane.md-dark");

    /** Any fixed colour (ICON-08): a hex, an rgb()/rgba()/hsb()/hsba() value or a named colour. */
    private static final Pattern FIXED = Pattern.compile("#[0-9a-fA-F]{3,8}\\b|\\b(?:rgba?|hsba?)\\s*\\("
            + "|:\\s*(?:white|black|red|green|blue|gray|grey|yellow|orange|purple|silver|navy|gold)\\s*(?:;|$)");

    /** A role behind a state colour (ICON-07). */
    private static final Pattern STATE_ROLE = Pattern.compile("-color-(?:danger|warning|success)-[a-z]+");

    /**
     * What each state colour may mark (ICON-18, the State colors table), as the words a selector that uses it names.
     * Red is also a destructive menu row's (MENU-12 asks for {@code -color-danger-fg}, which {@code -state-red} is).
     */
    private static final Map<String, Pattern> MEANINGS = new LinkedHashMap<>();

    static {
        MEANINGS.put(
                "-state-red", // broken: errors, failures, conflicts, breakpoints, a recording; a destructive row
                Pattern.compile("error|fail|problem|danger|conflict|missing|ragged|unknown|cycle|too-long|orphaned"
                        + "|uncaught|attention|unmatched|stderr|caution|exception|breakpoint|macro-rec|bulb-marker\\.fix"
                        + "|encryption-banner|encryption-pill-red|run-tests-note|build-tasks-notice"
                        // a canvas painter's probe, read for errors only (OverlayPalette.Seeds)
                        + "|ink-probe-red"));
        MEANINGS.put(
                "-state-amber", // not durable yet, a warning, an update available in About
                Pattern.compile("warn|dirty|modified-file|narrow|readonly|view-mode|snapshot|rejected|partial|skip"
                        + "|client-error|cancelled|truncated|deprecated|guide-long|amend|operation-banner|branch-gone"
                        + "|literal|unused|mutual|preview-missing|needs-push|bell|lost|restart|untrusted|about-update"
                        + "|version-major|encoding-assumed|pr-review-notice|body-note|forced|encryption-pill-amber"
                        + "|doctor-remote-note|ink-probe-amber"));
        MEANINGS.put(
                "-state-green", // verified: found, added, passing, running
                Pattern.compile("added|adds|inserted|hunk-new|diff-right|success|-ok\\b|problem-counter-ok|passed"
                        + "|pass\\b|pass:|found|uptodate|complete|clear|trusted|copied|chosen|run-marker|debug-start"
                        + "|icon-run|run-config-play|status-mcp|encryption-pill-green|ink-probe-green"));
    }

    private record Rule(String sheet, String selectors, String body) {
        List<String> each() {
            List<String> out = new ArrayList<>();
            for (String s : selectors.split(",")) {
                out.add(s.strip().replaceAll("\\s+", " "));
            }
            return out;
        }
    }

    private static List<Rule> rules() throws Exception {
        List<Rule> out = new ArrayList<>();
        for (String sheet : SHEETS) {
            Matcher m = Pattern.compile("([^{}]+)\\{([^{}]*)\\}").matcher(StyleContrastTest.read(sheet));
            while (m.find()) {
                out.add(new Rule(sheet, m.group(1).strip().replaceAll("\\s+", " "), m.group(2)));
            }
        }
        return out;
    }

    @Test
    void noComponentRuleHoldsAFixedColour() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Rule rule : rules()) {
            if (TOKEN_BLOCKS.contains(rule.selectors())) {
                continue;
            }
            for (String declaration : rule.body().split(";")) {
                if (FIXED.matcher(declaration.strip()).find()) {
                    problems.add(rule.sheet() + " " + rule.selectors() + " { " + declaration.strip() + " }");
                }
            }
        }
        assertTrue(
                problems.isEmpty(),
                "a fixed colour outside a .root token (ICON-08): name a token, or define one on .root:\n"
                        + String.join("\n", problems));
    }

    @Test
    void stateColoursComeFromTheirTokensDeclaredOnceOnTheRoot() throws Exception {
        List<String> problems = new ArrayList<>();
        Map<String, Integer> declared = new LinkedHashMap<>();
        Pattern token = Pattern.compile("(-state-(?:red|amber|green)(?:-[a-z]+)?)\\s*:\\s*([^;]+);");
        for (Rule rule : rules()) {
            Matcher m = token.matcher(rule.body());
            while (m.find()) {
                declared.merge(m.group(1), 1, Integer::sum);
                if (!rule.selectors().equals(".root")) {
                    problems.add(rule.selectors() + " declares " + m.group(1) + " outside .root");
                }
                if (!m.group(2).strip().matches("-color-(?:danger|warning|success)-[a-z]+")) {
                    problems.add(m.group(1) + " chains to " + m.group(2).strip() + ", not a role");
                }
            }
            if (!TOKEN_BLOCKS.contains(rule.selectors())) {
                Matcher role = STATE_ROLE.matcher(rule.body());
                if (role.find()) {
                    problems.add(rule.sheet() + " " + rule.selectors() + " names " + role.group()
                            + ": use its -state-* token (ICON-07)");
                }
            }
        }
        for (String state : List.of("-state-red", "-state-amber", "-state-green")) {
            for (String variant : List.of("", "-bg", "-hover", "-emphasis")) {
                assertEquals(1, declared.getOrDefault(state + variant, 0), state + variant + " is declared once");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void amberRedAndGreenMarkOnlyTheirMeanings() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Rule rule : rules()) {
            if (TOKEN_BLOCKS.contains(rule.selectors())) {
                continue;
            }
            for (Map.Entry<String, Pattern> meaning : MEANINGS.entrySet()) {
                if (!Pattern.compile(Pattern.quote(meaning.getKey()) + "\\b(?!-)|" + Pattern.quote(meaning.getKey())
                                + "-(?:bg|hover|emphasis)\\b")
                        .matcher(rule.body())
                        .find()) {
                    continue;
                }
                for (String selector : rule.each()) {
                    if (!meaning.getValue().matcher(selector).find()) {
                        problems.add(rule.sheet() + " " + selector + " uses " + meaning.getKey());
                    }
                }
            }
        }
        assertTrue(
                problems.isEmpty(),
                "amber, red and green mean a warning or \"not durable yet\", broken, and verified (ICON-18); a"
                        + " bookmark, a note, Stop or a deletion takes the accent or the ink. If the selector does"
                        + " mark that meaning, name it in the selector:\n" + String.join("\n", problems));
    }

    /** Main-code inline styles with a fixed colour; the printed page is paper, white whatever the theme. */
    private static final Set<String> INLINE_FIXED_ALLOWED = Set.of("com/editora/print/MarkdownPrintLayout.java");

    @Test
    void inlineStylesNameTokensNotFixedColours() throws IOException {
        Path root = Path.of("src/main/java");
        assertTrue(Files.isDirectory(root), "Run from the Maven project root");
        Pattern inline = Pattern.compile("setStyle\\(\\s*\"([^\"]*)\"");
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file :
                    files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String name = root.relativize(file).toString().replace('\\', '/');
                Matcher m = inline.matcher(Files.readString(file));
                while (m.find()) {
                    boolean fixed = FIXED.matcher(m.group(1)).find()
                            || Pattern.compile(":\\s*(?:white|black)\\b")
                                    .matcher(m.group(1))
                                    .find();
                    if ((fixed && !INLINE_FIXED_ALLOWED.contains(name))
                            || STATE_ROLE.matcher(m.group(1)).find()) {
                        problems.add(name + ": " + m.group(1));
                    }
                }
            }
        }
        assertTrue(
                problems.isEmpty(),
                "an inline style with a fixed colour or a state's role (ICON-07, ICON-08): use a style class:\n"
                        + String.join("\n", problems));
    }

    /**
     * A fixed colour in main code: a {@code Color.web}, {@code rgb}, {@code color} or {@code hsb} whose first argument is
     * a literal, or a named hue ({@code Color.RED}). {@code Color.gray(…)}, {@code WHITE}, {@code BLACK} and
     * {@code TRANSPARENT} are not hues: the neutral fallback and the ink chosen for contrast.
     */
    private static final Pattern COLOUR_LITERAL =
            Pattern.compile("Color\\.(?:web|rgb|color|hsb)\\(\\s*(?:\"|[0-9.]+\\s*,)"
                    + "|Color\\.(?:RED|GREEN|BLUE|YELLOW|ORANGE|GOLD|PURPLE|MAGENTA|CYAN|PINK|NAVY|ORANGERED|CRIMSON|LIME"
                    + "|DARK[A-Z]+|LIGHT[A-Z]+)\\b");

    /**
     * Main code allowed a colour literal, with why: data that is a palette of its own, not the app's chrome.
     */
    private static final Map<String, String> COLOUR_LITERALS_ALLOWED = Map.of(
            "com/editora/terminal/TerminalPalettes.java",
            "the terminal's built-in schemes: the colours a program prints, theme data",
            "com/editora/ui/UserThemes.java",
            "the ground and ink a user's theme file falls back to when it sets neither: theme data");

    /**
     * ICON-08: a canvas painter (an overlay, a stripe, the minimap, the Git log's lanes, the Map) takes its colours from
     * the theme through probes ({@code editor/ThemeProbes}, the Map's own), never a literal.
     */
    @Test
    void canvasPaintersTakeTheirColoursFromTheTheme() throws IOException {
        Path root = Path.of("src/main/java");
        assertTrue(Files.isDirectory(root), "Run from the Maven project root");
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file :
                    files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String name = root.relativize(file).toString().replace('\\', '/');
                if (COLOUR_LITERALS_ALLOWED.containsKey(name)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (COLOUR_LITERAL.matcher(lines.get(i)).find()) {
                        problems.add(name + ":" + (i + 1) + ": " + lines.get(i).strip());
                    }
                }
            }
        }
        assertTrue(
                problems.isEmpty(),
                "a fixed colour in main code (ICON-08): read it from the theme (ThemeProbes and an .ink-probe-* rule"
                        + " in app.css), or fall back to Color.gray(…):\n" + String.join("\n", problems));
    }

    /** What computes a contrast ratio in a test. */
    private static final Pattern CONTRAST = Pattern.compile("\\bcontrast(?:Ratio)?\\(|\\bluminance\\(");

    @Test
    void noFxTestComputesAContrastRatio() throws IOException {
        Path root = Path.of("src/test/java");
        assertTrue(Files.isDirectory(root), "Run from the Maven project root");
        Pattern fx = Pattern.compile("@Tag\\(\\s*\"fx\"\\s*\\)");
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file :
                    files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String source = Files.readString(file);
                if (fx.matcher(source).find() && CONTRAST.matcher(source).find()) {
                    problems.add(root.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        assertTrue(
                problems.isEmpty(),
                "a contrast ratio in an FX test (ICON-14): compute it in StyleContrastTest, from the stylesheets'"
                        + " text, and have the FX test check only which colour the node took:\n"
                        + String.join("\n", problems));
    }
}
