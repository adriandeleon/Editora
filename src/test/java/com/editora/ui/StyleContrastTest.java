package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.scene.paint.Color;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrast and colour-token rules for the first-party style sheets (pure: reads the CSS text and does
 * the WCAG arithmetic; {@code Color.web} needs no toolkit). These pin review findings that are invisible
 * in a code diff: a readable legend set in the ~3:1 "subtle" role, white ink on a light emphasis fill,
 * a fixed hex that only suits one theme.
 */
class StyleContrastTest {

    private static final String STYLES = "/com/editora/styles/";

    /**
     * The first-party pairs, each named for its control theme and its editor theme alike: Caret &amp; Ink, and the
     * high-contrast pair built on it (ACC-09), which every flagship check holds too.
     */
    static final List<String> FLAGSHIP =
            List.of("editora-light", "editora-dark", "high-contrast-light", "high-contrast-dark");

    static String read(String path) throws Exception {
        try (var in = StyleContrastTest.class.getResourceAsStream(STYLES + path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "");
        }
    }

    /** {@code -color-*} tokens of a control theme's {@code .root} palette, aliases resolved. */
    static Map<String, String> palette(String base) throws Exception {
        String css = read("atlantafx-themes/" + base + ".css");
        int start = css.indexOf(".root {");
        String block = css.substring(start, css.indexOf("\n}", start));
        Map<String, String> tokens = new HashMap<>();
        Matcher m = Pattern.compile("(-color-[a-z0-9-]+)\\s*:\\s*([^;]+);").matcher(block);
        while (m.find()) {
            tokens.put(m.group(1), m.group(2).trim());
        }
        return tokens;
    }

    static Color color(Map<String, String> palette, String token) {
        String value = palette.get(token);
        while (palette.containsKey(value)) {
            value = palette.get(value);
        }
        return Color.web(value);
    }

    private static double luminance(Color c) {
        return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
    }

