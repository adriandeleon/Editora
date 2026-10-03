package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
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

    private static String read(String path) throws Exception {
        try (var in = StyleContrastTest.class.getResourceAsStream(STYLES + path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "");
        }
    }

    /** {@code -color-*} tokens of a control theme's {@code .root} palette, aliases resolved. */
    private static Map<String, String> palette(String base) throws Exception {
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

    private static Color color(Map<String, String> palette, String token) {
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
    private static List<String> rules(String css, String selector) {
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
        for (String base : List.of("editora-light", "editora-dark")) {
            Map<String, String> p = palette(base);
            for (String role : List.of("accent", "success", "warning", "danger")) {
                double ratio = contrast(color(p, "-color-fg-emphasis"), color(p, "-color-" + role + "-emphasis"));
                assertTrue(ratio >= 4.5, base + ": fg-emphasis on " + role + "-emphasis is " + ratio);
            }
            assertTrue(contrast(color(p, "-color-light"), color(p, "-color-neutral-emphasis")) >= 4.5, base);
        }
        String app = read("app.css");
        for (String selector : List.of(".markwhen-cal-chip", ".openapi-method")) {
            String body = String.join(";", rules(app, selector));
            assertTrue(body.contains("-fx-text-fill: -color-fg-emphasis"), selector + ": " + body);
            assertFalse(body.contains("white"), selector);
        }
    }

    @Test
    void theFocusRingIsAtLeastThreeToOneOnEverySurface() throws Exception {
        String app = read("app.css");
        assertTrue(String.join(";", rules(app, ".root")).contains("-focus-ring: -color-accent-emphasis"));
        for (String base : List.of("editora-light", "editora-dark")) {
            Map<String, String> p = palette(base);
            for (String surface :
                    List.of("-color-bg-default", "-color-bg-subtle", "-color-bg-inset", "-color-bg-overlay")) {
                double ratio = contrast(color(p, "-color-accent-emphasis"), color(p, surface));
                assertTrue(ratio >= 3.0, base + ": ring on " + surface + " is " + ratio);
            }
        }
    }

    @Test
    void textThatMustBeReadUsesTheMutedRoleNotSubtle() throws Exception {
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
        for (String base : List.of("editora-light", "editora-dark")) {
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
                ".debug-panel .debug-val-string", "-state-green",
                ".debug-panel .debug-val-number", "-state-accent",
                ".debug-panel .debug-val-bool", "-state-amber",
                ".debug-panel .debug-start", "-state-green",
                ".run-marker", "-state-green",
                ".dev-mode-badge", "-state-red");
        Pattern hex = Pattern.compile("#[0-9a-fA-F]{3,8}\\b");
        expected.forEach((selector, token) -> {
            String body = String.join(";", rules(app, selector));
            assertTrue(body.contains(token), selector + " should use " + token + ": " + body);
            assertFalse(hex.matcher(body).find(), selector + " still has a fixed colour: " + body);
        });
    }

    @Test
    void flagshipLineNumbersReachAa() throws Exception {
        for (String base : List.of("editora-light", "editora-dark")) {
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
        assertTrue(rules(app, ".combo-box-base:danger").get(0).contains("-color-danger-emphasis"));
        assertTrue(rules(app, ".settings-search:focused").get(0).contains("-color-input-border-focused"));
    }
}
