package com.editora.process;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Editora's working folder on a target ({@code ~/.cache/editora}): the files it puts there for tools that run
 * there — a jdtls index, the java-debug jars, a debuggee's classes, a stand-in git config. Names are relative
 * to the folder and {@code /}-separated; every method returns the file as the target names it, for a tool's
 * arguments. A target whose files this machine reaches as paths keeps them with {@link PathTargetCache}; a
 * container, whose home this machine cannot see, with {@link ShellTargetCache}. May ask the target: call it off
 * the FX thread.
 */
public interface TargetCache {

    /** Makes the folder {@code relative} and its parents. */
    String makeDirectories(String relative) throws IOException;

    /**
     * Copies {@code localFile} to {@code relative}, unless a file of the same size is there already (the name
     * carries the version). Written aside and moved in, so a tool never reads half a file.
     */
    String putFile(String relative, Path localFile) throws IOException;

    /**
     * Writes {@code text} (UTF-8) to {@code relative}, unless a file is there already: callers name such files
     * by their contents, or put them in a new folder. Written aside and moved in.
     */
    String putText(String relative, String text) throws IOException;

    /** Removes {@code relative} and everything in it; nothing when it is not there. */
    void delete(String relative) throws IOException;

    /**
     * {@code relative}, checked: not empty, not absolute, no {@code .} or {@code ..} segment, nothing a shell or
     * a file system would read as anything but a name.
     */
    static String checked(String relative) {
        if (relative == null || relative.isEmpty() || relative.startsWith("/") || relative.endsWith("/")) {
            throw new IllegalArgumentException("not a name in the cache: " + relative);
        }
        for (String segment : relative.split("/", -1)) {
            if (segment.isEmpty()
                    || segment.equals(".")
                    || segment.equals("..")
                    || segment.indexOf('\\') >= 0
                    || segment.indexOf('\0') >= 0
                    || segment.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("not a name in the cache: " + relative);
            }
        }
        return relative;
    }
}
