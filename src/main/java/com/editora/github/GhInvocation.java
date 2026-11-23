package com.editora.github;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * One {@code gh} call: its arguments (without the leading {@code gh}) and, when not {@code null}, the text written
 * to its standard input — how a pull request's or a review's body reaches {@code gh} ({@code --body-file -},
 * {@code --input -}) instead of as an argument, where a long body could pass the system's limit on the length of a
 * command line and every body would show in the process list.
 */
public record GhInvocation(List<String> args, @Nullable String stdin) {

    public GhInvocation {
        args = List.copyOf(args);
    }
}
