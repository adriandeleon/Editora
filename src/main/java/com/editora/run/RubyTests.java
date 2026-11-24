package com.editora.run;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Minitest's test at the caret, for Debug Test at Caret outside Rails (RSpec and {@code bin/rails test} take the
 * caret's line themselves): the {@code def test_…} method, or ActiveSupport's {@code test "…" do} block, whose span —
 * to the next test — holds the caret's line, by the name Minitest gives it ({@code test "greets by name"} is {@code
 * test_greets_by_name}). Pure.
 */
public final class RubyTests {

    private RubyTests() {}

    /** {@code def test_adds}, {@code test "greets by name" do}, {@code test 'x' do}. */
    private static final Pattern TEST =
            Pattern.compile("^\\s*(?:def\\s+(test_\\w+[?!]?)|test\\s*\\(?\\s*(['\"])(.*?)\\2)");

    /** The Minitest name of the test whose span holds 0-based line {@code caret} of {@code text}, or null for none. */
    public static @Nullable String at(String text, int caret) {
        String[] lines = text.split("\n", -1);
        String found = null;
        for (int i = 0; i < lines.length && i <= caret; i++) {
            Matcher m = TEST.matcher(lines[i]);
            if (m.find()) {
                found = m.group(1) != null ? m.group(1) : name(m.group(3));
            }
        }
        return found;
    }

    /**
     * The method ActiveSupport makes of {@code test "description"}: {@code test_} and the description with its runs of
     * whitespace made {@code _} ({@code "test_#{name.gsub(/\\s+/, '_')}"}), its case kept.
     */
    static String name(String description) {
        return "test_" + description.replaceAll("\\s+", "_");
    }
}
