package com.editora.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javafx.collections.ObservableList;

/**
 * Brings a list of child nodes in line with a fresh listing while keeping the node of every entry that is
 * still there. Replacing the children wholesale costs a node per entry on the FX thread and throws away what
 * hangs off them (expansion, selection, the scroll anchor); a directory re-listed because one file appeared
 * should cost one insertion. Pure apart from the observable list it edits.
 */
final class ChildReconciler {

    /** More differences than this are applied as one {@code setAll} rather than as that many list events. */
    static final int BULK_THRESHOLD = 64;

    private ChildReconciler() {}

    /**
     * Edits {@code children} so that its keys equal {@code desired}, in order, reusing existing elements.
     *
     * @return whether anything changed
     */
    static <T, K> boolean reconcile(
            ObservableList<T> children, List<K> desired, Function<T, K> keyOf, Function<K, T> create) {
        // Both listings are in the same order, so what did not change is a common head and a common tail;
        // only the window between them needs anything more than a comparison per row.
        int n = children.size();
        int m = desired.size();
        int head = 0;
        while (head < n && head < m && desired.get(head).equals(keyOf.apply(children.get(head)))) {
            head++;
        }
        if (head == n && head == m) {
            return false;
        }
        int tail = 0;
        while (tail < n - head
                && tail < m - head
                && desired.get(m - 1 - tail).equals(keyOf.apply(children.get(n - 1 - tail)))) {
            tail++;
        }
        List<K> wantedWindow = desired.subList(head, m - tail);
        int stale = n - tail - head;
        if (stale == 0) {
            children.addAll(head, createAll(wantedWindow, create)); // pure insertion: one list event
            return true;
        }
        if (wantedWindow.isEmpty()) {
            children.remove(head, n - tail); // pure removal
            return true;
        }
        Map<K, T> existing = new HashMap<>(stale * 2);
        for (int i = head; i < n - tail; i++) {
            T child = children.get(i);
            existing.put(keyOf.apply(child), child);
        }
        Set<K> wanted = new HashSet<>(wantedWindow);
        List<T> gone = new ArrayList<>();
        for (int i = head; i < n - tail; i++) {
            T child = children.get(i);
            if (!wanted.contains(keyOf.apply(child))) {
                gone.add(child);
            }
        }
        int added = 0;
        for (K key : wanted) {
            if (!existing.containsKey(key)) {
                added++;
            }
        }
        if (gone.size() + added <= BULK_THRESHOLD && wanted.size() == wantedWindow.size()) {
            if (!gone.isEmpty()) {
                children.removeAll(gone);
            }
            boolean inOrder = true;
            for (int i = 0; i < wantedWindow.size() && inOrder; i++) {
                K key = wantedWindow.get(i);
                int at = head + i;
                if (at < children.size() - tail && key.equals(keyOf.apply(children.get(at)))) {
                    continue;
                }
                if (existing.containsKey(key)) {
                    inOrder = false; // a survivor moved: the listing order changed under it
                } else {
                    T made = create.apply(key);
                    existing.put(key, made);
                    children.add(at, made);
                }
            }
            if (inOrder && children.size() == m) {
                return true;
            }
        }
        // Too different to patch row by row (a checkout), or reordered: one replacement, same nodes.
        List<T> all = new ArrayList<>(m);
        for (int i = 0; i < head; i++) {
            all.add(children.get(i));
        }
        for (K key : wantedWindow) {
            T kept = existing.remove(key); // remove: a duplicate key must not place one node twice
            all.add(kept != null ? kept : create.apply(key));
        }
        int size = children.size();
        for (int i = size - tail; i < size; i++) {
            all.add(children.get(i));
        }
        children.setAll(all);
        return true;
    }

    private static <T, K> List<T> createAll(List<K> keys, Function<K, T> create) {
        List<T> made = new ArrayList<>(keys.size());
        for (K key : keys) {
            made.add(create.apply(key));
        }
        return made;
    }
}
