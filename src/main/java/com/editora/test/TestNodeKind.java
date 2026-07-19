package com.editora.test;

/**
 * The role of a {@link TestNode} in the results tree. {@code SUITE} is the grouping level (a JUnit test class,
 * a Go package, a Cargo crate, a TAP file); {@code CLASS} is reserved for a future package→class two-level
 * grouping and is unused today. {@code TEST} is a single test method/case leaf.
 */
public enum TestNodeKind {
    ROOT,
    SUITE,
    CLASS,
    TEST
}
