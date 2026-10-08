package com.editora.template;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.editora.editorconfig.EditorConfigProperties;
import com.editora.editorconfig.EditorConfigTransform;
import com.editora.io.PathContainment;

/**
 * Turns a template plus the wizard's answers into files: first a {@link Plan} (every target path worked
 * out, contained and checked for an existing file — <em>nothing written</em>), then {@link #write}.
 *
 * <p>The two steps are separate so the caller can stop before the first byte: a path that leaves the
 * target folder refuses the whole template, and files that already exist are shown to the user, who
 * chooses between creating only the missing ones and cancelling. A write is never an overwrite
 * ({@code CREATE_NEW}). Touches the filesystem but no toolkit — unit-tested against a temp directory, and
 * meant to be called off the FX thread.
 */
public final class TemplateOutput {

    private TemplateOutput() {}

    /**
     * Everything a render needs besides the template.
     *
     * @param dir the folder the files go into (null only for {@link #untitled})
     * @param answers the wizard's answers, by variable name
     * @param projectName {@code ${projectName}} when the wizard did not ask for it; may be blank
     * @param projectRoot bounds the Java package inference ({@link NewFileContent#packageFor}); may be null
     * @param editorConfig the EditorConfig rules for a file about to be created; null = none
     */
    public record Request(
            Template template,
            Path dir,
            Map<String, String> answers,
            String author,
            String projectName,
            Path projectRoot,
            LocalDateTime now,
            Function<Path, EditorConfigProperties> editorConfig) {}

    /** One file to create: where, its name relative to the target folder, its text and caret offset. */
    public record Target(Path path, String relative, String text, int caret, boolean hasCursor, boolean exists) {}

    /**
     * What applying the template would do. {@code refusedPath} non-null means a path pattern did not stay
     * inside the folder and <b>nothing</b> may be written.
     */
    public record Plan(Path dir, List<Target> targets, String refusedPath) {

        public boolean refused() {
            return refusedPath != null;
        }

        /** The targets whose file is already there (they are kept, never overwritten). */
        public List<Target> existing() {
            return targets.stream().filter(Target::exists).toList();
        }

        /** The targets that would be created. */
        public List<Target> missing() {
            return targets.stream().filter(t -> !t.exists()).toList();
        }
    }

    /**
     * What a write did. {@code failedAt} non-null means it stopped there with {@code error}; everything in
     * {@code created} is on disk either way.
     */
    public record Outcome(Path dir, List<Target> created, List<Target> skipped, Target failedAt, String error) {

        public boolean failed() {
            return failedAt != null;
        }

        /** The file to open: the created one that marks {@code ${cursor}}, else the first created. */
        public Target primary() {
            for (Target t : created) {
                if (t.hasCursor()) {
                    return t;
                }
            }
            return created.isEmpty() ? null : created.get(0);
        }
    }

    /** A single-file template rendered for an unsaved buffer: the suggested name and the text. */
    public record Untitled(String fileName, TemplateEngine.Rendered rendered) {}

    /** Renders a single-file template with no folder: the name is a suggestion, nothing is written. */
    public static Untitled untitled(Request r) {
        Template t = r.template();
        String fileName = fileNameOf(r);
        TemplateEngine.Rendered rendered = TemplateEngine.render(t.body(), resolver(r, fileName, null, ""));
        return new Untitled(fileName, rendered);
    }

    /** Works out every file the template would create in {@code r.dir()}. Reads the disk, writes nothing. */
    public static Plan plan(Request r) {
        Template t = r.template();
        Path dir = r.dir().toAbsolutePath().normalize();
        List<Target> targets = new ArrayList<>();
        if (t.isMultiFile()) {
            TemplateVariableResolver vars = resolver(r, "", null, "");
            Set<Path> seen = new java.util.HashSet<>();
            for (TemplateFile f : t.files()) {
                Path target = TemplateEngine.resolveTargetPath(dir, f.path(), vars);
                if (target == null) {
                    return new Plan(dir, List.of(), TemplateEngine.expand(f.path(), vars));
                }
                if (!seen.add(target)) {
                    continue; // two entries expanding to one path: the first one is the file
                }
                targets.add(target(dir, target, TemplateEngine.render(f.body(), vars), r));
            }
            return new Plan(dir, List.copyOf(targets), null);
        }
        String fileName = fileNameOf(r);
        Path target = TemplateEngine.containedPath(dir, fileName);
        if (target == null) {
            return new Plan(dir, List.of(), fileName);
        }
        // A Java file's package comes from the folder it lands in, exactly as "New ▸ Class" infers it.
        String packageName =
                TemplateEngine.createsJavaFile(t) ? NewFileContent.packageFor(target.getParent(), r.projectRoot()) : "";
        TemplateVariableResolver vars = resolver(r, target.getFileName().toString(), target, packageName);
        targets.add(target(dir, target, TemplateEngine.render(t.body(), vars), r));
        return new Plan(dir, List.copyOf(targets), null);
    }

