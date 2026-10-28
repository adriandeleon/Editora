package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.application.Platform;

import com.editora.editor.EditorBuffer;
import com.editora.maven.MalformedPoms;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What the window does around the Maven pom server beyond starting it: where it may run ({@link
 * #pomServerRunsFor}), and keeping its coordinate completion alive when a pom elsewhere in the project is malformed
 * (review 2, E3). lemminx-maven answers the first {@code groupId}/{@code artifactId}/{@code version} completion, then
 * only {@code <!--} and {@code <![CDATA[} for every pom while any pom of its workspace is not well-formed XML: its
 * workspace list keeps the failed build's null project, and the completion throws on it (lemminx-maven 0.12.0,
 * {@code MavenLemminxExtension.getCurrentWorkspaceProjects}). On such an answer the project is searched (off the FX
 * thread, at most once every few seconds per root), the malformed poms are taken out of the server's workspace, the
 * completion is asked again, and the status bar names the file. Saving such a pom well-formed restarts the server,
 * which takes it back.
 */
final class PomServerSupport {

    /** The elements whose text the Maven server completes from the local repository. */
    private static final Set<String> COORDINATES = Set.of("groupId", "artifactId", "version");

    /** The element whose text the caret is in: the last start tag before it on the line, with no tag after it. */
    private static final Pattern OPEN_ELEMENT = Pattern.compile("<([A-Za-z][\\w.-]*)>[^<]*$");

    /** How long one search's answer stands for a root before a degraded completion searches again. */
    static final long RECHECK_MILLIS = 5_000;

    private final Consumer<String> status;
    private @Nullable Path lastRoot;
    private long lastCheck;

    PomServerSupport(Consumer<String> status) {
        this.status = status;
    }

    /**
     * The coordinate element ({@code groupId}, {@code artifactId}, {@code version}) whose text holds the caret at
     * {@code column} of {@code line}, or null. Pure.
     */
    static @Nullable String coordinateElement(@Nullable String line, int column) {
        if (line == null) {
            return null;
        }
        String before = line.substring(0, Math.max(0, Math.min(column, line.length())));
        Matcher m = OPEN_ELEMENT.matcher(before);
        return m.find() && COORDINATES.contains(m.group(1)) ? m.group(1) : null;
    }

    /** Whether a completion at a coordinate offers nothing but XML markup ({@code <!--}, {@code <![CDATA[}). Pure. */
    static boolean onlyMarkup(List<String> labels) {
        for (String label : labels) {
            if (label != null && !label.startsWith("<")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Hears one Maven-server completion answered at a coordinate ({@code element} non-null). When it holds only
     * markup, searches {@code root} for malformed poms, hands them to {@code leaveOut} (which returns the ones newly
     * taken out of the server's workspace) and runs {@code askAgain} — or {@code deliver}, the answer as it came, when
     * none was. {@code openPoms} (asked on this thread, only then) gives the open poms' unsaved text by absolute path:
     * what the server sees for them. Returns whether it took the answer over; when false the caller delivers it.
     */
    boolean onCompletion(
            @Nullable String element,
            List<String> labels,
            @Nullable Path root,
            Supplier<Map<Path, String>> openPoms,
            Function<List<Path>, List<Path>> leaveOut,
            Runnable askAgain,
            Runnable deliver) {
        if (element == null || root == null || !onlyMarkup(labels)) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (root.equals(lastRoot) && now - lastCheck < RECHECK_MILLIS) {
            return false;
        }
        lastRoot = root;
        lastCheck = now;
        Map<Path, String> open = Map.copyOf(openPoms.get());
        Thread.ofVirtual().name("pom-wellformed").start(() -> {
            List<MalformedPoms.Problem> problems = MalformedPoms.allIn(
                    root, file -> open.get(file.toAbsolutePath().normalize()));
            Platform.runLater(() -> {
                if (problems.isEmpty()) {
                    deliver.run();
                    return;
                }
                List<Path> out = leaveOut.apply(
                        problems.stream().map(MalformedPoms.Problem::file).toList());
                MalformedPoms.Problem first = problems.get(0);
                String where = displayPath(root, first.file());
                String line = String.valueOf(Math.max(1, first.line()));
                if (out.isEmpty()) {
                    status.accept(tr("status.maven.completionMalformed", where, line));
                    deliver.run();
                    return;
                }
                status.accept(
                        problems.size() == 1
                                ? tr("status.maven.completionLeftOut", where, line)
                                : tr("status.maven.completionLeftOutMore", where, line, problems.size() - 1));
                askAgain.run();
            });
        });
        return true;
    }

    /**
     * Whether saving {@code pom} as {@code text} should restart its Maven server: it was left out of the server's
     * workspace ({@code leftOut}) and is well-formed now. lemminx-maven cannot take it back in the same session.
     */
    static boolean wellFormedAgain(boolean leftOut, Path pom, String text) {
        return leftOut && MalformedPoms.check(pom, text) == null;
    }

    /** {@code file} relative to {@code root} when it lies under it, else its full path. Pure. */
    static String displayPath(Path root, Path file) {
        try {
            Path rel = root.toAbsolutePath()
                    .normalize()
                    .relativize(file.toAbsolutePath().normalize());
            return rel.toString().isEmpty() || rel.startsWith("..") ? file.toString() : rel.toString();
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    /** Every open, loaded pom's text by absolute path (FX thread). */
    static Map<Path, String> openPoms(Consumer<Consumer<EditorBuffer>> forEachBuffer) {
        Map<Path, String> out = new java.util.HashMap<>();
        forEachBuffer.accept(b -> {
            Path p = b.getPath();
            if (p != null && b.isPom() && !b.isLoading()) {
                out.put(p.toAbsolutePath().normalize(), b.getContent());
            }
        });
        return out;
    }

    /**
     * Whether the Maven pom server may serve {@code file}: always here; on a remote host (SSH, WSL, a container) only
     * with a command that host can run — {@code lsp.maven-pom} in its tools, or a bare command loading nothing from
     * this machine. The local {@code java -cp "<config>/plugins/lsp/maven/*"} ran there with a classpath that exists
     * only here and crash-looped (review 2, A5); without one the host's plain lemminx serves the pom.
     */
    static boolean pomServerRunsFor(@Nullable Path file, @Nullable String localCommand) {
        String pom = com.editora.lsp.LspServerRegistry.MAVEN_POM_SERVER_ID;
        return com.editora.process.ExecTargets.runsHere(file)
                || !com.editora.lsp.LspServerRegistry.remoteCommandFor(
                                pom, localCommand, com.editora.process.ExecTargets.remoteTool(file, "lsp." + pom))
                        .isEmpty();
    }
}
