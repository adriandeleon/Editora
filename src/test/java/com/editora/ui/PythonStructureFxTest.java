package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;

import com.editora.editor.EditorBuffer;
import com.editora.editor.highlight.GrammarRegistry;
import com.editora.editor.highlight.TextMateHighlighter;
import com.editora.text.FoldRegions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A Python file's Structure without a language server: nested by indentation even now that Python has fold
 * regions (a one-line {@code def} folds nothing but is still structure), and return annotations shown without
 * the body's {@code :} (review findings A3, B13).
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PythonStructureFxTest {

    private static final String SOURCE = String.join(
            "\n",
            "class Greeter:",
            "    def __init__(self, name: str) -> None:",
            "        self.name = name",
            "",
            "    def upper(self) -> str: return self.name.upper()",
            "",
            "    def make(self) -> \"Greeter\":",
            "        return self",
            "",
            "",
            "def table() -> dict[str, int]:  # counts",
            "    return {}",
            "");

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void methodsNestUnderTheirClassWithTheirReturnTypes() throws Exception {
        assertEquals(
                List.of(
                        "Greeter",
                        "  __init__() : None",
                        "  upper() : str",
                        "  make() : \"Greeter\"",
                        "table() : dict[str, int]"),
                rows(SOURCE));
    }

    @Test
    void constantsFieldsAndDefKindsShowWithoutAServer() throws Exception {
        // Review 2 C113 (constants, fields, async/property markers), A105 (`def f` read "de" as a return type),
        // A104 (a regex named group listed as a symbol).
        String source = String.join(
                "\n",
                "import re",
                "MAX_SIDES = 6",
                "_registry: dict[str, int] = {}",
                "PATTERN = re.compile(r\"(?P<year>\\d{4})\")",
                "",
                "class Shape:",
                "    radius: float",
                "    sides = 3",
                "",
                "    @property",
                "    def area(self) -> float:",
                "        local = 1",
                "        return local",
                "",
                "    @classmethod",
                "    def make(cls): ...",
                "",
                "    async def fetch(self): ...",
                "",
                "    class Meta:",
                "        ordering = 1",
                "",
                "    tail = 0",
                "",
                "def f(match, case):",
                "    pass",
                "");
        assertEquals(
                List.of(
                        "MAX_SIDES",
                        "_registry",
                        "PATTERN",
                        "Shape",
                        "  radius",
                        "  sides",
                        "  area() : float  property",
                        "  make()  classmethod",
                        "  fetch()  async",
                        "  Meta",
                        "    ordering",
                        "  tail",
                        "f()"),
                rows(source));
    }

    private static List<String> rows(String source) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StructurePanel p = new StructurePanel();
            new Scene(new StackPane(p), 600, 400);
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("python");
            b.setContent(source);
            var field = EditorBuffer.class.getDeclaredField("symbols");
            field.setAccessible(true);
            field.set(
                    b,
                    TextMateHighlighter.analyze(source, GrammarRegistry.shared().forLanguageName("python"))
                            .symbols());
            b.getFoldManager().setServerRegions(FoldRegions.detect(source, "python"));
            p.attach(b);
            @SuppressWarnings("unchecked")
            TreeView<Object> tree = (TreeView<Object>) FxTestSupport.<TreeView<?>>field(p, "tree");
            List<String> out = new ArrayList<>();
            collect(tree, tree.getRoot(), "", out);
            return out;
        });
    }

    private static void collect(TreeView<Object> tree, TreeItem<Object> parent, String indent, List<String> out) {
        for (TreeItem<Object> item : parent.getChildren()) {
            @SuppressWarnings("unchecked")
            TreeCell<Object> cell = (TreeCell<Object>) tree.getCellFactory().call(tree);
            Object value = item.getValue();
            FxTestSupport.call(cell, "updateItem", new Class<?>[] {value.getClass(), boolean.class}, value, false);
            StringBuilder sb = new StringBuilder();
            for (var n : ((HBox) cell.getGraphic()).getChildren()) {
                if (n instanceof Text t && !t.getStyleClass().contains("structure-line-number")) {
                    sb.append(t.getText());
                }
            }
            out.add(indent + sb.toString().strip());
            collect(tree, item, indent + "  ", out);
        }
    }
}
