package com.editora.http;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static com.editora.i18n.Messages.tr;

/**
 * Runs the JavaScript response handlers of a request ({@link HttpFile.Parsed#handlers()}) once its response has
 * arrived, on the run's worker, before the next request of a Run All starts — so a token a handler stores with
 * {@code client.global.set} is what the next request sends. {@link HttpClientService} calls it; the editor passes a
 * {@link NodeResponseScripts} built for the run (its trust answer, its settings), and {@link #OFF} runs nothing.
 */
public interface ResponseScripts {

    /** Runs no handler: a request that has one says it was not run. */
    ResponseScripts OFF = (request, exchange, input) -> request.handlers().isEmpty()
            ? Result.NONE
            : new Result(HttpTests.NONE, List.of(), List.of(tr("http.warn.handlerIgnored")));

    /**
     * Called once per run, on the worker, before its first request is sent, with every request of the run; may block
     * on a question to the user (whether the folder may run scripts). Asks nothing by default.
     */
    default void beforeRun(List<HttpFile.Parsed> requests, BooleanSupplier cancelled) {}

    /** Runs {@code request}'s handlers against {@code exchange}, a response that arrived. Never throws. */
    Result run(HttpFile.Parsed request, HttpExchange exchange, Input input);

    /**
     * What a handler sees beyond the exchange: the selected environment's variables (as written in the environment
     * files), the session variables when it starts, and whether the run was cancelled meanwhile.
     */
    record Input(Map<String, String> environment, Map<String, String> globals, BooleanSupplier cancelled) {
        public Input {
            environment = environment == null ? Map.of() : environment;
            globals = globals == null ? Map.of() : globals;
            cancelled = cancelled == null ? () -> false : cancelled;
        }
    }

    /**
     * A change to the session variables, in the order the handlers made them: {@code set} {@code name} to
     * {@code value}, {@code clear} {@code name}, or {@code clearAll}.
     */
    record GlobalChange(String kind, String name, String value) {
        public static final String SET = "set";
        public static final String CLEAR = "clear";
        public static final String CLEAR_ALL = "clearAll";

        /** Applies this change to {@code session}. */
        public void applyTo(CapturedResponses session) {
            switch (kind) {
                case SET -> session.setGlobal(name, value == null ? "" : value);
                case CLEAR -> session.setGlobal(name, null);
                case CLEAR_ALL -> session.clearGlobals();
                default -> {}
            }
        }

        /** Applies this change to a plain map of session variables. */
        public void applyTo(Map<String, String> globals) {
            switch (kind) {
                case SET -> globals.put(name, value == null ? "" : value);
                case CLEAR -> globals.remove(name);
                case CLEAR_ALL -> globals.clear();
                default -> {}
            }
        }
    }

    /** What the handlers of one request did: their tests and log, their session-variable changes, and warnings. */
    record Result(HttpTests tests, List<GlobalChange> changes, List<String> warnings) {
        public static final Result NONE = new Result(HttpTests.NONE, List.of(), List.of());

        public Result {
            tests = tests == null ? HttpTests.NONE : tests;
            changes = changes == null ? List.of() : List.copyOf(changes);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
