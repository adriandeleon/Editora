package com.editora.dap;

import java.util.List;

/** The exception filters an external adapter's session starts with when none were chosen. Pure. */
final class ExternalAdapterFilters {

    private ExternalAdapterFilters() {}

    /** The filters {@code language}'s profile marks on by default (Rust: {@code rust_panic}). */
    static List<String> defaults(DebugLanguage language) {
        return language.exceptionFilters().stream()
                .filter(DapModels.ExceptionFilterOption::byDefault)
                .map(DapModels.ExceptionFilterOption::filter)
                .toList();
    }
}
