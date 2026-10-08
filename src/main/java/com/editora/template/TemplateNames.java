package com.editora.template;

import java.util.Locale;

/** Small pure naming helpers for the template wizards. Unit-tested. */
public final class TemplateNames {

    private TemplateNames() {}

    /**
     * A readable label for a variable identifier when neither the template nor the message catalog names
     * it: {@code packageName} → "Package name", {@code base_name} → "Base name", {@code HTMLTitle} → "HTML
     * Title".
     */
    public static String humanize(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        String id = identifier.strip();
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c == '_' || c == '-') {
                appendSpace(out);
                continue;
            }
            boolean upper = Character.isUpperCase(c);
            char prev = i > 0 ? id.charAt(i - 1) : 0;
            char next = i + 1 < id.length() ? id.charAt(i + 1) : 0;
            // A word starts at a capital after a lower-case letter/digit, or at the last capital of an
            // acronym run ("HTMLTitle" → "HTML" + "Title").
            boolean boundary = upper
                    && i > 0
                    && (Character.isLowerCase(prev)
                            || Character.isDigit(prev)
                            || (Character.isUpperCase(prev) && Character.isLowerCase(next)));
            if (boundary) {
                appendSpace(out);
            }
            boolean acronym = upper && (Character.isUpperCase(prev) || Character.isUpperCase(next));
            out.append(out.isEmpty() ? Character.toUpperCase(c) : acronym ? c : Character.toLowerCase(c));
        }
        return out.toString().strip();
    }

    private static void appendSpace(StringBuilder out) {
        if (!out.isEmpty() && out.charAt(out.length() - 1) != ' ') {
            out.append(' ');
        }
    }

    /**
     * A package / module name derived from a project name, usable in both Python and Java: lower case,
     * anything that is not a letter or digit becomes {@code _}, and a leading digit gets a {@code _} in
     * front — {@code "My App 2"} → {@code my_app_2}. Empty when the name has nothing usable in it.
     */
    public static String packageNameFor(String projectName) {
        if (projectName == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (char c : projectName.strip().toLowerCase(Locale.ROOT).toCharArray()) {
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (ok) {
                out.append(c);
            } else if (!out.isEmpty() && out.charAt(out.length() - 1) != '_') {
                out.append('_');
            }
        }
        while (!out.isEmpty() && out.charAt(out.length() - 1) == '_') {
            out.setLength(out.length() - 1);
        }
        if (!out.isEmpty() && Character.isDigit(out.charAt(0))) {
            out.insert(0, '_');
        }
        return out.toString();
    }
}
