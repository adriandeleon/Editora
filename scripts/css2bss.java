import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javafx.css.CssParser;
import javafx.css.Stylesheet;

/**
 * Precompiles Editora's JavaFX stylesheets to the binary {@code .bss} form, beside each {@code .css}.
 *
 * <p>Run by the Maven build at {@code process-classes} (see the {@code exec-maven-plugin} execution
 * {@code css-to-bss} in {@code pom.xml}) against {@code target/classes/com/editora/styles}, so every
 * artifact built from the compiled classes — {@code javafx:run}, the test classpath, the jlink image
 * behind the installers, the fat jar — carries the binary form. JavaFX's {@code StyleManager} looks for
 * {@code name.bss} before parsing {@code name.css}, whatever a stylesheet was named by (a user-agent
 * stylesheet, a scene stylesheet, a node stylesheet), and falls back to the text when the binary is
 * missing or unreadable; a relative {@code url()} inside the sheet resolves against the {@code .bss}'s
 * own location, which is the {@code .css}'s. Nothing in the application names a {@code .bss} itself.
 *
 * <p>Usage: {@code java -cp <javafx.base:javafx.graphics> scripts/css2bss.java <styles-dir>}. Every
 * {@code .css} under the directory is converted, except {@code markdown-export.css}, which is HTML CSS
 * for exported pages, not a JavaFX stylesheet. A {@code .bss} newer than its {@code .css} is kept.
 * Parse errors are printed and fail the run: the text form would drop the same rules silently at
 * runtime, and a stylesheet that does not compile should not ship either way.
 */
public final class css2bss {

    /** Stylesheets under the styles directory that are not JavaFX CSS. */
    private static final List<String> NOT_JAVAFX = List.of("markdown-export.css");

    private css2bss() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: css2bss <styles-dir>");
            System.exit(2);
        }
        Path root = Path.of(args[0]);
        if (!Files.isDirectory(root)) {
            System.err.println("css2bss: not a directory: " + root);
            System.exit(2);
        }
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(root)) {
            sources = walk.filter(p -> p.getFileName().toString().endsWith(".css"))
                    .filter(p -> !NOT_JAVAFX.contains(p.getFileName().toString()))
                    .sorted()
                    .toList();
        }
        int converted = 0;
        List<String> failures = new ArrayList<>();
        for (Path css : sources) {
            Path bss = Path.of(css.toString().substring(0, css.toString().length() - 4) + ".bss");
            if (Files.exists(bss)
                    && Files.getLastModifiedTime(bss).compareTo(Files.getLastModifiedTime(css)) >= 0) {
                continue; // up to date
            }
            CssParser.errorsProperty().clear();
            Stylesheet.convertToBinary(css.toFile(), bss.toFile());
            if (!CssParser.errorsProperty().isEmpty()) {
                for (CssParser.ParseError e : CssParser.errorsProperty()) {
                    failures.add(root.relativize(css) + ": " + e.getMessage());
                }
                Files.deleteIfExists(bss); // never ship a binary of a sheet that did not parse cleanly
            }
            converted++;
        }
        if (!failures.isEmpty()) {
            System.err.println("css2bss: stylesheet errors:");
            failures.forEach(f -> System.err.println("  " + f));
            System.exit(1);
        }
        System.out.println("css2bss: " + converted + " stylesheet(s) compiled, " + (sources.size() - converted)
                + " up to date, under " + root);
    }
}
