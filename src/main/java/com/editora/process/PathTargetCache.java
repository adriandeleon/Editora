package com.editora.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * A {@link TargetCache} this machine reaches as a folder: an SSH host's over SFTP, a WSL distribution's through
 * its {@code \\wsl.localhost} share. Files are written through the folder's file system and named for the tool
 * by {@link ExecTarget#toTargetPath}.
 */
public final class PathTargetCache implements TargetCache {

    private final ExecTarget target;
    private final Path dir;

    public PathTargetCache(ExecTarget target, Path dir) {
        this.target = target;
        this.dir = dir;
    }

    private Path resolve(String relative) {
        Path path = dir;
        for (String segment : TargetCache.checked(relative).split("/")) {
            path = path.resolve(segment);
        }
        return path;
    }

    @Override
    public String makeDirectories(String relative) throws IOException {
        return target.toTargetPath(Files.createDirectories(resolve(relative)));
    }

    @Override
    public String putFile(String relative, Path localFile) throws IOException {
        Path copy = resolve(relative);
        if (!Files.isRegularFile(copy) || Files.size(copy) != Files.size(localFile)) {
            Files.createDirectories(copy.getParent());
            Path part = copy.resolveSibling(copy.getFileName() + ".part");
            Files.copy(localFile, part, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(copy);
            Files.move(part, copy);
        }
        return target.toTargetPath(copy);
    }

    @Override
    public String putText(String relative, String text) throws IOException {
        Path file = resolve(relative);
        if (!Files.isRegularFile(file)) {
            Files.createDirectories(file.getParent());
            Path part = file.resolveSibling(file.getFileName() + ".part");
            Files.writeString(part, text, StandardCharsets.UTF_8);
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return target.toTargetPath(file);
    }

    @Override
    public void delete(String relative) throws IOException {
        Path root = resolve(relative);
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException error) throws IOException {
                    if (error != null) {
                        throw error;
                    }
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (NoSuchFileException gone) {
            // nothing to remove
        }
    }
}
