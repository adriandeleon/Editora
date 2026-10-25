package com.editora.maven;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Maven's version ordering — a port of {@code org.apache.maven.artifact.versioning.ComparableVersion}'s
 * rules, so "newer" here means what {@code mvn} itself means by it.
 *
 * <p>Why not the plugin registry's {@code PluginInstaller.compareVersions}: that is a semver comparator,
 * and semver ranks a letter above a number. Under it Guava's {@code r09} (2011) outranked {@code 33.0.0-jre},
 * and Update Versions wrote a <em>downgrade</em> into the pom. Maven's rules are different in exactly the
 * places that matter for real artifacts:
 *
 * <ul>
 *   <li>a number always beats a qualifier ({@code 33.0.0-jre > r09});
 *   <li>known qualifiers order {@code alpha < beta < milestone < rc = cr < snapshot < "" = ga = final = release
 *       < sp}, so {@code 3.0.0-M9 < 3.0.0} and {@code 1.0-SNAPSHOT < 1.0};
 *   <li>a single letter followed by a digit is short for a known qualifier ({@code 1.0a1 = 1.0-alpha-1});
 *   <li>an unknown qualifier sorts after every known one, by string ({@code -jre}, {@code -android},
 *       {@code .Final} — the last is an alias for "no qualifier");
 *   <li>trailing zeros and empty qualifiers do not count ({@code 1.0 = 1 = 1.0.0.Final}).
 * </ul>
 *
 * <p>Pure, allocation-light enough for a few hundred comparisons per update, and unit-tested against real
 * version lists ({@code MavenVersionTest}). Only the comparison is ported — not the range syntax.
 */
public final class MavenVersion implements Comparable<MavenVersion> {

    /** {@code String} comparator with these rules; nulls compare as the empty version. */
    public static final Comparator<String> ORDER = (a, b) -> compare(a, b);

    private static final List<String> QUALIFIERS = List.of("alpha", "beta", "milestone", "rc", "snapshot", "", "sp");

    /** Index of the empty qualifier: a bare {@code 1.0} sits here, between {@code snapshot} and {@code sp}. */
    private static final String RELEASE_INDEX = String.valueOf(QUALIFIERS.indexOf(""));

    private final String value;
    private final ListItem items;

    private MavenVersion(String value) {
        this.value = value == null ? "" : value.strip();
        this.items = parse(this.value.toLowerCase(Locale.ROOT));
    }

    public static MavenVersion of(String version) {
        return new MavenVersion(version);
    }

    /** Negative, zero or positive as {@code a} is older than, the same as, or newer than {@code b}. */
    public static int compare(String a, String b) {
        return of(a).compareTo(of(b));
    }

    /** {@code a} is strictly newer than {@code b}. */
    public static boolean isNewer(String a, String b) {
        return compare(a, b) > 0;
    }

