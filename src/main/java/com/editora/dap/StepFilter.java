package com.editora.dap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One "Don't step into" entry (IntelliJ's stepping filters, VS Code's {@code skipFiles}, debugpy's {@code rules}): code
 * that Step Into goes over, and that a step coming out into it leaves at once. Kept in {@code
 * Settings.debugStepFilterList}; how each debugger is told is {@link StepFilters}'s.
 *
 * <p>The pattern is a class or package ({@code com.shop.util.*}), a module ({@code requests}), a function ({@code
 * fmt.*}, {@code std::*}) or a path ({@code **}{@code /vendor/**}), with {@code *} for any text.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class StepFilter {

    private boolean enabled = true;
    /** The debug language it applies to ({@code java}, {@code python}…; {@code ""}: every language). */
    private String language = "";

    private String pattern = "";

    public StepFilter() {}

    public StepFilter(boolean enabled, String language, String pattern) {
        this.enabled = enabled;
        setLanguage(language);
        setPattern(pattern);
    }

    /** A copy, for an editor that changes its own until it saves. */
    public StepFilter copy() {
        return new StepFilter(enabled, language, pattern);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getLanguage() {
        return language == null ? "" : language;
    }

    public void setLanguage(String language) {
        this.language = language == null ? "" : language.strip();
    }

    public String getPattern() {
        return pattern == null ? "" : pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern == null ? "" : pattern.strip();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StepFilter f
                && f.enabled == enabled
                && f.getLanguage().equals(getLanguage())
                && f.getPattern().equals(getPattern());
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(enabled, getLanguage(), getPattern());
    }

    @Override
    public String toString() {
        return (enabled ? "" : "(off) ") + getLanguage() + ":" + getPattern();
    }
}