    private static double channel(double v) {
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** Declarations of every rule whose selector list contains {@code selector} exactly. */
    static List<String> rules(String css, String selector) {
        List<String> bodies = new ArrayList<>();
        Matcher m = Pattern.compile("([^{}]+)\\{([^{}]*)\\}").matcher(css);
        while (m.find()) {
            for (String s : m.group(1).split(",")) {
                if (s.strip().replaceAll("\\s+", " ").equals(selector)) {
                    bodies.add(m.group(2));
                }
            }
        }
        return bodies;
    }

    @Test
    void lightTintedTextReachesAaOnItsOwnGroundAndOnTheSubtleSurface() throws Exception {
        Map<String, String> light = palette("editora-light");
        for (String role : List.of("accent", "warning", "success", "danger")) {
            Color fg = color(light, "-color-" + role + "-fg");
            for (String ground : List.of("-color-" + role + "-subtle", "-color-bg-subtle", "-color-bg-default")) {
                double ratio = contrast(fg, color(light, ground));
                assertTrue(ratio >= 4.5, role + "-fg on " + ground + " is " + ratio);
            }
        }
    }

    @Test
    void darkTintedTextStaysAa() throws Exception {
        Map<String, String> dark = palette("editora-dark");
        for (String role : List.of("accent", "warning", "success", "danger")) {
            Color fg = color(dark, "-color-" + role + "-fg");
            for (String ground : List.of("-color-" + role + "-subtle", "-color-bg-subtle", "-color-bg-default")) {
                assertTrue(contrast(fg, color(dark, ground)) >= 4.5, role + "-fg on " + ground);
            }
        }
    }

    @Test
    void inkOnAnEmphasisFillIsTheThemesOnEmphasisColour() throws Exception {
        // White on the accent is 1.68:1 in Editora Dark; -color-fg-emphasis is the token that tracks it.
        for (String base : FLAGSHIP) {
            Map<String, String> p = palette(base);
            for (String role : List.of("accent", "success", "warning", "danger")) {
                double ratio = contrast(color(p, "-color-fg-emphasis"), color(p, "-color-" + role + "-emphasis"));
                assertTrue(ratio >= 4.5, base + ": fg-emphasis on " + role + "-emphasis is " + ratio);
            }
            assertTrue(contrast(color(p, "-color-light"), color(p, "-color-neutral-emphasis")) >= 4.5, base);
        }
        String app = read("app.css");
        for (String selector :
                List.of(".markwhen-cal-chip", ".openapi-method", ".project-map-deps-cell.project-map-deps-weight-4")) {
            String body = String.join(";", rules(app, selector));
            assertTrue(body.contains("-fx-text-fill: -color-fg-emphasis"), selector + ": " + body);
            assertFalse(body.contains("white"), selector);
        }
        // In About, only an available update is amber: its links stay accent.
        assertTrue(String.join(";", rules(app, ".about-update")).contains("-fx-text-fill: -state-amber"));
        // The Project Map's heaviest dependency cells once named this token, which no theme defines, so their
        // ink fell back to whatever the cell inherited.
        assertFalse(app.contains("-color-fg-on-emphasis"), "-color-fg-on-emphasis is not a token of any theme");
    }

    @Test
    void theFocusRingIsAtLeastThreeToOneOnEverySurface() throws Exception {
        String app = read("app.css");
        assertTrue(String.join(";", rules(app, ".root")).contains("-focus-ring: -color-accent-emphasis"));
        for (String base : FLAGSHIP) {
            Map<String, String> p = palette(base);
            for (String surface :
                    List.of("-color-bg-default", "-color-bg-subtle", "-color-bg-inset", "-color-bg-overlay")) {
                double ratio = contrast(color(p, "-color-accent-emphasis"), color(p, surface));
                assertTrue(ratio >= 3.0, base + ": ring on " + surface + " is " + ratio);
            }
        }
    }

    /**
     * The only selectors whose text may be the ~3:1 subtle ink (ICON-09, Pauta 6.2.0): decorative text a person
     * does not read. The test walks every rule of the sheets that colour by role and fails any other that sets a
     * text fill to it, so a readable selector it does not name cannot slip through.
     */
    private static final java.util.Set<String> SUBTLE_TEXT = java.util.Set.of(
            ".status-bar .status-format-sep", // the dot between the status bar's format segments
            ".text.csv-delimiter", // a CSV file's separators: they mark the columns, the fields are what is read
            ".editor-area .fold-chevron"); // the adaptive theme's fold chevron, a glyph

    @Test
    void textThatMustBeReadUsesTheMutedRoleNotSubtle() throws Exception {
        Pattern subtle = Pattern.compile("-fx-(text-)?fill\\s*:\\s*-color-fg-subtle\\b");
        List<String> problems = new ArrayList<>();
        for (String sheet : List.of("app.css", "syntax.css", "editor-themes/adaptive.css")) {
            Matcher rule = Pattern.compile("([^{}]+)\\{([^{}]*)\\}").matcher(read(sheet));
            while (rule.find()) {
                if (!subtle.matcher(rule.group(2)).find()) {
                    continue;
                }
                for (String selector : rule.group(1).split(",")) {
                    String s = selector.strip().replaceAll("\\s+", " ");
                    if (!SUBTLE_TEXT.contains(s)) {
                        problems.add(sheet + ": " + s);
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), "readable text in the ~3:1 subtle role:\n" + String.join("\n", problems));
        String app = read("app.css");
        for (String selector : List.of(
                ".command-palette .palette-hint",
                ".switcher-hint",
                ".shortcut-unbound",
                ".notes-tree .note-line-number",
                ".structure-tree .structure-line-number",
                ".editor-area .fold-gutter .blame-date")) {
            List<String> bodies = rules(app, selector);
            assertFalse(bodies.isEmpty(), "no rule for " + selector);
            for (String body : bodies) {
                assertFalse(body.contains("-color-fg-subtle"), selector + " is set in the ~3:1 subtle role");
            }
        }
        for (String base : FLAGSHIP) {
            Map<String, String> p = palette(base);
            for (String surface : List.of("-color-bg-default", "-color-bg-subtle", "-color-bg-overlay")) {
                assertTrue(contrast(color(p, "-color-fg-muted"), color(p, surface)) >= 4.5, base + " " + surface);
            }
        }
    }

    @Test
    void severityAndDebugColoursComeFromTheStateTokens() throws Exception {
        String app = read("app.css");
        Map<String, String> expected = Map.of(
                ".problems-panel .problem-error", "-state-red",
                ".problems-panel .problem-warning", "-state-amber",
                ".problems-panel .problem-info", "-state-accent",
                ".debug-panel .debug-start", "-state-green",
                ".run-marker", "-state-green",
                ".breakpoint-marker", "-state-red-emphasis",
                ".dev-mode-badge", "-color-accent-fg");
        Pattern hex = Pattern.compile("#[0-9a-fA-F]{3,8}\\b");
        expected.forEach((selector, token) -> {
            String body = String.join(";", rules(app, selector));
            assertTrue(body.contains(token), selector + " should use " + token + ": " + body);
            assertFalse(hex.matcher(body).find(), selector + " still has a fixed colour: " + body);
        });
    }

    @Test
    void flagshipLineNumbersReachAa() throws Exception {
        for (String base : FLAGSHIP) {
            String css = read("editor-themes/" + base + ".css");
            String body = rules(css, ".editor-area .lineno").get(0);
            Matcher bg =
                    Pattern.compile("-fx-background-color:\\s*(#[0-9a-fA-F]+)").matcher(body);
            Matcher fg = Pattern.compile("-fx-text-fill:\\s*(#[0-9a-fA-F]+)").matcher(body);
            assertTrue(bg.find() && fg.find(), base);
            double ratio = contrast(Color.web(fg.group(1)), Color.web(bg.group(1)));
            assertTrue(ratio >= 4.5, base + " line numbers are " + ratio);
        }
    }

    @Test
    void stripeLineIconsFollowTheStripesOwnStates() throws Exception {
        String app = read("app.css");
        // Stripe buttons are never :selected — they carry the custom :open / :tw-active pseudo-classes.
        assertTrue(rules(app, ".tool-stripe-button:selected .icon-line").isEmpty());
        assertEquals(1, rules(app, ".tool-stripe-button:open .icon-line").size());
        assertEquals(1, rules(app, ".tool-stripe-button:tw-active .icon-line").size());
        assertTrue(rules(app, ".tool-stripe-button:open .icon-line").get(0).contains("-color-accent-fg"));
    }

    @Test
    void comboStatesErasedByTheBaseRuleAreRestated() throws Exception {
        String app = read("app.css");
        int base = app.indexOf(".combo-box-base, .choice-box {");
        assertTrue(base >= 0);
        for (String state : List.of(".combo-box-base:focused", ".combo-box-base:success", ".combo-box-base:danger")) {
            List<String> bodies = rules(app, state);
            assertEquals(1, bodies.size(), state);
            assertTrue(bodies.get(0).contains("-fx-background-color"), state);
            assertTrue(app.indexOf(state) > base, state + " must follow the base rule");
        }
        assertTrue(rules(app, ".combo-box-base:danger").get(0).contains("-state-red-emphasis"));
        assertTrue(rules(app, ".settings-search:focused").get(0).contains("-color-input-border-focused"));
    }

    @Test
    void anOffSwitchStandsOutFromItsCardInEveryEditoraTheme() throws Exception {
        String app = read("app.css");
        assertTrue(rules(app, ".toggle-switch > .thumb").get(0).contains("-color-fg-muted"), "the off knob");
        assertTrue(rules(app, ".toggle-switch > .thumb-area").get(0).contains("-color-fg-muted"), "the track outline");
        for (String state : List.of(".toggle-switch:selected > .thumb", ".toggle-switch:selected > .thumb-area")) {
            assertTrue(rules(app, state).get(0).contains("-color-accent-emphasis"), state + " is restated");
        }
        for (String base : FLAGSHIP) {
            Map<String, String> p = palette(base);
            for (String card : List.of("-color-bg-default", "-color-bg-subtle")) {
                assertTrue(contrast(color(p, "-color-fg-muted"), color(p, card)) >= 3, base + " " + card);
            }
        }
    }

    /** The {@code .root} palette of an AtlantaFX built-in theme, read from the library. */
    static Map<String, String> builtInPalette(String base) throws Exception {
        try (var in = StyleContrastTest.class.getResourceAsStream("/atlantafx/base/theme/" + base + ".css")) {
            String css = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "");
            int start = css.indexOf(".root {");
            String block = css.substring(start, css.indexOf("\n}", start));
            Map<String, String> tokens = new HashMap<>();
            Matcher m = Pattern.compile("(-color-[a-z0-9-]+)\\s*:\\s*([^;]+);").matcher(block);
            while (m.find()) {
                tokens.put(m.group(1), m.group(2).trim());
            }
            return tokens;
        }
    }

    /** JavaFX's ladder(): the stop colours, interpolated at the reference colour's brightness (.3r+.59g+.11b). */
    static Color ladder(Color ref, List<double[]> offsets, List<Color> colors) {
        double b = 0.3 * ref.getRed() + 0.59 * ref.getGreen() + 0.11 * ref.getBlue();
        if (b <= offsets.get(0)[0]) {
            return colors.get(0);
        }
        for (int i = 1; i < offsets.size(); i++) {
            double o1 = offsets.get(i - 1)[0];
            double o2 = offsets.get(i)[0];
            if (b <= o2) {
                return colors.get(i - 1).interpolate(colors.get(i), (b - o1) / (o2 - o1));
            }
        }
        return colors.get(colors.size() - 1);
    }

    @Test
    void theKeyboardPeriwinkleReadsOnEveryBundledAppTheme() throws Exception {
        String app = read("app.css");
        Matcher decl = Pattern.compile("-state-key:\\s*ladder\\(-color-bg-default,([^;]+)\\);")
                .matcher(app);
        assertTrue(decl.find(), "-state-key is a ladder of the app theme's ground");
        List<double[]> offsets = new ArrayList<>();
        List<Color> colors = new ArrayList<>();
        Matcher stop = Pattern.compile("(#[0-9a-fA-F]{6})\\s+([0-9.]+)%").matcher(decl.group(1));
        while (stop.find()) {
            colors.add(Color.web(stop.group(1)));
            offsets.add(new double[] {Double.parseDouble(stop.group(2)) / 100});
        }
        assertTrue(colors.size() >= 2);
        for (String editorTheme : FLAGSHIP) {
            assertFalse(
                    read("editor-themes/" + editorTheme + ".css").contains("-state-key"), "set by the app theme only");
        }
        List<String> problems = new ArrayList<>();
        for (String name : Themes.NAMES) {
            String[] bundled = Themes.BUNDLED.get(name);
            Map<String, String> p = bundled != null
                    ? palette(bundled[0])
                    : builtInPalette(name.toLowerCase(java.util.Locale.ROOT).replace(' ', '-'));
            for (String surface : List.of("-color-bg-default", "-color-bg-subtle")) {
                Color ground = color(p, surface);
                Color ink = ladder(color(p, "-color-bg-default"), offsets, colors);
                Color chip = ground.interpolate(ink, 0.15); // the chip's tint over that surface
                double worst = Math.min(contrast(ink, ground), contrast(ink, chip));
                if (worst < 4.5) {
                    problems.add(name + " " + surface + ": " + String.format("%.2f", worst));
                }
            }
        }
        assertTrue(problems.isEmpty(), "chord chips under 4.5:1:\n" + String.join("\n", problems));
    }

    @Test
    void theModifiedMarkerStandsOutFromItsCard() throws Exception {
        String app = read("app.css");
        assertTrue(rules(app, ".settings-row:modified").get(0).contains("-color-accent-emphasis"));
        for (String base : FLAGSHIP) {
            Map<String, String> p = palette(base);
            assertTrue(contrast(color(p, "-color-accent-emphasis"), color(p, "-color-bg-default")) >= 3, base);
        }
    }

    /**
     * Settings ▸ Sync's status pill — green Connected, amber Not connected, red Sync failing — reads on its tint
     * (4.5:1) and stands out from the card (3:1) in Editora's own light and dark themes.
     */
    @Test
    void theSyncStatusPillsReadInLightAndDark() throws Exception {
        String app = read("app.css");
        Map<String, String[]> pills = new LinkedHashMap<>();
        pills.put(".settings-git-found", new String[] {"-state-green", "-color-success-fg", "-color-success-subtle"});
        pills.put(".settings-git-warn", new String[] {"-state-amber", "-color-warning-fg", "-color-warning-subtle"});
        pills.put(".settings-git-missing", new String[] {"-state-red", "-color-danger-fg", "-color-danger-subtle"});
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String[]> pill : pills.entrySet()) {
            String rule = rules(app, pill.getKey()).get(0);
            assertTrue(
                    rule.contains(pill.getValue()[0] + ";") && rule.contains(pill.getValue()[0] + "-bg;"),
                    pill.getKey());
            for (String name : FLAGSHIP) {
                Map<String, String> p = palette(name);
                Color ink = color(p, pill.getValue()[1]);
                Color card = color(p, "-color-bg-default");
                Color raw = color(p, pill.getValue()[2]);
                Color tint =
                        card.interpolate(Color.color(raw.getRed(), raw.getGreen(), raw.getBlue()), raw.getOpacity());
                double onTint = contrast(ink, tint);
                double onCard = contrast(ink, card);
                if (onTint < 4.5 || onCard < 3) {
                    problems.add(String.format(
                            "%s %s: %.2f on its tint, %.2f on the card", name, pill.getKey(), onTint, onCard));
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /**
     * The Git "untracked" olive and "renamed" violet (also the Project Map's Structure tint) are ladders of the
     * app theme's ground like {@code -state-key}: one fixed light-theme hex read at 3.2:1 on Editora Dark. Each
     * reads at 4.5:1 on the default and subtle surfaces of every bundled app theme (Map review B20).
     */
    @Test
    void theGitOliveAndVioletFollowTheAppThemesGround() throws Exception {
        String app = read("app.css");
        List<String> problems = new ArrayList<>();
        for (String token : List.of("-state-olive", "-state-violet")) {
            Matcher decl = Pattern.compile(Pattern.quote(token) + ":\\s*ladder\\(-color-bg-default,([^;]+)\\);")
                    .matcher(app);
            assertTrue(decl.find(), token + " is a ladder of the app theme's ground");
            List<double[]> offsets = new ArrayList<>();
            List<Color> colors = new ArrayList<>();
            Matcher stop = Pattern.compile("(#[0-9a-fA-F]{6})\\s+([0-9.]+)%").matcher(decl.group(1));
            while (stop.find()) {
                colors.add(Color.web(stop.group(1)));
                offsets.add(new double[] {Double.parseDouble(stop.group(2)) / 100});
            }
            assertTrue(colors.size() >= 2, token);
            for (String name : Themes.NAMES) {
                String[] bundled = Themes.BUNDLED.get(name);
                Map<String, String> p = bundled != null
                        ? palette(bundled[0])
                        : builtInPalette(name.toLowerCase(java.util.Locale.ROOT).replace(' ', '-'));
                Color ink = ladder(color(p, "-color-bg-default"), offsets, colors);
                for (String surface : List.of("-color-bg-default", "-color-bg-subtle")) {
                    double ratio = contrast(ink, color(p, surface));
                    if (ratio < 4.5) {
                        problems.add(String.format("%s %s %s: %.2f", token, name, surface, ratio));
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), "Git state colours under 4.5:1:\n" + String.join("\n", problems));
    }

    /**
     * "Show recent changes" mixes the heat ({@code -state-amber}) into a Project Map row's fill. At the hottest step the
     * row's label still reads at 4.5:1 and its muted ink at 3:1, the tint still shows, and the heat meter stands 3:1 off
     * the row, in every bundled app theme; Editora's own themes get the full tint (Map explore position 3).
     */
    @Test
    void theMapsHeatTintKeepsItsRowsReadableInEveryTheme() throws Exception {
        String app = read("app.css");
        // The heat is the accent, not amber: recent change is not "not durable yet" (ICON-18).
        assertTrue(String.join(";", rules(app, ".project-map-probe-heat")).contains("-fx-fill: -color-accent-fg"));
        assertTrue(String.join(";", rules(app, ".project-map-probe-surface")).contains("-color-bg-subtle"));
        double hottest = ProjectMapActivity.HEAT_TINT[ProjectMapActivity.LEVELS];
        List<String> problems = new ArrayList<>();
        for (String name : Themes.NAMES) {
            String[] bundled = Themes.BUNDLED.get(name);
            String base = bundled != null
                    ? bundled[0]
                    : name.toLowerCase(java.util.Locale.ROOT).replace(' ', '-');
            Map<String, String> p = bundled != null ? palette(base) : builtInPalette(base);
            Color row = color(p, "-color-bg-subtle");
            Color heat = color(p, "-color-accent-fg");
            Color ink = color(p, "-color-fg-default");
            Color mutedInk = color(p, "-color-fg-muted");
            Color tinted = ProjectMapActivity.tinted(row, heat, ProjectMapActivity.LEVELS, ink, mutedInk);
            double text = contrast(ink, tinted);
            double muted = contrast(mutedInk, tinted);
            if (muted < 3 && contrast(mutedInk, row) >= 3 || contrast(tinted, row) < 1.04) {
                muted = 0; // the tint took the muted ink under 3:1, or was given up altogether
            } else if (muted < 3) {
                muted = 3; // the theme's own muted ink is under 3:1 on its rows: the tint keeps what there was
            }
            double meter = Double.MAX_VALUE;
            for (int level = 1; level <= ProjectMapActivity.LEVELS; level++) { // the meter sits on each step's tint
                Color ground = ProjectMapActivity.tinted(row, heat, level, ink, mutedInk);
                meter = Math.min(meter, contrast(ProjectMapActivity.meterInk(heat, ground, ink), ground));
            }
            if (base.startsWith("editora") && !tinted.equals(row.interpolate(heat, hottest))) {
                problems.add(name + ": Editora's own theme gets the full tint");
            }
            if (text < 4.5 || muted < 3 || meter < 3) {
                problems.add(String.format("%s: text %.2f, muted %.2f, meter %.2f", name, text, muted, meter));
            }
        }
        assertTrue(problems.isEmpty(), "heat tint under its floor:\n" + String.join("\n", problems));
    }

    /** A role's colour laid over {@code ground}, its opacity applied (a see-through tint as it is drawn). */
    static Color over(Color ground, Color tint) {
        return ground.interpolate(Color.color(tint.getRed(), tint.getGreen(), tint.getBlue()), tint.getOpacity());
    }

    /** Every bundled app theme's {@code .root} palette, by name: Editora's own sheets and AtlantaFX's built-ins. */
    static Map<String, Map<String, String>> bundledPalettes() throws Exception {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (String name : Themes.NAMES) {
            String[] bundled = Themes.BUNDLED.get(name);
            out.put(
                    name,
                    bundled != null
                            ? palette(bundled[0])
                            : builtInPalette(
                                    name.toLowerCase(java.util.Locale.ROOT).replace(' ', '-')));
        }
        return out;
    }

    /**
     * A popup's ink on its overlay ground (the debugger's value popup, the hover cards) reads at 4.5:1 in both
     * flagship themes. DebugValuePopupThemeFxTest checks the popup takes these two roles; this checks the roles.
     */
    @Test
    void popupInkReadsOnTheOverlayGround() throws Exception {
        for (String base : FLAGSHIP) {
            Map<String, String> p = palette(base);
            double ratio = contrast(color(p, "-color-fg-default"), color(p, "-color-bg-overlay"));
            assertTrue(ratio >= 4.5, base + ": ink on the overlay ground is " + ratio);
        }
    }

    /**
     * The Project Map's row border and connector stand 3:1 off their grounds, an open file's accent label and the
     * unfocused selection's label read at 4.5:1, in both Editora themes (moved here from ProjectMapLayoutFxTest:
     * the arithmetic needs no toolkit, ICON-14).
     */
    @Test
    void mapRowAndConnectorInkReachesThreeToOneInBothEditoraThemes() throws Exception {
        for (String theme : FLAGSHIP) {
            Map<String, String> palette = palette(theme);
            Color background = color(palette, "-color-bg-default");
            Color card = color(palette, "-color-bg-subtle");
            Color border = ProjectMapView.rowBorderColor(
                    color(palette, "-color-border-default"), color(palette, "-color-fg-muted"));
            assertTrue(contrast(border, card) >= 3.0, theme + ": a row's border on its card " + contrast(border, card));
            Color connector = background.interpolate(
                    color(palette, "-color-accent-emphasis"), ProjectMapView.connectorOpacity(false, true));
            assertTrue(
                    contrast(connector, background) >= 3.0, theme + ": a connector " + contrast(connector, background));
            assertTrue(contrast(color(palette, "-color-accent-fg"), card) >= 4.5, theme + ": an open file's label");
            assertTrue(
                    contrast(color(palette, "-color-fg-default"), color(palette, "-color-accent-subtle")) >= 4.5,
                    theme + ": the unfocused selection's label");
        }
    }

    /** A faded Map label recedes but keeps 3.5:1 (Map review B19); moved from ProjectMapLayoutReviewFxTest. */
    @Test
    void aFadedMapLabelRecedesButStaysReadableInBothEditoraThemes() throws Exception {
        for (String theme : FLAGSHIP) {
            Map<String, String> palette = palette(theme);
            Color ground = color(palette, "-color-bg-subtle");
            Color text = color(palette, "-color-fg-default");
            Color faded = ProjectMapView.fadedInk(text, ground, 3.5, 0.62);
            double ratio = ProjectMapView.contrastRatio(faded, ground);
            assertTrue(ratio >= 3.5, theme + ": " + ratio);
            assertTrue(ratio < ProjectMapView.contrastRatio(text, ground) - 2, theme + ": visibly fainter: " + ratio);
        }
        Color weak = Color.web("#999999");
        assertEquals(weak, ProjectMapView.fadedInk(weak, Color.WHITE, 3.5, 0.62), "ink already under the floor stays");
    }

    /**
     * A note's sheet (the Map's note card and sticky notes, -note-paper over the opaque ground) carries the theme's ink
     * at 7:1 in both Editora themes; moved from ProjectMapLayoutFxTest when the sheet left amber for the accent wash.
     */
    @Test
    void noteSheetsCarryTheThemesInk() throws Exception {
        String app = read("app.css");
        assertTrue(String.join(";", rules(app, ".root")).contains("-note-paper: -color-accent-subtle"));
        for (String theme : FLAGSHIP) {
            Map<String, String> p = palette(theme);
            Color sheet = over(color(p, "-color-bg-default"), color(p, "-color-accent-subtle"));
            double ratio = contrast(color(p, "-color-fg-default"), sheet);
            assertTrue(ratio >= 7.0, theme + ": note text on its sheet is " + ratio);
        }
    }

    /**
     * "Show recent changes": the heat meter on a selected row (the unfocused selection's accent wash over the card)
     * stands off it, in both Editora themes. ProjectMapActivityFxTest checks the meter is drawn; this its ink.
     */
    @Test
    void theHeatMeterStandsOffASelectedRow() throws Exception {
        for (String theme : FLAGSHIP) {
            Map<String, String> p = palette(theme);
            Color raw = color(p, "-color-accent-subtle");
            Color drawn = over(color(p, "-color-bg-subtle"), raw);
            Color ink = ProjectMapActivity.meterInk(color(p, "-color-accent-fg"), raw, color(p, "-color-fg-default"));
            double ratio = contrast(ink, drawn);
            assertTrue(ratio >= 2.5, theme + ": the meter on a selected row is " + ratio);
        }
    }

    /**
     * The gutter's marks — a bookmark, a note, the quick-action bulb — stand 3:1 off the gutter of every editor theme
     * that draws them in its own colours (a graphic's floor), and the default theme's (Primer Light) line numbers
     * read at 4.5:1 on its gutter. Bookmarks and notes are each theme's accent, not amber or red (ICON-18).
     */
    @Test
    void theGutterMarksStandOffEveryEditorThemesGutter() throws Exception {
        List<String> problems = new ArrayList<>();
        for (String name : List.of(
                "editora-light",
                "editora-dark",
                "high-contrast-light",
                "high-contrast-dark",
                "primer-dark",
                "nord-light",
                "nord-dark",
                "cupertino-light",
                "cupertino-dark",
                "dracula",
                "islands-light",
                "islands-dark")) {
            String css = read("editor-themes/" + name + ".css");
            Color gutter = Color.web(fill(css, ".editor-area .fold-gutter", "-fx-background-color"));
            for (String mark : List.of(
                    ".editor-area .fold-gutter .bookmark-marker",
                    ".editor-area .fold-gutter .note-marker",
                    ".editor-area .fold-gutter .bulb-marker",
                    ".editor-area .fold-gutter .bulb-marker.fix")) {
                double ratio = contrast(Color.web(fill(css, mark, "-fx-fill")), gutter);
                if (ratio < 3) {
                    problems.add(String.format("%s %s: %.2f", name, mark, ratio));
                }
            }
            assertEquals(
                    fill(css, ".editor-area .fold-gutter .bookmark-marker", "-fx-fill"),
                    fill(css, ".editor-area .fold-chevron:hover", "-fx-text-fill"),
                    name + ": a bookmark is the theme's accent");
        }
        assertTrue(problems.isEmpty(), "gutter marks under 3:1:\n" + String.join("\n", problems));
        Map<String, String> primer = new HashMap<>();
        Matcher token = Pattern.compile("(-primer-[a-z-]+)\\s*:\\s*([^;]+);")
                .matcher(String.join(";", rules(read("app.css"), ".root")));
        while (token.find()) {
            primer.put(token.group(1), token.group(2).strip());
        }
        double lineNumbers = contrast(color(primer, "-primer-lineno"), color(primer, "-primer-gutter"));
        assertTrue(lineNumbers >= 4.5, "Primer Light's line numbers are " + lineNumbers);
        double link = contrast(color(primer, "-primer-link"), color(primer, "-primer-gutter"));
        assertTrue(link >= 3, "Primer Light's bookmark is " + link);
    }

    /**
     * ACC-09: the high-contrast pair's inks and states read at 7:1 (WCAG AAA) on every ground, and the ink on an
     * emphasis fill too; its palette is tokens the component rules use, no colour set apart from them.
     */
    @Test
    void theHighContrastPairReadsAtSevenToOne() throws Exception {
        List<String> problems = new ArrayList<>();
        for (String base : List.of("high-contrast-light", "high-contrast-dark")) {
            Map<String, String> p = palette(base);
            for (String ground :
                    List.of("-color-bg-default", "-color-bg-subtle", "-color-bg-overlay", "-color-bg-inset")) {
                for (String ink : List.of(
                        "-color-fg-default",
                        "-color-fg-muted",
                        "-color-accent-fg",
                        "-color-success-fg",
                        "-color-warning-fg",
                        "-color-danger-fg",
                        "-color-border-default")) {
                    double ratio = contrast(color(p, ink), color(p, ground));
                    if (ratio < 7) {
                        problems.add(String.format("%s %s on %s: %.2f", base, ink, ground, ratio));
                    }
                }
            }
            for (String role : List.of("accent", "success", "warning", "danger")) {
                double ratio = contrast(color(p, "-color-fg-emphasis"), color(p, "-color-" + role + "-emphasis"));
                if (ratio < 7) {
                    problems.add(String.format("%s fg-emphasis on %s-emphasis: %.2f", base, role, ratio));
                }
            }
        }
        assertTrue(problems.isEmpty(), "high contrast under 7:1:\n" + String.join("\n", problems));
    }

    /**
     * ACC-09: the high-contrast editor themes' text, every syntax colour and the line numbers read at 7:1 on the
     * editor's ground and on the current line's highlight.
     */
    @Test
    void theHighContrastEditorThemesReadAtSevenToOne() throws Exception {
        List<String> problems = new ArrayList<>();
        for (String name : List.of(SystemTheme.HIGH_CONTRAST_LIGHT, SystemTheme.HIGH_CONTRAST_DARK)) {
            String base = name.toLowerCase(java.util.Locale.ROOT).replace(' ', '-');
            String css = read("editor-themes/" + base + ".css");
            Color ground = Color.web(fill(css, ".editor-area", "-fx-background-color"));
            assertEquals(ground, EditorThemes.editorBackgroundFor(name), name + ": the code's ground is the sheet's");
            List<Color> grounds = List.of(ground, EditorThemes.lineHighlightFor(name));
            Map<String, Color> inks = new LinkedHashMap<>();
            inks.put(".editor-area .text", Color.web(fill(css, ".editor-area .text", "-fx-fill")));
            inks.put(".editor-area .lineno", Color.web(fill(css, ".editor-area .lineno", "-fx-text-fill")));
            Matcher token = Pattern.compile("(\\.text\\.[a-z-]+)\\s*\\{[^}]*-fx-fill:\\s*(#[0-9a-fA-F]{6})\\b")
                    .matcher(css);
            while (token.find()) {
                if (!token.group(1).equals(".text.invalid")) { // drawn on its own red ground, below
                    inks.put(token.group(1), Color.web(token.group(2)));
                }
            }
            assertTrue(inks.size() > 20, name + ": " + inks.keySet());
            for (var ink : inks.entrySet()) {
                for (Color g : grounds) {
                    double ratio = contrast(ink.getValue(), g);
                    if (ratio < 7) {
                        problems.add(String.format("%s %s on %s: %.2f", name, ink.getKey(), g, ratio));
                    }
                }
            }
            Matcher invalid = Pattern.compile(
                            "\\.text\\.invalid\\s*\\{\\s*-fx-fill:\\s*(#[0-9a-fA-F]{6});\\s*-rtfx-background-color:\\s*(#[0-9a-fA-F]{6})")
                    .matcher(css);
            assertTrue(invalid.find(), name + ": .text.invalid");
            double ratio = contrast(Color.web(invalid.group(1)), Color.web(invalid.group(2)));
            if (ratio < 4.5) {
                problems.add(String.format("%s invalid: %.2f", name, ratio));
            }
        }
        assertTrue(problems.isEmpty(), "high-contrast editor ink under 7:1:\n" + String.join("\n", problems));
    }

    /** The value of {@code property} in the one rule for {@code selector}. */
    private static String fill(String css, String selector, String property) {
        List<String> bodies = rules(css, selector);
        assertEquals(1, bodies.size(), selector);
        Matcher m =
                Pattern.compile(Pattern.quote(property) + "\\s*:\\s*([^;]+);").matcher(bodies.get(0));
        assertTrue(m.find(), selector + " " + property);
        return m.group(1).strip();
    }

    /**
     * A view-mode banner (a file opened read-only) and the narrowing strip are amber: their title in -state-amber
     * on -state-amber-bg, their text in the theme's ink, readable in both flagship themes; and a Stop glyph is the
     * ink, not red (ICON-18).
     */
    @Test
    void readOnlyBannersAreAmberAndStopIsTheInk() throws Exception {
        String app = read("app.css");
        assertTrue(String.join(";", rules(app, ".view-mode-bar")).contains("-state-amber-bg"));
        assertTrue(
                String.join(";", rules(app, ".view-mode-bar .view-mode-title")).contains("-state-amber"));
        assertTrue(
                String.join(";", rules(app, ".view-mode-bar .view-mode-desc")).contains("-color-fg-default"));
        for (String stop :
                List.of(".icon-line.icon-stop", ".run-config-stop .icon-line", ".run-marker.http-running-marker")) {
            assertTrue(String.join(";", rules(app, stop)).contains("-color-fg-default"), stop);
        }
        for (String base : FLAGSHIP) {
            Map<String, String> p = palette(base);
            Color band = over(color(p, "-color-bg-default"), color(p, "-color-warning-subtle"));
            assertTrue(contrast(color(p, "-color-warning-fg"), band) >= 4.5, base + ": the banner's title");
            assertTrue(contrast(color(p, "-color-fg-default"), band) >= 4.5, base + ": the banner's text");
        }
    }

    /**
     * The six lane hues (the Git log's graph, a timeline's tags, read by canvas painters through probes) stand 3:1 off
     * the default and subtle grounds of every bundled app theme, and none is a state's hue (ICON-18): each is a ladder
     * of the ground, as the Git olive and violet are.
     */
    @Test
    void theLaneHuesStandOffEveryBundledThemesGround() throws Exception {
        String app = read("app.css");
        List<String> problems = new ArrayList<>();
        for (int lane = 0; lane < 6; lane++) {
            Matcher decl = Pattern.compile("-lane-" + lane + ":\\s*ladder\\(-color-bg-default,([^;]+)\\);")
                    .matcher(app);
            assertTrue(decl.find(), "-lane-" + lane + " is a ladder of the ground");
            List<double[]> offsets = new ArrayList<>();
            List<Color> colors = new ArrayList<>();
            Matcher stop = Pattern.compile("(#[0-9a-fA-F]{6})\\s+([0-9.]+)%").matcher(decl.group(1));
            while (stop.find()) {
                colors.add(Color.web(stop.group(1)));
                offsets.add(new double[] {Double.parseDouble(stop.group(2)) / 100});
            }
            for (Color c : colors) {
                double hue = c.getHue();
                assertFalse(
                        c.getSaturation() > 0.25
                                && (hue < 20 || hue > 345 || (hue > 25 && hue < 65) || (hue > 85 && hue < 165)),
                        "-lane-" + lane + " " + c + " is a red, amber or green hue");
            }
            for (Map.Entry<String, Map<String, String>> theme :
                    bundledPalettes().entrySet()) {
                Map<String, String> p = theme.getValue();
                Color ink = ladder(color(p, "-color-bg-default"), offsets, colors);
                for (String surface : List.of("-color-bg-default", "-color-bg-subtle")) {
                    double ratio = contrast(ink, color(p, surface));
                    if (ratio < 3) {
                        problems.add(String.format("-lane-%d %s %s: %.2f", lane, theme.getKey(), surface, ratio));
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), "lane hues under 3:1:\n" + String.join("\n", problems));
    }
}
