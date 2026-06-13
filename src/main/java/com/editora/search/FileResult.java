package com.editora.search;

import java.nio.file.Path;
import java.util.List;

/** All matches found in one file. */
public record FileResult(Path file, List<LineMatch> matches) {}
