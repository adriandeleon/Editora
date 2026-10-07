package com.editora.git;

/**
 * Whether a name typed by the user can be a tag or branch name — the rules of {@code git check-ref-format}
 * (applied to {@code refs/tags/<name>}), plus git's own refusal of a tag or branch name that starts with
 * {@code -}, plus the lone {@code @} (a legal ref, but as a revision it means {@code HEAD}). Pure; the unit test compares it with the installed git over a table of names.
 *
 * <p>Checked before the name reaches a command line: a rejected name gets a clear message instead of git's,
 * and a name that starts with {@code -} never becomes an option.
 */
public final class GitRefName {

    private GitRefName() {}

    public static boolean isValid(String name) {
        if (name == null || name.isEmpty() || name.equals("@")) {
            return false;
        }
        if (name.charAt(0) == '-' || name.charAt(0) == '/' || name.endsWith("/") || name.endsWith(".")) {
            return false;
        }
        if (name.contains("..") || name.contains("//") || name.contains("@{")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f || " ~^:?*[\\".indexOf(c) >= 0) {
                return false;
            }
        }
        for (String component : name.split("/")) {
            if (component.startsWith(".") || component.endsWith(".lock")) {
                return false;
            }
        }
        return true;
    }
}
