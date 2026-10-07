package com.editora.git;

/**
 * Pure validation of a name the user typed for a branch, a tag or a remote against the rules of
 * {@code git check-ref-format} (the unit test compares it with the installed git over a table of names). Checked before the name reaches an argv, so a bad one is refused
 * with a sentence instead of a git usage error — and so a name that git would read as an option, or that
 * names something other than a branch ({@code HEAD}, {@code a..b}, {@code x@{1}}), never gets that far.
 * Toolkit-free and unit-tested.
 */
public final class GitRefNames {

    private GitRefNames() {}

    /** Why a name is not a valid branch name. */
    public enum Problem {
        /** Nothing, or only white space. */
        EMPTY,
        /** Starts with {@code -}: git would read it as an option. */
        LEADING_DASH,
        /** A space, a control character, or one of {@code ~ ^ : ? * [ \}. */
        FORBIDDEN_CHARACTER,
        /** {@code ..} or {@code @{} anywhere, or the single character {@code @}. */
        FORBIDDEN_SEQUENCE,
        /** Starts or ends with {@code /}, or contains {@code //}. */
        EMPTY_COMPONENT,
        /** A {@code /}-separated component starts with {@code .} or ends with {@code .lock}. */
        BAD_COMPONENT,
        /** Ends with {@code .}. */
        TRAILING_DOT,
        /** {@code HEAD}: git refuses a branch of that name. */
        RESERVED
    }

    /** The first rule {@code name} breaks as a branch name, or {@code null} when it is a valid one. */
    public static Problem problem(String name) {
        if (name == null || name.isBlank()) {
            return Problem.EMPTY;
        }
        if (name.charAt(0) == '-') {
            return Problem.LEADING_DASH;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c <= 0x20 || c == 0x7f || "~^:?*[\\".indexOf(c) >= 0) {
                return Problem.FORBIDDEN_CHARACTER;
            }
        }
        if (name.contains("..") || name.contains("@{") || name.equals("@")) {
            return Problem.FORBIDDEN_SEQUENCE;
        }
        if (name.startsWith("/") || name.endsWith("/") || name.contains("//")) {
            return Problem.EMPTY_COMPONENT;
        }
        for (String component : name.split("/")) {
            if (component.startsWith(".") || component.endsWith(".lock")) {
                return Problem.BAD_COMPONENT;
            }
        }
        if (name.endsWith(".")) {
            return Problem.TRAILING_DOT;
        }
        if (name.equals("HEAD")) {
            return Problem.RESERVED;
        }
        return null;
    }

    /** Whether {@code name} can be given to git as a new branch name. */
    public static boolean isValidBranch(String name) {
        return problem(name) == null;
    }

    /**
     * Whether {@code name} can be given to git as a new tag name. The rules are the branch rules: the same
     * spelling ({@code refs/tags/<name>}), no leading {@code -}, and neither {@code @} nor {@code HEAD},
     * which as revisions mean the checked-out commit.
     */
    public static boolean isValidTag(String name) {
        return problem(name) == null;
    }

    /**
     * Whether {@code name} can be a remote's name: a valid ref component sequence that is also free of
     * {@code /}, so {@code <remote>/<branch>} always splits at the first slash.
     */
    public static boolean isValidRemote(String name) {
        return problem(name) == null && name.indexOf('/') < 0;
    }

    /**
     * Whether {@code url} can be stored as a remote's URL: something, on one line, that git cannot read as an
     * option. Nothing more is checked — a remote may be a path, an scp-style host or any transport git knows.
     */
    public static boolean isUsableUrl(String url) {
        if (url == null || url.isBlank() || url.strip().charAt(0) == '-') {
            return false;
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        return true;
    }
}
