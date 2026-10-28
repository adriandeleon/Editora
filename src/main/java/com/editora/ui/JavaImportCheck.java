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
            if (m.contains("is a non-project file") || m.contains("is not on the classpath of project ")) {
                return m; // also the build's own project, kept without source folders (thirteenth review CK2)
            }
        }
        return null;
    }

    /** Whether {@code build}'s build file names its own source folders, so its {@code src/main/java} may be none. */
    static boolean ownSources(Path build) {
        for (String name : List.of("pom.xml", "build.gradle", "build.gradle.kts")) {
            try {
                String text = Files.readString(build.resolve(name));
                if (text.contains("<sourceDirectory>") || text.contains("srcDir")) {
                    return true;
                }
            } catch (java.io.IOException | RuntimeException e) {
                // not this build file
            }
        }
        return false;
    }

    /** Whether {@code file} is under a Maven or Gradle source folder of {@code root}. */
    static boolean inSourceFolder(Path root, Path file) {
        String rel = "/" + root.relativize(file).toString().replace('\\', '/');
        return rel.contains("/src/main/java/") || rel.contains("/src/test/java/");
    }

    /**
     * Why a pom.xml does not parse: the parser's {@code message} without its closing period, and where, 1-based, or -1
     * when the parser did not say (twelfth review CL4).
     */
    record PomError(String message, int line, int column) {
        /** In English, for the Debug Log. */
        String describe() {
            return line < 1 ? message : message + " (line " + line + ", column " + column + ")";
        }
    }

    /**
     * Why the XML at {@code pom} does not parse, with its line and column, or null when it parses or is not there
     * (eleventh review CM4). An empty file does not parse.
     */
    static @Nullable PomError unreadablePom(Path pom) {
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
            return new PomError(withoutPeriod(e.getMessage()), e.getLineNumber(), e.getColumnNumber());
        } catch (Exception e) {
            return new PomError(withoutPeriod(e.getMessage()), -1, -1);
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

    private static String withoutPeriod(@Nullable String message) {
        return String.valueOf(message).strip().replaceFirst("\\.$", "");
    }

    /** What a script error says is wrong, the first of several: a parse error's place, an unknown name. */
    private static final java.util.regex.Pattern SPECIFIC_CAUSE = java.util.regex.Pattern.compile(
            "(?i)unexpected (input|character|token)|unresolved reference|expecting|@ line \\d+, column \\d+");

    /** A wrapper's or a lone cause's wording, the last of which is the nearest the cause. */
    private static final java.util.regex.Pattern GENERAL_CAUSE =
            java.util.regex.Pattern.compile("(?i)could not|cannot|not found");

    /** An absolute path in a message ({@code '/tmp/x/settings.gradle'}), whose file name is enough. */
    private static final java.util.regex.Pattern ABSOLUTE_PATH =
            java.util.regex.Pattern.compile("(?:[A-Za-z]:)?[\\\\/](?:[^'\"\\s\\\\/]+[\\\\/])+([^'\"\\s\\\\/]+)");

    /**
     * The line of a failed import's message that names the cause: Gradle ends a script that does not compile with
     * "1 error" under a caret line, so the line saying what is wrong ("Unexpected input", "Unresolved reference"),
     * with its "Line N:" when Kotlin gives one, beats the last line (eleventh review CM3). The first such line, the
     * one Problems lists, and a script's path as its file name (twelfth review CM3).
     */
    static @Nullable String reasonLine(String message) {
        List<String> lines = message.lines()
                .map(String::strip)
                .filter(l -> !l.isEmpty())
                .map(l -> ABSOLUTE_PATH.matcher(l).replaceAll("$1"))
                .toList();
        if (lines.isEmpty()) {
            return null;
        }
        for (int i = 0; i < lines.size(); i++) {
            if (SPECIFIC_CAUSE.matcher(lines.get(i)).find()) {
                return withLine(lines, i);
            }
        }
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (GENERAL_CAUSE.matcher(lines.get(i)).find()) {
                return withLine(lines, i);
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

    /** Line {@code i} of {@code lines} without a caret, after the Kotlin "Line N:" of the line before it. */
    private static String withLine(List<String> lines, int i) {
        String text = lines.get(i).replaceFirst("^\\^\\s*", "");
        java.util.regex.Matcher at =
                i > 0 ? java.util.regex.Pattern.compile("^(Line \\d+):").matcher(lines.get(i - 1)) : null;
        return at != null && at.find() && !text.startsWith("Line ") ? at.group(1) + ": " + text : text;
    }
}
