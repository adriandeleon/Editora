package nativeimage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import lib.Json;
import lib.Repo;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Generate only main.fxml's named injection fields and no-argument event handlers.
 *
 * <p>This is intentionally not a general Java/FXML parser. Fail if a handler changes signature; review
 * and extend the generator rather than registering the controller's entire reflection surface.
 *
 * <pre>
 *   java scripts/nativeimage/FxmlMetadata.java           regenerate
 *   java scripts/nativeimage/FxmlMetadata.java --check   fail on drift
 * </pre>
 */
public final class FxmlMetadata {

    private static final String FX = "http://javafx.com/fxml";
    private static final String MODIFIER = "(?:(?:private|protected|public)\\s+)?";
    private static final String REACHED = "javafx.fxml.FXMLLoader";

    private FxmlMetadata() {}

    static Path fxml() {
        return Repo.root().resolve("src/main/resources/com/editora/ui/main.fxml");
    }

    static Path dest() {
        return Repo.root()
                .resolve("src/native/resources/META-INF/native-image/com.editora/fxml/reachability-metadata.json");
    }

    static Map<String, Object> generate() throws IOException {
        Element root;
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            root = factory.newDocumentBuilder().parse(fxml().toFile()).getDocumentElement();
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("cannot parse " + fxml(), e);
        }
        String controller = root.getAttributeNS(FX, "controller");
        String source = TextFiles.read(
                Repo.root().resolve("src/main/java").resolve(controller.replace('.', '/') + ".java"));
        var fields = new TreeSet<String>();
        var methods = new TreeSet<String>();
        // The access modifier is not part of what reflection needs: handlers may be private or package-private.
        NodeList nodes = root.getOwnerDocument().getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            var node = (Element) nodes.item(i);
            if (node.hasAttributeNS(FX, "id")) {
                String name = node.getAttributeNS(FX, "id");
                if (!Pattern.compile("@FXML\\s+" + MODIFIER + "[\\w.<>]+\\s+" + Pattern.quote(name) + "\\s*;")
                        .matcher(source)
                        .find()) {
                    throw new IllegalArgumentException("Review changed injection field: " + name);
                }
                fields.add(name);
            }
            NamedNodeMap attributes = node.getAttributes();
            for (int a = 0; a < attributes.getLength(); a++) {
                String value = attributes.item(a).getNodeValue();
                if (value.startsWith("#")) {
                    String name = value.substring(1);
                    if (!Pattern.compile("@FXML\\s+" + MODIFIER + "void\\s+" + Pattern.quote(name) + "\\s*\\(\\s*\\)")
                            .matcher(source)
                            .find()) {
                        throw new IllegalArgumentException("Review changed event signature: " + name);
                    }
                    methods.add(name);
                }
            }
        }
        var controllerEntry = entry(controller);
        controllerEntry.put("fields", fields.stream().map(name -> Map.of("name", name)).toList());
        var controllerMethods = new ArrayList<Object>();
        controllerMethods.add(method("<init>"));
        methods.forEach(name -> controllerMethods.add(method(name)));
        controllerEntry.put("methods", controllerMethods);
        var reflection = new ArrayList<Object>();
        reflection.add(controllerEntry);
        // These FXML element types are looked up from import processing, then populated through beans.
        // Application controllers remain restricted to exact named members above.
        var imports = new TreeSet<String>();
        Matcher imported = Pattern.compile("<\\?import ([\\w.]+)\\?>").matcher(TextFiles.read(fxml()));
        while (imported.find()) {
            imports.add(imported.group(1));
        }
        for (String name : imports) {
            var element = entry(name);
            element.put("allPublicMethods", true);
            element.put("methods", List.of(method("<init>")));
            reflection.add(element);
        }
        // BeanAdapter walks declared methods on layout/control superclasses of the
        // imported nodes, even though those superclasses are not FXML elements.
        for (String name : List.of(
                "javafx.scene.Node",
                "javafx.scene.Parent",
                "javafx.scene.layout.Region",
                "javafx.scene.layout.Pane",
                "javafx.scene.control.Control",
                "javafx.scene.control.Labeled",
                "javafx.scene.control.ButtonBase",
                "javafx.scene.control.ComboBoxBase")) {
            var superclass = entry(name);
            superclass.put("queryAllDeclaredMethods", true);
            reflection.add(superclass);
        }
        return Map.of("reflection", reflection);
    }

    private static Map<String, Object> entry(String type) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("condition", Map.of("typeReached", REACHED));
        entry.put("type", type);
        return entry;
    }

    private static Map<String, Object> method(String name) {
        var method = new LinkedHashMap<String, Object>();
        method.put("name", name);
        method.put("parameterTypes", List.of());
        return method;
    }

    public static void main(String[] argv) throws IOException {
        var p = new Args(
                "FxmlMetadata.java [-h] [--check]",
                "Generate only main.fxml's named injection fields and no-argument event handlers.",
                argv,
                List.of(),
                List.of("--check"),
                List.of());
        String expected = Json.pretty(generate()) + "\n";
        Path dest = dest();
        if (p.flag("--check")) {
            if (!Files.exists(dest) || !TextFiles.read(dest).equals(expected)) {
                p.error("FXML metadata drift; regenerate and review the diff");
            }
        } else {
            Files.createDirectories(dest.getParent());
            TextFiles.write(dest, expected);
        }
    }
}
