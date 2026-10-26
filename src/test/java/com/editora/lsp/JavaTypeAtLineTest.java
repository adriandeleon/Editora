package com.editora.lsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The type a line of a library class is in: a nested class's rows are listed by its own name (fifth BN4). */
class JavaTypeAtLineTest {

    private static final String SOURCE = """
            package java.util;
            /** A list. { not a brace } "class Fake {" */
            public abstract class AbstractList<E> implements List<E> {
                public boolean add(E e) { return true; }
                private class Itr implements Iterator<E> {
                    String s = "}";
                    public void remove() { new Runnable() { public void run() {} }; }
                }
                Class<?> c = AbstractList.class;
                private static class SubList<E> extends AbstractList<E> {
                    public void add(int i, E e) {}
                }
                void after() {}
            }
            """;

    @Test
    void eachLineIsNamedForTheTypeItIsIn() {
        assertNull(JavaTypeAtLine.at(SOURCE, 0));
        assertEquals("AbstractList", JavaTypeAtLine.at(SOURCE, 3));
        assertEquals("AbstractList.Itr", JavaTypeAtLine.at(SOURCE, 6), "an anonymous class is not named");
        assertEquals("AbstractList.SubList", JavaTypeAtLine.at(SOURCE, 10));
        assertEquals("AbstractList", JavaTypeAtLine.at(SOURCE, 12), "back out of the nested class");
    }
}