    private static String fileNameOf(Request r) {
        String fileName = TemplateEngine.expand(r.template().fileName(), resolver(r, "", null, ""))
                .trim();
        return fileName.isEmpty() ? "untitled" : fileName;
    }

    private static TemplateVariableResolver resolver(Request r, String fileName, Path target, String packageName) {
        return new TemplateVariableResolver(
                r.answers(),
                r.author(),
                r.projectName(),
                packageName,
                fileName,
                r.dir() == null ? "" : r.dir().toString(),
                target == null ? "" : target.toString(),
                r.now());
    }

    private static Target target(Path dir, Path path, TemplateEngine.Rendered rendered, Request r) {
        EditorConfigProperties rules =
                r.editorConfig() == null ? null : r.editorConfig().apply(path);
        return new Target(
                path,
                dir.relativize(path).toString().replace('\\', '/'),
                finish(rendered.text(), rules),
                rendered.caret(),
                rendered.hasCursor(),
                Files.exists(path, LinkOption.NOFOLLOW_LINKS));
    }

    /**
     * The text as it goes to disk: it ends with a line break (a file whose last line is not terminated is
     * a diff waiting to happen) unless the project's {@code .editorconfig} says
     * {@code insert_final_newline = false}, and it uses the project's {@code end_of_line} when one is set
     * (LF otherwise). {@code trim_trailing_whitespace} is deliberately not applied: the indentation in
     * front of {@code ${cursor}} is where the caret is about to go.
     */
    public static String finish(String text, EditorConfigProperties rules) {
        String out = text == null ? "" : text;
        Boolean finalNewline = rules == null ? null : rules.insertFinalNewline();
        if (!Boolean.FALSE.equals(finalNewline) && !out.isEmpty() && !out.endsWith("\n")) {
            out += "\n";
        }
        if (rules == null) {
            return out;
        }
        return EditorConfigTransform.transform(
                out, new EditorConfigProperties(null, null, null, rules.endOfLine(), null, null, finalNewline, null));
    }

    /**
     * Creates the plan's missing files, in order, never overwriting. Stops at the first failure and says
     * exactly which files were created before it.
     */
    public static Outcome write(Plan plan) {
        List<Target> created = new ArrayList<>();
        List<Target> skipped = new ArrayList<>();
        if (plan.refused()) {
            return new Outcome(plan.dir(), created, skipped, null, null);
        }
        for (Target t : plan.targets()) {
            if (t.exists()) {
                skipped.add(t);
                continue;
            }
            try {
                create(plan.dir(), t.path(), t.text());
                created.add(t);
            } catch (FileAlreadyExistsException e) {
                skipped.add(t); // appeared since the plan was made: still not ours to overwrite
            } catch (IOException | RuntimeException e) {
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                return new Outcome(plan.dir(), created, skipped, t, reason);
            }
        }
        return new Outcome(plan.dir(), created, skipped, null, null);
    }

    /**
     * Writes one new file (UTF-8) below {@code dir}, creating its parent folders. Refuses to overwrite, and
     * re-checks containment after the folders exist — a parent that turned out to be a symbolic link to
     * somewhere else must not receive the file. A file that starts with {@code #!} is made executable.
     */
    public static void create(Path dir, Path target, String text) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (FileAlreadyExistsException e) {
                // A file sits where a folder is needed. Not "the target already exists": that one is a skip.
                throw new IOException("Not a folder: " + e.getFile(), e);
            }
        }
        if (dir != null && !PathContainment.isWithin(dir, target)) {
            throw new IOException("Path leaves the target folder: " + target);
        }
        // Exclusive create — "never overwrite" must hold even when an earlier exists() was wrong.
        Files.write(
                target, text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        if (text.startsWith("#!")) {
            makeExecutable(target);
        }
    }

    /** Adds the execute bit wherever the read bit is set (so the umask's choices carry over). POSIX only. */
    static void makeExecutable(Path file) {
        try {
            Set<PosixFilePermission> perms = EnumSet.copyOf(Files.getPosixFilePermissions(file));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            if (perms.contains(PosixFilePermission.GROUP_READ)) {
                perms.add(PosixFilePermission.GROUP_EXECUTE);
            }
            if (perms.contains(PosixFilePermission.OTHERS_READ)) {
                perms.add(PosixFilePermission.OTHERS_EXECUTE);
            }
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException e) {
            // No POSIX permissions here (Windows, some network mounts): the shebang is all there is.
        }
    }
}
