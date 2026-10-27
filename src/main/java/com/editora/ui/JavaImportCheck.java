package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.editora.lsp.LspManager;
import com.editora.text.LspDiagnostic;
import org.jspecify.annotations.Nullable;

/**
 * What tells a Java project's import apart from its files' problems, read from text: jdtls's "non-project file"
 * warning, a build file that does not parse, and the line of a project diagnostic that says why an import failed.
 * {@link LspCoordinator}'s import verdict, apart for its size.
 */
final class JavaImportCheck {

    private JavaImportCheck() {}

    /** A project diagnostic's folder and the line that says why its import failed. */
    record Reason(Path folder, String line) {}

    /**
     * jdtls's warning that {@code diagnostics}' file is checked for syntax only because no project holds it: "is a
     * non-project file", or "is not on the classpath of project <folder>_<hash>", the invisible project jdtls makes
     * for a loose file opened first (eleventh review CO2); null when it is not there.
     */
    static @Nullable String nonProjectMessage(List<LspDiagnostic> diagnostics) {
        for (LspDiagnostic d : diagnostics) {
            String m = d.message();
            if (m.contains("is a non-project file")
                    || m.matches("(?s).*is not on the classpath of project \\S+_[0-9a-f]{8}\\b.*")) {
                return m;
            }
        }
        return null;
    }

    /** Whether {@code file} is under a Maven or Gradle source folder of {@code root}. */
    static boolean inSourceFolder(Path root, Path file) {
        String rel = "/" + root.relativize(file).toString().replace('\\', '/');
        return rel.contains("/src/main/java/") || rel.contains("/src/test/java/");
    }

    /**
     * Why the XML at {@code pom} does not parse, with its line and column, or null when it parses or is not there
     * (eleventh review CM4). An empty file does not parse.
     */
    static @Nullable String unreadablePom(Path pom) {
        if (!Files.isRegularFile(pom)) {
            return null;
        }
        try {
            javax.xml.parsers.SAXParserFactory spf = javax.xml.parsers.SAXParserFactory.newInstance();
            spf.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true); // Maven reads a DOCTYPE; no fetch
            spf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            spf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            spf.newSAXParser().parse(pom.toFile(), new org.xml.sax.helpers.DefaultHandler());
            return null;
        } catch (org.xml.sax.SAXParseException e) {
            return e.getMessage() + " (line " + e.getLineNumber() + ", column " + e.getColumnNumber() + ")";
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    /** The module folders {@code root}'s pom.xml lists, outside comments and profiles; empty when none. */
    static List<Path> mavenModules(Path root) {
        List<Path> out = new ArrayList<>();
        try {
            String pom = Files.readString(root.resolve("pom.xml"))
                    .replaceAll("(?s)<!--.*?-->", "")
                    .replaceAll("(?s)<profiles>.*?</profiles>", "");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("<module>\\s*([^<]+?)\\s*</module>")
                    .matcher(pom);
            while (m.find()) {
                out.add(root.resolve(m.group(1)).normalize());
            }
        } catch (java.io.IOException | RuntimeException e) {
            // no pom, or not readable: no modules
        }
        return out;
    }

    /**
     * Why {@code root}'s import failed, in the project's own words, from an error jdtls put on {@code root} or a folder
     * under it (a Gradle build beside a Maven one, eleventh review CM2): the line that names the cause, which beats the
     * generic wrapper its log says first (third review CT7). Null when there is none yet.
     */
    static @Nullable Reason projectReason(Map<Path, List<LspDiagnostic>> problems, @Nullable Path root) {
        if (root == null) {
            return null;
        }
        Path r = root.toAbsolutePath().normalize();
        for (Map.Entry<Path, List<LspDiagnostic>> e : problems.entrySet()) {
            Path p = e.getKey().toAbsolutePath().normalize();
            if (!LspManager.projectLevel(p) || !p.startsWith(r)) {
                continue;
            }
            for (LspDiagnostic d : e.getValue()) {
                if (d.severity() == LspDiagnostic.Severity.ERROR) {
                    String line = reasonLine(d.message());
                    if (line != null) {
                        return new Reason(Files.isDirectory(p) ? p : p.getParent() == null ? p : p.getParent(), line);
                    }
                }
            }
        }
        return null;
    }

    /**
     * The line of a failed import's message that names the cause: Gradle ends a script that does not compile with
     * "1 error" under a caret line, so the line saying what is wrong ("Unexpected input", "Unresolved reference"),
     * with its "Line N:" when Kotlin gives one, beats the last line (eleventh review CM3).
     */
    static @Nullable String reasonLine(String message) {
        List<String> lines =
                message.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        if (lines.isEmpty()) {
            return null;
        }
        java.util.regex.Pattern cause = java.util.regex.Pattern.compile(
                "(?i)unexpected input|unresolved reference|expecting|could not|cannot|unexpected token|not found");
        for (int i = lines.size() - 1; i >= 0; i--) {
            String l = lines.get(i);
            if (cause.matcher(l).find()) {
                String text = l.replaceFirst("^\\^\\s*", "");
                java.util.regex.Matcher at =
                        i > 0 ? java.util.regex.Pattern.compile("^(Line \\d+):").matcher(lines.get(i - 1)) : null;
                return at != null && at.find() && !text.startsWith("Line ") ? at.group(1) + ": " + text : text;
            }
        }
        for (int i = lines.size() - 1; i >= 0; i--) {
            String l = lines.get(i);
            if (!l.matches("\\d+ errors?|\\^.*|[-=*~]+")) {
                return l;
            }
        }
        return lines.get(lines.size() - 1);
    }
}
