package com.editora.run;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainMethodScannerTest {

    @Test
    void findsMainWithPackage() {
        String src = """
                package com.app;
                public class Main {
                    public static void main(String[] args) {
                        System.out.println("hi");
                    }
                }
                """;
        List<MainMethodScanner.MainMethod> found = MainMethodScanner.scan(src);
        assertEquals(1, found.size());
        assertEquals("com.app.Main", found.get(0).fqn());
        assertEquals(2, found.get(0).line());
    }

    @Test
    void noPackage() {
        String src = "class App {\n  static public void main(String... a) {}\n}\n";
        List<MainMethodScanner.MainMethod> found = MainMethodScanner.scan(src);
        assertEquals(1, found.size());
        assertEquals("App", found.get(0).fqn());
        assertEquals(1, found.get(0).line());
    }

    @Test
    void cStyleArrayParam() {
        String src = "class A {\n  public static void main(String args[]) {}\n}\n";
        assertEquals(List.of(new MainMethodScanner.MainMethod(1, "A")), MainMethodScanner.scan(src));
    }

    @Test
    void ignoresNonStaticOrWrongSignature() {
        String src = """
                class A {
                    public void main(String[] a) {}
                    static void main() {}
                    static void mainish(String[] a) {}
                    void run(String[] a) {}
                }
                """;
        assertTrue(MainMethodScanner.scan(src).isEmpty());
    }

    @Test
    void ignoresMainInStringOrComment() {
        String src = """
                class A {
                    // public static void main(String[] a) {}
                    String s = "public static void main(String[] a)";
                }
                """;
        assertTrue(MainMethodScanner.scan(src).isEmpty());
    }

    @Test
    void ignoresNestedClassMain() {
        String src = """
                class Outer {
                    static class Inner {
                        public static void main(String[] a) {}
                    }
                }
                """;
        // main is at depth 2 (inside Inner) — not reported.
        assertTrue(MainMethodScanner.scan(src).isEmpty());
    }

    @Test
    void twoTopLevelClassesEachWithMain() {
        String src = """
                package p;
                class A {
                    public static void main(String[] a) {}
                }
                class B {
                    public static void main(String[] a) {}
                }
                """;
        List<MainMethodScanner.MainMethod> found = MainMethodScanner.scan(src);
        assertEquals(2, found.size());
        assertEquals("p.A", found.get(0).fqn());
        assertEquals("p.B", found.get(1).fqn());
    }

    @Test
    void emptyAndNull() {
        assertTrue(MainMethodScanner.scan(null).isEmpty());
        assertTrue(MainMethodScanner.scan("   ").isEmpty());
    }
}
