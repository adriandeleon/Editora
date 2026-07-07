package com.editora.maven;

/** A pom.xml could not be parsed (malformed XML, or missing a required element like {@code artifactId}) —
 *  a user-facing condition (the file on disk is bad), not a programmer error. */
public final class PomParseException extends Exception {

    public PomParseException(String message) {
        super(message);
    }

    public PomParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
