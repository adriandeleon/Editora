package com.editora.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Decides which project buckets the Bookmarks / Personal Notes tool windows show, and in what order, when they
 * group their entries by project. Pure — no toolkit, unit-tested.
 *
 * <p>Order: <b>General</b> (the {@code ""} no-project bucket) first, then the <b>current</b> project, then —
 * only when "Show all projects" is on — every <b>other</b> project, alphabetical by display name. Empty buckets
 * are omitted. The current project, when it is the no-project session, coincides with General (shown once).
 */
final class ScopeGroups {

    private ScopeGroups() {}

    /** The no-project bucket key. */
    static final String GENERAL = "";

    /**
     * @param keysWithContent project keys whose bucket has at least one entry
     * @param currentKey      this window's project key ({@code ""} = general/no-project)
     * @param showAll         whether the "Show all projects" toggle is on
     * @param nameFor         maps a project key to its display name (for the alphabetical ordering of others)
     * @return the ordered, de-duplicated project keys to render as groups
     */
    static List<String> visibleKeys(
            Collection<String> keysWithContent, String currentKey, boolean showAll, Function<String, String> nameFor) {
        String cur = currentKey == null ? GENERAL : currentKey;
        Set<String> have = new HashSet<>(keysWithContent);
        List<String> out = new ArrayList<>();
        if (have.contains(GENERAL)) {
            out.add(GENERAL);
        }
        if (!cur.isEmpty() && have.contains(cur)) {
            out.add(cur);
        }
        if (showAll) {
            keysWithContent.stream()
                    .filter(k -> k != null && !k.isEmpty() && !k.equals(cur))
                    .sorted(Comparator.comparing(nameFor, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(k -> k))
                    .forEach(out::add);
        }
        return out;
    }
}
