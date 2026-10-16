package com.editora.http;

import java.util.ArrayList;
import java.util.List;

/**
 * What a response's tests found: the {@code # @assert} checks ({@link HttpChecks}) and the {@code client.test} calls
 * of its JavaScript response handlers ({@link ResponseScripts}), in the order they ran; the handlers'
 * {@code client.log} lines; and the error a handler stopped with ({@code null} when none did), its stack included.
 * {@link #NONE} — nothing ran — is what a request without checks or handlers carries.
 */
public record HttpTests(List<Test> tests, List<String> log, String error) {

    /** No tests, no log, no error. */
    public static final HttpTests NONE = new HttpTests(List.of(), List.of(), null);

    /** One test: its {@code name}, whether it {@code passed}, and why not ({@code ""} when it passed). */
    public record Test(String name, boolean passed, String message) {
        public Test {
            name = name == null ? "" : name;
            message = message == null ? "" : message;
        }
    }

    public HttpTests {
        tests = tests == null ? List.of() : List.copyOf(tests);
        log = log == null ? List.of() : List.copyOf(log);
        error = error == null || error.isBlank() ? null : error;
    }

    /** Whether nothing ran: no test, no log line, no error. */
    public boolean isEmpty() {
        return tests.isEmpty() && log.isEmpty() && error == null;
    }

    public int passed() {
        return (int) tests.stream().filter(Test::passed).count();
    }

    public int failed() {
        return tests.size() - passed();
    }

    /** Whether a test failed or a handler stopped with an error. */
    public boolean hasFailures() {
        return failed() > 0 || error != null;
    }

    /** These results followed by {@code more}'s (a later handler's); the first error is kept, the others logged. */
    public HttpTests plus(HttpTests more) {
        if (more == null || more.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return more;
        }
        List<Test> t = new ArrayList<>(tests);
        t.addAll(more.tests);
        List<String> l = new ArrayList<>(log);
        l.addAll(more.log);
        String e = error;
        if (e == null) {
            e = more.error;
        } else if (more.error != null) {
            l.add(more.error);
        }
        return new HttpTests(t, l, e);
    }
}
