package com.editora.markdown;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The markdownlint rule options this linter honours — the ones a project sets in its config file (or a
 * {@code markdownlint-configure-file} comment) for the rules that exist here. Everything else in an options
 * object is ignored. Immutable; {@link #DEFAULT} is markdownlint's own defaults, except that hard tabs inside code
 * blocks are not reported unless asked for.
 *
 * @param brSpaces {@code MD009.br_spaces}: trailing spaces that make a hard line break and are left alone
 *     (below 2: none are)
 * @param punctuation {@code MD026.punctuation}: the characters a heading may not end in
 * @param frontMatterTitle {@code MD025/MD041.front_matter_title}: a regex that finds the document title in the
 *     front matter; "" means front matter never supplies the title
 * @param titleLevel {@code MD025.level}: the heading level there should be only one of
 * @param firstLineLevel {@code MD041.level}: the heading level the first line should be
 * @param tabsInCode {@code MD010.code_blocks}: also report hard tabs inside code blocks (never auto-fixed)
 * @param spacesPerTab {@code MD010.spaces_per_tab}: spaces a fixed tab becomes; 0 = the editor's tab size
 * @param maxBlankLines {@code MD012.maximum}: consecutive blank lines allowed
 * @param linesAbove {@code MD022.lines_above}: blank lines required above a heading (0 or less: not checked)
 * @param linesBelow {@code MD022.lines_below}: blank lines required below a heading (0 or less: not checked)
 */
public record MarkdownLintOptions(
        int brSpaces,
        String punctuation,
        String frontMatterTitle,
        int titleLevel,
        int firstLineLevel,
        boolean tabsInCode,
        int spacesPerTab,
        int maxBlankLines,
        int linesAbove,
        int linesBelow) {

    /** markdownlint's default heading punctuation (no question mark), full-width forms included. */
    public static final String DEFAULT_PUNCTUATION = ".,;:!。，；：！";

    /** markdownlint's default {@code front_matter_title}: a YAML {@code title:} or a TOML {@code title =}. */
    public static final String DEFAULT_FRONT_MATTER_TITLE = "^\\s*\"?title\"?\\s*[:=]";

    public static final MarkdownLintOptions DEFAULT =
            new MarkdownLintOptions(2, DEFAULT_PUNCTUATION, DEFAULT_FRONT_MATTER_TITLE, 1, 1, false, 0, 1, 1, 1);

    /**
     * These options with {@code option} of rule {@code code} set to {@code value} (a {@code Number},
     * {@code Boolean} or {@code String}, as parsed from the config). An option this linter does not honour, a
     * value of the wrong type or an invalid regex leaves the options as they are.
     */
    public MarkdownLintOptions with(String code, String option, Object value) {
        String key = code + "." + option;
        Integer n = value instanceof Number num ? Integer.valueOf(num.intValue()) : null;
        String s = value instanceof String str ? str : null;
        Boolean b = value instanceof Boolean bool ? bool : null;
        return switch (key) {
            case "MD009.br_spaces" ->
                n == null ? this : copy(Math.max(0, n), null, null, null, null, null, null, null, null, null);
            case "MD026.punctuation" ->
                s == null ? this : copy(null, s, null, null, null, null, null, null, null, null);
            case "MD025.front_matter_title", "MD041.front_matter_title" ->
                s == null || !validRegex(s) ? this : copy(null, null, s, null, null, null, null, null, null, null);
            case "MD025.level" -> level(n) ? copy(null, null, null, n, null, null, null, null, null, null) : this;
            case "MD041.level" -> level(n) ? copy(null, null, null, null, n, null, null, null, null, null) : this;
            case "MD010.code_blocks" ->
                b == null ? this : copy(null, null, null, null, null, b, null, null, null, null);
            case "MD010.spaces_per_tab" ->
                n == null || n < 1 ? this : copy(null, null, null, null, null, null, n, null, null, null);
            case "MD012.maximum" ->
                n == null || n < 1 ? this : copy(null, null, null, null, null, null, null, n, null, null);
            case "MD022.lines_above" ->
                n == null ? this : copy(null, null, null, null, null, null, null, null, n, null);
            case "MD022.lines_below" ->
                n == null ? this : copy(null, null, null, null, null, null, null, null, null, n);
            default -> this;
        };
    }

    /** The compiled {@link #frontMatterTitle}, or null when front matter never supplies the title. */
    Pattern frontMatterTitlePattern() {
        return frontMatterTitle.isEmpty() ? null : Pattern.compile(frontMatterTitle);
    }

    private static boolean level(Integer n) {
        return n != null && n >= 1 && n <= 6;
    }

    private static boolean validRegex(String s) {
        try {
            Pattern.compile(s);
            return true;
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    private MarkdownLintOptions copy(
            Integer br,
            String punct,
            String title,
            Integer titleLvl,
            Integer firstLvl,
            Boolean tabs,
            Integer perTab,
            Integer maxBlank,
            Integer above,
            Integer below) {
        return new MarkdownLintOptions(
                br == null ? brSpaces : br,
                punct == null ? punctuation : punct,
                title == null ? frontMatterTitle : title,
                titleLvl == null ? titleLevel : titleLvl,
                firstLvl == null ? firstLineLevel : firstLvl,
                tabs == null ? tabsInCode : tabs,
                perTab == null ? spacesPerTab : perTab,
                maxBlank == null ? maxBlankLines : maxBlank,
                above == null ? linesAbove : above,
                below == null ? linesBelow : below);
    }
}
