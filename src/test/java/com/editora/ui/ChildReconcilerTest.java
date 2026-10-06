package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A re-listed directory keeps the node of every entry that is still there and creates only the new ones. */
class ChildReconcilerTest {

    /** A stand-in tree node: identity matters, the key is its name. */
    private static final class Node {
        final String name;

        Node(String name) {
            this.name = name;
        }
    }

    private final List<String> created = new ArrayList<>();
    private final Function<String, Node> create = name -> {
        created.add(name);
        return new Node(name);
    };

    private static ObservableList<Node> nodes(String... names) {
        ObservableList<Node> list = FXCollections.observableArrayList();
        for (String name : names) {
            list.add(new Node(name));
        }
        return list;
    }

    private static List<String> names(List<Node> nodes) {
        return nodes.stream().map(n -> n.name).toList();
    }

    @Test
    void anUnchangedListingTouchesNothing() {
        ObservableList<Node> children = nodes("a", "b", "c");
        int[] events = {0};
        children.addListener((ListChangeListener<Node>) c -> events[0]++);

        assertFalse(ChildReconciler.reconcile(children, List.of("a", "b", "c"), n -> n.name, create));

        assertEquals(0, events[0]);
        assertEquals(List.of(), created);
    }

    @Test
    void oneNewEntryIsOneInsertionAndEveryOtherNodeSurvives() {
        ObservableList<Node> children = nodes("a", "c", "d");
        List<Node> before = List.copyOf(children);
        int[] events = {0};
        children.addListener((ListChangeListener<Node>) c -> events[0]++);

        assertTrue(ChildReconciler.reconcile(children, List.of("a", "b", "c", "d"), n -> n.name, create));

        assertEquals(List.of("a", "b", "c", "d"), names(children));
        assertEquals(List.of("b"), created);
        assertEquals(1, events[0]);
        assertSame(before.get(0), children.get(0));
        assertSame(before.get(1), children.get(2));
        assertSame(before.get(2), children.get(3));
    }

    @Test
    void removedEntriesGoAndTheRestKeepTheirNodes() {
        ObservableList<Node> children = nodes("a", "b", "c", "d");
        Node a = children.get(0);
        Node d = children.get(3);

        assertTrue(ChildReconciler.reconcile(children, List.of("a", "d", "e"), n -> n.name, create));

        assertEquals(List.of("a", "d", "e"), names(children));
        assertSame(a, children.get(0));
        assertSame(d, children.get(1));
        assertEquals(List.of("e"), created);
    }

    @Test
    void aReorderedListingStillReusesTheNodes() {
        ObservableList<Node> children = nodes("a", "b", "c");
        Node a = children.get(0);
        Node c = children.get(2);

        assertTrue(ChildReconciler.reconcile(children, List.of("c", "b", "a"), n -> n.name, create));

        assertEquals(List.of("c", "b", "a"), names(children));
        assertSame(c, children.get(0));
        assertSame(a, children.get(2));
        assertEquals(List.of(), created);
    }

    @Test
    void aLargeDifferenceIsAppliedAsOneChange() {
        ObservableList<Node> children = nodes("keep");
        Node keep = children.get(0);
        List<String> desired = new ArrayList<>();
        for (int i = 0; i < ChildReconciler.BULK_THRESHOLD * 4; i++) {
            desired.add("f" + i);
        }
        desired.add("keep");
        int[] events = {0};
        children.addListener((ListChangeListener<Node>) c -> events[0]++);

        assertTrue(ChildReconciler.reconcile(children, desired, n -> n.name, create));

        assertEquals(desired, names(children));
        assertEquals(1, events[0], "a checkout that adds hundreds of files is one list event, not hundreds");
        assertSame(keep, children.get(children.size() - 1));
    }

    @Test
    void anEmptiedDirectoryLosesItsRows() {
        ObservableList<Node> children = nodes("a", "b");
        assertTrue(ChildReconciler.reconcile(children, List.of(), n -> n.name, create));
        assertTrue(children.isEmpty());
    }
}
