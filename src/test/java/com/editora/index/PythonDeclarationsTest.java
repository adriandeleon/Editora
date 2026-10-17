package com.editora.index;

import java.util.List;

import com.editora.editops.StringCase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Go to Symbol without a language server in Python, and case cycling on Python names (finding A12). */
class PythonDeclarationsTest {

    private static List<String> symbols(String src) {
        return DeclarationScanner.scan(src, "python").stream()
                .map(s -> (s.container().isEmpty() ? "" : s.container() + ".") + s.name() + ":" + s.kind())
                .toList();
    }

    @Test
    void methodsBelongToTheirClassAndAliasesAreTypes() {
        String src = """
                \"\"\"Module.

                class NotAClass:
                \"\"\"
                type Vector = list[float]
                type Pair[T] = tuple[T, T]
                Json: TypeAlias = dict[str, object]
                MAX_SIZE = 10

                class Point:
                    \"\"\"A point.

                def not_a_method(): (in the docstring)
                \"\"\"
                    def norm(self):
                        return 1

                    class Inner:
                        def deep(self): ...

                    async def fetch(self): ...

                def helper():
                    pass
                """;
        assertEquals(
                List.of(
                        "Vector:TYPE",
                        "Pair:TYPE",
                        "Json:TYPE",
                        "MAX_SIZE:VARIABLE",
                        "Point:TYPE",
                        "Point.norm:METHOD",
                        "Point.Inner:TYPE",
                        "Inner.deep:METHOD",
                        "Point.fetch:METHOD",
                        "helper:FUNCTION"),
                symbols(src));
    }

    @Test
    void caseCyclingKeepsPythonNamesValid() {
        // A12: MAX_SIZE used to cycle to max-size, which is not a Python name.
        assertEquals("MaxSize", StringCase.cycle("MAX_SIZE", "python"));
        assertEquals(
                "max-size", StringCase.cycle("MAX_SIZE", "css"), "kebab-case stays in the cycle where it is a name");
        assertEquals("MY_VAR_NAME", StringCase.cycle("my_var_name", "python"));
    }
}
