package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Dart's outline comes from the declaration scanner: the Dart grammar scopes every call before {@code (} as a
 * function name and names no class, so its own symbols listed {@code setState} and left every type out.
 */
class StructureOutlineDartTest {

    @BeforeAll
    static void messages() {
        com.editora.i18n.Messages.init("en");
    }

    private static List<String> outline(String text) {
        List<String> out = new ArrayList<>();
        dump(
                StructureOutline.build(
                        StructureOutline.Source.file("home.dart", text).analyzed()),
                "",
                out);
        return out;
    }

    private static void dump(List<StructureNode> nodes, String indent, List<String> out) {
        for (StructureNode n : nodes) {
            out.add(indent + n.kind() + " " + n.label() + " @" + (n.line() + 1));
            dump(n.children(), indent + "  ", out);
        }
    }

    @Test
    void classesAndMembersNotCalls() {
        String src = """
                class Counter extends StatefulWidget {
                  const Counter({super.key});

                  @override
                  State<Counter> createState() => _CounterState();
                }

                class _CounterState extends State<Counter> {
                  int _count = 0;

                  void _increment() {
                    setState(() {
                      _count++;
                    });
                  }

                  @override
                  Widget build(BuildContext context) {
                    return Text('$_count');
                  }
                }

                void main() {
                  runApp(const Counter());
                }
                """;
        assertEquals(
                List.of(
                        "class Counter @1",
                        "  method Counter @2",
                        "  method createState @5",
                        "class _CounterState @8",
                        "  method _increment @11",
                        "  method build @18",
                        "function main @23"),
                outline(src));
    }
}
