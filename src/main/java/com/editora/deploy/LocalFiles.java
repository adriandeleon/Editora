package com.editora.deploy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.editora.process.ProcessRunner;

/**
 * The files of a local project a deployment sends: what Git tracks or would track (when the project is a Git
 * repository and the deployment follows {@code .gitignore}), else every file, minus {@link DeployExcludes}.
 * Reads the disk: call it off the FX thread.
 */
public final class LocalFiles {

    /**
     * One file to send.
     *
     * @param relative its path from the project root, {@code /}-separated
     * @param path the file on this machine
     * @param size its length in bytes
     * @param modified its last-modified time, in milliseconds
     * @param executable whether it should be executable on the host
     */
    public record LocalFile(String relative, Path path, long size, long modified, boolean executable) {}

    /** More files than a deployment sends: a mistake (a home folder as the project), not a project. */
    public static final int MAX_FILES = 200_000;

    private LocalFiles() {}

    /** The files of {@code root} to send, sorted by path. */
    public static List<LocalFile> list(Path root, DeployExcludes excludes, boolean useGitignore) throws IOException {
        List<String> fromGit = useGitignore ? gitFiles(root) : null;
        List<LocalFile> files = fromGit != null ? fromList(root, fromGit, excludes) : walk(root, excludes);
        files.sort(Comparator.comparing(LocalFile::relative));
        return files;
    }

    /**
     * What Git tracks or would track in {@code root} ({@code ls-files --cached --others --exclude-standard});
     * null when {@code root} is not in a Git work tree or Git cannot be run, so the caller walks the folder.
     */
    static List<String> gitFiles(Path root) {
        if (!Files.exists(root.resolve(".git"))) {
            return null; // only the repository's own root: a subfolder of a bigger repository walks
        }
        ProcessRunner.BytesResult result = ProcessRunner.runBytes(
                root,
                Duration.ofMinutes(2),
                List.of("git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"),
                java.util.Map.of());
        if (!result.ok() || result.outTruncated()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String entry : new String(result.out(), StandardCharsets.UTF_8).split("\0")) {
            if (!entry.isEmpty()) {
                out.add(entry);
            }
        }
        return out;
    }

    private static List<LocalFile> fromList(Path root, List<String> relatives, DeployExcludes excludes)
            throws IOException {
        List<LocalFile> files = new ArrayList<>();
        for (String relative : relatives) {
            if (!acceptable(relative) || excludes.excludes(relative)) {
                continue;
            }
            Path file = root.resolve(relative);
            String name = nfc(relative);
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(file, BasicFileAttributes.class);
            } catch (IOException e) {
                continue; // tracked but deleted in the work tree: nothing to send
            }
            if (attrs.isRegularFile()) { // a submodule is listed as its folder: not sent
                files.add(file(name, file, attrs));
                if (files.size() > MAX_FILES) {
                    throw new IOException("more than " + MAX_FILES + " files to deploy");
                }
            }
        }
        return files;
    }

    private static List<LocalFile> walk(Path root, DeployExcludes excludes) throws IOException {
        List<LocalFile> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = relative(root, dir);
                return !acceptable(relative) || excludes.excludesDirectory(relative)
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE; // a link to a folder, a device: not sent
                }
                String relative = relative(root, file);
                if (acceptable(relative) && !excludes.excludes(relative)) {
                    files.add(file(relative, file, attrs));
                    if (files.size() > MAX_FILES) {
                        throw new IOException("more than " + MAX_FILES + " files to deploy");
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE; // unreadable: left out rather than failing the deploy
            }
        });
        return files;
    }

    private static LocalFile file(String relative, Path file, BasicFileAttributes attrs) {
        return new LocalFile(
                relative, file, attrs.size(), attrs.lastModifiedTime().toMillis(), executable(file));
    }

    /**
     * Whether {@code file} should be executable on the host: its own executable bit where the file system has
     * one, else (Windows) a {@code #!} first line — a script checked out on Windows keeps working on the host.
     */
    static boolean executable(Path file) {
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            try {
                return Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)
                        .contains(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
            } catch (IOException | UnsupportedOperationException e) {
                return false;
            }
        }
        try (InputStream in = Files.newInputStream(file)) {
            return in.read() == '#' && in.read() == '!';
        } catch (IOException e) {
            return false;
        }
    }

    private static String relative(Path root, Path file) {
        return nfc(root.relativize(file).toString().replace('\\', '/'));
    }

    /**
     * {@code name} composed (NFC): how a deploy names a file on both sides, since a host may store it decomposed
     * (macOS's tar does) and this machine may list it either way (HFS+, a Git index).
     */
    static String nfc(String name) {
        return java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC);
    }

    /**
     * Whether {@code relative} can be named safely on the host: inside the root, and nothing a line-based
     * listing or a shell would read as two names.
     */
    static boolean acceptable(String relative) {
        if (relative == null
                || relative.isEmpty()
                || relative.startsWith("/")
                || relative.indexOf('\n') >= 0
                || relative.indexOf('\r') >= 0
                || relative.indexOf('\0') >= 0
                || relative.indexOf('\\') >= 0) {
            return false;
        }
        for (String segment : relative.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }
}