    /**
     * The leading number of a version ({@code 33} for {@code 33.7.2-jre}), or {@code -1} when it does not
     * start with one ({@code r09}, {@code RELEASE}). Used as the extra "never walk backwards" guard: a
     * candidate whose leading number is below the current one is not an upgrade whatever the qualifiers say.
     */
    public static long leadingNumber(String version) {
        if (version == null) {
            return -1;
        }
        String v = version.strip();
        int end = 0;
        while (end < v.length() && Character.isDigit(v.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return -1;
        }
        try {
            return Long.parseLong(v.substring(0, Math.min(end, 18)));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public int compareTo(MavenVersion o) {
        return items.compareTo(o.items);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MavenVersion other && items.compareTo(other.items) == 0;
    }

    @Override
    public int hashCode() {
        return items.canonical().hashCode();
    }

    @Override
    public String toString() {
        return value;
    }

    // --- the parse ------------------------------------------------------------------------------

    /**
     * Splits on {@code .} and {@code -}; a change between digits and letters also splits, and every
     * {@code -} (explicit or implied) opens a sub-list so that {@code 1.0-alpha.1} nests as
     * {@code [1, 0, [alpha, 1]]}. A sub-list compares below a number and above a qualifier.
     */
    private static ListItem parse(String version) {
        ListItem root = new ListItem();
        ListItem list = root;
        List<ListItem> stack = new ArrayList<>();
        stack.add(list);
        boolean digits = false;
        int start = 0;
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (c == '.') {
                list.add(i == start ? IntItem.ZERO : item(digits, version.substring(start, i)));
                start = i + 1;
            } else if (c == '-') {
                list.add(i == start ? IntItem.ZERO : item(digits, version.substring(start, i)));
                start = i + 1;
                list = list.openSubList();
                stack.add(list);
            } else if (Character.isDigit(c)) {
                if (!digits && i > start) {
                    // letters then a digit: "rc1" → [rc, [1]], "a1" → [alpha, [1]]
                    list.add(new StringItem(version.substring(start, i), true));
                    start = i;
                    list = list.openSubList();
                    stack.add(list);
                }
                digits = true;
            } else {
                if (digits && i > start) {
                    // digits then a letter: "1a" → [1, [alpha]]
                    list.add(item(true, version.substring(start, i)));
                    start = i;
                    list = list.openSubList();
                    stack.add(list);
                }
                digits = false;
            }
        }
        if (version.length() > start) {
            list.add(item(digits, version.substring(start)));
        }
        for (int i = stack.size() - 1; i >= 0; i--) {
            stack.get(i).normalize();
        }
        return root;
    }

    private static Item item(boolean digits, String text) {
        return digits ? new IntItem(text) : new StringItem(text, false);
    }

    private sealed interface Item permits IntItem, StringItem, ListItem {
        /** Against the missing counterpart in a shorter version. */
        int compareToNull();

        int compareTo(Item other);

        /** Equal to "nothing": a trailing zero, an empty qualifier, an empty sub-list. */
        default boolean isNull() {
            return compareToNull() == 0;
        }

        String canonical();
    }

    private static final class IntItem implements Item {
        static final IntItem ZERO = new IntItem("0");

        private final BigInteger value;

        IntItem(String digits) {
            this.value = new BigInteger(digits);
        }

        @Override
        public int compareToNull() {
            return value.signum() == 0 ? 0 : 1;
        }

        @Override
        public int compareTo(Item other) {
            return switch (other) {
                case IntItem n -> value.compareTo(n.value);
                case StringItem s -> 1; // a number beats any qualifier: 1.1 > 1-alpha, 33.0.0 > r09
                case ListItem l -> 1; // 1.1 > 1-1
            };
        }

        @Override
        public String canonical() {
            return value.toString();
        }
    }

    private static final class StringItem implements Item {
        private final String qualifier;

        StringItem(String text, boolean followedByDigit) {
            String q = text;
            if (followedByDigit && q.length() == 1) {
                q = switch (q) {
                    case "a" -> "alpha";
                    case "b" -> "beta";
                    case "m" -> "milestone";
                    default -> q;
                };
            }
            q = switch (q) {
                case "ga", "final", "release" -> "";
                case "cr" -> "rc";
                default -> q;
            };
            this.qualifier = q;
        }

        /**
         * Known qualifiers map to their index; an unknown one sorts after all of them, by its text — which is
         * what puts {@code -jre} and {@code -android} above {@code -rc} and below nothing numeric.
         */
        private static String comparable(String q) {
            int i = QUALIFIERS.indexOf(q);
            return i < 0 ? QUALIFIERS.size() + "-" + q : String.valueOf(i);
        }

        @Override
        public int compareToNull() {
            return comparable(qualifier).compareTo(RELEASE_INDEX);
        }

        @Override
        public int compareTo(Item other) {
            return switch (other) {
                case IntItem n -> -1; // 1.any < 1.1
                case StringItem s -> comparable(qualifier).compareTo(comparable(s.qualifier));
                case ListItem l -> -1; // 1.any < 1-1
            };
        }

        @Override
        public String canonical() {
            return comparable(qualifier);
        }
    }

    private static final class ListItem implements Item {
        private final List<Item> items = new ArrayList<>(4);

        void add(Item item) {
            items.add(item);
        }

        ListItem openSubList() {
            ListItem sub = new ListItem();
            items.add(sub);
            return sub;
        }

        /** Drops the trailing "nothing" items: {@code 1.0.0} becomes {@code 1}, {@code 1.0-ga} becomes {@code 1}. */
        void normalize() {
            for (int i = items.size() - 1; i >= 0; i--) {
                Item last = items.get(i);
                if (last.isNull()) {
                    items.remove(i);
                } else if (!(last instanceof ListItem)) {
                    break;
                }
            }
        }

        @Override
        public int compareToNull() {
            // The whole list against nothing, not just its head (Maven's MNG-6964): 1-0.1 is above 1.
            for (Item item : items) {
                int cmp = item.compareToNull();
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
        }

        @Override
        public int compareTo(Item other) {
            return switch (other) {
                case IntItem n -> -1; // 1-1 < 1.0.x
                case StringItem s -> 1; // 1-1 > 1-sp
                case ListItem l -> {
                    int n = Math.max(items.size(), l.items.size());
                    for (int i = 0; i < n; i++) {
                        Item left = i < items.size() ? items.get(i) : null;
                        Item right = i < l.items.size() ? l.items.get(i) : null;
                        int cmp = left == null
                                ? (right == null ? 0 : -right.compareToNull())
                                : (right == null ? left.compareToNull() : left.compareTo(right));
                        if (cmp != 0) {
                            yield cmp;
                        }
                    }
                    yield 0;
                }
            };
        }

        @Override
        public String canonical() {
            StringBuilder sb = new StringBuilder("[");
            for (Item item : items) {
                sb.append(item.canonical()).append(',');
            }
            return sb.append(']').toString();
        }
    }
}
